package spawnog.flight;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ToggleRegionFlightCommand implements CommandExecutor {

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

            player.sendMessage(miniMessage
                    .deserialize("<red>Flight can only be toggled inside a region that allows flight.</red>"));
            return true;

        }

        boolean enabled = flightService.toggleFlight(player);
        player.sendMessage(miniMessage
                .deserialize(enabled ? "<green>Flight enabled.</green>" : "<yellow>Flight disabled.</yellow>"));
        return true;

    }

}
