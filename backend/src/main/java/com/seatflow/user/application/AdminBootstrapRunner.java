package com.seatflow.user.application;

import com.seatflow.user.domain.Role;
import com.seatflow.user.domain.RoleName;
import com.seatflow.user.domain.User;
import com.seatflow.user.infrastructure.RoleRepository;
import com.seatflow.user.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures the configured administrator exists at startup.
 * <p>
 * Idempotent, and safe to run on every boot:
 * <ul>
 *   <li>account missing - create it with the ADMIN role;</li>
 *   <li>account present without ADMIN - grant the role (promotion path);</li>
 *   <li>account present with ADMIN - do nothing.</li>
 * </ul>
 * An existing password is never overwritten. Someone who already has the account
 * keeps their credentials; only the role is reconciled.
 */
@Component
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(
            AdminBootstrapProperties properties,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {

        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("Admin bootstrap skipped: seatflow.admin is not configured.");
            return;
        }

        String email = User.normalizeEmail(properties.email());
        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "Role ADMIN is missing. Migration V1 seeds it; the database is inconsistent."));

        userRepository.findByEmail(email).ifPresentOrElse(
                existing -> promoteIfNeeded(existing, adminRole),
                () -> create(email, adminRole));
    }

    private void promoteIfNeeded(User user, Role adminRole) {
        boolean alreadyAdmin = user.getRoles().stream()
                .anyMatch(role -> role.getName() == RoleName.ADMIN);

        if (alreadyAdmin) {
            log.debug("Admin bootstrap: {} already has the ADMIN role.", user.getEmail());
            return;
        }

        user.grant(adminRole);
        userRepository.save(user);
        log.info("Admin bootstrap: granted ADMIN to existing account {}", user.getEmail());
    }

    private void create(String email, Role adminRole) {
        String fullName = (properties.fullName() == null || properties.fullName().isBlank())
                ? "SeatFlow Administrator"
                : properties.fullName();

        User admin = User.register(email, passwordEncoder.encode(properties.password()), fullName, adminRole);
        userRepository.save(admin);
        log.info("Admin bootstrap: created ADMIN account {}", email);
    }
}
