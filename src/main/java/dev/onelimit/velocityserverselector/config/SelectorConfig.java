package dev.onelimit.velocityserverselector.config;

import java.util.List;

public record SelectorConfig(
    int configVersion,
    boolean debug,
    boolean commandEnabled,
    List<String> commandAliases,
    boolean requirePermission,
    String permission,
    int menuRows,
    String menuTitle,
    String menuHeader,
    String menuFooter,
    String emptySlotSymbol,
    String queueCommandTemplate,
    List<MenuItemConfig> items
) {
    public static SelectorConfig defaults() {
        return new SelectorConfig(
            1,
            false,
            true,
            List.of("server"),
            false,
            "velocityserverselector.admin",
            3,
            "<gold><bold>Server Selector</bold></gold>",
            "<gray>Select a server:</gray>",
            "<dark_gray>Click a server block or legend entry.</dark_gray>",
            "<dark_gray>·</dark_gray>",
            "/ajqueue join %server%",
            List.of(
                new MenuItemConfig(
                    "lobby",
                    10,
                    "lobby",
                    "<aqua><bold>Lobby</bold></aqua>",
                    List.of(
                        "<gray>Players:</gray> <white>%player_count%</white>",
                        "<gray>Status:</gray> <green>%status%</green>"
                    ),
                    "NETHER_STAR",
                    false
                )
            )
        );
    }
}
