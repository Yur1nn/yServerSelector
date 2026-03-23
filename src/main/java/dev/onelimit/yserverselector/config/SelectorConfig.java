package dev.onelimit.yserverselector.config;

import java.util.List;

public record SelectorConfig(
    int configVersion,
    boolean debug,
    boolean commandEnabled,
    List<String> commandAliases,
    boolean requirePermission,
    String permission,
    String pluginMessageChannel,
    int pingIntervalSeconds,
    int menuRows,
    String menuTitle,
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
            "yserverselector.admin",
            "onelimit:yss",
            5,
            3,
            "<gold><bold>Server Selector</bold></gold>",
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
                    true,
                    true,
                    false
                )
            )
        );
    }
}

