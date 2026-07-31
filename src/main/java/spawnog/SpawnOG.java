package spawnog;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import spawnog.commands.SetSpawnCommand;
import spawnog.commands.SpawnBackCommand;
import spawnog.commands.SpawnCommand;
import spawnog.flight.RegionFlightService;
import spawnog.flight.ToggleRegionFlightCommand;
import spawnog.integration.MyWorldsSpawnBridge;
import spawnog.login.AutopsyMigrationStore;
import spawnog.login.GameModeInventoriesAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.LoginMigrationService;
import spawnog.login.ReturnLocationStore;
import spawnog.listener.SpawnListener;
import spawnog.region.RegionLookup;
import spawnog.region.WorldGuardRegionLookup;
import spawnog.teleport.SpawnWarmupService;

@Slf4j
public final class SpawnOG extends JavaPlugin {

    @Getter
    private static SpawnOG instance;

    @Getter
    private LuckPerms luckPerms;

    private MyWorldsSpawnBridge myWorldsSpawnBridge;
    private LoginMigrationService loginMigrationService;
    private RegionFlightService regionFlightService;
    private SpawnWarmupService spawnWarmupService;

    @Override
    public void onEnable() {

        instance = this;
        saveDefaultConfig();

        RegisteredServiceProvider<LuckPerms> rsp = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (rsp != null)
            luckPerms = rsp.getProvider();

        spawnWarmupService = new SpawnWarmupService(this);
        getServer().getPluginManager().registerEvents(spawnWarmupService, this);

        myWorldsSpawnBridge = new MyWorldsSpawnBridge(this);
        getServer().getPluginManager().registerEvents(myWorldsSpawnBridge, this);
        // Deferred a tick so MyWorlds has finished loading its worlds and stamping
        // its own spawn points before Spawn-OG overrides them.
        getServer().getScheduler().runTask(this, myWorldsSpawnBridge::adopt);

        register("spawn", new SpawnCommand(spawnWarmupService), "spawnog.spawn");
        register("setspawn", new SetSpawnCommand(myWorldsSpawnBridge), "spawnog.setspawn");

        AutopsyMigrationStore migrationStore = new AutopsyMigrationStore(this);
        ReturnLocationStore returnLocationStore = new ReturnLocationStore(this);
        GamemodePolicy gamemodePolicy = new GamemodePolicy(this, regionLookup(),
                GameModeInventoriesAuthority.find(this));
        loginMigrationService = new LoginMigrationService(this, migrationStore, returnLocationStore, gamemodePolicy);
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

        // Constructed after the flight service so /spawnback can hand players
        // their wings back once the return teleport lands.
        SpawnBackCommand spawnBackCommand = new SpawnBackCommand(this, returnLocationStore, loginMigrationService,
                regionFlightService);
        register("spawnback", spawnBackCommand, "spawnog.spawnback");
        getServer().getPluginManager().registerEvents(spawnBackCommand, this);

    }

    @Override
    public void onDisable() {

        if (spawnWarmupService != null)
            spawnWarmupService.close();
        if (loginMigrationService != null)
            loginMigrationService.close();
        if (regionFlightService != null)
            regionFlightService.close();

    }

    // WorldGuard-backed when both it and WorldEdit are present, so the WorldGuard
    // classes are never resolved on servers running without them.
    private RegionLookup regionLookup() {

        if (getServer().getPluginManager().isPluginEnabled("WorldGuard")
                && getServer().getPluginManager().isPluginEnabled("WorldEdit"))
            return new WorldGuardRegionLookup();

        getLogger().warning("WorldGuard or WorldEdit is unavailable; region-scoped gamemode exemptions are disabled.");
        return RegionLookup.NONE;

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
