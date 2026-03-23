package dev.onelimit.velocityserverselector.config;

import java.util.List;

public record MenuItemConfig(
    String key,
    int slot,
    String server,
    String display,
    List<String> lore,
    String icon,
    boolean useQueue
) {
}
