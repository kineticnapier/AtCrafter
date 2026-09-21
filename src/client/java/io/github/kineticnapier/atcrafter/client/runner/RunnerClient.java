package io.github.kineticnapier.atcrafter.client.runner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunnerClient {
    private static final URI HEALTH_URI = URI.create("http://127.0.0.1:8765/health");
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

    public enum Status {
        UNKNOWN("Runner: Unknown", 0xA0A0A0),
        CHECKING("Runner: Checking...", 0xE0E0E0),
        ONLINE("Runner: Online", 0x55FF55),
        OFFLINE("Runner: Offline", 0xFF5555);

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
