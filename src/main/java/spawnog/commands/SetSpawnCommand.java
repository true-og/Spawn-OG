package spawnog.commands;

import java.util.*;
import javax.management.openmbean.InvalidOpenTypeException;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spawnog.SpawnOG;

public class SetSpawnCommand implements CommandExecutor, TabCompleter {

    private final FileConfiguration cfg = SpawnOG.getInstance().getConfig();
    private static final Set<String> FLAGS = Set.of("normalize-view", "normalize-position", "unset");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String lbl,
            @NotNull String[] args)
    {

        if (!(sender instanceof Player p))
            return false;
        if (!p.hasPermission(cmd.getPermission())) {

            p.sendMessage(cfg.getString("locale.missingPermission", "You are lacking the required permissions."));
            return true;

        }

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
            SpawnOG.getInstance().saveConfig();
            p.sendMessage(cfg.getString("locale.spawnSet", "Spawn location updated."));
            return true;

        }

        Location l = p.getLocation();

        if (flags.contains("normalize-view")) {

            l.setYaw((float) closest(l.getYaw()));
            l.setPitch((float) closest(l.getPitch()));

        }

        if (flags.contains("normalize-position")) {

            l.setX(l.getX() > l.getBlockX() ? l.getBlockX() + .5 : l.getBlockX() - .5);
            l.setZ(l.getZ() > l.getBlockZ() ? l.getBlockZ() + .5 : l.getBlockZ() - .5);
            l.setY(l.getBlockY());

        }

        l.setX(round(l.getX()));
        l.setY(round(l.getY()));
        l.setZ(round(l.getZ()));
        l.setYaw((float) round(l.getYaw()));
        l.setPitch((float) round(l.getPitch()));

        cfg.set(path, l);
        SpawnOG.getInstance().saveConfig();
        p.sendMessage(cfg.getString("locale.spawnSet", "Spawn location updated."));
        return true;

    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String lbl, @NotNull String[] args)
    {

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
