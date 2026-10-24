package com.reservation.notifications;

import com.reservation.authentication.verification.EmailVerificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.*;

import java.time.Duration;

@Component
@Profile("worker & !resend-test")
public class EmailWorker implements CommandLineRunner {

    private static final String STREAM = "reservation:email-jobs";
    private static final String GROUP = "email-workers";
    private static final String CONSUMER = "worker-1";
    private final EmailVerificationService verificationService;
    private final ResendEmailSender emailSender;

    private final StringRedisTemplate redisTemplate;

    public EmailWorker(
            StringRedisTemplate redisTemplate,
            EmailVerificationService verificationService,
            ResendEmailSender emailSender
    ) {
        this.redisTemplate = redisTemplate;
        this.verificationService = verificationService;
        this.emailSender = emailSender;
    }

    @Override
    public void run(String... args) {
        System.out.println("Email Worker started");

        while (!Thread.currentThread().isInterrupted()) {
            var messages = redisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty()
                            .count(5)
                            .block(Duration.ofSeconds(2)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed())
            );

            if (messages == null || messages.isEmpty()) {
                continue;
            }

            for (var message : messages) {
                String type = (String) message.getValue().get("type");

                if ("TEST".equals(type)) {
                    System.out.println("Processing TEST job");

                    Long acknowledged = redisTemplate.opsForStream()
                            .acknowledge(STREAM, GROUP, message.getId());

                    System.out.println("TEST job acknowledged: " + acknowledged);

                } else if ("VERIFY_EMAIL".equals(type)) {
                    try {
                        String email = (String) message.getValue().get("email");
                        String rawToken = (String) message.getValue().get("token");

                        if (email == null || rawToken == null) {
                            System.err.println("Invalid email job structure");
                            // Пока оставляем сообщение pending.
                            continue;
                        }

                        if (!verificationService.isTokenActive(rawToken)) {
                            System.out.println("Verification token is no longer active");

                            redisTemplate.opsForStream()
                                    .acknowledge(STREAM, GROUP, message.getId());

                            continue;
                        }

                        emailSender.sendVerificationEmail(email, rawToken);

                        redisTemplate.opsForStream()
                                .acknowledge(STREAM, GROUP, message.getId());

                        System.out.println("Verification email accepted by Resend");

                    } catch (Exception e) {
                        // Не раскрываем email, токен или содержимое ответа Resend.
                        System.err.println(
                                "Email job failed: " + e.getClass().getSimpleName()
                        );

                        // XACK не вызываем: задание остаётся pending.
                    }

                    // Не подтверждаем: письмо ещё не отправлено.
                } else {
                    System.out.println("Unknown job type; leaving pending");
                }
            }
        }
    }
}