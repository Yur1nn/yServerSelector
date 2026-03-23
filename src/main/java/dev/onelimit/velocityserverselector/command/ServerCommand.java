package dev.onelimit.velocityserverselector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.onelimit.velocityserverselector.ServerSelectorService;
import dev.onelimit.velocityserverselector.VelocityServerSelectorPlugin;

public final class ServerCommand implements SimpleCommand {
    private final VelocityServerSelectorPlugin plugin;
    private final ServerSelectorService selectorService;

    public ServerCommand(VelocityServerSelectorPlugin plugin, ServerSelectorService selectorService) {
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
            invocation.source().sendPlainMessage("VelocityServerSelector reloaded.");
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

        if ("join".equalsIgnoreCase(args[0]) && args.length >= 2) {
            selectorService.joinByKey(player, args[1]);
            return;
        }

        selectorService.openMenu(player);
    }
}
