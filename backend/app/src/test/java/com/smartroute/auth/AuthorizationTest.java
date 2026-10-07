package com.smartroute.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role matrix, enforced on the server. Each row is one request made with a real signed token for
 * that role; the expected status proves both that allowed calls pass and that forbidden ones are blocked.
 */
class AuthorizationTest extends ApiTestSupport {

    @Autowired
    private JwtEncoder encoder;

    private Map<Role, String> tokens;
    private long ownDriverId;
    private long otherDriverId;
    private long orderId;
    private long warehouseId;

    @BeforeEach
    void setUpFleet() throws Exception {
        warehouseId = createWarehouse("WH-RBAC");
        ownDriverId = createDriver(warehouseId, createVehicle("KA01-T-0001", "VAN"));
        otherDriverId = createDriver(warehouseId, createVehicle("KA01-T-0002", "VAN"));
        orderId = body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.97,"dropLongitude":77.6,"priority":"NORMAL","weightKg":2,"volumeM3":0.01}
                """.formatted(warehouseId))).get("id").asLong();
        tokens = Map.of(
                Role.ADMIN, adminToken,
                Role.DISPATCHER, users.token(Role.DISPATCHER),
                Role.VIEWER, users.token(Role.VIEWER),
                Role.DRIVER, users.driverToken(ownDriverId));
    }

    private String resolve(String url) {
        return url.replace("{own}", String.valueOf(ownDriverId))
                .replace("{other}", String.valueOf(otherDriverId))
                .replace("{order}", String.valueOf(orderId))
                .replace("{wh}", String.valueOf(warehouseId));
    }

    private String bodyFor(String url, HttpMethod method) {
        if (method == HttpMethod.POST && url.equals("/api/orders")) {
            return """
                    {"warehouseId":%d,"customerName":"Another Customer","dropAddress":"2 Test Street",
                     "dropLatitude":12.98,"dropLongitude":77.61,"priority":"HIGH","weightKg":1,"volumeM3":0.01}
                    """.formatted(warehouseId);
        }
        if (method == HttpMethod.POST && url.equals("/api/warehouses")) {
            return """
                    {"code":"WH-NEW","name":"New Hub","address":"3 Test Road","latitude":12.9,"longitude":77.5}""";
        }
        if (url.endsWith("/cancel")) {
            return "{\"reason\":\"Customer cancelled\"}";
        }
        return "";
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            // Orders: staff write, staff + viewer read, drivers have no company-wide access.
            "ADMIN,      GET,  /api/orders,                 200",
            "DISPATCHER, GET,  /api/orders,                 200",
            "VIEWER,     GET,  /api/orders,                 200",
            "DRIVER,     GET,  /api/orders,                 403",
            "DISPATCHER, POST, /api/orders,                 201",
            "VIEWER,     POST, /api/orders,                 403",
            "DRIVER,     POST, /api/orders,                 403",
            "VIEWER,     GET,  /api/orders/{order}/history, 200",
            "DRIVER,     GET,  /api/orders/{order},         403",
            "VIEWER,     POST, /api/orders/{order}/cancel,  403",
            "DISPATCHER, POST, /api/orders/{order}/cancel,  200",
            // Drivers: a DRIVER sees and updates only their own record.
            "VIEWER,     GET,  /api/drivers,                200",
            "DRIVER,     GET,  /api/drivers,                403",
            "DRIVER,     GET,  /api/drivers/{own},          200",
            "DRIVER,     GET,  /api/drivers/{other},        403",
            "DRIVER,     PUT,  /api/drivers/{own}/status?status=AVAILABLE,   200",
            "DRIVER,     PUT,  /api/drivers/{other}/status?status=AVAILABLE, 403",
            "DISPATCHER, PUT,  /api/drivers/{other}/status?status=AVAILABLE, 200",
            "VIEWER,     PUT,  /api/drivers/{own}/status?status=AVAILABLE,   403",
            // Master data: everyone reads warehouses, only ADMIN changes them; vehicles are staff/viewer only.
            "DRIVER,     GET,  /api/warehouses,             200",
            "DISPATCHER, POST, /api/warehouses,             403",
            "ADMIN,      POST, /api/warehouses,             201",
            "DISPATCHER, PUT,  /api/warehouses/{wh}/active?active=false, 403",
            "DRIVER,     GET,  /api/vehicles,               403",
            "VIEWER,     GET,  /api/vehicles,               200",
            // Admin area.
            "ADMIN,      GET,  /api/admin/users,            200",
            "DISPATCHER, GET,  /api/admin/users,            403",
            "VIEWER,     GET,  /api/admin/users,            403",
            "DRIVER,     GET,  /api/admin/users,            403",
            // Everyone can read their own profile.
            "DRIVER,     GET,  /api/auth/me,                200",
    })
    void roleMatrix(Role role, String method, String url, int expectedStatus) throws Exception {
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        String body = bodyFor(url, httpMethod);
        var request = request(httpMethod, resolve(url));
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(auth(request, tokens.get(role))).andExpect(status().is(expectedStatus));
    }

    @Test
    void forbiddenResponsesUseTheApiErrorFormat() throws Exception {
        getUrl("/api/admin/users", tokens.get(Role.VIEWER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isString());
        getUrl("/api/drivers/" + otherDriverId, tokens.get(Role.DRIVER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousRequestsGet401WithBearerChallenge() throws Exception {
        getUrl("/api/orders", null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        getUrl("/actuator/health", null).andExpect(status().isOk());
        getUrl("/v3/api-docs", null).andExpect(status().isOk());
    }

    /** "Never trust role information from the client": editing the role claim breaks the signature. */
    @Test
    void tokenWithEditedRoleClaimIsRejected() throws Exception {
        String[] parts = tokens.get(Role.VIEWER).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"VIEWER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        getUrl("/api/admin/users", forged)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.message").value("Access token is invalid or expired"));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() throws Exception {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"smartroute\",\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":"
                + Instant.now().plusSeconds(600).getEpochSecond() + "}");
        getUrl("/api/admin/users", header + "." + payload + ".").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        byte[] otherKey = "a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8);
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        getUrl("/api/admin/users", sign(attacker, claims("smartroute", Instant.now(), Duration.ofMinutes(5))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Beyond the 60 s clock-skew allowance.
        Instant issued = Instant.now().minus(Duration.ofMinutes(20));
        getUrl("/api/orders", sign(encoder, claims("smartroute", issued, Duration.ofMinutes(15))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        getUrl("/api/orders", sign(encoder, claims("someone-else", Instant.now(), Duration.ofMinutes(5))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validHandMadeTokenIsAcceptedSoTheNegativeTestsAreMeaningful() throws Exception {
        getUrl("/api/orders", sign(encoder, claims("smartroute", Instant.now(), Duration.ofMinutes(5))))
                .andExpect(status().isOk());
    }

    @Test
    void theApiDocumentIsOpenHereAndCanBeClosed() throws Exception {
        // API_DOCS_PUBLIC defaults to true, which is why this passes without a token: the document is a map
        // of every endpoint, and that is a deliberate choice for a localhost demo, not an oversight.
        // ApiDocsClosedTest runs the same request with the setting off.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt, Duration ttl) {
        return JwtClaimsSet.builder().issuer(issuer).subject("1").issuedAt(issuedAt).expiresAt(issuedAt.plus(ttl))
                .claim("email", "admin@test.local").claim("role", "ADMIN").build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
