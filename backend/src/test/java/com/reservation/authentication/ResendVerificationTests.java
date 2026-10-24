package com.reservation.authentication;

import com.reservation.authentication.verification.EmailVerificationService;
import com.reservation.notifications.EmailJobPublisher;
import com.reservation.support.ApiSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// EmailJobPublisher подменён моком: тест не обращается к Redis и не пишет в поток разработки.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResendVerificationTests {

    private static final String PATH = "/api/auth/resend-verification";

    @MockitoBean
    private EmailJobPublisher emailJobPublisher;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailVerificationService verificationService;

    private String domain;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("tests must run against a *_test database").endsWith("_test");
        domain = "it-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where email like ?", "%@" + domain);
    }

    @Test
    void eligibleUserGetsNewTokenAndOldTokenStopsWorking() throws Exception {
        String email = "eligible@" + domain;
        long userId = createUser(email, false, true, false);
        insertToken(userId, "old-raw-token", Instant.now().minusSeconds(120));
        assertThat(verificationService.isTokenActive("old-raw-token")).isTrue();

        HttpResponse<String> response = resend("  " + email.toUpperCase() + " ");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(tokenCount(userId)).isEqualTo(2);
        assertThat(activeTokenCount(userId)).isEqualTo(1);
        assertThat(verificationService.isTokenActive("old-raw-token")).isFalse();

        ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
        verify(emailJobPublisher).publishVerificationEmail(eq(email), rawToken.capture());
        assertThat(verificationService.isTokenActive(rawToken.getValue())).isTrue();
        // В ответе нет токена.
        assertThat(response.body()).doesNotContain(rawToken.getValue());
    }

    @Test
    void ineligibleAndUnknownAccountsGetIdenticalResponseWithoutTokens() throws Exception {
        long verified = createUser("verified@" + domain, true, true, false);
        long disabled = createUser("disabled@" + domain, false, false, false);
        long deleted = createUser("deleted@" + domain, false, true, true);
        long eligible = createUser("eligible@" + domain, false, true, false);

        HttpResponse<String> expected = resend("eligible@" + domain);
        assertThat(expected.statusCode()).isEqualTo(202);

        for (String name : List.of("unknown", "verified", "disabled", "deleted")) {
            HttpResponse<String> response = resend(name + "@" + domain);
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).isEqualTo(expected.body());
        }

        assertThat(tokenCount(verified)).isZero();
        assertThat(tokenCount(disabled)).isZero();
        assertThat(tokenCount(deleted)).isZero();
        assertThat(tokenCount(eligible)).isEqualTo(1);
        verify(emailJobPublisher, times(1)).publishVerificationEmail(anyString(), anyString());
    }

    @Test
    void cooldownPreventsRepeatedRequests() throws Exception {
        String email = "cooldown@" + domain;
        long userId = createUser(email, false, true, false);

        assertThat(resend(email).statusCode()).isEqualTo(202);
        assertThat(resend(email).statusCode()).isEqualTo(202);

        assertThat(tokenCount(userId)).isEqualTo(1);
        verify(emailJobPublisher, times(1)).publishVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendIsAllowedAgainAfterCooldown() throws Exception {
        String email = "after-cooldown@" + domain;
        long userId = createUser(email, false, true, false);
        insertToken(userId, "stale-token", Instant.now().minusSeconds(61));

        assertThat(resend(email).statusCode()).isEqualTo(202);

        assertThat(tokenCount(userId)).isEqualTo(2);
        assertThat(activeTokenCount(userId)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCreateOnlyOneActiveToken() throws Exception {
        String email = "concurrent@" + domain;
        long userId = createUser(email, false, true, false);
        insertToken(userId, "old-raw-token", Instant.now().minusSeconds(120));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> resend(email).statusCode()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tokenCount(userId)).isEqualTo(2);
        assertThat(activeTokenCount(userId)).isEqualTo(1);
        verify(emailJobPublisher, times(1)).publishVerificationEmail(anyString(), anyString());
    }

    @Test
    void missingCsrfIsRejected() throws Exception {
        String email = "csrf@" + domain;
        long userId = createUser(email, false, true, false);

        HttpResponse<String> response = new ApiSession(baseUrl())
                .postJson(PATH, "{\"email\":\"" + email + "\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(tokenCount(userId)).isZero();
        verify(emailJobPublisher, never()).publishVerificationEmail(anyString(), anyString());
    }

    private HttpResponse<String> resend(String email) throws Exception {
        return new ApiSession(baseUrl())
                .postJsonWithCsrf(PATH, "{\"email\":\"" + email + "\"}");
    }

    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    private long createUser(String email, boolean verified, boolean enabled, boolean deleted) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, email_verified, enabled, deleted_at)
                values (?, 'x', 'Test', 'User', ?, ?, ?) returning id
                """, Long.class, email, verified, enabled,
                deleted ? Timestamp.from(Instant.now()) : null);
    }

    private void insertToken(long userId, String rawToken, Instant createdAt) {
        jdbc.update("""
                insert into email_verification_tokens (user_id, token_hash, expires_at, created_at)
                values (?, ?, ?, ?)
                """, userId, sha256(rawToken),
                Timestamp.from(Instant.now().plusSeconds(3600)), Timestamp.from(createdAt));
    }

    private long tokenCount(long userId) {
        return jdbc.queryForObject(
                "select count(*) from email_verification_tokens where user_id = ?", Long.class, userId);
    }

    private long activeTokenCount(long userId) {
        return jdbc.queryForObject("""
                select count(*) from email_verification_tokens
                where user_id = ? and used_at is null and expires_at > now()
                """, Long.class, userId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
