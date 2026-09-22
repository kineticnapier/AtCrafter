package io.github.kineticnapier.atcrafter.client.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Client for the Runner's optional AtCoder submission bridge. */
public final class AtCoderSubmissionClient {
    private static final String BASE_URL = "http://127.0.0.1:8765";
    private static final URI SESSION_URI = URI.create(BASE_URL + "/atcoder/session");
    private static final URI SUBMIT_URI = URI.create(BASE_URL + "/atcoder/submit");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(600))
        .build();

    private AtCoderSubmissionClient() {
    }

    public static CompletableFuture<SessionStatus> checkSession() {
        HttpRequest request = HttpRequest.newBuilder(SESSION_URI)
            .timeout(Duration.ofSeconds(25))
            .GET()
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                ensureOk(response);
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                return new SessionStatus(
                    json.has("available") && json.get("available").getAsBoolean(),
                    json.has("loggedIn") && json.get("loggedIn").getAsBoolean(),
                    stringOr(json, "message", "")
                );
            });
    }

    public static CompletableFuture<SubmissionResult> submit(String problemId, String code) {
        JsonObject body = new JsonObject();
        body.addProperty("problemId", problemId);
        body.addProperty("code", code);

        HttpRequest request = HttpRequest.newBuilder(SUBMIT_URI)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                ensureOk(response);
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                return new SubmissionResult(
                    stringOr(json, "url", ""),
                    stringOr(json, "problemUrl", ""),
                    stringOr(json, "languageId", ""),
                    stringOr(json, "languageDescription", "")
                );
            });
    }

    private static void ensureOk(HttpResponse<String> response) {
        if (response.statusCode() == 200) {
            return;
        }

        String message = "Runner returned HTTP " + response.statusCode();
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("message") && !json.get("message").isJsonNull()) {
                String remoteMessage = json.get("message").getAsString();
                if (!remoteMessage.isBlank()) {
                    message = remoteMessage;
                }
            }
        } catch (RuntimeException ignored) {
        }
        throw new IllegalStateException(message);
    }

    private static String stringOr(JsonObject json, String name, String fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : fallback;
    }

    public record SessionStatus(boolean available, boolean loggedIn, String message) {
    }

    public record SubmissionResult(
        String url,
        String problemUrl,
        String languageId,
        String languageDescription
    ) {
    }
}
