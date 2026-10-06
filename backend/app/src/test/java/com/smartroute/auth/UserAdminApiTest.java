package com.smartroute.auth;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminApiTest extends ApiTestSupport {

    private static String userJson(String email, String role, Long driverId, String password) {
        return """
                {"email":"%s","fullName":"Some Person","role":"%s","driverId":%s,"password":"%s"}
                """.formatted(email, role, driverId, password);
    }

    @Test
    void adminCreatesAUserWithoutExposingThePasswordHash() throws Exception {
        postJson("/api/admin/users", userJson("New.Person@Test.local", "DISPATCHER", null, "long-enough-password"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.person@test.local"))
                .andExpect(jsonPath("$.role").value("DISPATCHER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void emailsAreUniqueIgnoringCase() throws Exception {
        postJson("/api/admin/users", userJson("dup@test.local", "VIEWER", null, "long-enough-password"))
                .andExpect(status().isCreated());
        postJson("/api/admin/users", userJson("DUP@test.local", "VIEWER", null, "long-enough-password"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void passwordRulesAreEnforced() throws Exception {
        postJson("/api/admin/users", userJson("p@test.local", "VIEWER", null, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        // 25 three-byte characters = 75 bytes: BCrypt would silently ignore the tail, so it is rejected.
        postJson("/api/admin/users", userJson("p@test.local", "VIEWER", null, "語".repeat(25)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at most 72 bytes in UTF-8"));
    }

    @Test
    void driverUsersMustBeLinkedToExactlyOneExistingDriver() throws Exception {
        long warehouse = createWarehouse("WH-USR");
        long driver = createDriver(warehouse, null);
        postJson("/api/admin/users", userJson("d1@test.local", "DRIVER", null, "long-enough-password"))
                .andExpect(status().isUnprocessableContent());
        postJson("/api/admin/users", userJson("d1@test.local", "DRIVER", 999_999L, "long-enough-password"))
                .andExpect(status().isUnprocessableContent());
        postJson("/api/admin/users", userJson("v1@test.local", "VIEWER", driver, "long-enough-password"))
                .andExpect(status().isUnprocessableContent());
        postJson("/api/admin/users", userJson("d1@test.local", "DRIVER", driver, "long-enough-password"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value(driver));
        postJson("/api/admin/users", userJson("d2@test.local", "DRIVER", driver, "long-enough-password"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Driver " + driver + " already has a login"));
    }

    @Test
    void adminCannotDisableOrDemoteThemselves() throws Exception {
        long me = body(getUrl("/api/auth/me")).get("id").asLong();
        putJson("/api/admin/users/" + me + "/enabled?enabled=false", "")
                .andExpect(status().isUnprocessableContent());
        putJson("/api/admin/users/" + me + "/role", "{\"role\":\"VIEWER\"}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void disablingAUserEndsTheirSessions() throws Exception {
        long viewer = users.create("viewer@test.local", Role.VIEWER, null);
        putJson("/api/admin/users/" + viewer + "/enabled?enabled=false", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        postJson("/api/auth/login", """
                {"email":"viewer@test.local","password":"%s"}""".formatted(com.smartroute.support.TestUsers.PASSWORD), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingARoleRequiresAValidDriverLink() throws Exception {
        long viewer = users.create("viewer@test.local", Role.VIEWER, null);
        putJson("/api/admin/users/" + viewer + "/role", "{\"role\":\"DISPATCHER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DISPATCHER"));
        putJson("/api/admin/users/" + viewer + "/role", "{\"role\":\"DRIVER\"}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void listsUsersPaged() throws Exception {
        users.create("viewer@test.local", Role.VIEWER, null);
        getUrl("/api/admin/users?size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }
}
