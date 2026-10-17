package com.reservation.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Существует только в тестовом classpath: защищённый endpoint для проверки ответа 401.
 * В рабочее приложение не попадает.
 */
@RestController
public class ProtectedTestController {

    @GetMapping("/api/test/protected")
    public Map<String, String> secret() {
        return Map.of("status", "secret");
    }
}
