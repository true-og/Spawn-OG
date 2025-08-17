package spawnog;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import spawnog.commands.SetSpawnCommand;
import spawnog.commands.SpawnCommand;
import spawnog.listener.SpawnListener;

@Slf4j
public final class SpawnOG extends JavaPlugin {

    @Getter
    private static SpawnOG instance;

    @Getter
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {

        instance = this;
        saveDefaultConfig();

        RegisteredServiceProvider<LuckPerms> rsp = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (rsp != null)
            luckPerms = rsp.getProvider();

        register("spawn", new SpawnCommand(), "essentials.spawn");
        register("setspawn", new SetSpawnCommand(), "essentials.setspawn");

        getServer().getPluginManager().registerEvents(new SpawnListener(), this);

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
