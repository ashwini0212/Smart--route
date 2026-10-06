package com.smartroute.common.security;

import org.springframework.stereotype.Component;

/**
 * Authorization rules used in {@code @PreAuthorize}. The constants keep role lists in one place;
 * the bean (named {@code access}) answers ownership questions such as "is this the caller's own driver
 * record?", which roles alone can't express.
 */
@Component("access")
public class Access {

    /** Any logged-in user, whatever the role. */
    public static final String ANY_USER = "isAuthenticated()";
    public static final String ADMIN = "hasRole('ADMIN')";
    /** People who run operations: create and change orders, manage driver availability. */
    public static final String STAFF = "hasAnyRole('ADMIN', 'DISPATCHER')";
    /** Everyone who may read operational data across the whole company. */
    public static final String STAFF_OR_VIEWER = "hasAnyRole('ADMIN', 'DISPATCHER', 'VIEWER')";

    /** True if the caller is a DRIVER and {@code driverId} is their own driver record. */
    public boolean isDriver(long driverId) {
        return CurrentUser.find()
                .filter(user -> user.role() == Role.DRIVER)
                .map(user -> user.driverId() != null && user.driverId() == driverId)
                .orElse(false);
    }
}
