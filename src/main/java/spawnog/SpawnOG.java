package spawnog;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import spawnog.commands.SetSpawnCommand;
import spawnog.commands.SpawnCommand;
import spawnog.flight.RegionFlightService;
import spawnog.flight.ToggleRegionFlightCommand;
import spawnog.login.AutopsyMigrationStore;
import spawnog.login.LoginMigrationService;
import spawnog.listener.SpawnListener;

@Slf4j
public final class SpawnOG extends JavaPlugin {

    @Getter
    private static SpawnOG instance;

    @Getter
    private LuckPerms luckPerms;

    private LoginMigrationService loginMigrationService;
    private RegionFlightService regionFlightService;

    @Override
    public void onEnable() {

        instance = this;
        saveDefaultConfig();

        RegisteredServiceProvider<LuckPerms> rsp = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (rsp != null)
            luckPerms = rsp.getProvider();

        register("spawn", new SpawnCommand(), "spawnog.spawn");
        register("setspawn", new SetSpawnCommand(), "spawnog.setspawn");

        AutopsyMigrationStore migrationStore = new AutopsyMigrationStore(this);
        loginMigrationService = new LoginMigrationService(this, migrationStore);
        getServer().getPluginManager().registerEvents(new SpawnListener(this, loginMigrationService), this);

        if (getConfig().getBoolean("flight.enabled", true)
                && getServer().getPluginManager().isPluginEnabled("WorldGuard")
                && getServer().getPluginManager().isPluginEnabled("WorldEdit"))
        {

            regionFlightService = new RegionFlightService(this, loginMigrationService);
            getServer().getPluginManager().registerEvents(regionFlightService, this);
            getCommand("fly").setExecutor(new ToggleRegionFlightCommand(regionFlightService));

        } else if (getConfig().getBoolean("flight.enabled", true)) {

            getLogger().warning("WorldGuard or WorldEdit is unavailable; regional flight management is disabled.");

        }

    }

    @Override
    public void onDisable() {

        if (loginMigrationService != null)
            loginMigrationService.close();
        if (regionFlightService != null)
            regionFlightService.close();

    }

    private void register(String name, Object exec, String perm) {

        var cmd = getCommand(name);
        if (cmd == null) {

            getLogger().severe("Command '" + name + "' missing from plugin.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;

        }

        cmd.setPermission(perm);
        if (exec instanceof org.bukkit.command.CommandExecutor e)
            cmd.setExecutor(e);
        if (exec instanceof org.bukkit.command.TabCompleter t)
            cmd.setTabCompleter(t);

    }

}
