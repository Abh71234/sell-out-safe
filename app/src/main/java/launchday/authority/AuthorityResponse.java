package launchday.authority;

/** Outcome of a call to the Central Authority. */
public record AuthorityResponse(int code, String status, int available, boolean reached) {

    /** The authority answered in time (regardless of 2xx/4xx). */
    public boolean reached() {
        return reached;
    }

    public boolean confirmed() {
        return reached && code == 201;
    }

    public static AuthorityResponse unreachable() {
        return new AuthorityResponse(0, null, -1, false);
    }
}
