package launchday.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import launchday.model.ApiResult;
import launchday.service.ReservationService;

import java.io.IOException;
import java.util.Map;

/**
 * Routes /reservations:
 *   POST /reservations         -> create (idempotent)
 *   GET  /reservations/{id}    -> fetch one
 *   GET  /reservations?userId= -> list
 */
public final class ReservationsHandler implements HttpHandler {
    private static final Gson GSON = new Gson();
    private final ReservationService service;

    public ReservationsHandler(ReservationService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (HttpJson.preflight(ex)) return;
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if ("POST".equals(method) && "/reservations".equals(path)) {
            create(ex);
        } else if ("GET".equals(method) && path.startsWith("/reservations/")) {
            getOne(ex, path.substring("/reservations/".length()));
        } else if ("GET".equals(method)) {
            list(ex);
        } else {
            HttpJson.writeJson(ex, 405, Map.of("error", "method"));
        }
    }

    private void create(HttpExchange ex) throws IOException {
        JsonObject req = GSON.fromJson(HttpJson.readBody(ex), JsonObject.class);
        String itemId = req.get("itemId").getAsString();
        String userId = req.get("userId").getAsString();
        Integer qty = req.has("qty") ? req.get("qty").getAsInt() : null;
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");

        ApiResult result = service.reserve(itemId, userId, qty, key);
        HttpJson.writeJson(ex, result.code(), result.body());
    }

    private void getOne(HttpExchange ex, String id) throws IOException {
        try {
            Map<String, Object> r = service.getById(id);
            if (r == null) {
                HttpJson.writeJson(ex, 404, Map.of("error", "not_found"));
            } else {
                HttpJson.writeJson(ex, 200, r);
            }
        } catch (Exception e) {
            HttpJson.writeJson(ex, 500, Map.of("error", "query"));
        }
    }

    private void list(HttpExchange ex) throws IOException {
        try {
            HttpJson.writeJson(ex, 200, service.listByUser(HttpJson.queryParam(ex, "userId")));
        } catch (Exception e) {
            HttpJson.writeJson(ex, 500, Map.of("error", "query"));
        }
    }
}
