package launchday.repository;

import launchday.db.ConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audit trail persistence for the stretch goal (reservation_events table). */
public final class EventRepository {
    private final ConnectionPool pool;

    public EventRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    public void migrate() throws Exception {
        Connection c = pool.borrow();
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS reservation_events(" +
                    "id BIGSERIAL PRIMARY KEY, reservation_id TEXT NOT NULL, event TEXT NOT NULL, " +
                    "detail TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        } finally {
            pool.release(c);
        }
    }

    public void record(String reservationId, String event, String detail) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO reservation_events(reservation_id,event,detail) VALUES (?,?,?)")) {
                ps.setString(1, reservationId);
                ps.setString(2, event);
                ps.setString(3, detail);
                ps.executeUpdate();
            }
        } catch (Exception ignore) {
            // auditing is best-effort
        } finally {
            pool.release(c);
        }
    }

    public List<Map<String, Object>> findByReservation(String reservationId) throws Exception {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT reservation_id, event, detail, created_at FROM reservation_events " +
                "WHERE (? IS NULL OR reservation_id=?) ORDER BY id")) {
            ps.setString(1, reservationId);
            ps.setString(2, reservationId);
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reservationId", rs.getString(1));
                row.put("event", rs.getString(2));
                row.put("detail", rs.getString(3) == null ? "" : rs.getString(3));
                row.put("at", String.valueOf(rs.getTimestamp(4)));
                out.add(row);
            }
            return out;
        } finally {
            pool.release(c);
        }
    }

    public void deleteAll() throws Exception {
        Connection c = pool.borrow();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM reservation_events");
        } finally {
            pool.release(c);
        }
    }
}
