package com.smartroute.auth;

import com.smartroute.common.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByDriverId(Long driverId);

    long countByRoleAndEnabledTrue(Role role);
}
