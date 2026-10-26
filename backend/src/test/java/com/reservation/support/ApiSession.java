package com.reservation.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Отдельный HTTP-клиент со своим хранилищем cookie — как отдельная вкладка браузера или окно Postman.
 * Cookie JSESSIONID, полученная в одном экземпляре, не видна другим.
 */
public class ApiSession {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiSession(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    /** Получает CSRF-токен текущей сессии: поля token, headerName, parameterName. */
    public JsonNode fetchCsrf() throws Exception {
        HttpResponse<String> response = get("/api/auth/csrf");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET /api/auth/csrf returned " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    /** POST без CSRF-заголовка. */
    public HttpResponse<String> postJson(String path, String body) throws Exception {
        return send(jsonPost(path, body).build());
    }

    /** POST с CSRF-заголовком. */
    public HttpResponse<String> postJson(String path, String body, String headerName, String token) throws Exception {
        return send(jsonPost(path, body).header(headerName, token).build());
    }

    /** Получает токен этой же сессии и отправляет POST с ним. */
    public HttpResponse<String> postJsonWithCsrf(String path, String body) throws Exception {
        JsonNode csrf = fetchCsrf();
        return postJson(path, body, csrf.path("headerName").asString(), csrf.path("token").asString());
    }

    /** Запрос с телом (PUT/PATCH/...) и CSRF-токеном текущей сессии. */
    public HttpResponse<String> sendJsonWithCsrf(String method, String path, String body) throws Exception {
        JsonNode csrf = fetchCsrf();
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header(csrf.path("headerName").asString(), csrf.path("token").asString())
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    public List<HttpCookie> cookies() {
        return cookies.getCookieStore().getCookies();
    }

    private HttpRequest.Builder jsonPost(String path, String body) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
