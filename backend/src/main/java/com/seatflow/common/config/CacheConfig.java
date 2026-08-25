package com.seatflow.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import com.seatflow.event.presentation.dto.EventDtos;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis caching.
 *
 * <p><b>Redis is not allowed to matter.</b> The cache holds read models - seat
 * maps and event details - and nothing else. No lock, no counter, nothing any
 * correctness property depends on. Kill Redis and reservations still work,
 * expiry still works, and no seat is ever sold twice; pages just get slower.
 * That is the whole argument in {@code docs/CONCURRENCY.md} part 6, and this
 * class is where it is enforced rather than merely claimed.
 *
 * <p>The {@link CacheErrorHandler} below is what makes it true. Without one,
 * Spring propagates cache exceptions to the caller, and a Redis outage becomes
 * a site outage - the exact coupling the design spent effort avoiding.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** The whole seat map for one event. Large, hot, and precisely invalidated. */
    public static final String SEAT_MAPS = "seatMaps";

    /** Event detail including availability counts. Changes whenever seats do. */
    public static final String EVENT_DETAILS = "eventDetails";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        /*
         * Each cache is serialized with the exact type it holds, rather than a
         * generic Object serializer.
         *
         * The generic route needs Jackson's default typing to record what a
         * value was, and without it a cached record comes back as a
         * LinkedHashMap. That failure is nastier than it looks: the cache read
         * succeeds, so the CacheErrorHandler never sees it, and the
         * ClassCastException surfaces as a 500 from a healthy endpoint - which
         * would mean Redis could break the site after all. Naming the type keeps
         * any deserialization problem inside the cache read, where the error
         * handler can turn it into a database fallback.
         */
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // A null seat map is not worth remembering.
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.of(
                        // Short TTLs even though entries are evicted explicitly.
                        // The TTL is the backstop for a missed invalidation: a
                        // stale seat map corrects itself in a minute rather than
                        // persisting until someone reserves a seat.
                        SEAT_MAPS, typed(base, EventDtos.SeatMapResponse.class, Duration.ofSeconds(60)),
                        EVENT_DETAILS, typed(base, EventDtos.EventResponse.class, Duration.ofSeconds(60))))
                .build();
    }

    private static RedisCacheConfiguration typed(
            RedisCacheConfiguration base, Class<?> valueType, Duration ttl) {
        return base
                .entryTtl(ttl)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new JacksonJsonRedisSerializer<>(valueType)));
    }

    /**
     * Turns cache failures into log lines instead of exceptions.
     * <p>
     * A read that cannot reach Redis falls through to the database. A write that
     * cannot reach Redis is dropped, which is safe because the TTL will expire
     * the stale entry anyway - and because nothing in this cache is authoritative.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache read failed on {} (falling back to the database): {}",
                        cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCachePutError(
                    RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache write failed on {} (entry not stored): {}",
                        cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // The TTL is the safety net for exactly this case.
                log.warn("Cache evict failed on {} (entry expires on its own): {}",
                        cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache clear failed on {}: {}", cache.getName(), exception.getMessage());
            }
        };
    }

    @Override
    public CacheResolver cacheResolver() {
        return null;
    }

    @Override
    public KeyGenerator keyGenerator() {
        return null;
    }
}
