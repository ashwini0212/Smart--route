package com.smartroute.auth;

import com.smartroute.support.DatabaseCleaner;
import com.smartroute.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a real Tomcat (MockMvc would bypass it), because the client IP is resolved by Tomcat's
 * RemoteIpValve: X-Forwarded-For is honoured only when the TCP peer is a private-network proxy.
 * Here the peer is 127.0.0.1, which counts as such a proxy, like nginx in docker compose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, DatabaseCleaner.class})
class ForwardedClientIpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LoginRateLimiter limiter;

    private final HttpClient http = HttpClient.newHttpClient();

    private int login(String email, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"%s\",\"password\":\"password-guess\"}".formatted(email)));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void limitsPerForwardedClientNotPerProxy() throws Exception {
        limiter.reset();
        // 25 different users behind the proxy, each from their own IP: none is limited (the proxy's own
        // address would have hit the 20-per-minute IP limit).
        for (int i = 0; i < 25; i++) {
            assertThat(login("user" + i + "@test.local", "203.0.113." + i)).isEqualTo(401);
        }
        // One client IP trying many accounts is limited.
        for (int i = 0; i < 20; i++) {
            login("target" + i + "@test.local", "198.51.100.7");
        }
        assertThat(login("target99@test.local", "198.51.100.7")).isEqualTo(429);
    }
}
