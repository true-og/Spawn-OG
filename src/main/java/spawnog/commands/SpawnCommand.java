package spawnog.commands;

import java.util.*;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spawnog.SpawnOG;
import spawnog.teleport.SpawnWarmupService;

public class SpawnCommand implements CommandExecutor, TabCompleter {

    private final SpawnOG plugin = SpawnOG.getInstance();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final SpawnWarmupService warmupService;

    public SpawnCommand(SpawnWarmupService warmupService) {

        this.warmupService = warmupService;

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String lbl,
            @NotNull String[] args)
    {

        if (args.length == 0) {

            if (!(sender instanceof Player p))
                return false;
            if (!p.hasPermission(cmd.getPermission())) {

                send(sender, "locale.missingPermission", "<red>You are lacking the required permissions.</red>");
                return true;

            }

            Location dest = resolve(p);
            if (dest == null) {

                send(sender, "locale.spawnNotSet", "<red>Server spawn not set.</red>");
                return true;

            }

            warmupService.schedule(p, dest);
            return true;

        }

        if (!sender.hasPermission("spawnog.spawn.others")) {

            send(sender, "locale.missingPermission", "<red>You are lacking the required permissions.</red>");
            return true;

        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {

            send(sender, "locale.playerNotFound", "<red>Player not found.</red>");
            return true;

        }

        Location dest = resolve(target);
        if (dest == null) {

            send(sender, "locale.spawnNotSet", "<red>Server spawn not set.</red>");
            return true;

        }

        // No countdown for a pull the target never asked for: their ordinary
        // walking would cancel it and defeat the staff action.
        warmupService.teleportNow(target, dest);
        return true;

    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String lbl, @NotNull String[] args)
    {

        if (args.length == 1 && sender.hasPermission("spawnog.spawn.others")) {

            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());

        }

        return List.of();

    }

    private void send(CommandSender sender, String path, String fallback) {

        String message = plugin.getConfig().getString(path, fallback);
        sender.sendMessage(miniMessage.deserialize(message));

    }

    private Location resolve(Player p) {

        String group = null;
        LuckPerms lp = plugin.getLuckPerms();
        if (lp != null) {

            User u = lp.getUserManager().getUser(p.getUniqueId());
            if (u != null)
                group = u.getPrimaryGroup();

        }

        if (group != null) {

            Location g = plugin.getConfig().getLocation("spawns." + group.toLowerCase() + ".location");
            if (g != null)
                return g.clone();

        }

        Location def = plugin.getConfig().getLocation("spawns.global.location");
        return def == null ? null : def.clone();

    }

}
