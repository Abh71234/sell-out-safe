package launchday.model;

/** Reservation lifecycle statuses (stored in the DB and returned to clients). */
public final class Statuses {
    public static final String PROCESSING = "processing"; // transient: owner is mid-flight
    public static final String CONFIRMED  = "confirmed";
    public static final String PENDING    = "pending";    // accepted in stand-in, awaiting replay
    public static final String REJECTED   = "rejected";
    public static final String REVERSED   = "reversed";   // refused during replay

    private Statuses() {}

    public static boolean isTerminal(String status) {
        return !PROCESSING.equals(status);
    }
}
