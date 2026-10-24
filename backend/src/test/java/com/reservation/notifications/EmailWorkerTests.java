package com.reservation.notifications;

import com.reservation.authentication.verification.EmailVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Uses a real Redis but ONLY a random stream key (test:email-jobs:<uuid>) that is deleted afterwards.
// Resend and the database are mocked: no real emails are sent, development jobs are never read.
class EmailWorkerTests {

    private static final String GROUP = "email-workers";
    private static final Duration MIN_IDLE = Duration.ofMillis(50);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private EmailVerificationService verification;
    private ResendEmailSender sender;
    private String stream;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST", "localhost"), 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.hasKey("test:ping");
        } catch (RuntimeException e) {
            Assumptions.abort("Redis is not reachable");
        }
        verification = mock(EmailVerificationService.class);
        sender = mock(ResendEmailSender.class);
        stream = "test:email-jobs:" + UUID.randomUUID();
        when(verification.isTokenActive(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && stream != null) {
            redis.delete(stream);
            redis.delete(stream + ":dead");
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    private EmailWorker worker(StringRedisTemplate template, int maxAttempts) {
        return new EmailWorker(template, verification, sender, stream, MIN_IDLE, maxAttempts, Duration.ofMillis(50));
    }

    private void add(Map<String, String> fields) {
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(stream));
    }

    private long pendingCount() {
        return redis.opsForStream().pending(stream, GROUP).getTotalPendingMessages();
    }

    private long deadCount() {
        Long size = redis.opsForStream().size(stream + ":dead");
        return size == null ? 0 : size;
    }

    private static Map<String, String> verifyJob() {
        return Map.of("type", "VERIFY_EMAIL", "email", "someone@example.test", "token", "raw-token");
    }

    @Test
    void createsMissingGroupOnFreshStream() {
        assertThat(redis.hasKey(stream)).isFalse();
        EmailWorker w = worker(redis, 3);

        assertThat(w.runCycle()).isZero();
        assertThat(redis.opsForStream().groups(stream).stream().map(g -> g.groupName())).contains(GROUP);

        // BUSYGROUP: a second worker starting against the same stream must not fail.
        assertThat(worker(redis, 3).runCycle()).isZero();
    }

    @Test
    void processesTestJobAndAcknowledges() {
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(Map.of("type", "TEST", "message", "hi"));

        w.runCycle();

        assertThat(pendingCount()).isZero();
        verifyNoInteractions(sender);
    }

    @Test
    void sendsVerificationEmailAndAcknowledges() {
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(verifyJob());

        w.runCycle();

        verify(sender).sendVerificationEmail("someone@example.test", "raw-token");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void skipsInactiveTokenWithoutSending() {
        when(verification.isTokenActive(anyString())).thenReturn(false);
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(verifyJob());

        w.runCycle();

        verifyNoInteractions(sender);
        assertThat(pendingCount()).isZero();
        assertThat(deadCount()).isZero();
    }

    @Test
    void anotherWorkerRecoversAbandonedPendingJob() throws Exception {
        doThrow(new IllegalStateException("boom")).when(sender).sendVerificationEmail(anyString(), anyString());
        EmailWorker first = worker(redis, 5);
        first.runCycle();
        add(verifyJob());
        first.runCycle(); // fails -> stays pending
        assertThat(pendingCount()).isEqualTo(1);

        // A worker with a long idle threshold must not steal a job that is not idle long enough.
        EmailWorker patient = new EmailWorker(redis, verification, sender, stream,
                Duration.ofSeconds(30), 5, Duration.ofMillis(50));
        patient.runCycle();
        verify(sender, times(1)).sendVerificationEmail(anyString(), anyString());

        doNothing().when(sender).sendVerificationEmail(anyString(), anyString());
        Thread.sleep(120);
        worker(redis, 5).runCycle();

        verify(sender, times(2)).sendVerificationEmail("someone@example.test", "raw-token");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void failedDeliveryIsRetriedBoundedThenDeadLettered() throws Exception {
        doThrow(new IllegalStateException("boom")).when(sender).sendVerificationEmail(anyString(), anyString());
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(verifyJob());

        // Backoff with a 50ms base: waits of 50ms, 100ms, 200ms between deliveries.
        for (int i = 0; i < 15; i++) {
            w.runCycle();
            Thread.sleep(60);
        }

        verify(sender, times(3)).sendVerificationEmail(anyString(), anyString());
        assertThat(pendingCount()).isZero();
        assertThat(deadCount()).isEqualTo(1);
        var note = redis.opsForStream().range(stream + ":dead", Range.unbounded()).get(0).getValue();
        assertThat(note.toString()).doesNotContain("someone@example.test").doesNotContain("raw-token");
        assertThat(note.get("reason")).isEqualTo("max-attempts-exceeded");
    }

    @Test
    void malformedAndUnknownJobsAreDeadLetteredWithoutSending() {
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(Map.of("type", "VERIFY_EMAIL", "email", "someone@example.test")); // no token
        add(Map.of("type", "SOMETHING_ELSE"));
        add(Map.of("foo", "bar")); // no type

        w.runCycle();

        verifyNoInteractions(sender);
        assertThat(deadCount()).isEqualTo(3);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void survivesTemporaryRedisOutage() {
        StringRedisTemplate flaky = spy(redis);
        EmailWorker w = worker(flaky, 3);
        w.runCycle();
        add(verifyJob());

        doThrow(new RedisConnectionFailureException("down")).when(flaky).opsForStream();
        long delay = w.runCycle();
        assertThat(delay).isBetween(1L, 30_000L);

        reset(flaky);
        assertThat(w.runCycle()).isZero(); // group re-checked (BUSYGROUP), job processed
        verify(sender).sendVerificationEmail("someone@example.test", "raw-token");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void processesJobsPublishedBeforeGroupExisted() {
        add(verifyJob()); // stream exists, group does not

        worker(redis, 3).runCycle();

        verify(sender).sendVerificationEmail("someone@example.test", "raw-token");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void existingGroupIsNotReset() {
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(verifyJob());
        w.runCycle(); // processed and acked
        verify(sender, times(1)).sendVerificationEmail(anyString(), anyString());

        worker(redis, 3).runCycle(); // BUSYGROUP: must not replay the already delivered job

        verify(sender, times(1)).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void unknownTypeWithSensitiveTextIsNotCopiedToDeadLetter() {
        EmailWorker w = worker(redis, 3);
        w.runCycle();
        add(Map.of("type", "leak@example.test token=SECRET123", "email", "victim@example.test"));

        w.runCycle();

        var note = redis.opsForStream().range(stream + ":dead", Range.unbounded()).get(0).getValue();
        assertThat(note.get("type")).isEqualTo("unknown");
        assertThat(note.toString()).doesNotContain("leak@").doesNotContain("SECRET123").doesNotContain("victim");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void deadLetterTrimFailureDoesNotRecordTwice() {
        StringRedisTemplate flaky = spy(redis);
        StreamOperations<String, Object, Object> ops = spy(redis.opsForStream());
        doThrow(new RedisConnectionFailureException("trim failed")).when(ops).trim(anyString(), anyLong(), anyBoolean());
        doReturn(ops).when(flaky).opsForStream();
        EmailWorker w = worker(flaky, 3);
        w.runCycle();
        add(Map.of("type", "SOMETHING_ELSE"));

        assertThat(w.runCycle()).isZero();
        assertThat(w.runCycle()).isZero();

        assertThat(deadCount()).isEqualTo(1);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void exhaustedJobWithExpiredTokenIsAckedNotDeadLettered() throws Exception {
        doThrow(new IllegalStateException("boom")).when(sender).sendVerificationEmail(anyString(), anyString());
        EmailWorker w = worker(redis, 1);
        w.runCycle();
        add(verifyJob());
        w.runCycle(); // delivery 1 fails
        when(verification.isTokenActive(anyString())).thenReturn(false);
        Thread.sleep(120);

        w.runCycle(); // delivery 2 > max, but the token expired meanwhile

        assertThat(pendingCount()).isZero();
        assertThat(deadCount()).isZero();
    }

    @Test
    void exhaustedJobStaysPendingWhileDatabaseIsDown() throws Exception {
        doThrow(new IllegalStateException("boom")).when(sender).sendVerificationEmail(anyString(), anyString());
        EmailWorker w = worker(redis, 1);
        w.runCycle();
        add(verifyJob());
        w.runCycle();
        when(verification.isTokenActive(anyString())).thenThrow(new IllegalStateException("db down"));
        Thread.sleep(120);

        w.runCycle();

        assertThat(pendingCount()).isEqualTo(1);
        assertThat(deadCount()).isZero();
    }

    @Test
    void backoffGrowsAndIsCapped() {
        Duration base = Duration.ofSeconds(60);
        assertThat(EmailWorker.requiredIdle(base, 1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(EmailWorker.requiredIdle(base, 2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(EmailWorker.requiredIdle(base, 4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(EmailWorker.requiredIdle(base, 6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(EmailWorker.requiredIdle(base, 500)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void retryDelayGrowsButIsCapped() {
        assertThat(EmailWorker.retryDelayMs(1)).isEqualTo(1_000);
        assertThat(EmailWorker.retryDelayMs(3)).isEqualTo(4_000);
        assertThat(EmailWorker.retryDelayMs(1_000)).isEqualTo(30_000);
    }
}
