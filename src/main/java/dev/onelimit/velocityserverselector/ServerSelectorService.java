package dev.onelimit.velocityserverselector;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.onelimit.velocityserverselector.config.MenuItemConfig;
import dev.onelimit.velocityserverselector.config.SelectorConfig;
import dev.onelimit.ycore.velocity.api.compat.DependencyChecker;
import dev.onelimit.ycore.velocity.api.text.CoreTextRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ServerSelectorService {
    private final ProxyServer server;
    private final Logger logger;
    private final CoreTextRenderer textRenderer;
    private final DependencyChecker dependencyChecker;

    private SelectorConfig config;
    private Map<String, MenuItemConfig> itemsByKey;

    public ServerSelectorService(ProxyServer server, Logger logger, SelectorConfig config) {
        this.server = server;
        this.logger = logger;
        this.textRenderer = new CoreTextRenderer();
        this.dependencyChecker = new DependencyChecker(server);
        this.config = config;
        rebuildItemsIndex();
    }

    public void updateConfig(SelectorConfig config) {
        this.config = config;
        rebuildItemsIndex();
    }

    public void openMenu(Player player) {
        player.sendMessage(render(config.menuTitle()));
        player.sendMessage(render(config.menuHeader()));

        int slots = config.menuRows() * 9;
        Map<Integer, MenuItemConfig> bySlot = new HashMap<>();
        for (MenuItemConfig item : config.items()) {
            if (item.slot() >= 0 && item.slot() < slots) {
                bySlot.put(item.slot(), item);
            }
        }

        String primaryLabel = config.commandAliases().isEmpty() ? "server" : config.commandAliases().get(0);

        for (int row = 0; row < config.menuRows(); row++) {
            Component line = Component.empty();
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                MenuItemConfig item = bySlot.get(slot);
                if (item == null) {
                    line = line.append(render(config.emptySlotSymbol()));
                } else {
                    Component cell = render("<yellow>[■]</yellow>")
                        .hoverEvent(HoverEvent.showText(buildItemHover(item)))
                        .clickEvent(ClickEvent.runCommand("/" + primaryLabel + " join " + item.key()));
                    line = line.append(cell);
                }
                if (col < 8) {
                    line = line.append(Component.space());
                }
            }
            player.sendMessage(line);
        }

        player.sendMessage(render("<gray>Legend:</gray>"));
        List<MenuItemConfig> sorted = config.items().stream()
            .sorted(Comparator.comparingInt(MenuItemConfig::slot))
            .toList();
        int index = 1;
        for (MenuItemConfig item : sorted) {
            String line = "<gray>[" + index + "]</gray> " + applyPlaceholders(item.display(), item);
            Component legendEntry = render(line)
                .hoverEvent(HoverEvent.showText(buildItemHover(item)))
                .clickEvent(ClickEvent.runCommand("/" + primaryLabel + " join " + item.key()));
            player.sendMessage(legendEntry);
            index++;
        }

        player.sendMessage(render(config.menuFooter()));
    }

    public void joinByKey(Player player, String key) {
        MenuItemConfig item = itemsByKey.get(key.toLowerCase(Locale.ROOT));
        if (item == null) {
            player.sendMessage(render("<red>Unknown server key:</red> <gray>" + key + "</gray>"));
            return;
        }

        if (item.useQueue() && hasAjQueue()) {
            String command = config.queueCommandTemplate().replace("%server%", item.server());
            String toRun = command.startsWith("/") ? command.substring(1) : command;
            player.spoofChatInput(toRun);
            return;
        }

        Optional<RegisteredServer> target = server.getServer(item.server());
        if (target.isEmpty()) {
            player.sendMessage(render("<red>Server not found:</red> <gray>" + item.server() + "</gray>"));
            return;
        }

        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to connect {} to {}", player.getUsername(), item.server(), error);
                player.sendMessage(render("<red>Failed to connect to</red> <gray>" + item.server() + "</gray>"));
                return;
            }

            if (!result.isSuccessful()) {
                player.sendMessage(render("<red>Could not connect to</red> <gray>" + item.server() + "</gray>"));
            }
        });
    }

    private void rebuildItemsIndex() {
        Map<String, MenuItemConfig> map = new HashMap<>();
        for (MenuItemConfig item : config.items()) {
            map.put(item.key().toLowerCase(Locale.ROOT), item);
        }
        this.itemsByKey = map;
    }

    private boolean hasAjQueue() {
        return dependencyChecker.hasPlugin("ajqueue");
    }

    private Component buildItemHover(MenuItemConfig item) {
        Component hover = render(applyPlaceholders(item.display(), item));
        for (String loreLine : item.lore()) {
            hover = hover.append(Component.newline()).append(render(applyPlaceholders(loreLine, item)));
        }
        return hover;
    }

    private String applyPlaceholders(String text, MenuItemConfig item) {
        int online = server.getServer(item.server())
            .map(RegisteredServer::getPlayersConnected)
            .map(players -> players.size())
            .orElse(0);

        String status = server.getServer(item.server()).isPresent() ? "ONLINE" : "OFFLINE";

        return text
            .replace("%server%", item.server())
            .replace("%server_name%", item.server())
            .replace("%player_count%", Integer.toString(online))
            .replace("%online%", Integer.toString(online))
            .replace("%status%", status)
            .replace("%icon%", item.icon())
            .replace("%has_ajqueue%", Boolean.toString(hasAjQueue()));
    }

    private Component render(String miniMessageText) {
        return textRenderer.render(miniMessageText);
    }
}
