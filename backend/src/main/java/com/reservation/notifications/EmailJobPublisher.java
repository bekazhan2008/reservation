package com.reservation.notifications;

import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmailJobPublisher {

    private static final String STREAM_KEY =
            "reservation:email-jobs";

    private final StringRedisTemplate redisTemplate;

    public EmailJobPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String publishTestJob() {
        var record = StreamRecords.string(Map.of(
                "type", "TEST",
                "message", "hello-from-java"
        )).withStreamKey(STREAM_KEY);

        var recordId = redisTemplate.opsForStream().add(record);

        if (recordId == null) {
            throw new IllegalStateException("Redis did not return a record ID");
        }

        return recordId.getValue();
    }

    public String publishVerificationEmail(String email, String rawToken) {
        var record = StreamRecords.string(Map.of(
                "type", "VERIFY_EMAIL",
                "email", email,
                "token", rawToken
        )).withStreamKey(STREAM_KEY);

        var recordId = redisTemplate.opsForStream().add(record);

        if (recordId == null) {
            throw new IllegalStateException("Redis did not return a record ID");
        }

        return recordId.getValue();
    }
}