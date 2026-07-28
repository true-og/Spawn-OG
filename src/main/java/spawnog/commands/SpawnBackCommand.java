package spawnog.commands;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import spawnog.SpawnOG;
import spawnog.login.LoginMigrationService;
import spawnog.login.ReturnLocationStore;
import spawnog.login.ReturnLocationStore.ReturnPoint;

// Sends a player back to wherever a login safety migration took them from. The
// position is knowingly unsafe, so the first call only warns and the teleport
// waits for an explicit confirmation.
public final class SpawnBackCommand implements CommandExecutor, TabCompleter, Listener {

    private static final int CONFIRM_SECONDS = 30;

    private final SpawnOG plugin;
    private final ReturnLocationStore returnLocationStore;
    private final LoginMigrationService loginMigrationService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Long> pendingConfirmations = new HashMap<>();

    public SpawnBackCommand(SpawnOG plugin, ReturnLocationStore returnLocationStore,
            LoginMigrationService loginMigrationService)
    {

        this.plugin = plugin;
        this.returnLocationStore = returnLocationStore;
        this.loginMigrationService = loginMigrationService;

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String lbl,
            @NotNull String[] args)
    {

        if (!(sender instanceof Player player))
            return false;

        if (!player.hasPermission(cmd.getPermission())) {

            send(player, "locale.missingPermission", "<red>You are lacking the required permissions.</red>");
            return true;

        }

        UUID playerId = player.getUniqueId();

        // The migration owns the player's position until it resolves; returning
        // mid-flight would race the teleport it is already performing.
        if (loginMigrationService.isPending(player)) {

            send(player, "locale.returnBusy",
                    "<red>Your login is still being made safe. Try again in a few seconds.</red>");
            return true;

        }

        ReturnPoint point = returnLocationStore.get(playerId);
        if (point == null) {

            if (returnLocationStore.has(playerId))
                send(player, "locale.returnWorldMissing",
                        "<red>The world you were in (<world>) is not loaded, so you cannot return there.</red>",
                        Placeholder.unparsed("world", returnLocationStore.worldName(playerId)));
            else
                send(player, "locale.returnNone", "<red>You have no location to return to.</red>");

            pendingConfirmations.remove(playerId);
            return true;

        }

        if (!isConfirmed(player, args)) {

            pendingConfirmations.put(playerId, System.currentTimeMillis() + CONFIRM_SECONDS * 1000L);
            send(player, "locale.returnWarning",
                    "<gold>You were moved from <red><x>, <y>, <z></red> in <world> because <red><reason></red>. Run <red>/spawnback confirm</red> within <seconds> seconds to go back anyway; you may take damage or die.</gold>",
                    coordinates(point), Placeholder.unparsed("reason", point.reason()),
                    Placeholder.unparsed("seconds", String.valueOf(CONFIRM_SECONDS)));
            return true;

        }

        pendingConfirmations.remove(playerId);

        // The record is only consumed once the player is actually standing there
        // again, so a failed teleport does not cost them the way back.
        player.teleportAsync(point.location().clone()).whenComplete((success, error) -> {

            if (!plugin.isEnabled())
                return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {

                if (!Boolean.TRUE.equals(success) || error != null) {

                    send(player, "locale.returnFailed", "<red>Your return teleport failed.</red>");
                    return;

                }

                returnLocationStore.clear(playerId);
                send(player, "locale.returnConfirmed",
                        "<gold>Returning you to <red><x>, <y>, <z></red> in <world>. Good luck.</gold>",
                        coordinates(point));

            });

        });

        return true;

    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String lbl, @NotNull String[] args)
    {

        if (args.length == 1 && "confirm".startsWith(args[0].toLowerCase()))
            return List.of("confirm");
        return List.of();

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        pendingConfirmations.remove(event.getPlayer().getUniqueId());

    }

    private boolean isConfirmed(Player player, String[] args) {

        Long expiry = pendingConfirmations.get(player.getUniqueId());
        if (expiry == null || expiry < System.currentTimeMillis())
            return false;
        return args.length > 0 && args[0].equalsIgnoreCase("confirm");

    }

    private TagResolver coordinates(ReturnPoint point) {

        Location location = point.location();
        return TagResolver.resolver(Placeholder.unparsed("x", String.valueOf(location.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(location.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(location.getBlockZ())),
                Placeholder.unparsed("world", point.worldName()));

    }

    private void send(Player player, String path, String fallback, TagResolver... placeholders) {

        String message = plugin.getConfig().getString(path, fallback);
        Component component = miniMessage.deserialize(message, placeholders);
        player.sendMessage(component);

    }

}
