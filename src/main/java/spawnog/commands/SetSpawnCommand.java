package spawnog.commands;

import java.util.*;
import javax.management.openmbean.InvalidOpenTypeException;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spawnog.SpawnOG;
import spawnog.integration.MyWorldsSpawnBridge;

public class SetSpawnCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> FLAGS = Set.of("normalize-view", "normalize-position", "unset");

    private final SpawnOG plugin = SpawnOG.getInstance();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final MyWorldsSpawnBridge myWorldsSpawnBridge;

    public SetSpawnCommand(MyWorldsSpawnBridge myWorldsSpawnBridge) {

        this.myWorldsSpawnBridge = myWorldsSpawnBridge;

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String lbl,
            @NotNull String[] args)
    {

        if (!(sender instanceof Player p))
            return false;
        if (!p.hasPermission(cmd.getPermission())) {

            send(p, "locale.missingPermission", "<red>You are lacking the required permissions.</red>");
            return true;

        }

        // Fetched per command: /reload replaces the instance, and writing
        // through a cached one would edit a discarded config.
        FileConfiguration cfg = plugin.getConfig();

        String group = null;
        var flags = new HashSet<String>();

        for (String a : args) {

            if (FLAGS.contains(a.toLowerCase()))
                flags.add(a.toLowerCase());
            else if (group == null)
                group = a.toLowerCase();

        }

        String path = "spawns." + (group == null ? "global" : group) + ".location";

        if (flags.contains("unset")) {

            cfg.set(path, null);
            plugin.saveConfig();
            send(p, "locale.spawnSet", "<gold>Spawn location updated.</gold>");
            return true;

        }

        Location l = p.getLocation();

        if (flags.contains("normalize-view")) {

            l.setYaw((float) closest(l.getYaw()));
            l.setPitch((float) closest(l.getPitch()));

        }

        if (flags.contains("normalize-position")) {

            l.setX(l.getBlockX() + .5);
            l.setZ(l.getBlockZ() + .5);
            l.setY(l.getBlockY());

        }

        l.setX(round(l.getX()));
        l.setY(round(l.getY()));
        l.setZ(round(l.getZ()));
        l.setYaw((float) round(l.getYaw()));
        l.setPitch((float) round(l.getPitch()));

        cfg.set(path, l);
        plugin.saveConfig();

        // Only the global spawn maps onto a world spawn point.
        if (group == null && myWorldsSpawnBridge != null)
            myWorldsSpawnBridge.adopt();

        send(p, "locale.spawnSet", "<gold>Spawn location updated.</gold>");

        // Warn, not refuse: staff may stage an odd spawn deliberately, but
        // every rescue lands here, so an unsafe one must be loud.
        spawnog.login.LocationSafety.Issue issue = spawnog.login.LocationSafety.check(l);
        if (issue.unsafe())
            send(p, "locale.spawnUnsafe", "<red>Warning: this spawn looks unsafe because <reason>.</red>",
                    Placeholder.unparsed("reason", issue.description()));
        return true;

    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String lbl, @NotNull String[] args)
    {

        FileConfiguration cfg = plugin.getConfig();
        var current = Arrays.asList(args);
        var out = new ArrayList<String>();

        if (args.length <= 1) {

            out.addAll(cfg.getConfigurationSection("spawns") == null ? List.of()
                    : cfg.getConfigurationSection("spawns").getKeys(false));

        }

        out.addAll(FLAGS);

        return out.stream().filter(s -> !current.contains(s))
                .filter(s -> s.startsWith(args[args.length - 1].toLowerCase())).toList();

    }

    private void send(Player player, String path, String fallback, TagResolver... placeholders) {

        String message = plugin.getConfig().getString(path, fallback);
        player.sendMessage(miniMessage.deserialize(message, placeholders));

    }

    private double round(double d) {

        return Math.round(d * 100.0) / 100.0;

    }

    private double closest(double d) {

        double[] arr = { -180, -90, 0, 90, 180 };
        if (arr.length == 0)
            throw new InvalidOpenTypeException();
        return Arrays.stream(arr).boxed().min(Comparator.comparing(a -> Math.abs(a - d))).orElse(arr[0]);

    }

}
