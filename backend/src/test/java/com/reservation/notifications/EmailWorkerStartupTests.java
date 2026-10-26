package com.reservation.notifications;

import com.reservation.authentication.verification.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Only checks bean construction and @Value wiring. ApplicationContextRunner does not execute
// CommandLineRunners, so run() is never called; Redis, the database and Resend are all mocks.
class EmailWorkerStartupTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(EmailVerificationService.class, () -> mock(EmailVerificationService.class))
            .withBean(ResendEmailSender.class, () -> mock(ResendEmailSender.class))
            .withUserConfiguration(EmailWorker.class);

    @Test
    void workerProfileCreatesWorkerWithDefaultSettings() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("worker"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmailWorker.class);
                    EmailWorker worker = context.getBean(EmailWorker.class);
                    assertThat(ReflectionTestUtils.getField(worker, "minIdle")).isEqualTo(Duration.ofSeconds(60));
                    assertThat(ReflectionTestUtils.getField(worker, "maxAttempts")).isEqualTo(8);
                    assertThat(ReflectionTestUtils.getField(worker, "stream")).isEqualTo("reservation:email-jobs");
                });
    }

    @Test
    void workerProfileUsesConfiguredValues() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("worker"))
                .withPropertyValues("email.worker.min-idle-seconds=7", "email.worker.max-attempts=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EmailWorker worker = context.getBean(EmailWorker.class);
                    assertThat(ReflectionTestUtils.getField(worker, "minIdle")).isEqualTo(Duration.ofSeconds(7));
                    assertThat(ReflectionTestUtils.getField(worker, "maxAttempts")).isEqualTo(3);
                });
    }

    @Test
    void withoutWorkerProfileNoWorkerIsCreated() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmailWorker.class));
    }
}
