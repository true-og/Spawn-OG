package spawnog.login;

import me.eccentric_nz.gamemodeinventories.api.GameModePolicy;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import spawnog.SpawnOG;

// GameModeInventories-OG's published policy, taken from the services manager it
// registers on enable. Only constructed when that plugin is enabled, so its
// classes are never resolved on servers running without it.
public final class GameModeInventoriesAuthority implements GamemodeAuthority {

    public static final String PLUGIN_NAME = "GameModeInventories-OG";

    private final SpawnOG plugin;

    private GameModeInventoriesAuthority(SpawnOG plugin) {

        this.plugin = plugin;

    }

    // The authority, or null when GameModeInventories-OG is not running.
    public static GamemodeAuthority find(SpawnOG plugin) {

        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME))
            return null;

        return new GameModeInventoriesAuthority(plugin);

    }

    @Override
    public Boolean mayUse(Player player, GameMode gamemode, Location location) {

        // Looked up per call rather than cached: reloading GameModeInventories-OG
        // republishes a new policy, and disabling it must hand the rules back.
        RegisteredServiceProvider<GameModePolicy> registration = plugin.getServer().getServicesManager()
                .getRegistration(GameModePolicy.class);
        if (registration == null)
            return null;

        return registration.getProvider().mayUse(player, gamemode, location);

    }

    @Override
    public boolean changeGameMode(Player player, GameMode gamemode) {

        // The plugin instance rather than the published policy, so the switch is
        // flagged as plugin driven and the region listener skips its denial.
        org.bukkit.plugin.Plugin candidate = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (!(candidate instanceof me.eccentric_nz.gamemodeinventories.GameModeInventories gmi)
                || !candidate.isEnabled())
            return false;

        gmi.internalGameModeChange(player, gamemode);
        // Another plugin may cancel the underlying gamemode change event, so the
        // outcome is judged by what actually stuck.
        return player.getGameMode() == gamemode;

    }

}
