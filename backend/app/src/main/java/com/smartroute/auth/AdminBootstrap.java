package com.smartroute.auth;

import com.smartroute.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Solves "who creates the first admin?" without a default password in the code: if no enabled ADMIN
 * exists and {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD} are set, that admin is created once.
 */
@Component
@Order(1)
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties properties;
    private final UserAccountService accounts;

    AdminBootstrap(SecurityProperties properties, UserAccountService accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        SecurityProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (accounts.anyEnabledAdmin()) {
            return;
        }
        if (!admin.isConfigured()) {
            log.warn("No admin user exists. Set ADMIN_EMAIL and ADMIN_PASSWORD (or use the seed profile) to create one.");
            return;
        }
        UserResponse created = accounts.create(
                new CreateUserRequest(admin.email(), "Administrator", Role.ADMIN, null, admin.password()));
        // The id, not the address: an email is personal data and the logging policy keeps it out of the log.
        log.info("Created bootstrap admin from ADMIN_EMAIL, user id {}", created.id());
    }
}
