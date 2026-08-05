package spawnog;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import spawnog.commands.SetSpawnCommand;
import spawnog.commands.SpawnBackCommand;
import spawnog.commands.SpawnCommand;
import spawnog.flight.FlightIntentStore;
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
import spawnog.teleport.FallProtection;
import spawnog.teleport.SpawnWarmupService;
import spawnog.world.ManagedWorlds;

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
    private FallProtection fallProtection;
    private ManagedWorlds managedWorlds;

    @Override
    public void onEnable() {

        instance = this;
        saveDefaultConfig();

        RegisteredServiceProvider<LuckPerms> rsp = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (rsp != null)
            luckPerms = rsp.getProvider();

        spawnWarmupService = new SpawnWarmupService(this);
        getServer().getPluginManager().registerEvents(spawnWarmupService, this);

        // Registered unconditionally: a /spawnback return has to cushion the
        // landing even on a server with no WorldGuard and no regional flight.
        fallProtection = new FallProtection();
        getServer().getPluginManager().registerEvents(fallProtection, this);

        myWorldsSpawnBridge = new MyWorldsSpawnBridge(this);
        getServer().getPluginManager().registerEvents(myWorldsSpawnBridge, this);
        // Deferred a tick so MyWorlds has finished loading its worlds and stamping
        // its own spawn points before Spawn-OG overrides them.
        getServer().getScheduler().runTask(this, myWorldsSpawnBridge::adopt);

        register("spawn", new SpawnCommand(spawnWarmupService), "spawnog.spawn");
        register("setspawn", new SetSpawnCommand(myWorldsSpawnBridge), "spawnog.setspawn");

        // Registered before the login listener so a player's world is on record
        // before login safety can move them out of it.
        managedWorlds = new ManagedWorlds(this);
        getServer().getPluginManager().registerEvents(managedWorlds, this);

        AutopsyMigrationStore migrationStore = new AutopsyMigrationStore(this);
        ReturnLocationStore returnLocationStore = new ReturnLocationStore(this);
        GamemodePolicy gamemodePolicy = new GamemodePolicy(this, regionLookup(),
                GameModeInventoriesAuthority.find(this));
        loginMigrationService = new LoginMigrationService(this, migrationStore, returnLocationStore, gamemodePolicy,
                managedWorlds);
        getServer().getPluginManager().registerEvents(new SpawnListener(this, loginMigrationService, managedWorlds),
                this);

        if (getConfig().getBoolean("flight.enabled", true)
                && getServer().getPluginManager().isPluginEnabled("WorldGuard")
                && getServer().getPluginManager().isPluginEnabled("WorldEdit"))
        {

            regionFlightService = new RegionFlightService(this, loginMigrationService, new FlightIntentStore(this),
                    fallProtection);
            getServer().getPluginManager().registerEvents(regionFlightService, this);
            getCommand("fly").setExecutor(new ToggleRegionFlightCommand(regionFlightService));
            // Login safety asks before rescuing an airborne player, so a flyer
            // whose wings come back in place is left where they logged out.
            loginMigrationService.setFlightEligibility(regionFlightService::willResumeFlightAt);

        } else if (getConfig().getBoolean("flight.enabled", true)) {

            getLogger().warning("WorldGuard or WorldEdit is unavailable; regional flight management is disabled.");

        }

        // Constructed after the flight service so /spawnback can hand players
        // their wings back once the return teleport lands.
        SpawnBackCommand spawnBackCommand = new SpawnBackCommand(this, returnLocationStore, loginMigrationService,
                regionFlightService, fallProtection);
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
        if (fallProtection != null)
            fallProtection.close();
        if (managedWorlds != null)
            managedWorlds.close();

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
