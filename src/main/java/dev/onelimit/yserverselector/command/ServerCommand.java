package dev.onelimit.yserverselector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.onelimit.yserverselector.ServerSelectorService;
import dev.onelimit.yserverselector.YServerSelectorPlugin;

public final class ServerCommand implements SimpleCommand {
    private final YServerSelectorPlugin plugin;
    private final ServerSelectorService selectorService;

    public ServerCommand(YServerSelectorPlugin plugin, ServerSelectorService selectorService) {
        this.plugin = plugin;
        this.selectorService = selectorService;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
            if (plugin.config().requirePermission() && !invocation.source().hasPermission(plugin.config().permission())) {
                invocation.source().sendPlainMessage("You do not have permission.");
                return;
            }

            plugin.reload();
            invocation.source().sendPlainMessage("yServerSelector reloaded.");
            return;
        }

        if (args.length > 0 && "debug".equalsIgnoreCase(args[0])) {
            if (plugin.config().requirePermission() && !invocation.source().hasPermission(plugin.config().permission())) {
                invocation.source().sendPlainMessage("You do not have permission.");
                return;
            }

            selectorService.debugLines().forEach(invocation.source()::sendPlainMessage);
            return;
        }

        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Only players can use this command for menu/join.");
            return;
        }

        if (args.length == 0) {
            selectorService.openMenu(player);
            return;
        }

        if ("leave".equalsIgnoreCase(args[0]) || "queueleave".equalsIgnoreCase(args[0])) {
            selectorService.leaveQueue(player);
            return;
        }

        if ("status".equalsIgnoreCase(args[0]) || "queuestatus".equalsIgnoreCase(args[0])) {
            selectorService.queueStatus(player);
            return;
        }

        if ("join".equalsIgnoreCase(args[0]) && args.length >= 2) {
            selectorService.joinByKey(player, args[1]);
            return;
        }

        selectorService.openMenu(player);
    }
}

