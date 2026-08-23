package com.seatflow.user.application;

import com.seatflow.common.exception.AuthExceptions;
import com.seatflow.common.security.TokenService;
import com.seatflow.user.domain.RefreshToken;
import com.seatflow.user.domain.Role;
import com.seatflow.user.domain.RoleName;
import com.seatflow.user.domain.User;
import com.seatflow.user.infrastructure.RefreshTokenRepository;
import com.seatflow.user.infrastructure.RoleRepository;
import com.seatflow.user.infrastructure.UserRepository;
import com.seatflow.user.presentation.dto.AuthDtos.AuthResponse;
import com.seatflow.user.presentation.dto.AuthDtos.LoginRequest;
import com.seatflow.user.presentation.dto.AuthDtos.RegisterRequest;
import com.seatflow.user.presentation.dto.AuthDtos.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Registration, login, and refresh-token rotation.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * A throwaway hash, verified when no account matches, so that a login attempt
     * for an unknown address costs the same time as one for a known address.
     * Without it, response latency leaks which emails are registered.
     */
    private final String timingEqualizerHash;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {

        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;

        byte[] filler = new byte[24];
        new SecureRandom().nextBytes(filler);
        this.timingEqualizerHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(filler));
    }

    /**
     * Creates an account with the USER role and signs it straight in.
     * <p>
     * The pre-check on email is a courtesy that produces a clean error message.
     * The actual guarantee is the unique index on {@code lower(email)}: two
     * simultaneous registrations both pass the check, and the database rejects
     * the loser. Same pattern as the seat reservation path, on a smaller scale.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new AuthExceptions.EmailAlreadyRegistered(email);
        }

        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException(
                        "Role USER is missing. Migration V1 seeds it; the database is inconsistent."));

        User user = User.register(email, passwordEncoder.encode(request.password()), request.fullName(), userRole);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration for the same email.
            throw new AuthExceptions.EmailAlreadyRegistered(email);
        }

        log.info("Registered user {}", user.getId());
        return issueTokensFor(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            // Burn the same time a real verification costs, then fail identically.
            passwordEncoder.matches(request.password(), timingEqualizerHash);
            throw new AuthExceptions.InvalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AuthExceptions.InvalidCredentials();
        }
        if (!user.isEnabled()) {
            throw new AuthExceptions.AccountDisabled();
        }

        return issueTokensFor(user);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token.
     * <p>
     * Reuse detection: a token that is presented after it has already been
     * revoked has almost certainly been stolen, since the legitimate client
     * discarded it on first use. The safe response is to revoke every live token
     * for that user and force a fresh login.
     * <p>
     * {@code noRollbackFor} is load-bearing, not decoration. The reuse branch
     * revokes the whole token family and then throws to reject the request. A
     * plain {@code @Transactional} would roll that revocation back on the way
     * out, so the attacker's stolen token would keep working - the exact
     * opposite of what the check is for. Marking the exception as non-rolling
     * back lets the revocation commit while the caller still gets a 401.
     */
    @Transactional(noRollbackFor = AuthExceptions.InvalidRefreshToken.class)
    public AuthResponse refresh(String rawRefreshToken) {
        String hash = tokenService.hash(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new AuthExceptions.InvalidRefreshToken(
                        "This refresh token is not recognised."));

        Instant now = Instant.now();

        if (stored.isRevoked()) {
            User owner = stored.getUser();
            int revoked = refreshTokenRepository.revokeAllActiveForUser(owner.getId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} active token(s)",
                    owner.getId(), revoked);
            throw new AuthExceptions.InvalidRefreshToken(
                    "This refresh token has already been used. All sessions have been ended.");
        }

        if (stored.isExpired(now)) {
            throw new AuthExceptions.InvalidRefreshToken("This refresh token has expired.");
        }

        User user = stored.getUser();
        if (!user.isEnabled()) {
            throw new AuthExceptions.AccountDisabled();
        }

        AuthResponse response = issueTokensFor(user);
        // issueTokensFor persisted the successor; link and revoke the old one.
        refreshTokenRepository.findByTokenHash(tokenService.hash(response.refreshToken()))
                .ifPresent(stored::rotateTo);

        return response;
    }

    /** Ends every session for the user. */
    @Transactional
    public int logoutEverywhere(UUID userId) {
        return refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now());
    }

    /** Mints an access token and a fresh refresh token, storing only the hash. */
    private AuthResponse issueTokensFor(User user) {
        TokenService.AccessToken accessToken = tokenService.issueAccessToken(user);

        String rawRefreshToken = tokenService.generateRefreshTokenValue();
        RefreshToken refreshToken = RefreshToken.issue(
                user, tokenService.hash(rawRefreshToken), tokenService.refreshTokenExpiry());
        refreshTokenRepository.saveAndFlush(refreshToken);

        return AuthResponse.of(
                accessToken.value(),
                rawRefreshToken,
                tokenService.accessTokenTtlSeconds(),
                accessToken.expiresAt(),
                toUserResponse(user));
    }

    private UserResponse toUserResponse(User user) {
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .sorted()
                .toList();
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), roles);
    }
}
