package launchday.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import launchday.service.ReservationService;

import java.io.IOException;
import java.util.Map;

/**
 * Admin endpoints:
 *   POST /admin/reset          -> clear state
 *   POST /admin/reconcile      -> replay the durable queue now
 *   GET  /admin/audit?reservationId= -> audit trail (stretch)
 */
public final class AdminHandler implements HttpHandler {
    public enum Action { RESET, RECONCILE, AUDIT }

    private final ReservationService service;
    private final Action action;

    public AdminHandler(ReservationService service, Action action) {
        this.service = service;
        this.action = action;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (HttpJson.preflight(ex)) return;
        switch (action) {
            case RESET -> reset(ex);
            case RECONCILE -> reconcile(ex);
            case AUDIT -> audit(ex);
        }
    }

    private void reset(HttpExchange ex) throws IOException {
        try {
            service.reset();
            HttpJson.writeJson(ex, 200, Map.of("status", "reset"));
        } catch (Exception e) {
            HttpJson.writeJson(ex, 500, Map.of("error", "reset"));
        }
    }

    private void reconcile(HttpExchange ex) throws IOException {
        int[] r = service.replay();
        HttpJson.writeJson(ex, 200, Map.of("confirmed", r[0], "reversed", r[1]));
    }

    private void audit(HttpExchange ex) throws IOException {
        try {
            HttpJson.writeJson(ex, 200, service.audit(HttpJson.queryParam(ex, "reservationId")));
        } catch (Exception e) {
            HttpJson.writeJson(ex, 500, Map.of("error", "query"));
        }
    }
}
