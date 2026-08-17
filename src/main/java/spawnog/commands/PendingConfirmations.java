package spawnog.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

// Two-step /spawnback state: first call arms a warning, second in the window
// executes. Keyed by record token; clock injected so expiry is testable.
public final class PendingConfirmations {

    private final LongSupplier clock;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public PendingConfirmations(LongSupplier clock) {

        this.clock = clock;

    }

    // True consumes a live warning for exactly this token. False arms anew: a
    // first call, an expired window, and a token mismatch all warn again.
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
