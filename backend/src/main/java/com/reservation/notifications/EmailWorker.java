package com.reservation.notifications;

import com.reservation.authentication.verification.EmailVerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads email jobs from a Redis Stream and sends them through Resend.
 *
 * Delivery is AT-LEAST-ONCE: a job is acknowledged (XACK) only after it was handled.
 * If the worker crashes after Resend accepted the request but before XACK, the job is
 * reclaimed later and the user may receive a duplicate email. Also, "accepted by Resend"
 * does not guarantee that the email reaches the inbox.
 *
 * Each loop cycle does two things:
 *   1. recover: take over pending jobs (a failed or abandoned job) once they have been idle
 *      long enough, and handle them;
 *   2. read: handle brand-new jobs.
 * Both paths use the same handle() method.
 *
 * Retry policy: the wait before delivery n+1 is minIdle * 2^(n-1), capped at minIdle * 30.
 * With the defaults (60s, 8 attempts) the waits are 1, 2, 4, 8, 16, 30, 30 minutes, so a job
 * survives roughly 90 minutes of Resend or database trouble. After maxAttempts the job is
 * dead-lettered, but only if its token is still active (checked first); if the database
 * cannot answer, the job stays pending instead of being discarded.
 */
@Component
@Profile("worker & !resend-test")
public class EmailWorker implements CommandLineRunner {

    private static final String STREAM = "reservation:email-jobs";
    private static final String GROUP = "email-workers";
    private static final long RETRY_DELAY_MIN_MS = 1_000;
    private static final long RETRY_DELAY_MAX_MS = 30_000;
    private static final int BATCH_SIZE = 5;
    // Backed-off jobs stay in the pending list, so look at more entries than we handle.
    private static final int PENDING_SCAN_SIZE = 50;
    private static final int MAX_BACKOFF_FACTOR = 30;
    private static final java.util.Set<String> KNOWN_TYPES = java.util.Set.of("TEST", "VERIFY_EMAIL");

    private final EmailVerificationService verificationService;
    private final ResendEmailSender emailSender;
    private final StringRedisTemplate redisTemplate;

    private final String stream;
    private final String deadLetterStream;
    // A unique name per process: two workers must never share one consumer name.
    private final Consumer consumer = Consumer.from(GROUP, "worker-" + UUID.randomUUID().toString().substring(0, 8));
    private final Duration minIdle;
    private final int maxAttempts;
    private final Duration blockTime;

    private boolean groupReady;
    private int consecutiveFailures;

    @Autowired
    public EmailWorker(
            StringRedisTemplate redisTemplate,
            EmailVerificationService verificationService,
            ResendEmailSender emailSender,
            @Value("${email.worker.min-idle-seconds:60}") long minIdleSeconds,
            @Value("${email.worker.max-attempts:8}") int maxAttempts
    ) {
        this(redisTemplate, verificationService, emailSender, STREAM,
                Duration.ofSeconds(minIdleSeconds), maxAttempts, Duration.ofSeconds(2));
    }

    // Used by tests: own stream key, so development jobs are never touched.
    EmailWorker(
            StringRedisTemplate redisTemplate,
            EmailVerificationService verificationService,
            ResendEmailSender emailSender,
            String stream,
            Duration minIdle,
            int maxAttempts,
            Duration blockTime
    ) {
        this.redisTemplate = redisTemplate;
        this.verificationService = verificationService;
        this.emailSender = emailSender;
        this.stream = stream;
        this.deadLetterStream = stream + ":dead";
        this.minIdle = minIdle;
        this.maxAttempts = maxAttempts;
        this.blockTime = blockTime;
    }

    @Override
    public void run(String... args) throws InterruptedException {
        System.out.println("Email Worker started");

        while (!Thread.currentThread().isInterrupted()) {
            long delay = runCycle();
            if (delay > 0) {
                Thread.sleep(delay);
            }
        }
    }

    /** One loop iteration. Returns how many ms to wait before the next one (0 = no wait). */
    long runCycle() {
        try {
            if (!groupReady) {
                ensureGroup();
                groupReady = true;
            }
            recoverIdleJobs();
            readNewJobs();
            consecutiveFailures = 0;
            return 0;
        } catch (RuntimeException e) {
            // Redis down, group deleted (NOGROUP), etc. Retry later with a growing, capped delay.
            groupReady = false;
            consecutiveFailures++;
            System.err.println("Email worker cycle failed: " + e.getClass().getSimpleName());
            return retryDelayMs(consecutiveFailures);
        }
    }

