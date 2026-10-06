package com.smartroute.auth;

import com.smartroute.common.persistence.BaseEntity;
import com.smartroute.common.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** A login identity. Only the BCrypt hash of the password is stored. */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {
        // for JPA
    }

    AppUser(String email, String passwordHash, String fullName, Role role, Long driverId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.driverId = driverId;
    }

    void changeRole(Role role, Long driverId) {
        this.role = role;
        this.driverId = driverId;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public Long getDriverId() {
        return driverId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
