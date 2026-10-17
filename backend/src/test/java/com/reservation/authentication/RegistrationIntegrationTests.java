package com.reservation.authentication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistrationIntegrationTests {

    private static final String PASSWORD = "Correct-Horse-1";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Уникальный домен для данных одного теста: по нему же удаляем только свои строки.
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
    void registersUserWithNormalizedDataAndDefaults() throws Exception {
        HttpResponse<String> response = register(Map.of(
                "email", "  Anna.Petrova@" + domain.toUpperCase() + "  ",
                "password", PASSWORD,
                "firstName", "  Анна ",
                "lastName", " Петрова  "
        ));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        String email = "anna.petrova@" + domain;
        assertThat(body.path("email").asString()).isEqualTo(email);
        assertThat(body.path("firstName").asString()).isEqualTo("Анна");
        assertThat(body.path("lastName").asString()).isEqualTo("Петрова");
        assertThat(body.path("emailVerified").asBoolean()).isFalse();
        assertThat(body.path("id").asLong()).isPositive();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id", "email", "firstName", "lastName", "emailVerified");
        assertThat(response.body()).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD);

        Map<String, Object> row = jdbc.queryForMap("select * from users where id = ?", body.path("id").asLong());
        assertThat(row.get("email")).isEqualTo(email);
        assertThat(row.get("first_name")).isEqualTo("Анна");
        assertThat(row.get("last_name")).isEqualTo("Петрова");
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("email_verified")).isEqualTo(false);
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(row.get("deleted_at")).isNull();
        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        assertThat(createdAt).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        String hash = (String) row.get("password_hash");
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void ignoresRoleAndStateFieldsFromClient() throws Exception {
        HttpResponse<String> response = register(Map.of(
                "email", "mallory@" + domain,
                "password", PASSWORD,
                "firstName", "Mallory",
                "lastName", "Admin",
                "role", "ADMIN",
                "emailVerified", true,
                "enabled", false
        ));

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap(
                "select role, email_verified, enabled from users where email = ?", "mallory@" + domain);
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("email_verified")).isEqualTo(false);
        assertThat(row.get("enabled")).isEqualTo(true);
    }

    @Test
    void rejectsDuplicateEmailIgnoringCaseAndSpaces() throws Exception {
        assertThat(register(validRequest("ivan@" + domain)).statusCode()).isEqualTo(201);

        HttpResponse<String> duplicate = register(validRequest("  IVAN@" + domain.toUpperCase() + " "));

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("status").asInt()).isEqualTo(409);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneUser() throws Exception {
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String variant = i % 2 == 0 ? "race@" + domain : " RACE@" + domain.toUpperCase();
                tasks.add(() -> register(validRequest(variant)).statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(countUsers()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidAndMissingFields() throws Exception {
        HttpResponse<String> response = register(Map.of(
                "email", "not-an-email",
                "password", "short",
                "firstName", "   "
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        List<String> fields = new ArrayList<>();
        body.path("fieldErrors").forEach(error -> fields.add(error.path("field").asString()));
        assertThat(fields).contains("email", "password", "firstName", "lastName");
        assertThat(response.body()).doesNotContain("short");
        assertThat(countUsers()).isZero();
    }

    @Test
    void rejectsTooLongNamesAndEmail() throws Exception {
        String longEmail = "a".repeat(250) + "@" + domain;
        HttpResponse<String> response = register(Map.of(
                "email", longEmail,
                "password", PASSWORD,
                "firstName", "x".repeat(101),
                "lastName", "y".repeat(101)
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"email\"", "\"firstName\"", "\"lastName\"");
    }

    @Test
    void rejectsPasswordLongerThan72Utf8Bytes() throws Exception {
        // 37 кириллических символов = 74 байта UTF-8, хотя символов меньше 72.
        String password = "ж".repeat(37);
        HttpResponse<String> response = register(Map.of(
                "email", "long@" + domain,
                "password", password,
                "firstName", "Long",
                "lastName", "Password"
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"password\"").doesNotContain(password);
        assertThat(countUsers()).isZero();
    }

    @Test
    void acceptsPasswordOfExactly72Utf8Bytes() throws Exception {
        HttpResponse<String> response = register(Map.of(
                "email", "edge@" + domain,
                "password", "ж".repeat(36),
                "firstName", "Edge",
                "lastName", "Case"
        ));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        HttpResponse<String> response = post("{\"email\": \"broken@" + domain + "\", \"password\": ");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("message").asString()).isEqualTo("Malformed request body");
        assertThat(countUsers()).isZero();
    }

    @Test
    void requestToStringDoesNotExposePassword() {
        RegistrationRequest request = new RegistrationRequest("a@" + domain, PASSWORD, "A", "B");

        assertThat(request.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void propertyTypesEndpointStillWorks() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/property-types")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).size()).isEqualTo(6);
    }

    private Map<String, Object> validRequest(String email) {
        return Map.of("email", email, "password", PASSWORD, "firstName", "Иван", "lastName", "Иванов");
    }

    private HttpResponse<String> register(Map<String, Object> body) throws Exception {
        return post(json.writeValueAsString(body));
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private long countUsers() {
        Long count = jdbc.queryForObject(
                "select count(*) from users where email like ?", Long.class, "%@" + domain);
        return count == null ? 0 : count;
    }
}
