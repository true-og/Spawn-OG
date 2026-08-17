package spawnog.login;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// Reports whether a player is inside a minigame arena. Minigames use spectator
// legitimately; without this an arena disconnect looks like an abandoned autopsy.
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

            // Survives a crash: the live session is gone but the saved pre-match
            // state is not, exactly when a casualty would be mistaken for SMP.
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
