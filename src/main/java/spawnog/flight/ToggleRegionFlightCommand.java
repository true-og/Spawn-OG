package spawnog.flight;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import spawnog.SpawnOG;

public final class ToggleRegionFlightCommand implements CommandExecutor {

    private final SpawnOG plugin = SpawnOG.getInstance();
    private final RegionFlightService flightService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ToggleRegionFlightCommand(RegionFlightService flightService) {

        this.flightService = flightService;

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args)
    {

        if (args.length != 0)
            return false;
        if (!(sender instanceof Player player)) {

            sender.sendMessage("Only players can use /fly.");
            return true;

        }

        if (!flightService.canToggleFlight(player)) {

            // Three distinct refusals; telling a creative player "wrong
            // region" sends them hunting for a problem they don't have.
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)
                send(player, "locale.flightGamemodeGoverned",
                        "<red>Your gamemode already governs flight; /fly is for survival.</red>");
            else if (!flightService.hasFlightPermission(player))
                send(player, "locale.missingPermission", "<red>You are lacking the required permissions.</red>");
            else
                send(player, "locale.flightRegionRefused",
                        "<red>Flight can only be toggled inside a region that allows flight.</red>");
            return true;

        }

        boolean enabled = flightService.toggleFlight(player);
        if (enabled)
            send(player, "locale.flightEnabled", "<green>Flight enabled.</green>");
        else
            send(player, "locale.flightDisabled", "<yellow>Flight disabled.</yellow>");
        return true;

    }

    private void send(Player player, String path, String fallback) {

        String message = plugin.getConfig().getString(path, fallback);
        player.sendMessage(miniMessage.deserialize(message));

    }

}
