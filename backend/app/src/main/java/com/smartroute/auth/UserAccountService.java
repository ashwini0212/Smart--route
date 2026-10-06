package com.smartroute.auth;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/** User management (ADMIN) and self-service password change. */
@Service
@Transactional(readOnly = true)
public class UserAccountService {

    private final AppUserRepository users;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final DriverService drivers;

    UserAccountService(AppUserRepository users, RefreshTokenService refreshTokens, PasswordEncoder passwordEncoder,
                       DriverService drivers) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.drivers = drivers;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.duplicate("A user with email " + email + " already exists");
        }
        requireValidDriverLink(request.role(), request.driverId(), null);
        PasswordPolicy.check(request.password());
        AppUser user = users.save(new AppUser(email, passwordEncoder.encode(request.password()),
                request.fullName().strip(), request.role(), request.driverId()));
        return UserResponse.from(user);
    }

    public Page<UserResponse> list(Pageable pageable) {
        return users.findAll(pageable).map(UserResponse::from);
    }

    public UserResponse get(long id) {
        return UserResponse.from(load(id));
    }

    public boolean anyEnabledAdmin() {
        return users.countByRoleAndEnabledTrue(Role.ADMIN) > 0;
    }

    /** Changes a role and ends the user's sessions so the new role applies at their next login. */
    @Transactional
    public UserResponse changeRole(long id, ChangeRoleRequest request, long actingUserId) {
        AppUser user = load(id);
        if (user.getRole() == Role.ADMIN && request.role() != Role.ADMIN) {
            requireNotSelf(id, actingUserId, "change your own role");
            requireAnotherEnabledAdmin(user);
        }
        requireValidDriverLink(request.role(), request.driverId(), id);
        user.changeRole(request.role(), request.driverId());
        refreshTokens.revokeAllForUser(id);
        return UserResponse.from(user);
    }

    /** Disabling ends every session of the user (their refresh tokens stop working immediately). */
    @Transactional
    public UserResponse setEnabled(long id, boolean enabled, long actingUserId) {
        AppUser user = load(id);
        if (!enabled) {
            requireNotSelf(id, actingUserId, "disable your own account");
            if (user.getRole() == Role.ADMIN) {
                requireAnotherEnabledAdmin(user);
            }
            refreshTokens.revokeAllForUser(id);
        }
        user.setEnabled(enabled);
        return UserResponse.from(user);
    }

    /** Requires the current password, then logs the user out everywhere. */
    @Transactional
    public void changePassword(long userId, ChangePasswordRequest request) {
        AppUser user = load(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Current password is incorrect");
        }
        PasswordPolicy.check(request.newPassword());
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeAllForUser(userId);
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    AppUser load(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }

    private void requireValidDriverLink(Role role, Long driverId, Long userId) {
        if (role == Role.DRIVER && driverId == null) {
            throw ApiException.businessRule("A DRIVER user must be linked to a driver record (driverId)");
        }
        if (role != Role.DRIVER && driverId != null) {
            throw ApiException.businessRule("Only DRIVER users can be linked to a driver record");
        }
        if (driverId == null) {
            return;
        }
        if (!drivers.exists(driverId)) {
            throw ApiException.businessRule("Driver " + driverId + " does not exist");
        }
        boolean linkedElsewhere = users.existsByDriverId(driverId)
                && (userId == null || !Objects.equals(load(userId).getDriverId(), driverId));
        if (linkedElsewhere) {
            throw ApiException.businessRule("Driver " + driverId + " already has a login");
        }
    }

    private static void requireNotSelf(long id, long actingUserId, String action) {
        if (id == actingUserId) {
            throw ApiException.businessRule("You cannot " + action + "; ask another admin");
        }
    }

    /** Never leave the system without an enabled admin. */
    private void requireAnotherEnabledAdmin(AppUser admin) {
        long enabledAdmins = users.countByRoleAndEnabledTrue(Role.ADMIN);
        if (admin.isEnabled() && enabledAdmins <= 1) {
            throw ApiException.businessRule("This is the last enabled admin");
        }
    }
}
