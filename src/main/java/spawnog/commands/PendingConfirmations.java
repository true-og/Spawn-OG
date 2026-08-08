package spawnog.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

// The two-step /spawnback state: the first invocation arms a warning, the
// second within the window executes. Keyed by the return record's token, so a
// warning issued for one rescue can never confirm a different one recorded in
// between. Clock injected so the expiry logic is testable off the server.
public final class PendingConfirmations {

    private final LongSupplier clock;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public PendingConfirmations(LongSupplier clock) {

        this.clock = clock;

    }

    // True when a live warning for exactly this token is on record, consuming
    // it. False arms (or re-arms) the warning for this token instead: a first
    // call, an expired window, and a token mismatch all warn anew.
    public boolean confirm(UUID playerId, String token, long windowMillis) {

        long now = clock.getAsLong();
        Pending armed = pending.get(playerId);
        if (armed != null && armed.token().equals(token) && armed.expiresAt() >= now) {

            pending.remove(playerId);
            return true;

        }

        pending.put(playerId, new Pending(token, now + windowMillis));
        return false;

    }

    public void clear(UUID playerId) {

        pending.remove(playerId);

    }

    private record Pending(String token, long expiresAt) {
    }

}
