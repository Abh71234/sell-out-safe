package launchday.model;

/** A durable reservation record as read from the database. */
public record Reservation(
        String id,
        String itemId,
        String userId,
        int qty,
        String status,
        String mode,
        String idempotencyKey,
        String bodyHash) {
}
