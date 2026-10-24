package com.reservation.notifications;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.*;

import java.time.Duration;

@Component
@Profile("worker")
public class EmailWorker implements CommandLineRunner {

    private static final String STREAM = "reservation:email-jobs";
    private static final String GROUP = "email-workers";
    private static final String CONSUMER = "worker-1";

    private final StringRedisTemplate redisTemplate;

    public EmailWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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
                    System.out.println("VERIFY_EMAIL job pending Resend integration");

                    // Не подтверждаем: письмо ещё не отправлено.
                } else {
                    System.out.println("Unknown job type; leaving pending");
                }
            }
        }
    }
}