package spawnog.commands;

import java.util.List;
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
import spawnog.flight.FlightMode;
import spawnog.flight.FlightRestorer;
import spawnog.login.LoginMigrationService;
import spawnog.login.ReturnLocationStore;
import spawnog.login.ReturnLocationStore.ReturnPoint;
import spawnog.teleport.FallProtection;

// Sends a player back to wherever a login safety migration took them from. The
// position is knowingly unsafe, so the first /spawnback only warns and the
// second one within the window executes; there is no subcommand.
public final class SpawnBackCommand implements CommandExecutor, TabCompleter, Listener {

    private static final int DEFAULT_CONFIRM_SECONDS = 30;

    private final SpawnOG plugin;
    private final ReturnLocationStore returnLocationStore;
    private final LoginMigrationService loginMigrationService;
    private final FlightRestorer flightRestorer;
    private final FallProtection fallProtection;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PendingConfirmations pendingConfirmations = new PendingConfirmations(System::currentTimeMillis);

    public SpawnBackCommand(SpawnOG plugin, ReturnLocationStore returnLocationStore,
            LoginMigrationService loginMigrationService, FlightRestorer flightRestorer, FallProtection fallProtection)
    {

        this.plugin = plugin;
        this.returnLocationStore = returnLocationStore;
        this.loginMigrationService = loginMigrationService;
        this.flightRestorer = flightRestorer;
        this.fallProtection = fallProtection;

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

            pendingConfirmations.clear(playerId);
            return true;

        }

        int confirmSeconds = plugin.getConfig().getInt("spawnback.confirm-seconds", DEFAULT_CONFIRM_SECONDS);
        if (!pendingConfirmations.confirm(playerId, point.token(), confirmSeconds * 1000L)) {

            send(player, "locale.spawnbackWarning",
                    "<gold>You were moved from <red><x>, <y>, <z></red> in <world> because <red><reason></red>. Run <click:run_command:'/spawnback'><hover:show_text:'<gold>Click to run <red>/spawnback</red> again</gold>'><red>/spawnback</red></hover></click> again within <seconds> seconds to go back anyway; you may take damage or die.</gold>",
                    coordinates(point), Placeholder.unparsed("reason", point.reason()),
                    Placeholder.unparsed("seconds", String.valueOf(confirmSeconds)));
            // A flyer headed for a spot that no longer permits their flight is
            // told about the drop before they commit to it.
            if (point.mode() != FlightMode.NONE && !flightRestorer.canRestore(point.mode(), player, point.location()))
                send(player, "locale.spawnbackFallWarning",
                        "<gold>You can no longer fly there, so you will fall; a one-time fall protection will cover the landing.</gold>");
            return true;

        }

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

                // Re-checked live at the destination: a permission or region
                // right lost since the warning means protection, not flight.
                boolean restored = flightRestorer.restore(point.mode(), player);
                if (!restored) {

                    fallProtection.grant(player);
                    if (point.mode() != FlightMode.NONE)
                        send(player, "locale.returnFlightDenied",
                                "<gold>You don't have the authority to fly here.</gold>");

                }

                // Cleared only after the restore attempt, so the one-shot record
                // is never burned before its flight decision has been made.
                returnLocationStore.clear(playerId);
                send(player, "locale.spawnbackExecuted",
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

        return List.of();

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        pendingConfirmations.clear(event.getPlayer().getUniqueId());

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
