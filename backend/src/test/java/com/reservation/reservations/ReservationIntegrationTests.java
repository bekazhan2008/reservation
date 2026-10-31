package com.reservation.reservations;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// EmailJobPublisher подменён моком: тест не обращается к Redis и не отправляет письма.
// Объявления создаются прямо в базе reservation_test, поэтому RustFS не затрагивается.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReservationIntegrationTests {

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
    private long ownerId;
    private long propertyId;
    private LocalDate start;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("tests must run against a *_test database").endsWith("_test");
        domain = "it-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        typeId = jdbc.queryForObject("select min(id) from property_types", Long.class);
        ownerId = createUser("owner", true);
        createUser("tenant", true);
        createUser("tenant2", true);
        propertyId = createProperty(ownerId, true);
        start = LocalDate.now().plusDays(30);
    }

    @AfterEach
    void cleanUp() {
        String users = "select id from users where email like ?";
        String pattern = "%@" + domain;
        jdbc.update("delete from reservations where tenant_id in (" + users + ")", pattern);
        jdbc.update("delete from properties where owner_id in (" + users + ")", pattern);
        jdbc.update("delete from users where email like ?", pattern);
    }

    @Test
    void verifiedUserCreatesPendingReservation() throws Exception {
        HttpResponse<String> response = create(login("tenant"), propertyId, start, 3);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.path("status").asString()).isEqualTo("PENDING");
        assertThat(json.path("propertyId").asLong()).isEqualTo(propertyId);
        assertThat(json.path("startDate").asString()).isEqualTo(start.toString());
        assertThat(json.path("endDate").asString()).isEqualTo(start.plusMonths(3).toString());
        assertThat(json.path("months").asInt()).isEqualTo(3);
        assertThat(json.path("totalPrice").decimalValue()).isEqualByComparingTo("300");
        assertThat(status(json.path("id").asLong())).isEqualTo("PENDING");
    }

    @Test
    void guestCannotCreateReservation() throws Exception {
        ApiSession guest = new ApiSession(baseUrl());

        assertApiError(create(guest, propertyId, start, 1), 401, "Authentication required");
        assertThat(count()).isZero();
    }

    @Test
    void requestWithoutCsrfIsRejected() throws Exception {
        ApiSession tenant = login("tenant");

        HttpResponse<String> response = tenant.postJson(path(propertyId), body(start, 1));

        assertApiError(response, 403, "Invalid or missing CSRF token");
        assertThat(count()).isZero();
    }

    @Test
    void unverifiedUserCannotCreateReservation() throws Exception {
        long userId = createUser("unverified", true);
        ApiSession session = login("unverified");
        // Сессия уже выдана, но состояние аккаунта проверяется по базе при каждом запросе.
        jdbc.update("update users set email_verified = false where id = ?", userId);

        assertApiError(create(session, propertyId, start, 1), 403, "Access denied");
        assertThat(count()).isZero();
    }

    @Test
    void ownerCannotReserveOwnProperty() throws Exception {
        HttpResponse<String> response = create(login("owner"), propertyId, start, 1);

        assertApiError(response, 400, "You cannot rent your own property");
        assertThat(count()).isZero();
    }

    @Test
    void unpublishedOrMissingPropertyCannotBeReserved() throws Exception {
        long hidden = createProperty(ownerId, false);
        ApiSession tenant = login("tenant");

        assertApiError(create(tenant, hidden, start, 1), 404, "Property not found");
        assertApiError(create(tenant, 999999999L, start, 1), 404, "Property not found");
        assertThat(count()).isZero();
    }

    @Test
    void invalidDatesAndDurationsAreRejected() throws Exception {
        ApiSession tenant = login("tenant");
        String path = path(propertyId);

        assertApiError(create(tenant, propertyId, LocalDate.now().minusDays(1), 1), 400, "Validation failed");
        assertApiError(create(tenant, propertyId, start, 0), 400, "Validation failed");
        assertApiError(create(tenant, propertyId, start, 13), 400, "Validation failed");
        assertApiError(create(tenant, propertyId, start, -1), 400, "Validation failed");
        assertApiError(tenant.postJsonWithCsrf(path, "{\"months\":1}"), 400, "Validation failed");
        assertApiError(tenant.postJsonWithCsrf(path, "{\"startDate\":\"2030-01-01\"}"), 400, "Malformed request body");
        assertApiError(tenant.postJsonWithCsrf(path, "{\"startDate\":\"2030-02-31\",\"months\":1}"),
                400, "Malformed request body");
        assertThat(count()).isZero();
    }

    @Test
    void onlyOwnerCanConfirmOrReject() throws Exception {
        long id = insertReservation("tenant", propertyId, start, 1, "PENDING");

        for (String action : List.of("confirm", "reject")) {
            assertApiError(act(login("tenant"), propertyId, id, action), 403, "Access denied");
            assertApiError(act(login("tenant2"), propertyId, id, action), 403, "Access denied");
            assertApiError(act(new ApiSession(baseUrl()), propertyId, id, action), 401, "Authentication required");
        }
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void ownerCanConfirmPendingReservation() throws Exception {
        long id = insertReservation("tenant", propertyId, start, 1, "PENDING");

        HttpResponse<String> response = act(login("owner"), propertyId, id, "confirm");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(status(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void ownerCanRejectPendingReservation() throws Exception {
        long id = insertReservation("tenant", propertyId, start, 1, "PENDING");

        HttpResponse<String> response = act(login("owner"), propertyId, id, "reject");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("REJECTED");
        assertThat(status(id)).isEqualTo("REJECTED");
    }

    @Test
    void finalReservationsCannotBeConfirmedOrRejectedAgain() throws Exception {
        ApiSession owner = login("owner");
        long confirmed = insertReservation("tenant", propertyId, start, 1, "CONFIRMED");
        long rejected = insertReservation("tenant", propertyId, start.plusMonths(6), 1, "REJECTED");

        assertApiError(act(owner, propertyId, confirmed, "confirm"), 409, "Only pending reservations can be confirmed");
        assertApiError(act(owner, propertyId, confirmed, "reject"), 409, "Only pending reservations can be rejected");
        assertApiError(act(owner, propertyId, rejected, "confirm"), 409, "Only pending reservations can be confirmed");
        assertApiError(act(owner, propertyId, rejected, "reject"), 409, "Only pending reservations can be rejected");
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(status(rejected)).isEqualTo("REJECTED");
    }

    @Test
    void overlappingReservationsCannotBothBeConfirmed() throws Exception {
        ApiSession owner = login("owner");
        long first = insertReservation("tenant", propertyId, start, 3, "PENDING");
        long second = insertReservation("tenant2", propertyId, start.plusMonths(1), 3, "PENDING");

        assertThat(act(owner, propertyId, first, "confirm").statusCode()).isEqualTo(200);
        assertApiError(act(owner, propertyId, second, "confirm"), 409,
                "Property is already reserved for these dates");

        assertThat(status(first)).isEqualTo("CONFIRMED");
        assertThat(status(second)).isEqualTo("PENDING");
    }

    @Test
    void adjacentPeriodsCanBothBeConfirmed() throws Exception {
        ApiSession owner = login("owner");
        long first = insertReservation("tenant", propertyId, start, 2, "PENDING");
        long second = insertReservation("tenant2", propertyId, start.plusMonths(2), 2, "PENDING");

        assertThat(act(owner, propertyId, first, "confirm").statusCode()).isEqualTo(200);
        assertThat(act(owner, propertyId, second, "confirm").statusCode()).isEqualTo(200);
        assertThat(status(first)).isEqualTo("CONFIRMED");
        assertThat(status(second)).isEqualTo("CONFIRMED");
    }

    @Test
    void sameDatesOnDifferentPropertiesDoNotConflict() throws Exception {
        long otherProperty = createProperty(ownerId, true);
        ApiSession owner = login("owner");
        long first = insertReservation("tenant", propertyId, start, 2, "PENDING");
        long second = insertReservation("tenant2", otherProperty, start, 2, "PENDING");

        assertThat(act(owner, propertyId, first, "confirm").statusCode()).isEqualTo(200);
        assertThat(act(owner, otherProperty, second, "confirm").statusCode()).isEqualTo(200);
    }

    @Test
    void mismatchedPropertyIdReturns404() throws Exception {
        long otherProperty = createProperty(ownerId, true);
        long id = insertReservation("tenant", propertyId, start, 1, "PENDING");
        ApiSession owner = login("owner");

        assertApiError(act(owner, otherProperty, id, "confirm"), 404, "Reservation not found");
        assertApiError(act(owner, otherProperty, id, "reject"), 404, "Reservation not found");
        assertApiError(act(owner, propertyId, 999999999L, "confirm"), 404, "Reservation not found");
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void concurrentConfirmationOfOverlappingReservationsConfirmsAtMostOne() throws Exception {
        long first = insertReservation("tenant", propertyId, start, 3, "PENDING");
        long second = insertReservation("tenant2", propertyId, start.plusMonths(1), 3, "PENDING");
        ApiSession ownerA = login("owner");
        ApiSession ownerB = login("owner");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            results.add(pool.submit(() -> race(ownerA, first, ready, go)));
            results.add(pool.submit(() -> race(ownerB, second, ready, go)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<HttpResponse<String>> result : results) {
                codes.add(result.get(30, TimeUnit.SECONDS).statusCode());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from reservations where property_id = ? and status = 'CONFIRMED'",
                Long.class, propertyId)).isEqualTo(1);
    }

    // ---- helpers ----

    // CSRF-токен берём до старта гонки, чтобы в ней участвовали только сами PATCH-запросы.
    private HttpResponse<String> race(ApiSession owner, long reservationId, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        JsonNode csrf = owner.fetchCsrf();
        ready.countDown();
        go.await();
        return owner.sendJsonWithCsrfToken("PATCH", path(propertyId) + "/" + reservationId + "/confirm", "",
                csrf.path("headerName").asString(), csrf.path("token").asString());
    }

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

    private HttpResponse<String> create(ApiSession session, long property, LocalDate date, int months)
            throws Exception {
        return session.postJsonWithCsrf(path(property), body(date, months));
    }

    private HttpResponse<String> act(ApiSession session, long property, long reservationId, String action)
            throws Exception {
        return session.sendJsonWithCsrf("PATCH", path(property) + "/" + reservationId + "/" + action, "");
    }

    private String path(long property) {
        return "/api/properties/" + property + "/reservations";
    }

    private String body(LocalDate date, int months) {
        return "{\"startDate\":\"" + date + "\",\"months\":" + months + "}";
    }

    private String status(long reservationId) {
        return jdbc.queryForObject("select status from reservations where id = ?", String.class, reservationId);
    }

    private long count() {
        return jdbc.queryForObject("""
                select count(*) from reservations where property_id in
                (select id from properties where owner_id = ?)
                """, Long.class, ownerId);
    }

    private long insertReservation(String tenant, long property, LocalDate date, int months, String status) {
        return jdbc.queryForObject("""
                insert into reservations (property_id, tenant_id, start_date, end_date, months,
                                          price_per_month, total_price, status)
                values (?, (select id from users where email = ?), ?, ?, ?, 100, ?, ?) returning id
                """, Long.class, property, tenant + "@" + domain, date, date.plusMonths(months), months,
                100 * months, status);
    }

    private long createProperty(long owner, boolean published) {
        return jdbc.queryForObject("""
                insert into properties (owner_id, property_type_id, title, description, city,
                                        price_per_month, area_sqm, published)
                values (?, ?, 'Reservation test', 'Desc', 'Almaty', 100, 50, ?) returning id
                """, Long.class, owner, typeId, published);
    }

    private ApiSession login(String name) throws Exception {
        ApiSession session = new ApiSession(baseUrl());
        HttpResponse<String> response = session.postJsonWithCsrf("/api/auth/login",
                "{\"email\":\"" + name + "@" + domain + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return session;
    }

    private long createUser(String name, boolean verified) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, email_verified, enabled)
                values (?, ?, 'Test', 'User', ?, true) returning id
                """, Long.class, name + "@" + domain, passwordEncoder.encode(PASSWORD), verified);
    }

    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }
}
