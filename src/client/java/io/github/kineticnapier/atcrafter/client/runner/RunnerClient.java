package io.github.kineticnapier.atcrafter.client.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunnerClient {
    private static final String BASE_URL = "http://127.0.0.1:8765";
    private static final URI HEALTH_URI = URI.create(BASE_URL + "/health");
    private static final URI RUN_URI = URI.create(BASE_URL + "/run");
    private static final URI DEBUG_URI = URI.create(BASE_URL + "/debug");
    private static final URI PROBLEMS_URI = URI.create(BASE_URL + "/problems");
    private static final String STDOUT_KEY = "$stdout";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(600))
        .build();

    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile Status status = Status.UNKNOWN;
    private static int ticksUntilNextCheck = 0;

    private RunnerClient() {
    }

    public static void tick() {
        if (ticksUntilNextCheck > 0) {
            ticksUntilNextCheck--;
            return;
        }
        ticksUntilNextCheck = 20;
        checkHealth();
    }

    public static Status getStatus() {
        return status;
    }

    public static void checkNow() {
        ticksUntilNextCheck = 0;
        checkHealth();
    }

    public static CompletableFuture<List<ProblemSummary>> listProblems() {
        HttpRequest request = HttpRequest.newBuilder(PROBLEMS_URI)
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                ensureOk(response);
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray array = json.getAsJsonArray("problems");
                List<ProblemSummary> problems = new ArrayList<>();
                for (JsonElement element : array) {
                    JsonObject problem = element.getAsJsonObject();
                    problems.add(new ProblemSummary(
                        problem.get("id").getAsString(),
                        problem.get("title").getAsString()
                    ));
                }
                return List.copyOf(problems);
            });
    }

    public static CompletableFuture<ProblemData> loadProblem(String id) {
        URI uri = URI.create(BASE_URL + "/problems/" + id);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                ensureOk(response);
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                return new ProblemData(
                    json.get("id").getAsString(),
                    json.get("title").getAsString(),
                    json.get("statement").getAsString(),
                    json.get("defaultCode").getAsString(),
                    parseTests(json.getAsJsonArray("samples")),
                    parseTests(json.getAsJsonArray("tests"))
                );
            });
    }

    private static List<TestCase> parseTests(JsonArray array) {
        List<TestCase> tests = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject test = element.getAsJsonObject();
            tests.add(new TestCase(
                test.get("name").getAsString(),
                test.get("stdin").getAsString(),
                test.get("expected").getAsString()
            ));
        }
        return List.copyOf(tests);
    }

    public static CompletableFuture<RunResult> run(String code, String stdin) {
        return postExecution(RUN_URI, code, stdin).thenApply(RunnerClient::parseRunResult);
    }

    public static CompletableFuture<DebugResult> debug(String code, String stdin) {
        return postExecution(DEBUG_URI, code, stdin).thenApply(response -> {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            RunResult run = parseRunResult(json);
            List<DebugStep> steps = new ArrayList<>();
            JsonArray stepArray = json.getAsJsonArray("steps");
            if (stepArray != null) {
                for (JsonElement element : stepArray) {
                    JsonObject step = element.getAsJsonObject();
                    Map<String, DebugValue> typedLocals = new LinkedHashMap<>();
                    Map<String, String> displayLocals = new LinkedHashMap<>();
                    JsonObject localsJson = step.getAsJsonObject("locals");
                    if (localsJson != null) {
                        for (Map.Entry<String, JsonElement> entry : localsJson.entrySet()) {
                            if (!entry.getValue().isJsonObject()) {
                                continue;
                            }
                            DebugValue value = parseDebugValue(entry.getValue().getAsJsonObject());
                            typedLocals.put(entry.getKey(), value);
                            displayLocals.put(entry.getKey(), value.display());
                        }
                    }
                    String stdout = step.has("stdout") && !step.get("stdout").isJsonNull()
                        ? step.get("stdout").getAsString()
                        : null;
                    if (stdout != null) {
                        displayLocals.put(STDOUT_KEY, stdout);
                    }
                    steps.add(new DebugStep(
                        step.get("line").getAsInt(),
                        step.get("event").getAsString(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(displayLocals)),
                        Collections.unmodifiableMap(new LinkedHashMap<>(typedLocals)),
                        stdout
                    ));
                }
            }
            return new DebugResult(
                run,
                List.copyOf(steps),
                json.has("traceTruncated") && json.get("traceTruncated").getAsBoolean()
            );
        });
    }

    private static DebugValue parseDebugValue(JsonObject json) {
        String type = stringOr(json, "type", "object");
        String display = stringOr(json, "display", "?");

        List<DebugValue> items = new ArrayList<>();
        JsonArray itemArray = json.getAsJsonArray("items");
        if (itemArray != null) {
            for (JsonElement element : itemArray) {
                if (element.isJsonObject()) {
                    items.add(parseDebugValue(element.getAsJsonObject()));
                }
            }
        }

        List<DebugEntry> entries = new ArrayList<>();
        JsonArray entryArray = json.getAsJsonArray("entries");
        if (entryArray != null) {
            for (JsonElement element : entryArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                JsonObject key = entry.getAsJsonObject("key");
                JsonObject value = entry.getAsJsonObject("value");
                if (key != null && value != null) {
                    entries.add(new DebugEntry(parseDebugValue(key), parseDebugValue(value)));
                }
            }
        }

        Boolean boolValue = json.has("boolValue") && !json.get("boolValue").isJsonNull()
            ? json.get("boolValue").getAsBoolean()
            : null;
        String numberText = nullableString(json, "numberText");
        String text = nullableString(json, "text");
        String className = nullableString(json, "className");
        boolean truncated = json.has("truncated") && json.get("truncated").getAsBoolean();

        return new DebugValue(
            type,
            display,
            List.copyOf(items),
            List.copyOf(entries),
            boolValue,
            numberText,
            text,
            className,
            truncated
        );
    }

    private static String stringOr(JsonObject json, String name, String fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : fallback;
    }

    private static String nullableString(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : null;
    }

    private static CompletableFuture<HttpResponse<String>> postExecution(URI uri, String code, String stdin) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("stdin", stdin);
        body.addProperty("timeoutMs", 2_000);

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                ensureOk(response);
                return response;
            });
    }

    private static RunResult parseRunResult(HttpResponse<String> response) {
        return parseRunResult(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private static RunResult parseRunResult(JsonObject json) {
        Integer exitCode = json.get("exitCode").isJsonNull() ? null : json.get("exitCode").getAsInt();
        return new RunResult(
            json.get("status").getAsString(),
            exitCode,
            json.get("stdout").getAsString(),
            json.get("stderr").getAsString(),
            json.get("elapsedMs").getAsDouble(),
            json.get("timedOut").getAsBoolean(),
            json.get("outputTruncated").getAsBoolean()
        );
    }

    private static void ensureOk(HttpResponse<?> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Runner returned HTTP " + response.statusCode());
        }
    }

    private static void checkHealth() {
        if (!REQUEST_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        status = Status.CHECKING;
        HttpRequest request = HttpRequest.newBuilder(HEALTH_URI)
            .timeout(Duration.ofMillis(800))
            .GET()
            .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .whenComplete((response, error) -> {
                if (error == null && response.statusCode() == 200) {
                    status = Status.ONLINE;
                } else {
                    status = Status.OFFLINE;
                }
                REQUEST_IN_FLIGHT.set(false);
            });
    }

    public record ProblemSummary(String id, String title) {
    }

    public record TestCase(String name, String stdin, String expected) {
    }

    public record ProblemData(
        String id,
        String title,
        String statement,
        String defaultCode,
        List<TestCase> samples,
        List<TestCase> tests
    ) {
    }

    public record RunResult(
        String status,
        Integer exitCode,
        String stdout,
        String stderr,
        double elapsedMs,
        boolean timedOut,
        boolean outputTruncated
    ) {
    }

    public record DebugValue(
        String type,
        String display,
        List<DebugValue> items,
        List<DebugEntry> entries,
        Boolean boolValue,
        String numberText,
        String text,
        String className,
        boolean truncated
    ) {
        public boolean isSequence() {
            return type.equals("list")
                || type.equals("tuple")
                || type.equals("set")
                || type.equals("frozenset")
                || type.equals("deque");
        }

        public boolean isSetLike() {
            return type.equals("set") || type.equals("frozenset");
        }

        public boolean isDeque() {
            return type.equals("deque");
        }

        public boolean isDict() {
            return type.equals("dict");
        }
    }

    public record DebugEntry(DebugValue key, DebugValue value) {
    }

    public record DebugStep(
        int line,
        String event,
        Map<String, String> locals,
        Map<String, DebugValue> typedLocals,
        String stdout
    ) {
    }

    public record DebugResult(RunResult run, List<DebugStep> steps, boolean traceTruncated) {
    }

    public enum Status {
        UNKNOWN("Runner: 不明", 0xA0A0A0),
        CHECKING("Runner: 確認中...", 0xE0E0E0),
        ONLINE("Runner: オンライン", 0x55FF55),
        OFFLINE("Runner: オフライン", 0xFF5555);

        private final String label;
        private final int color;

        Status(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public int color() {
            return color;
        }
    }
}
