package launchday.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import launchday.service.CircuitBreaker;

import java.io.IOException;
import java.util.Map;

/** GET /health — reports authority reachability and current mode truthfully. */
public final class HealthHandler implements HttpHandler {
    private final CircuitBreaker breaker;

    public HealthHandler(CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (HttpJson.preflight(ex)) return;
        HttpJson.writeJson(ex, 200, Map.of(
                "status", "ok",
                "authority", breaker.isAuthorityHealthy() ? "healthy" : "down",
                "mode", breaker.mode().wire()));
    }
}
