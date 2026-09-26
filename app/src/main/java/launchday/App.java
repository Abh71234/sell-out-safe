package launchday;

import com.sun.net.httpserver.HttpServer;
import launchday.authority.AuthorityClient;
import launchday.config.Config;
import launchday.db.ConnectionPool;
import launchday.http.AdminHandler;
import launchday.http.HealthHandler;
import launchday.http.ItemsHandler;
import launchday.http.ReservationsHandler;
import launchday.repository.EventRepository;
import launchday.repository.ReservationRepository;
import launchday.service.CircuitBreaker;
import launchday.service.ReservationService;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Composition root: reads config, wires the layers together (dependency
 * injection by hand), starts the circuit breaker, and mounts the HTTP handlers.
 *
 * Layering:  http  ->  service  ->  repository  ->  db
 *                         └------>  authority (gateway)
 */
public class App {

    private static final Set<String> SEED_ITEMS =
            Set.of("hype-001", "hype-002", "hype-003", "hype-004", "hype-005");

    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();

        // infrastructure
        ConnectionPool pool = new ConnectionPool(cfg.dbUrl, cfg.dbUser, cfg.dbPass, cfg.poolSize);
        AuthorityClient authority = new AuthorityClient(cfg.authorityUrl);

        // repositories
        ReservationRepository reservationRepo = new ReservationRepository(pool);
        EventRepository eventRepo = new EventRepository(pool);
        reservationRepo.migrate();
        eventRepo.migrate();

        // domain services
        CircuitBreaker breaker = new CircuitBreaker(cfg.breakerPollMs, cfg.breakerHealthyChecks);
        ReservationService service = new ReservationService(
                reservationRepo, eventRepo, authority, breaker, cfg.standinMaxPerItem, SEED_ITEMS);
        service.resyncShadow();
        breaker.start(authority::isHealthy);

        // http layer
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port), 0);
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.createContext("/health", new HealthHandler(breaker));
        server.createContext("/items", new ItemsHandler(authority));
        server.createContext("/reservations", new ReservationsHandler(service));
        server.createContext("/admin/reset", new AdminHandler(service, AdminHandler.Action.RESET));
        server.createContext("/admin/reconcile", new AdminHandler(service, AdminHandler.Action.RECONCILE));
        server.createContext("/admin/audit", new AdminHandler(service, AdminHandler.Action.AUDIT));
        server.start();

        System.out.println("reservation api (java) :" + cfg.port);
    }
}
