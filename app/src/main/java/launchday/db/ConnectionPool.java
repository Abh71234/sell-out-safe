package launchday.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A tiny fixed-size JDBC connection pool (Object Pool pattern).
 *
 * The starter kit shared a single Connection across all requests, which is not
 * safe under the concurrent checks. Borrow/release hands each request its own
 * connection and validates it before use.
 */
public final class ConnectionPool {
    private final BlockingQueue<Connection> pool;
    private final String url, user, pass;

    public ConnectionPool(String url, String user, String pass, int size) throws SQLException {
        this.url = url;
        this.user = user;
        this.pass = pass;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(open());
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    /** Take a healthy connection, replacing any dead one. Blocks if the pool is empty. */
    public Connection borrow() throws Exception {
        Connection c = pool.take();
        if (c == null || c.isClosed() || !c.isValid(1)) {
            c = open();
        }
        return c;
    }

    /** Return a connection to the pool. Never throws. */
    public void release(Connection c) {
        try {
            if (c != null && !c.isClosed()) {
                pool.offer(c);
            }
        } catch (Exception ignore) {
            // drop broken connections silently
        }
    }
}
