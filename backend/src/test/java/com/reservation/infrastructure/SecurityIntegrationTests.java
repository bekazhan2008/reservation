package com.reservation.infrastructure;

import com.reservation.support.ApiSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityIntegrationTests {

    private static final String REGISTER = "/api/auth/register";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    private String domain;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("tests must run against a *_test database").endsWith("_test");
        domain = "sec-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where email like ?", "%@" + domain);
    }

    @Test
    void publicGetEndpointsWorkWithoutLogin() throws Exception {
        ApiSession guest = newSession();

        HttpResponse<String> health = guest.get("/api/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body()).path("status").asString()).isEqualTo("UP");

        HttpResponse<String> types = guest.get("/api/property-types");
        assertThat(types.statusCode()).isEqualTo(200);
        assertThat(json.readTree(types.body()).size()).isEqualTo(6);
    }

    @Test
    void csrfEndpointReturnsTokenAndCreatesHttpOnlyLaxSessionCookie() throws Exception {
        ApiSession guest = newSession();

        HttpResponse<String> response = guest.get("/api/auth/csrf");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("token").asString()).isNotBlank();
        assertThat(body.path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        assertThat(body.path("parameterName").asString()).isEqualTo("_csrf");

        List<String> setCookie = response.headers().allValues("Set-Cookie");
        assertThat(setCookie).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("JSESSIONID=")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .doesNotContainIgnoringCase("Secure"));
    }

    @Test
    void registerWithoutCsrfTokenIsForbidden() throws Exception {
        ApiSession guest = newSession();
        guest.fetchCsrf(); // сессия есть, но токен не отправляем

        HttpResponse<String> response = guest.postJson(REGISTER, registration("no-token"));

        assertForbiddenCsrf(response);
        assertThat(countUsers()).isZero();
    }

    @Test
    void registerWithWrongCsrfTokenIsForbidden() throws Exception {
        ApiSession guest = newSession();
        JsonNode csrf = guest.fetchCsrf();

        HttpResponse<String> response = guest.postJson(REGISTER, registration("wrong-token"),
                csrf.path("headerName").asString(), "not-a-real-token");

        assertForbiddenCsrf(response);
        assertThat(countUsers()).isZero();
    }

    @Test
    void registerWithValidCsrfTokenAndSameSessionCookieIsCreated() throws Exception {
        ApiSession guest = newSession();

        HttpResponse<String> response = guest.postJsonWithCsrf(REGISTER, registration("valid"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void csrfTokenFromAnotherSessionIsRejected() throws Exception {
        ApiSession alice = newSession();
        ApiSession mallory = newSession();
        JsonNode aliceCsrf = alice.fetchCsrf();
        mallory.fetchCsrf(); // у второй сессии своя cookie и свой токен

        HttpResponse<String> response = mallory.postJson(REGISTER, registration("foreign-token"),
                aliceCsrf.path("headerName").asString(), aliceCsrf.path("token").asString());

        assertForbiddenCsrf(response);
        assertThat(countUsers()).isZero();
    }

    @Test
    void protectedGetWithoutLoginReturnsJson401WithoutRedirect() throws Exception {
        HttpResponse<String> response = newSession().get("/api/test/protected");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("message").asString()).isEqualTo("Authentication required");
        assertThat(body.path("fieldErrors").isArray()).isTrue();
    }

    @Test
    void defaultLoginPageIsNotExposed() throws Exception {
        HttpResponse<String> response = newSession().get("/login");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContainIgnoringCase("<html");
    }

    private void assertForbiddenCsrf(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("message").asString()).isEqualTo("Invalid or missing CSRF token");
    }

    private String registration(String localPart) {
        return json.writeValueAsString(java.util.Map.of(
                "email", localPart + "@" + domain,
                "password", "Correct-Horse-1",
                "firstName", "Test",
                "lastName", "User"));
    }

    private ApiSession newSession() {
        return new ApiSession("http://localhost:" + environment.getProperty("local.server.port"));
    }

    private long countUsers() {
        Long count = jdbc.queryForObject(
                "select count(*) from users where email like ?", Long.class, "%@" + domain);
        return count == null ? 0 : count;
    }
}
