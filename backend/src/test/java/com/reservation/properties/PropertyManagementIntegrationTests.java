package com.reservation.properties;

import com.reservation.notifications.EmailJobPublisher;
import com.reservation.support.ApiSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// EmailJobPublisher подменён моком: тест не обращается к Redis и не отправляет письма.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PropertyManagementIntegrationTests {

    private static final String PASSWORD = "Test-password-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @MockitoBean
    private EmailJobPublisher emailJobPublisher;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String domain;
    private long typeId;
    private long otherTypeId;
    private long categoryId;
    private long otherCategoryId;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("tests must run against a *_test database").endsWith("_test");
        domain = "it-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        typeId = jdbc.queryForObject("select min(id) from property_types", Long.class);
        otherTypeId = jdbc.queryForObject("select max(id) from property_types", Long.class);
        categoryId = newCategory();
        otherCategoryId = newCategory();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from properties where owner_id in (select id from users where email like ?)",
                "%@" + domain);
        jdbc.update("delete from users where email like ?", "%@" + domain);
        jdbc.update("delete from categories where name like ?", "it-" + domain + "-%");
    }

    @Test
    void ownerCanUpdateOwnProperty() throws Exception {
        createUser("owner");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "Old title");

        HttpResponse<String> response = owner.sendJsonWithCsrf("PUT", "/api/properties/" + id,
                body("New title", otherTypeId, "[" + otherCategoryId + "]", "2500.50", "75.5"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.path("title").asString()).isEqualTo("New title");
        assertThat(json.path("propertyTypeId").asLong()).isEqualTo(otherTypeId);
        assertThat(json.path("categoryIds")).hasSize(1);
        assertThat(json.path("categoryIds").get(0).asLong()).isEqualTo(otherCategoryId);
        assertThat(jdbc.queryForObject(
                "select count(*) from property_categories where property_id = ? and category_id = ?",
                Long.class, id, categoryId)).isZero();
    }

    @Test
    void ownerIdInJsonIsIgnored() throws Exception {
        long ownerId = createUser("owner");
        long otherId = createUser("other");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "Mine");

        String json = body("Mine 2", typeId, "[" + categoryId + "]", "100", "50")
                .replaceFirst("\\{", "{\"ownerId\":" + otherId + ",");
        assertThat(owner.sendJsonWithCsrf("PUT", "/api/properties/" + id, json).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select owner_id from properties where id = ?", Long.class, id))
                .isEqualTo(ownerId);
    }

    @Test
    void anotherUserGetsForbiddenOnUpdateAndUnpublish() throws Exception {
        createUser("owner");
        createUser("other");
        long id = createProperty(login("owner"), "Owned");
        ApiSession other = login("other");

        HttpResponse<String> put = other.sendJsonWithCsrf("PUT", "/api/properties/" + id,
                body("Hacked", typeId, "[" + categoryId + "]", "100", "50"));
        HttpResponse<String> patch = other.sendJsonWithCsrf("PATCH", "/api/properties/" + id + "/unpublish", "");

        assertApiError(put, 403, "You do not own this property");
        assertApiError(patch, 403, "You do not own this property");
        assertThat(jdbc.queryForObject("select title from properties where id = ?", String.class, id))
                .isEqualTo("Owned");
        assertThat(jdbc.queryForObject("select published from properties where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void guestIsRejectedAndMissingCsrfIsRejected() throws Exception {
        createUser("owner");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "Owned");

        ApiSession guest = new ApiSession(baseUrl());
        assertThat(guest.sendJsonWithCsrf("PUT", "/api/properties/" + id,
                body("x", typeId, "[" + categoryId + "]", "1", "1")).statusCode()).isEqualTo(401);
        assertThat(guest.sendJsonWithCsrf("PATCH", "/api/properties/" + id + "/unpublish", "").statusCode())
                .isEqualTo(401);

        // Авторизован, но без CSRF-заголовка: 403 от CSRF-фильтра.
        assertThat(owner.postJson("/api/properties", "{}").statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select published from properties where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void ownerCanUnpublishAndPropertyBecomesHidden() throws Exception {
        createUser("owner");
        createUser("other");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "To hide");

        assertThat(new ApiSession(baseUrl()).get("/api/properties/" + id).statusCode()).isEqualTo(200);

        HttpResponse<String> response = owner.sendJsonWithCsrf("PATCH", "/api/properties/" + id + "/unpublish", "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("published").asBoolean()).isFalse();

        ApiSession guest = new ApiSession(baseUrl());
        assertThat(guest.get("/api/properties/" + id).statusCode()).isEqualTo(404);
        assertThat(guest.get("/api/properties?title=To%20hide").body()).doesNotContain("\"id\":" + id + ",");

        // Чужому пользователю скрытое объявление выглядит несуществующим.
        assertApiError(login("other").sendJsonWithCsrf("PATCH", "/api/properties/" + id + "/unpublish", ""),
                404, "Property not found");
        // Владелец по-прежнему может его изменить.
        assertThat(owner.sendJsonWithCsrf("PUT", "/api/properties/" + id,
                body("Edited hidden", typeId, "[" + categoryId + "]", "100", "50")).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidReferencesAndFieldsAreRejectedWithApiError() throws Exception {
        createUser("owner");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "Valid");
        String path = "/api/properties/" + id;

        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body("t", 999999999L, "[" + categoryId + "]", "100", "50")), 400, "Property type not found");
        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body("t", typeId, "[" + categoryId + ",999999999]", "100", "50")), 400,
                "One or more categories not found");
        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body("t", typeId, "[" + categoryId + "]", "0", "50")), 400, "Validation failed");
        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body("t", typeId, "[" + categoryId + "]", "100", "-1")), 400, "Validation failed");
        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body("t", typeId, "[]", "100", "50")), 400, "Validation failed");
        assertApiError(owner.sendJsonWithCsrf("PUT", path,
                body(" ", typeId, "[" + categoryId + "]", "100", "50")), 400, "Validation failed");
        assertApiError(owner.sendJsonWithCsrf("PUT", "/api/properties/999999999",
                body("t", typeId, "[" + categoryId + "]", "100", "50")), 404, "Property not found");
        assertApiError(owner.sendJsonWithCsrf("PATCH", "/api/properties/999999999/unpublish", ""),
                404, "Property not found");

        // Неудачные обновления ничего не изменили.
        assertThat(jdbc.queryForObject("select title from properties where id = ?", String.class, id))
                .isEqualTo("Valid");
        assertThat(jdbc.queryForObject("select count(*) from property_categories where property_id = ?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void accountThatBecameDisabledDeletedOrUnverifiedCannotManageProperties() throws Exception {
        long ownerId = createUser("owner");
        ApiSession owner = login("owner");
        long id = createProperty(owner, "Owned");
        String unpublish = "/api/properties/" + id + "/unpublish";
        String message = "Account is not allowed to manage properties";

        // Сессия уже выдана, но состояние аккаунта проверяется по базе при каждом запросе.
        jdbc.update("update users set enabled = false where id = ?", ownerId);
        assertApiError(owner.sendJsonWithCsrf("PATCH", unpublish, ""), 403, message);

        jdbc.update("update users set enabled = true, deleted_at = ? where id = ?",
                Timestamp.from(Instant.now()), ownerId);
        assertApiError(owner.sendJsonWithCsrf("PUT", "/api/properties/" + id,
                body("x", typeId, "[" + categoryId + "]", "1", "1")), 403, message);

        jdbc.update("update users set deleted_at = null, email_verified = false where id = ?", ownerId);
        assertApiError(owner.sendJsonWithCsrf("PATCH", unpublish, ""), 403, message);

        assertThat(jdbc.queryForObject("select published from properties where id = ?", Boolean.class, id)).isTrue();
    }

    // ---- helpers ----

    private void assertApiError(HttpResponse<String> response, int status, String message) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.has("error")).isTrue();
        assertThat(json.has("fieldErrors")).isTrue();
        assertThat(json.path("message").asString()).isEqualTo(message);
    }

    private String body(String title, long type, String categoryIds, String price, String area) {
        return """
                {"title":"%s","description":"Desc","city":"Almaty","propertyTypeId":%d,
                 "pricePerMonth":%s,"areaSqm":%s,"categoryIds":%s}
                """.formatted(title, type, price, area, categoryIds);
    }

    private long createProperty(ApiSession session, String title) throws Exception {
        HttpResponse<String> response = session.postJsonWithCsrf("/api/properties",
                body(title, typeId, "[" + categoryId + "]", "100", "50"));
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).path("id").asLong();
    }

    private long newCategory() {
        return jdbc.queryForObject("insert into categories (name) values (?) returning id", Long.class,
                "it-" + domain + "-" + UUID.randomUUID().toString().substring(0, 6));
    }

    private ApiSession login(String name) throws Exception {
        ApiSession session = new ApiSession(baseUrl());
        HttpResponse<String> response = session.postJsonWithCsrf("/api/auth/login",
                "{\"email\":\"" + name + "@" + domain + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return session;
    }

    private long createUser(String name) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, email_verified, enabled)
                values (?, ?, 'Test', 'User', true, true) returning id
                """, Long.class, name + "@" + domain, passwordEncoder.encode(PASSWORD));
    }

    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }
}
