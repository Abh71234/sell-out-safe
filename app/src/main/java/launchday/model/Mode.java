package launchday.model;

/** Operating mode of the API, reported truthfully by GET /health. */
public enum Mode {
    LIVE("live"),
    STANDIN("standin");

    private final String wire;

    Mode(String wire) {
        this.wire = wire;
    }

    /** The string form used in JSON responses and the DB. */
    public String wire() {
        return wire;
    }
}
