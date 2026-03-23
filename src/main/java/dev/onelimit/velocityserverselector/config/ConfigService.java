package dev.onelimit.velocityserverselector.config;

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
        int configVersion = integer(root.get("config-version"), 1);
        boolean debug = bool(root.get("debug"), false);

        Map<String, Object> command = map(root.get("command"));
        boolean commandEnabled = bool(command.get("enabled"), true);
        boolean requirePermission = bool(command.get("require-permission"), false);
        String permission = string(command.get("permission"), "velocityserverselector.admin");

        List<String> aliases = parseAliases(command.get("aliases"));
        if (aliases.isEmpty()) {
            aliases = List.of("server");
        }

        Map<String, Object> menu = map(root.get("menu"));
        int rows = Math.max(1, Math.min(6, integer(menu.get("rows"), 3)));
        String title = string(menu.get("title"), "<gold><bold>Server Selector</bold></gold>");
        String header = string(menu.get("header"), "<gray>Select a server:</gray>");
        String footer = string(menu.get("footer"), "<dark_gray>Click a server block or legend entry.</dark_gray>");
        String emptySlotSymbol = string(menu.get("empty-slot-symbol"), "<dark_gray>·</dark_gray>");
        String queueCommandTemplate = string(menu.get("queue-command-template"), "/ajqueue join %server%");

        List<MenuItemConfig> items = parseItems(menu.get("items"));

        return new SelectorConfig(
            configVersion,
            debug,
            commandEnabled,
            aliases,
            requirePermission,
            permission,
            rows,
            title,
            header,
            footer,
            emptySlotSymbol,
            queueCommandTemplate,
            items
        );
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

            String key = string(map.get("key"), "").trim().toLowerCase();
            String server = string(map.get("server"), "").trim();
            if (key.isEmpty() || server.isEmpty()) {
                continue;
            }

            int slot = Math.max(0, integer(map.get("slot"), 0));
            String display = string(map.get("display"), "<white>" + key + "</white>");
            String icon = string(map.get("icon"), "STONE");
            boolean useQueue = bool(map.get("use-queue"), false);
            List<String> lore = parseStringList(map.get("lore"));

            items.add(new MenuItemConfig(key, slot, server, display, lore, icon, useQueue));
        }

        return items;
    }

    private List<String> parseAliases(Object raw) {
        List<String> aliases = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object alias : list) {
                String value = string(alias, "").trim().toLowerCase();
                if (!value.isEmpty()) {
                    aliases.add(value);
                }
            }
        }
        return aliases;
    }

    private List<String> parseStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return values;
        }

        for (Object obj : list) {
            String value = string(obj, "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object input) {
        if (input instanceof Map<?, ?> source) {
            return (Map<String, Object>) source;
        }
        return Map.of();
    }

    private String string(Object input, String fallback) {
        if (input == null) {
            return fallback;
        }
        String value = String.valueOf(input);
        return value.isEmpty() ? fallback : value;
    }

    private int integer(Object input, int fallback) {
        if (input == null) {
            return fallback;
        }
        if (input instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(input));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean bool(Object input, boolean fallback) {
        if (input == null) {
            return fallback;
        }
        if (input instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(input));
    }
}
