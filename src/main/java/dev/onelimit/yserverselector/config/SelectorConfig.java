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
    boolean nativeQueueEnabled,
    int queueCheckIntervalSeconds,
    int queueEntryTimeoutSeconds,
    int queueMaxSizePerServer,
    boolean queueNotifyPosition,
    int queuePositionUpdateSeconds,
    int queueDrainPerCycle,
    String fallbackServer,
    List<ServerGroupConfig> groups,
    int menuRows,
    String menuTitle,
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
            true,
            2,
            180,
            200,
            true,
            10,
            1,
            "",
            List.of(),
            3,
            "<gold><bold>Server Selector</bold></gold>",
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

