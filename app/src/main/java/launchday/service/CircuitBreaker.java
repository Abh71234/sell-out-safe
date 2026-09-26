package launchday.service;

import launchday.model.Mode;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Circuit Breaker pattern.
 *
 * Owns the LIVE/STANDIN mode and the authority-health flag. A background poller
 * flips the mode: going DOWN is immediate (fail fast), while returning to LIVE
 * requires N consecutive healthy probes (hysteresis) to avoid flapping. On
 * recovery it fires an onRecover callback so the service can resync + replay.
 */
public final class CircuitBreaker {
    private final int pollMs;
    private final int healthyChecksToRecover;

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.LIVE);
    private volatile boolean authorityHealthy = true;
    private final AtomicInteger healthyStreak = new AtomicInteger(0);
    private volatile Runnable onRecover = () -> {};

    public CircuitBreaker(int pollMs, int healthyChecksToRecover) {
        this.pollMs = pollMs;
        this.healthyChecksToRecover = healthyChecksToRecover;
    }

    public void onRecover(Runnable callback) {
        this.onRecover = callback;
    }

    /** Start the daemon poller. probe returns true when the authority is healthy. */
    public void start(BooleanSupplier probe) {
        Thread t = new Thread(() -> {
            while (true) {
                if (probe.getAsBoolean()) {
                    recordSuccess();
                } else {
                    recordFailure();
                }
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "circuit-breaker");
        t.setDaemon(true);
        t.start();
    }

    private void recordSuccess() {
        authorityHealthy = true;
        int streak = healthyStreak.incrementAndGet();
        if (mode.get() == Mode.STANDIN && streak >= healthyChecksToRecover) {
            mode.set(Mode.LIVE);
            onRecover.run();
        }
    }

    private void recordFailure() {
        authorityHealthy = false;
        healthyStreak.set(0);
        mode.set(Mode.STANDIN);
    }

    /** Trip the breaker inline when a live request discovers the authority is gone. */
    public void trip() {
        recordFailure();
    }

    public void reset() {
        mode.set(Mode.LIVE);
        healthyStreak.set(0);
        authorityHealthy = true;
    }

    public Mode mode() {
        return mode.get();
    }

    public boolean isAuthorityHealthy() {
        return authorityHealthy;
    }
}
