package dev.onelimit.yserverselector.config;

import java.util.List;

public record ServerGroupConfig(
    String key,
    List<String> members,
    BalancingMode balancingMode
) {
}
