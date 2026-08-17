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
import spawnog.flight.FlightQuitListener;
import spawnog.flight.FlightRestorer;
import spawnog.flight.FlightSnapshotStore;
import spawnog.flight.RegionFlightService;
import spawnog.flight.ToggleRegionFlightCommand;
import spawnog.integration.MyWorldsSpawnBridge;
import spawnog.login.AutopsyMigrationStore;
import spawnog.login.GameModeInventoriesAuthority;
import spawnog.login.GamemodeAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.LoginMigrationService;
import spawnog.login.NoClipAuthority;
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
    private FlightQuitListener flightQuitListener;

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
        // Written by the quit capture, read by the next login: the record of
        // who left mid-flight and by which kind of flight.
        FlightSnapshotStore flightSnapshotStore = new FlightSnapshotStore(this);
        FlightIntentStore flightIntentStore = new FlightIntentStore(this);
        NoClipAuthority noClipAuthority = NoClipAuthority.find(this);
        GamemodeAuthority gamemodeAuthority = GameModeInventoriesAuthority.find(this);
        GamemodePolicy gamemodePolicy = new GamemodePolicy(this, regionLookup(), gamemodeAuthority);
        loginMigrationService = new LoginMigrationService(this, migrationStore, returnLocationStore,
                flightSnapshotStore, gamemodePolicy, managedWorlds, fallProtection);
        getServer().getPluginManager().registerEvents(new SpawnListener(this, loginMigrationService, managedWorlds),
                this);

        if (getConfig().getBoolean("flight.enabled", true)
                && getServer().getPluginManager().isPluginEnabled("WorldGuard")
                && getServer().getPluginManager().isPluginEnabled("WorldEdit"))
        {

            regionFlightService = new RegionFlightService(this, loginMigrationService, flightIntentStore,
                    fallProtection);
            getServer().getPluginManager().registerEvents(regionFlightService, this);
            getCommand("fly").setExecutor(new ToggleRegionFlightCommand(regionFlightService));

        } else if (getConfig().getBoolean("flight.enabled", true)) {

            getLogger().warning("WorldGuard or WorldEdit is unavailable; regional flight management is disabled.");

        }

        // Captures the flight snapshot at LOWEST, before the flight service's
        // NORMAL quit handler grounds the player.
        flightQuitListener = new FlightQuitListener(this, flightSnapshotStore, flightIntentStore, noClipAuthority);
        getServer().getPluginManager().registerEvents(flightQuitListener, this);

        // One decision table for handing flight back, shared by the login
        // bypass path and /spawnback so warning and outcome cannot disagree.
        FlightRestorer flightRestorer = new FlightRestorer(regionFlightService, gamemodePolicy, gamemodeAuthority,
                noClipAuthority);
        loginMigrationService.setFlightRestorer(flightRestorer);

        SpawnBackCommand spawnBackCommand = new SpawnBackCommand(this, returnLocationStore, loginMigrationService,
                flightRestorer, fallProtection);
        register("spawnback", spawnBackCommand, "spawnog.spawnback");
        getServer().getPluginManager().registerEvents(spawnBackCommand, this);

    }

    @Override
    public void onDisable() {

        // Snapshots first: a /reload fires no quit events, and the flight
        // service's close() grounds every flyer it tracks.
        if (flightQuitListener != null)
            flightQuitListener.captureAll();
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
