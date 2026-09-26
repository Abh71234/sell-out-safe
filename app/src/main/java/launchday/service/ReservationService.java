package launchday.service;

import launchday.authority.AuthorityClient;
import launchday.authority.AuthorityResponse;
import launchday.model.ApiResult;
import launchday.model.Mode;
import launchday.model.Reservation;
import launchday.model.Statuses;
import launchday.repository.EventRepository;
import launchday.repository.ReservationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core domain logic: idempotent reservation handling, stand-in authorisation,
 * and durable-queue replay. Depends only on the repository, the authority
 * gateway, and the circuit breaker — no HTTP or SQL details leak in here.
 */
public final class ReservationService {

    private final ReservationRepository reservations;
    private final EventRepository events;
    private final AuthorityClient authority;
    private final CircuitBreaker breaker;
    private final int standinMaxPerItem;

    /** Best-known available count per item while the authority is unreachable. */
    private final Map<String, Integer> shadow = new ConcurrentHashMap<>();
    private final Set<String> knownItems = ConcurrentHashMap.newKeySet();
    private final Object replayLock = new Object();

    public ReservationService(ReservationRepository reservations,
                              EventRepository events,
                              AuthorityClient authority,
                              CircuitBreaker breaker,
                              int standinMaxPerItem,
                              Set<String> seedItems) {
        this.reservations = reservations;
        this.events = events;
        this.authority = authority;
        this.breaker = breaker;
        this.standinMaxPerItem = standinMaxPerItem;
        this.knownItems.addAll(seedItems);
        // When the breaker recovers, resync the shadow then drain the queue.
        this.breaker.onRecover(() -> {
            resyncShadow();
            replayAsync();
        });
    }

    // ---------------- reservation flow ----------------

    public ApiResult reserve(String itemId, String userId, Integer qtyOrNull, String keyOrNull) {
        int qty = qtyOrNull == null ? 1 : qtyOrNull;
        knownItems.add(itemId);

        String rid = UUID.randomUUID().toString();
        String key = (keyOrNull == null || keyOrNull.isEmpty()) ? "auto:" + rid : keyOrNull;
        String bodyHash = sha256(itemId + "|" + qty);

        boolean owner;
        try {
            owner = reservations.claim(rid, itemId, userId, qty, key, bodyHash);
        } catch (Exception e) {
            return ApiResult.of(500, Map.of("error", "db"));
        }

        if (!owner) {
            return handleDuplicate(userId, key, bodyHash);
        }
        return handleOwner(rid, itemId, userId, qty);
    }

    /** A retry or concurrent duplicate: return the winner's stored outcome. */
    private ApiResult handleDuplicate(String userId, String key, String bodyHash) {
        try {
            Reservation existing = reservations.findByKey(userId, key);
            if (existing == null) {
                return ApiResult.of(500, Map.of("error", "db"));
            }
            if (!bodyHash.equals(existing.bodyHash())) {
                return ApiResult.of(422, Map.of("error", "idempotency_key_reused"));
            }
            Reservation terminal = awaitTerminal(userId, key);
            return storedResult(terminal);
        } catch (Exception e) {
            return ApiResult.of(500, Map.of("error", "db"));
        }
    }

    /** We own the claim: authorise it, live or stand-in. */
    private ApiResult handleOwner(String rid, String itemId, String userId, int qty) {
        if (breaker.mode() == Mode.LIVE) {
            AuthorityResponse a = authority.reserve(rid, itemId, userId, qty);
            if (a.reached()) {
                if (a.code() == 201) {
                    if (a.available() >= 0) shadow.put(itemId, a.available());
                    reservations.settle(rid, Statuses.CONFIRMED, null, Mode.LIVE.wire());
                    return ApiResult.of(201, resBody(rid, Statuses.CONFIRMED, null, Mode.LIVE));
                }
                reservations.settle(rid, Statuses.REJECTED, "insufficient_stock", Mode.LIVE.wire());
                return ApiResult.of(409, resBody(rid, Statuses.REJECTED, "insufficient_stock", Mode.LIVE));
            }
            // authority died between polls — trip the breaker and fall through
            breaker.trip();
        }
        return standinAuthorize(rid, itemId, userId, qty);
    }

