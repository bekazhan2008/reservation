package com.reservation.notifications;

import com.reservation.support.ApiSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// EmailJobPublisher подменён моком: тест не обращается к Redis и не пишет в поток разработки.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistrationRedisFailureTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @MockitoBean
    private EmailJobPublisher emailJobPublisher;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

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
    void registrationSucceedsWhenRedisPublishingFails() throws Exception {
        when(emailJobPublisher.publishVerificationEmail(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        String email = "redis-down@" + domain;
        HttpResponse<String> response = new ApiSession(
                "http://localhost:" + environment.getProperty("local.server.port"))
                .postJsonWithCsrf("/api/auth/register", json.writeValueAsString(Map.of(
                        "email", email,
                        "password", "Correct-Horse-1",
                        "firstName", "Иван",
                        "lastName", "Иванов")));

        assertThat(response.statusCode()).isEqualTo(201);
        verify(emailJobPublisher).publishVerificationEmail(anyString(), anyString());
        Long users = jdbc.queryForObject("select count(*) from users where email = ?", Long.class, email);
        Long tokens = jdbc.queryForObject(
                "select count(*) from email_verification_tokens t join users u on u.id = t.user_id where u.email = ?",
                Long.class, email);
        assertThat(users).isEqualTo(1);
        assertThat(tokens).isEqualTo(1);
    }
}
