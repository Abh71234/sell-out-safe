package launchday.model;

import java.util.Map;

/** Value object carrying an HTTP status code and a JSON-serialisable body. */
public record ApiResult(int code, Object body) {
    public static ApiResult of(int code, Map<String, Object> body) {
        return new ApiResult(code, body);
    }
}
