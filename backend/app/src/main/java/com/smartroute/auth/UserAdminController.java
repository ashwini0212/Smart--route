package com.smartroute.auth;

import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User management. The URL rule in SecurityConfig and the annotation both require ADMIN (defence in depth). */
@RestController
@RequestMapping("/api/admin/users")
@Validated
@PreAuthorize(Access.ADMIN)
@Tag(name = "Admin: users")
class UserAdminController {

    private final UserAccountService accounts;

    UserAdminController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @Operation(summary = "List users")
    PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(accounts.list(PageRequest.of(page, size, Sort.by("email"))), u -> u);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user")
    UserResponse get(@PathVariable long id) {
        return accounts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user with an initial password")
    UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return accounts.create(request);
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Change a user's role (ends their sessions)")
    UserResponse changeRole(@PathVariable long id, @Valid @RequestBody ChangeRoleRequest request) {
        return accounts.changeRole(id, request, CurrentUser.require().id());
    }

    @PutMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable a user (disabling ends their sessions)")
    UserResponse setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        return accounts.setEnabled(id, enabled, CurrentUser.require().id());
    }
}
