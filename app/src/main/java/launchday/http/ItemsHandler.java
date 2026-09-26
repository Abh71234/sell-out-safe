package launchday.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import launchday.authority.AuthorityClient;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;

/** GET /items/{id} — proxies the authority, falling back to the shadow count. */
public final class ItemsHandler implements HttpHandler {
    private final AuthorityClient authority;

    public ItemsHandler(AuthorityClient authority) {
        this.authority = authority;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (HttpJson.preflight(ex)) return;
        String id = ex.getRequestURI().getPath().replaceFirst("^/items/", "");
        try {
            HttpResponse<String> resp = authority.getItemRaw(id);
            HttpJson.writeRaw(ex, resp.statusCode(), resp.body());
        } catch (Exception e) {
            int shadow = authority.itemAvailable(id);
            if (shadow >= 0) {
                HttpJson.writeJson(ex, 200, Map.of("itemId", id, "available", shadow, "mode", "standin"));
            } else {
                HttpJson.writeJson(ex, 502, Map.of("error", "authority_unreachable"));
            }
        }
    }
}
