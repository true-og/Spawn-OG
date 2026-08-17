package spawnog.login;

import java.util.UUID;

import org.bukkit.entity.Player;

import plugin.NoClip;
import spawnog.SpawnOG;

// NoClip-OG's manager, a compile-time dependency on the submodule under libs/.
// Only constructed when enabled, so its classes never resolve without it.
public final class NoClipAuthority {

    public static final String PLUGIN_NAME = "NoClip-OG";

    private final SpawnOG plugin;

    private NoClipAuthority(SpawnOG plugin) {

        this.plugin = plugin;

    }

    // The authority, or null when NoClip-OG is not running.
    public static NoClipAuthority find(SpawnOG plugin) {

        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME))
            return null;

        return new NoClipAuthority(plugin);

    }

    public boolean isNoClipping(UUID playerId) {

        NoClip noClip = running();
        return noClip != null && noClip.getManager().isNoClipping(playerId);

    }

    // Re-engages /nc for a player already in creative or spectator. NoClip-OG
    // checks the gamemode and the permission itself.
    public boolean enterNoClip(Player player) {

        NoClip noClip = running();
        return noClip != null && noClip.getManager().enterNoClip(player);

    }

    // Looked up per call rather than cached: a reload replaces the plugin
    // instance, and disabling it must stop the answers.
    private NoClip running() {

        org.bukkit.plugin.Plugin candidate = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (candidate instanceof NoClip noClip && candidate.isEnabled())
            return noClip;

        return null;

    }

}
