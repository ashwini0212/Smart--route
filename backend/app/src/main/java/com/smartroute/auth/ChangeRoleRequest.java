package com.smartroute.auth;

import com.smartroute.common.security.Role;
import jakarta.validation.constraints.NotNull;

/** @param driverId required for (and only for) the DRIVER role */
public record ChangeRoleRequest(@NotNull Role role, Long driverId) {
}
