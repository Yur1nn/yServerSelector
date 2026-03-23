package dev.onelimit.yserverselector.config;

import java.util.List;

public record MenuItemConfig(
    String key,
    int slot,
    String server,
    String display,
    List<String> lore,
    String icon,
    boolean enabled,
    boolean showWhenOffline,
    boolean useQueue
) {
}

