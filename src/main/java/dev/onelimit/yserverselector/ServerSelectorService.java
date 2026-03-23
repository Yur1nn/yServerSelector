package dev.onelimit.yserverselector;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.onelimit.yserverselector.config.MenuItemConfig;
import dev.onelimit.yserverselector.config.SelectorConfig;
import dev.onelimit.ycore.velocity.api.compat.DependencyChecker;
import dev.onelimit.ycore.velocity.api.util.CorePlaceholders;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ServerSelectorService {
    private static final int PROTOCOL_VERSION = 1;
    private static final String OPEN_MENU_ACTION = "OPEN_MENU";
    private static final String SELECT_SERVER_ACTION = "SELECT_SERVER";
    private static final String HEARTBEAT_ACTION = "HEARTBEAT";
    private static final long HEARTBEAT_STALE_MILLIS = 30_000L;

    private final YServerSelectorPlugin plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final DependencyChecker dependencyChecker;

    private SelectorConfig config;
    private Map<String, MenuItemConfig> itemsByKey;
    private MinecraftChannelIdentifier channelIdentifier;
    private ScheduledTask pingTask;
    private final Map<String, RuntimeStatus> runtimeStatusByServer;

    public ServerSelectorService(YServerSelectorPlugin plugin, ProxyServer server, Logger logger, SelectorConfig config) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.dependencyChecker = new DependencyChecker(server);
        this.config = config;
        this.runtimeStatusByServer = new ConcurrentHashMap<>();
        this.channelIdentifier = MinecraftChannelIdentifier.from(config.pluginMessageChannel());
        rebuildItemsIndex();
        startStatusPolling();
    }

    public void updateConfig(SelectorConfig config) {
        MinecraftChannelIdentifier previousChannel = this.channelIdentifier;
        this.config = config;
        this.channelIdentifier = MinecraftChannelIdentifier.from(config.pluginMessageChannel());

        if (!previousChannel.getId().equals(channelIdentifier.getId())) {
            server.getChannelRegistrar().unregister(previousChannel);
            server.getChannelRegistrar().register(channelIdentifier);
        }

        rebuildItemsIndex();
        startStatusPolling();
    }

    public void shutdown() {
        if (pingTask != null) {
            pingTask.cancel();
            pingTask = null;
        }
    }

    public void registerChannel() {
        server.getChannelRegistrar().register(channelIdentifier);
    }

    public void unregisterChannel() {
        server.getChannelRegistrar().unregister(channelIdentifier);
    }

    public boolean matchesChannel(ChannelIdentifier identifier) {
        return channelIdentifier.equals(identifier);
    }

    public void handleBridgeMessage(ServerConnection source, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != PROTOCOL_VERSION) {
                return;
            }

            String action = in.readUTF();
            if (HEARTBEAT_ACTION.equals(action)) {
                handleHeartbeat(source, in);
                return;
            }

            if (SELECT_SERVER_ACTION.equals(action)) {
                handleSelection(source, in);
            }
        } catch (IOException ex) {
            logger.warn("Failed to process yServerSelector bridge message", ex);
        }
    }

    public void openMenu(Player player) {
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            player.sendPlainMessage("You must be connected to a child server to open the server selector GUI.");
            return;
        }

        byte[] payload = buildOpenMenuPayload(player.getUniqueId());
        boolean sent = current.get().sendPluginMessage(channelIdentifier, payload);
        if (!sent) {
            player.sendPlainMessage("Unable to open selector GUI. Install yServerSelector-Paper on this server.");
        }
    }

    public void joinByKey(Player player, String key) {
        MenuItemConfig item = itemsByKey.get(key.toLowerCase(Locale.ROOT));
        if (item == null) {
            player.sendPlainMessage("Unknown server key: " + key);
            return;
        }

        if (!item.enabled()) {
            player.sendPlainMessage("That destination is currently disabled.");
            return;
        }

        RuntimeStatus runtimeStatus = statusFor(item.server());
        if (!runtimeStatus.online() && !item.showWhenOffline()) {
            player.sendPlainMessage("That destination is currently offline.");
            return;
        }

        if (item.useQueue() && hasAjQueue()) {
            String command = CorePlaceholders.replaceNamed(config.queueCommandTemplate(), Map.of("server", item.server()));
            String toRun = command.startsWith("/") ? command.substring(1) : command;
            player.spoofChatInput(toRun);
            return;
        }

        Optional<RegisteredServer> target = server.getServer(item.server());
        if (target.isEmpty()) {
            player.sendPlainMessage("Server not found: " + item.server());
            return;
        }

        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to connect {} to {}", player.getUsername(), item.server(), error);
                player.sendPlainMessage("Failed to connect to " + item.server());
                return;
            }

            if (!result.isSuccessful()) {
                player.sendPlainMessage("Could not connect to " + item.server());
            }
        });
    }

    private void handleHeartbeat(ServerConnection source, DataInputStream in) throws IOException {
        in.readUTF();
        int playerCount = in.readInt();
        String serverName = source.getServerInfo().getName();
        RuntimeStatus existing = runtimeStatusByServer.get(serverName.toLowerCase(Locale.ROOT));
        RuntimeStatus updated = new RuntimeStatus(
            true,
            playerCount,
            System.currentTimeMillis(),
            existing != null ? existing.reachableByPing() : true
        );
        runtimeStatusByServer.put(serverName.toLowerCase(Locale.ROOT), updated);
    }

    private void handleSelection(ServerConnection source, DataInputStream in) throws IOException {
        UUID playerId = UUID.fromString(in.readUTF());
        String key = in.readUTF();

        server.getPlayer(playerId).ifPresent(player -> {
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isEmpty() || !current.get().getServerInfo().getName().equals(source.getServerInfo().getName())) {
                return;
            }
            joinByKey(player, key);
        });
    }

    private byte[] buildOpenMenuPayload(UUID playerId) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(OPEN_MENU_ACTION);
            out.writeUTF(playerId.toString());
            out.writeInt(config.menuRows());
            out.writeUTF(config.menuTitle());

            List<MenuPayloadItem> payloadItems = config.items().stream()
                .filter(MenuItemConfig::enabled)
                .map(item -> toPayloadItem(item, statusFor(item.server())))
                .filter(item -> item.showWhenOffline || item.online)
                .sorted(Comparator.comparingInt(item -> item.slot))
                .toList();

            out.writeInt(payloadItems.size());
            for (MenuPayloadItem item : payloadItems) {
                out.writeUTF(item.key);
                out.writeInt(item.slot);
                out.writeUTF(item.server);
                out.writeUTF(item.display);
                out.writeUTF(item.icon);
                out.writeBoolean(item.online);
                out.writeBoolean(item.useQueue);
                out.writeInt(item.playerCount);
                out.writeInt(item.lore.size());
                for (String lore : item.lore) {
                    out.writeUTF(lore);
                }
            }

            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize selector menu payload", ex);
        }
    }

    private MenuPayloadItem toPayloadItem(MenuItemConfig item, RuntimeStatus runtimeStatus) {
        String statusText = runtimeStatus.online() ? "ONLINE" : "OFFLINE";
        Map<String, Object> values = Map.of(
            "server", item.server(),
            "server_name", item.server(),
            "player_count", runtimeStatus.playerCount(),
            "online", runtimeStatus.playerCount(),
            "status", statusText,
            "icon", item.icon(),
            "has_ajqueue", hasAjQueue(),
            "server_online", runtimeStatus.online()
        );

        List<String> lore = item.lore().stream()
            .map(line -> CorePlaceholders.replaceNamed(line, values))
            .toList();

        return new MenuPayloadItem(
            item.key(),
            item.slot(),
            item.server(),
            CorePlaceholders.replaceNamed(item.display(), values),
            lore,
            item.icon(),
            runtimeStatus.online(),
            item.useQueue(),
            item.showWhenOffline(),
            runtimeStatus.playerCount()
        );
    }

    private void rebuildItemsIndex() {
        Map<String, MenuItemConfig> map = new HashMap<>();
        for (MenuItemConfig item : config.items()) {
            map.put(item.key().toLowerCase(Locale.ROOT), item);
        }
        this.itemsByKey = map;

        Set<String> configuredServers = new HashSet<>();
        for (MenuItemConfig item : config.items()) {
            configuredServers.add(item.server().toLowerCase(Locale.ROOT));
        }
        runtimeStatusByServer.entrySet().removeIf(entry -> !configuredServers.contains(entry.getKey()));
    }

    private boolean hasAjQueue() {
        return dependencyChecker.hasPlugin("ajqueue");
    }

    private void startStatusPolling() {
        if (pingTask != null) {
            pingTask.cancel();
        }

        long interval = Math.max(2, config.pingIntervalSeconds());
        pingTask = server.getScheduler()
            .buildTask(plugin, this::refreshServerStatuses)
            .repeat(interval, TimeUnit.SECONDS)
            .schedule();

        refreshServerStatuses();
    }

    private void refreshServerStatuses() {
        for (MenuItemConfig item : config.items()) {
            String key = item.server().toLowerCase(Locale.ROOT);
            Optional<RegisteredServer> registered = server.getServer(item.server());
            if (registered.isEmpty()) {
                runtimeStatusByServer.put(key, new RuntimeStatus(false, 0, 0L, false));
                continue;
            }

            int players = registered.get().getPlayersConnected().size();
            registered.get().ping().whenComplete((ping, error) -> {
                RuntimeStatus existing = runtimeStatusByServer.get(key);
                long heartbeatAt = existing != null ? existing.lastHeartbeatAt() : 0L;
                int bridgedPlayers = existing != null ? existing.playerCount() : players;
                boolean heartbeatFresh = heartbeatAt > 0L && (System.currentTimeMillis() - heartbeatAt) <= HEARTBEAT_STALE_MILLIS;

                int finalPlayers = heartbeatFresh ? bridgedPlayers : players;
                boolean online = error == null;

                runtimeStatusByServer.put(key, new RuntimeStatus(online, finalPlayers, heartbeatAt, online));
            });
        }
    }

    private RuntimeStatus statusFor(String serverName) {
        RuntimeStatus status = runtimeStatusByServer.get(serverName.toLowerCase(Locale.ROOT));
        if (status == null) {
            int online = server.getServer(serverName)
                .map(RegisteredServer::getPlayersConnected)
                .map(players -> players.size())
                .orElse(0);
            return new RuntimeStatus(server.getServer(serverName).isPresent(), online, 0L, false);
        }
        return status;
    }

    private record RuntimeStatus(boolean online, int playerCount, long lastHeartbeatAt, boolean reachableByPing) {
    }

    private record MenuPayloadItem(
        String key,
        int slot,
        String server,
        String display,
        List<String> lore,
        String icon,
        boolean online,
        boolean useQueue,
        boolean showWhenOffline,
        int playerCount
    ) {
    }
}

