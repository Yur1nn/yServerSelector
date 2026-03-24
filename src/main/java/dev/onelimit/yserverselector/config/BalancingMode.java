package dev.onelimit.yserverselector.config;

public enum BalancingMode {
    ROUND_ROBIN,
    LEAST_PLAYERS,
    RANDOM,
    FIRST_AVAILABLE,
    FILL;

    public static BalancingMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return ROUND_ROBIN;
        }

        try {
            return BalancingMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ROUND_ROBIN;
        }
    }
}