    static long retryDelayMs(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), 10);
        return Math.min(RETRY_DELAY_MIN_MS << shift, RETRY_DELAY_MAX_MS);
    }

    /** How long a job delivered {@code deliveries} times must be idle before the next attempt. */
    static Duration requiredIdle(Duration base, long deliveries) {
        long factor = 1L << Math.min(Math.max(deliveries - 1, 0), 5);
        return base.multipliedBy(Math.min(factor, MAX_BACKOFF_FACTOR));
    }

    /**
     * Creates the group (and the stream if needed). A NEW group starts at "0-0", so jobs
     * published before the worker ever started are not skipped (finished or expired ones are
     * cheaply acknowledged by the token check). An existing group is never reset: BUSYGROUP
     * means another worker (or an earlier run) already created it, which is fine.
     */
    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), GROUP);
        } catch (RuntimeException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes over jobs that nobody finished. Equivalent to XAUTOCLAIM (XPENDING with a minimum
     * idle time, then XCLAIM with the same minimum idle time), but it also tells us the
     * delivery counter. XCLAIM resets the idle time, so a second worker that tries to claim
     * the same job a moment later gets nothing: workers do not steal active jobs from each other.
     */
    private void recoverIdleJobs() {
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(stream, GROUP, Range.unbounded(), PENDING_SCAN_SIZE, minIdle);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        // Backoff: a job that was already delivered n times must be idle for requiredIdle(n).
        List<RecordId> ids = new ArrayList<>();
        for (PendingMessage p : pending) {
            if (ids.size() < BATCH_SIZE
                    && p.getElapsedTimeSinceLastDelivery().compareTo(
                            requiredIdle(minIdle, p.getTotalDeliveryCount())) >= 0) {
                ids.add(p.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }

        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                stream, GROUP, consumer.getName(),
                XClaimOptions.minIdle(minIdle).ids(ids));
        if (claimed == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : claimed) {
            // XCLAIM counts as one more delivery than XPENDING reported.
            long deliveries = pending.stream()
                    .filter(p -> p.getId().equals(record.getId()))
                    .mapToLong(p -> p.getTotalDeliveryCount() + 1)
                    .findFirst().orElse(1);
            handle(record, deliveries);
        }
    }

    private void readNewJobs() {
        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                consumer,
                StreamReadOptions.empty().count(BATCH_SIZE).block(blockTime),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (messages == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : messages) {
            handle(record, 1);
        }
    }

    /**
     * The single place that decides what happens to a job.
     * - success or deliberate terminal decision -> XACK (after a dead-letter entry if it failed)
     * - temporary failure -> no XACK; the job stays pending and is retried after minIdle
     */
    void handle(MapRecord<String, Object, Object> record, long deliveries) {
        String id = record.getId().getValue();
        Map<Object, Object> fields = record.getValue();
        String type = asString(fields.get("type"));

        if ("TEST".equals(type)) {
            acknowledge(record);
            System.out.println("TEST job processed: " + id);
            return;
        }

        if (!"VERIFY_EMAIL".equals(type)) {
            // Retrying cannot fix an unknown or missing type.
            deadLetter(record, type, type == null ? "missing-type" : "unknown-type", deliveries);
            return;
        }

        String email = asString(fields.get("email"));
        String rawToken = asString(fields.get("token"));
        if (email == null || email.isBlank() || rawToken == null || rawToken.isBlank()) {
            deadLetter(record, type, "malformed-job", deliveries);
            return;
        }

        try {
            // The token is checked first, even for a job that ran out of attempts: an expired
            // token is simply skipped, and a database error leaves the job pending (not discarded).
            if (!verificationService.isTokenActive(rawToken)) {
                acknowledge(record);
                System.out.println("Verification token no longer active, job skipped: " + id);
                return;
            }
            if (deliveries > maxAttempts) {
                deadLetter(record, type, "max-attempts-exceeded", deliveries);
                return;
            }
            emailSender.sendVerificationEmail(email, rawToken);
        } catch (Exception e) {
            // Never log the email, token or the Resend response body.
            System.err.println("Email job " + id + " failed (attempt " + deliveries + "/" + maxAttempts
                    + "): " + e.getClass().getSimpleName());
            return; // no XACK: retried later with backoff, or dead-lettered once attempts run out
        }

        acknowledge(record);
        System.out.println("Verification email accepted by Resend: " + id);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(stream, GROUP, record.getId());
    }

    /**
     * Terminal failure: store a small note (never the email or token) in the dead-letter
     * stream first, and only then XACK. If we crash in between, the job is handled again
     * and we may get a duplicate dead-letter note, but the job is never lost silently.
     */
    private void deadLetter(MapRecord<String, Object, Object> record, String type, String reason, long deliveries) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("originalId", record.getId().getValue());
        // Only known job types are copied; anything else could contain sensitive text.
        note.put("type", type != null && KNOWN_TYPES.contains(type) ? type : "unknown");
        note.put("reason", reason);
        note.put("deliveries", String.valueOf(deliveries));

        redisTemplate.opsForStream().add(StreamRecords.string(note).withStreamKey(deadLetterStream));
        acknowledge(record);
        // Trimming is housekeeping and runs after the XACK: if it fails, the terminal failure
        // is already recorded and acknowledged, so it can never be recorded twice.
        try {
            redisTemplate.opsForStream().trim(deadLetterStream, 1000, true);
        } catch (RuntimeException e) {
            System.err.println("Dead-letter trim failed: " + e.getClass().getSimpleName());
        }
        System.err.println("Email job " + record.getId().getValue() + " moved to dead-letter: " + reason);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
