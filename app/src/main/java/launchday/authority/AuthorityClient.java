package launchday.authority;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gateway to the Central Authority. All knowledge of the authority's HTTP
 * contract and the 2-second timeout lives here; the rest of the app talks in
 * terms of {@link AuthorityResponse}.
 *
 * A slow authority (>2s) and a down authority both surface as reached=false,
 * exactly as the brief requires ("slow and down are the same to the API").
 */
public final class AuthorityClient {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final String baseUrl;

    public AuthorityClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Health probe used by the circuit breaker. */
    public boolean isHealthy() {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET()
                            .timeout(TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch an item's real available count. Returns -1 if it cannot be read. */
    public int itemAvailable(String itemId) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/items/" + itemId)).GET()
                            .timeout(TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return -1;
            JsonObject o = GSON.fromJson(r.body(), JsonObject.class);
            return (o != null && o.has("available")) ? o.get("available").getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Raw item proxy (status code + body) for GET /items/{id}. */
    public HttpResponse<String> getItemRaw(String itemId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/items/" + itemId)).GET()
                        .timeout(TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Reserve against the authority. The reservationId is chosen by us and the
     * authority is idempotent on it, so replays are safe.
     */
    public AuthorityResponse reserve(String reservationId, String itemId, String userId, int qty) {
        JsonObject body = new JsonObject();
        body.addProperty("reservationId", reservationId);
        body.addProperty("itemId", itemId);
        body.addProperty("userId", userId);
        body.addProperty("qty", qty);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/reservations"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject o = GSON.fromJson(resp.body(), JsonObject.class);
            int avail = (o != null && o.has("available")) ? o.get("available").getAsInt() : -1;
            String status = (o != null && o.has("status")) ? o.get("status").getAsString() : null;
            return new AuthorityResponse(resp.statusCode(), status, avail, true);
        } catch (Exception e) {
            return AuthorityResponse.unreachable();
        }
    }

    /** Release a hold created earlier (used when reversing during replay). */
    public void release(String reservationId) {
        JsonObject body = new JsonObject();
        body.addProperty("reservationId", reservationId);
        try {
            http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/releases"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignore) {
            // best-effort compensation
        }
    }
}
