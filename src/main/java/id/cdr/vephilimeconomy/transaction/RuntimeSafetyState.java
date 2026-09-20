package id.cdr.vephilimeconomy.transaction;

import java.time.Instant;

public final class RuntimeSafetyState {
    private volatile boolean stopped;
    private volatile String reason = "";
    private volatile Instant stoppedAt;

    public synchronized boolean trip(String reason) {
        if (stopped) {
            return false;
        }
        this.stopped = true;
        this.reason = reason == null || reason.isBlank() ? "unspecified critical transaction inconsistency" : reason;
        this.stoppedAt = Instant.now();
        return true;
    }

    public boolean isStopped() {
        return stopped;
    }

    public String reason() {
        return reason;
    }

    public Instant stoppedAt() {
        return stoppedAt;
    }

    public String shortStatus() {
        if (!stopped) {
            return "OK";
        }
        return "STOPPED@" + stoppedAt;
    }
}