    /** Authorise locally against the shadow count and the per-item stand-in cap. */
    private ApiResult standinAuthorize(String rid, String itemId, String userId, int qty) {
        synchronized (itemId.intern()) {
            if (reservations.countPending(itemId) >= standinMaxPerItem) {
                reservations.settle(rid, Statuses.REJECTED, "standin_limit_reached", Mode.STANDIN.wire());
                return ApiResult.of(409, resBody(rid, Statuses.REJECTED, "standin_limit_reached", Mode.STANDIN));
            }
            int available = shadow.getOrDefault(itemId, 0);
            if (available < qty) {
                reservations.settle(rid, Statuses.REJECTED, "insufficient_stock", Mode.STANDIN.wire());
                return ApiResult.of(409, resBody(rid, Statuses.REJECTED, "insufficient_stock", Mode.STANDIN));
            }
            shadow.put(itemId, available - qty);
            reservations.settle(rid, Statuses.PENDING, null, Mode.STANDIN.wire());
            return ApiResult.of(202, resBody(rid, Statuses.PENDING, null, Mode.STANDIN));
        }
    }

    // ---------------- replay ----------------

    public void replayAsync() {
        Thread t = new Thread(this::replay, "replay");
        t.setDaemon(true);
        t.start();
    }

    /** Drain the durable queue in order. Returns {confirmed, reversed}. */
    public int[] replay() {
        int confirmed = 0, reversed = 0;
        synchronized (replayLock) {
            List<Reservation> pending;
            try {
                pending = reservations.findPendingOrdered();
            } catch (Exception e) {
                return new int[]{confirmed, reversed};
            }
            for (Reservation r : pending) {
                AuthorityResponse a = authority.reserve(r.id(), r.itemId(), r.userId(), r.qty());
                if (!a.reached()) {
                    break; // authority not healthy yet; try again next cycle
                }
                if (a.code() == 201) {
                    if (a.available() >= 0) shadow.put(r.itemId(), a.available());
                    reservations.settle(r.id(), Statuses.CONFIRMED, null, Mode.STANDIN.wire());
                    events.record(r.id(), Statuses.CONFIRMED, "replayed to authority");
                    confirmed++;
                } else {
                    reservations.settle(r.id(), Statuses.REVERSED, "authority_refused", Mode.STANDIN.wire());
                    authority.release(r.id()); // compensate any partial hold
                    events.record(r.id(), Statuses.REVERSED, "authority refused during replay");
                    reversed++;
                }
            }
        }
        return new int[]{confirmed, reversed};
    }

    // ---------------- queries / admin ----------------

    public List<Map<String, Object>> listByUser(String userId) throws Exception {
        return reservations.findByUser(userId).stream().map(ReservationService::view).toList();
    }

    public Map<String, Object> getById(String id) throws Exception {
        Reservation r = reservations.findById(id);
        return r == null ? null : view(r);
    }

    public List<Map<String, Object>> audit(String reservationId) throws Exception {
        return events.findByReservation(reservationId);
    }

    public void reset() throws Exception {
        reservations.deleteAll();
        events.deleteAll();
        breaker.reset();
        resyncShadow();
    }

    /** Overwrite the shadow counts with the authority's real numbers. */
    public void resyncShadow() {
        for (String id : knownItems) {
            int available = authority.itemAvailable(id);
            if (available >= 0) shadow.put(id, available);
        }
    }

    // ---------------- helpers ----------------

    private Reservation awaitTerminal(String userId, String key) throws Exception {
        for (int i = 0; i < 120; i++) { // ~6s
            Reservation r = reservations.findByKey(userId, key);
            if (r != null && Statuses.isTerminal(r.status())) return r;
            Thread.sleep(50);
        }
        return reservations.findByKey(userId, key);
    }

    private ApiResult storedResult(Reservation r) {
        int code = switch (r.status()) {
            case Statuses.CONFIRMED -> 201;
            case Statuses.PENDING -> 202;
            default -> 409; // rejected / reversed
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservationId", r.id());
        body.put("status", r.status());
        body.put("mode", r.mode());
        return ApiResult.of(code, body);
    }

    private static Map<String, Object> resBody(String rid, String status, String reason, Mode mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservationId", rid);
        body.put("status", status);
        if (reason != null) body.put("reason", reason);
        body.put("mode", mode.wire());
        return body;
    }

    private static Map<String, Object> view(Reservation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reservationId", r.id());
        m.put("itemId", r.itemId());
        m.put("userId", r.userId());
        m.put("qty", r.qty());
        m.put("status", r.status());
        m.put("mode", r.mode());
        return m;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }
}
