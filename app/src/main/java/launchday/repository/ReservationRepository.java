package launchday.repository;

import launchday.db.ConnectionPool;
import launchday.model.Reservation;
import launchday.model.Statuses;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * All reservation SQL lives here (Repository pattern). The service layer never
 * writes SQL; it calls named methods with clear intent.
 */
public final class ReservationRepository {
    private static final String UNIQUE_VIOLATION = "23505";

    private final ConnectionPool pool;

    public ReservationRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    /** Idempotent schema migration; safe to run on every boot. */
    public void migrate() throws Exception {
        Connection c = pool.borrow();
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS idempotency_key TEXT");
            st.execute("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS body_hash TEXT");
            st.execute("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'live'");
            st.execute("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS reason TEXT");
            st.execute("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS reservations_user_idem_idx " +
                    "ON reservations(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL");
        } finally {
            pool.release(c);
        }
    }

    /**
     * Attempt to claim the idempotency slot by inserting a 'processing' row.
     * Returns true if we won the claim, false if the (user, key) already exists
     * (Postgres unique_violation) — i.e. this request is a retry/duplicate.
     */
    public boolean claim(String rid, String itemId, String userId, int qty,
                         String key, String bodyHash) throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reservations (id,item_id,user_id,qty,status,idempotency_key,body_hash,mode) " +
                "VALUES (?,?,?,?, '" + Statuses.PROCESSING + "', ?, ?, 'live')")) {
            ps.setString(1, rid);
            ps.setString(2, itemId);
            ps.setString(3, userId);
            ps.setInt(4, qty);
            ps.setString(5, key);
            ps.setString(6, bodyHash);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) return false;
            throw e;
        } finally {
            pool.release(c);
        }
    }

    /** Load the reservation that owns a given (user, idempotency key). */
    public Reservation findByKey(String userId, String key) throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, idempotency_key, body_hash " +
                "FROM reservations WHERE user_id=? AND idempotency_key=?")) {
            ps.setString(1, userId);
            ps.setString(2, key);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        } finally {
            pool.release(c);
        }
    }

    public Reservation findById(String id) throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, idempotency_key, body_hash " +
                "FROM reservations WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        } finally {
            pool.release(c);
        }
    }

    public List<Reservation> findByUser(String userId) throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, idempotency_key, body_hash " +
                "FROM reservations WHERE (? IS NULL OR user_id=?) ORDER BY created_at")) {
            ps.setString(1, userId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            List<Reservation> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        } finally {
            pool.release(c);
        }
    }

    /** Move a reservation to a terminal (or pending) status. */
    public void settle(String rid, String status, String reason, String mode) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reservations SET status=?, reason=?, mode=?, updated_at=now() WHERE id=?")) {
                ps.setString(1, status);
                ps.setString(2, reason);
                ps.setString(3, mode);
                ps.setString(4, rid);
                ps.executeUpdate();
            }
        } catch (Exception ignore) {
            // settle is best-effort; replay/reconcile will re-drive anything left behind
        } finally {
            pool.release(c);
        }
    }

    public int countPending(String itemId) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM reservations WHERE item_id=? AND status='" + Statuses.PENDING + "'")) {
                ps.setString(1, itemId);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return Integer.MAX_VALUE; // fail closed: treat as "cap reached" on DB error
        } finally {
            pool.release(c);
        }
    }

    /** All pending reservations in creation order — the durable replay queue. */
    public List<Reservation> findPendingOrdered() throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, item_id, user_id, qty, status, mode, idempotency_key, body_hash " +
                "FROM reservations WHERE status='" + Statuses.PENDING + "' ORDER BY created_at")) {
            ResultSet rs = ps.executeQuery();
            List<Reservation> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        } finally {
            pool.release(c);
        }
    }

    public void deleteAll() throws Exception {
        Connection c = pool.borrow();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM reservations");
        } finally {
            pool.release(c);
        }
    }

    private static Reservation map(ResultSet rs) throws SQLException {
        return new Reservation(
                rs.getString("id"),
                rs.getString("item_id"),
                rs.getString("user_id"),
                rs.getInt("qty"),
                rs.getString("status"),
                rs.getString("mode"),
                rs.getString("idempotency_key"),
                rs.getString("body_hash"));
    }
}
