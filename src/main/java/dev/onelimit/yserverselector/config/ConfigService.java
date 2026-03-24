package dev.onelimit.yserverselector.config;

import dev.onelimit.ycore.velocity.api.config.ConfigValueReader;
import dev.onelimit.ycore.velocity.api.config.YamlConfigLoader;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigService {
    private final YamlConfigLoader<SelectorConfig> configLoader;

    public ConfigService(Logger logger, Path dataDirectory) {
        this.configLoader = new YamlConfigLoader<>(
            logger,
            dataDirectory,
            "config.yml",
            "config.yml",
            this::parse,
            SelectorConfig::defaults
        );
    }

    public SelectorConfig load() {
        return configLoader.load();
    }

    private SelectorConfig parse(Map<?, ?> root) {
        int configVersion = ConfigValueReader.integer(root.get("config-version"), 1);
        boolean debug = ConfigValueReader.bool(root.get("debug"), false);

        Map<String, Object> command = ConfigValueReader.map(root.get("command"));
        boolean commandEnabled = ConfigValueReader.bool(command.get("enabled"), true);
        boolean requirePermission = ConfigValueReader.bool(command.get("require-permission"), false);
        String permission = ConfigValueReader.string(command.get("permission"), "yserverselector.admin");

        List<String> aliases = parseAliases(command.get("aliases"));
        if (aliases.isEmpty()) {
            aliases = List.of("server");
        }

        Map<String, Object> menu = ConfigValueReader.map(root.get("menu"));
        String pluginMessageChannel = ConfigValueReader.string(menu.get("plugin-message-channel"), "onelimit:yss");
        int pingIntervalSeconds = Math.max(2, ConfigValueReader.integer(menu.get("ping-interval-seconds"), 5));

        Map<String, Object> queue = ConfigValueReader.map(root.get("queue"));
        boolean nativeQueueEnabled = ConfigValueReader.bool(queue.get("enabled"), true);
        int queueCheckIntervalSeconds = Math.max(1, ConfigValueReader.integer(queue.get("check-interval-seconds"), 2));
        int queueEntryTimeoutSeconds = Math.max(15, ConfigValueReader.integer(queue.get("entry-timeout-seconds"), 180));
        int queueMaxSizePerServer = Math.max(1, ConfigValueReader.integer(queue.get("max-size-per-server"), 200));
        boolean queueNotifyPosition = ConfigValueReader.bool(queue.get("notify-position"), true);
        int queuePositionUpdateSeconds = Math.max(2, ConfigValueReader.integer(queue.get("position-update-seconds"), 10));
        int queueDrainPerCycle = Math.max(1, ConfigValueReader.integer(queue.get("drain-per-cycle"), 1));
        String fallbackServer = ConfigValueReader.string(queue.get("fallback-server"), "").trim();

        List<ServerGroupConfig> groups = parseGroups(root.get("groups"));

        int rows = Math.max(1, Math.min(6, ConfigValueReader.integer(menu.get("rows"), 3)));
        String title = ConfigValueReader.string(menu.get("title"), "<gold><bold>Server Selector</bold></gold>");

        List<MenuItemConfig> items = parseItems(menu.get("items"));

        return new SelectorConfig(
            configVersion,
            debug,
            commandEnabled,
            aliases,
            requirePermission,
            permission,
            pluginMessageChannel,
            pingIntervalSeconds,
            nativeQueueEnabled,
            queueCheckIntervalSeconds,
            queueEntryTimeoutSeconds,
            queueMaxSizePerServer,
            queueNotifyPosition,
            queuePositionUpdateSeconds,
            queueDrainPerCycle,
            fallbackServer,
            groups,
            rows,
            title,
            items
        );
    }

    private List<ServerGroupConfig> parseGroups(Object raw) {
        List<ServerGroupConfig> groups = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> rootMap)) {
            return groups;
        }

        for (Map.Entry<?, ?> entry : rootMap.entrySet()) {
            String key = ConfigValueReader.string(entry.getKey(), "").trim().toLowerCase();
            if (key.isEmpty() || !(entry.getValue() instanceof Map<?, ?> groupMap)) {
                continue;
            }

            List<String> members = parseAliases(groupMap.get("members"));
            if (members.isEmpty()) {
                continue;
            }

            String modeRaw = ConfigValueReader.string(groupMap.get("balancing-mode"), "ROUND_ROBIN");
            BalancingMode mode = BalancingMode.fromString(modeRaw);
            groups.add(new ServerGroupConfig(key, members, mode));
        }

        return groups;
    }

    private List<MenuItemConfig> parseItems(Object raw) {
        List<MenuItemConfig> items = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return items;
        }

        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) {
                continue;
            }

            String key = ConfigValueReader.string(map.get("key"), "").trim().toLowerCase();
            String server = ConfigValueReader.string(map.get("target"), "").trim();
            if (server.isEmpty()) {
                server = ConfigValueReader.string(map.get("server"), "").trim();
            }
            if (key.isEmpty() || server.isEmpty()) {
                continue;
            }

            int slot = Math.max(0, ConfigValueReader.integer(map.get("slot"), 0));
            String display = ConfigValueReader.string(map.get("display"), "<white>" + key + "</white>");
            String icon = ConfigValueReader.string(map.get("icon"), "STONE");
            boolean enabled = ConfigValueReader.bool(map.get("enabled"), true);
            boolean showWhenOffline = ConfigValueReader.bool(map.get("show-when-offline"), true);
            boolean useQueue = ConfigValueReader.bool(map.get("use-queue"), false);
            List<String> lore = parseStringList(map.get("lore"));

            items.add(new MenuItemConfig(key, slot, server, display, lore, icon, enabled, showWhenOffline, useQueue));
        }

        return items;
    }

    private List<String> parseAliases(Object raw) {
        List<String> aliases = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object alias : list) {
                String value = ConfigValueReader.string(alias, "").trim().toLowerCase();
                if (!value.isEmpty()) {
                    aliases.add(value);
                }
            }
        }
        return aliases;
    }

    private List<String> parseStringList(Object raw) {
        return ConfigValueReader.stringList(raw);
    }
}

