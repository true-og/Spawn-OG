package spawnog.login;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// Reports whether a player is inside a minigame arena.
//
// Autopsy migration exists for OG:SMP season 1 players who were left in spectator mode on the SMP. A minigame puts
// its own players into spectator legitimately -- Spleef does it to everyone it eliminates, and Splegg runs whole
// matches in worlds it owns -- so without this check a routine minigame disconnect looks identical to the state the
// migration is meant to rescue. Migrating one of those players burns their one-shot migration flag and records the
// arena as their /spawnback location.
//
// Every lookup is reflective and optional: no minigame is a dependency, and an absent one simply answers "no".
public final class MinigameArenas {

    private static final String SPLEEF_PLUGIN = "Spleef-OG";
    private static final String SPLEEF_API = "net.trueog.spleefog.api.SpleefAPI";
    private static final String SPLEGG_PLUGIN = "Splegg-OG";
    private static final String DUELS_PLUGIN = "Duels-OG";

    private MinigameArenas() {

    }

    public static boolean contains(Player player) {

        return player != null && (inSpleef(player) || inSplegg(player) || inDuel(player));

    }

    private static boolean inSpleef(Player player) {

        Plugin plugin = enabled(SPLEEF_PLUGIN);
        if (plugin == null)
            return false;

        try {

            Class<?> api = Class.forName(SPLEEF_API, true, plugin.getClass().getClassLoader());
            if (Boolean.TRUE.equals(api.getMethod("isInSpleef", Player.class).invoke(null, player)))
                return true;

            // Survives a crash, where the live session is gone but the saved pre-match
            // state is not. That is
            // exactly the login on which a minigame casualty would otherwise be mistaken
            // for an SMP one.
            Method pending = api.getMethod("hasPendingRecovery", java.util.UUID.class);
            return Boolean.TRUE.equals(pending.invoke(null, player.getUniqueId()));

        } catch (ReflectiveOperationException | RuntimeException ex) {

            return false;

        }

    }

    // Splegg owns the worlds its games run in, so the player's world answers this
    // even after the game has ended.
    private static boolean inSplegg(Player player) {

        Plugin plugin = enabled(SPLEGG_PLUGIN);
        if (plugin == null)
            return false;

        String world = player.getWorld().getName();
        try {

            Object configured = plugin.getClass().getMethod("getInGameWorlds").invoke(plugin);
            if (configured instanceof List<?> worlds) {

                for (Object name : worlds) {

                    if (name != null && world.equalsIgnoreCase(name.toString()))
                        return true;

                }

            }

            Object prefix = plugin.getClass().getMethod("getGameWorldPrefix").invoke(plugin);
            // Per-game worlds are named <prefix><gameId>-<map>.
            return prefix != null && !prefix.toString().isEmpty()
                    && world.toLowerCase(Locale.ROOT).startsWith(prefix.toString().toLowerCase(Locale.ROOT));

        } catch (ReflectiveOperationException | RuntimeException ex) {

            return false;

        }

    }

    private static boolean inDuel(Player player) {

        Plugin plugin = enabled(DUELS_PLUGIN);
        if (plugin == null)
            return false;

        try {

            Object arenaManager = plugin.getClass().getMethod("getArenaManager").invoke(plugin);
            if (arenaManager == null)
                return false;

            Method query = arenaManager.getClass().getMethod("isInMatch", Player.class);
            query.setAccessible(true);
            return Boolean.TRUE.equals(query.invoke(arenaManager, player));

        } catch (ReflectiveOperationException | RuntimeException ex) {

            return false;

        }

    }

    private static Plugin enabled(String name) {

        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;

    }

}
