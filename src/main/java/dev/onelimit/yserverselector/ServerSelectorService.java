package dev.onelimit.yserverselector;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.onelimit.ycore.velocity.api.util.CorePlaceholders;
import dev.onelimit.yserverselector.config.BalancingMode;
import dev.onelimit.yserverselector.config.MenuItemConfig;
import dev.onelimit.yserverselector.config.SelectorConfig;
import dev.onelimit.yserverselector.config.ServerGroupConfig;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerSelectorService {
    private static final int PROTOCOL_VERSION = 1;
    private static final String OPEN_MENU_ACTION = "OPEN_MENU";
    private static final String SELECT_SERVER_ACTION = "SELECT_SERVER";
    private static final String HEARTBEAT_ACTION = "HEARTBEAT";
    private static final long HEARTBEAT_STALE_MILLIS = 30_000L;

    private final YServerSelectorPlugin plugin;
    private final ProxyServer server;
    private final Logger logger;

    private SelectorConfig config;
    private Map<String, MenuItemConfig> itemsByKey;
    private Map<String, ServerGroupConfig> groupsByKey;
    private final Map<String, AtomicInteger> groupRoundRobinIndex;

    private MinecraftChannelIdentifier channelIdentifier;
    private ScheduledTask pingTask;
    private ScheduledTask queueTask;

    private final Map<String, RuntimeStatus> runtimeStatusByServer;
    private final Map<String, ConcurrentLinkedDeque<QueueEntry>> queueByTarget;
    private final Map<UUID, String> queuedTargetByPlayer;
    private final Map<UUID, Long> lastQueuePositionNotice;
    private final Map<UUID, Map<String, String>> directSelectionByPlayer;

    public ServerSelectorService(YServerSelectorPlugin plugin, ProxyServer server, Logger logger, SelectorConfig config) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.runtimeStatusByServer = new ConcurrentHashMap<>();
        this.queueByTarget = new ConcurrentHashMap<>();
        this.queuedTargetByPlayer = new ConcurrentHashMap<>();
        this.lastQueuePositionNotice = new ConcurrentHashMap<>();
        this.directSelectionByPlayer = new ConcurrentHashMap<>();
        this.groupRoundRobinIndex = new ConcurrentHashMap<>();
        this.channelIdentifier = MinecraftChannelIdentifier.from(config.pluginMessageChannel());
        this.itemsByKey = new HashMap<>();
        this.groupsByKey = new HashMap<>();

        rebuildIndexes();
        startStatusPolling();
        startQueueProcessor();
    }

    public void updateConfig(SelectorConfig config) {
        MinecraftChannelIdentifier previousChannel = this.channelIdentifier;
        this.config = config;
        this.channelIdentifier = MinecraftChannelIdentifier.from(config.pluginMessageChannel());

        if (!previousChannel.getId().equals(channelIdentifier.getId())) {
            server.getChannelRegistrar().unregister(previousChannel);
            server.getChannelRegistrar().register(channelIdentifier);
        }

        rebuildIndexes();
        startStatusPolling();
        startQueueProcessor();
    }

    public void shutdown() {
        if (pingTask != null) {
            pingTask.cancel();
            pingTask = null;
        }
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
        queueByTarget.clear();
        queuedTargetByPlayer.clear();
        lastQueuePositionNotice.clear();
        directSelectionByPlayer.clear();
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
        directSelectionByPlayer.remove(player.getUniqueId());
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            player.sendPlainMessage("You must be connected to a child server to open the server selector GUI.");
            return;
        }

        List<MenuPayloadItem> payloadItems = config.items().stream()
            .filter(MenuItemConfig::enabled)
            .map(this::toPayloadItem)
            .filter(item -> item.showWhenOffline || item.online)
            .sorted(Comparator.comparingInt(item -> item.slot))
            .toList();

        boolean sent = sendMenuPayload(current.get(), player.getUniqueId(), config.menuRows(), config.menuTitle(), payloadItems);
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

        String targetKey = item.server().toLowerCase(Locale.ROOT);

        if (item.useQueue() && config.nativeQueueEnabled()) {
            enqueuePlayer(player, targetKey);
            return;
        }

        TargetStatus status = targetStatusFor(targetKey);
        if (!status.online() && !item.showWhenOffline()) {
            player.sendPlainMessage("That destination is currently offline.");
            return;
        }

        String destination = resolveDestination(targetKey, true);
        if (destination == null) {
            if (!connectToFallback(player, targetKey, "manual-no-destination")) {
                player.sendPlainMessage("No available backend for target " + targetKey + ".");
            }
            return;
        }

        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isPresent() && current.get().getServerInfo().getName().equalsIgnoreCase(destination)) {
            player.sendPlainMessage("You are already on " + destination + ".");
            return;
        }

        connectPlayerToServer(player, destination, true, "manual");
    }

    public void leaveQueue(Player player) {
        String targetKey = queuedTargetByPlayer.remove(player.getUniqueId());
        if (targetKey == null) {
            player.sendPlainMessage("You are not in any queue.");
            return;
        }

        ConcurrentLinkedDeque<QueueEntry> queue = queueByTarget.get(targetKey);
        if (queue != null) {
            queue.removeIf(entry -> entry.playerId().equals(player.getUniqueId()));
        }
        lastQueuePositionNotice.remove(player.getUniqueId());
        player.sendPlainMessage("You left the queue for " + targetKey + ".");
    }

    public void queueStatus(Player player) {
        String targetKey = queuedTargetByPlayer.get(player.getUniqueId());
        if (targetKey == null) {
            player.sendPlainMessage("You are not in any queue.");
            return;
        }

        int position = queuePosition(player.getUniqueId(), targetKey);
        int size = queueByTarget.getOrDefault(targetKey, new ConcurrentLinkedDeque<>()).size();
        player.sendPlainMessage("Queue target: " + targetKey + " | position: " + position + "/" + size);
    }

    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[yServerSelector debug]");
        lines.add("channel=" + config.pluginMessageChannel() + " | items=" + config.items().size() + " | groups=" + groupsByKey.size());
        lines.add("queue: enabled=" + config.nativeQueueEnabled()
            + " check=" + config.queueCheckIntervalSeconds() + "s"
            + " timeout=" + config.queueEntryTimeoutSeconds() + "s"
            + " max=" + config.queueMaxSizePerServer()
            + " drain=" + config.queueDrainPerCycle()
            + " notify=" + config.queueNotifyPosition()
            + " pos-update=" + config.queuePositionUpdateSeconds() + "s"
            + " fallback=" + (config.fallbackServer().isBlank() ? "<none>" : config.fallbackServer()));

        List<MenuItemConfig> items = config.items().stream().sorted(Comparator.comparingInt(MenuItemConfig::slot)).toList();
        for (MenuItemConfig item : items) {
            String targetKey = item.server().toLowerCase(Locale.ROOT);
            TargetStatus status = targetStatusFor(targetKey);
            String preview = resolveDestinationPreview(targetKey, true);
            int queueSize = queueByTarget.getOrDefault(targetKey, new ConcurrentLinkedDeque<>()).size();
            boolean isGroup = groupsByKey.containsKey(targetKey);

            lines.add("- item=" + item.key()
                + " slot=" + item.slot()
                + " target=" + targetKey + (isGroup ? "(group)" : "(server)")
                + " online=" + status.online()
                + " players=" + status.playerCount()
                + " queue=" + queueSize
                + " next=" + (preview == null ? "<none>" : preview));
        }

        if (!groupsByKey.isEmpty()) {
            lines.add("groups:");
            groupsByKey.values().stream()
                .sorted(Comparator.comparing(ServerGroupConfig::key))
                .forEach(group -> lines.add("  * " + group.key() + " mode=" + group.balancingMode() + " members=" + String.join(",", group.members())));
        }

        return lines;
    }

    private void handleHeartbeat(ServerConnection source, DataInputStream in) throws IOException {
        in.readUTF();
        int playerCount = in.readInt();
        String serverName = source.getServerInfo().getName().toLowerCase(Locale.ROOT);
        RuntimeStatus existing = runtimeStatusByServer.get(serverName);
        RuntimeStatus updated = new RuntimeStatus(
            true,
            playerCount,
            System.currentTimeMillis(),
            existing != null ? existing.reachableByPing() : true
        );
        runtimeStatusByServer.put(serverName, updated);
    }

    private void handleSelection(ServerConnection source, DataInputStream in) throws IOException {
        UUID playerId = UUID.fromString(in.readUTF());
        String key = in.readUTF();
        boolean openListRequested = in.available() > 0 && in.readBoolean();

        server.getPlayer(playerId).ifPresent(player -> {
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isEmpty() || !current.get().getServerInfo().getName().equals(source.getServerInfo().getName())) {
                return;
            }
            handleMenuSelection(player, key, openListRequested);
        });
    }

    private void handleMenuSelection(Player player, String key, boolean openListRequested) {
        Map<String, String> directMap = directSelectionByPlayer.get(player.getUniqueId());
        if (directMap != null && directMap.containsKey(key)) {
            String serverName = directMap.get(key);
            directSelectionByPlayer.remove(player.getUniqueId());

            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isPresent() && current.get().getServerInfo().getName().equalsIgnoreCase(serverName)) {
                player.sendPlainMessage("You are already on " + serverName + ".");
                return;
            }

            connectPlayerToServer(player, serverName, true, "group-select");
            return;
        }

        MenuItemConfig item = itemsByKey.get(key.toLowerCase(Locale.ROOT));
        if (item == null) {
            player.sendPlainMessage("Unknown server key: " + key);
            return;
        }

        String targetKey = item.server().toLowerCase(Locale.ROOT);
        ServerGroupConfig group = groupsByKey.get(targetKey);
        if (group != null) {
            boolean currentlyOnGroupMember = player.getCurrentServer()
                .map(serverConnection -> group.members().contains(serverConnection.getServerInfo().getName().toLowerCase(Locale.ROOT)))
                .orElse(false);

            if (openListRequested || currentlyOnGroupMember) {
                openGroupPicker(player, item, group);
                return;
            }
        }

        joinByKey(player, key);
    }

    private boolean sendMenuPayload(ServerConnection connection, UUID playerId, int rows, String title, List<MenuPayloadItem> payloadItems) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(OPEN_MENU_ACTION);
            out.writeUTF(playerId.toString());
            out.writeInt(Math.max(1, Math.min(6, rows)));
            out.writeUTF(title);

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

            return connection.sendPluginMessage(channelIdentifier, buffer.toByteArray());
        } catch (IOException ex) {
            logger.warn("Failed to serialize selector menu payload", ex);
            return false;
        }
    }

    private void openGroupPicker(Player player, MenuItemConfig item, ServerGroupConfig group) {
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            player.sendPlainMessage("You must be connected to a child server to open the group list.");
            return;
        }

        Map<String, String> directMap = new HashMap<>();
        List<MenuPayloadItem> payloadItems = new ArrayList<>();
        int slot = 0;
        for (String member : group.members()) {
            RuntimeStatus status = statusForServer(member);
            int queueSize = queueByTarget.getOrDefault(item.server().toLowerCase(Locale.ROOT), new ConcurrentLinkedDeque<>()).size();
            String key = "member:" + group.key() + ":" + member;
            directMap.put(key, member);

            Map<String, Object> values = Map.of(
                "server", member,
                "server_name", member,
                "player_count", status.playerCount(),
                "online", status.playerCount(),
                "queue_size", queueSize,
                "status", status.online() ? "ONLINE" : "OFFLINE",
                "icon", item.icon(),
                "server_online", status.online(),
                "is_group", false
            );

            List<String> lore = item.lore().stream()
                .map(line -> (String) CorePlaceholders.replaceNamed(line, values))
                .toList();

            payloadItems.add(new MenuPayloadItem(
                key,
                slot,
                member,
                CorePlaceholders.replaceNamed(item.display(), values),
                lore,
                item.icon(),
                status.online(),
                false,
                true,
                status.playerCount()
            ));
            slot++;
        }

        int rows = Math.max(1, Math.min(6, (int) Math.ceil(payloadItems.size() / 9.0)));
        String title = "<gold><bold>Select " + group.key() + "</bold></gold>";
        boolean sent = sendMenuPayload(current.get(), player.getUniqueId(), rows, title, payloadItems);
        if (!sent) {
            player.sendPlainMessage("Unable to open group list on this backend.");
            return;
        }

        directSelectionByPlayer.put(player.getUniqueId(), directMap);
    }

    private MenuPayloadItem toPayloadItem(MenuItemConfig item) {
        String targetKey = item.server().toLowerCase(Locale.ROOT);
        TargetStatus targetStatus = targetStatusFor(targetKey);
        String statusText = targetStatus.online() ? "ONLINE" : "OFFLINE";
        int queueSize = queueByTarget.getOrDefault(targetKey, new ConcurrentLinkedDeque<>()).size();

        Map<String, Object> values = Map.of(
            "server", targetStatus.displayServerName(),
            "server_name", targetStatus.displayServerName(),
            "player_count", targetStatus.playerCount(),
            "online", targetStatus.playerCount(),
            "queue_size", queueSize,
            "status", statusText,
            "icon", item.icon(),
            "server_online", targetStatus.online(),
            "is_group", targetStatus.group()
        );

        List<String> lore = item.lore().stream()
            .map(line -> (String) CorePlaceholders.replaceNamed(line, values))
            .toList();

        return new MenuPayloadItem(
            item.key(),
            item.slot(),
            targetStatus.displayServerName(),
            CorePlaceholders.replaceNamed(item.display(), values),
            lore,
            item.icon(),
            targetStatus.online(),
            item.useQueue(),
            item.showWhenOffline(),
            targetStatus.playerCount()
        );
    }

    private void connectPlayerToServer(Player player, String targetServerName, boolean allowFallback, String context) {
        Optional<RegisteredServer> target = server.getServer(targetServerName);
        if (target.isEmpty()) {
            if (allowFallback && connectToFallback(player, targetServerName, context + "-missing")) {
                return;
            }
            player.sendPlainMessage("Server not found: " + targetServerName);
            return;
        }

        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to connect {} to {}", player.getUsername(), targetServerName, error);
                if (!allowFallback || !connectToFallback(player, targetServerName, context + "-error")) {
                    player.sendPlainMessage("Failed to connect to " + targetServerName);
                }
                return;
            }

            if (!result.isSuccessful()) {
                if (!allowFallback || !connectToFallback(player, targetServerName, context + "-failed")) {
                    player.sendPlainMessage("Could not connect to " + targetServerName);
                }
            }
        });
    }

    private boolean connectToFallback(Player player, String originalTarget, String reason) {
        String fallbackServer = config.fallbackServer();
        if (fallbackServer == null || fallbackServer.isBlank()) {
            return false;
        }

        String normalizedFallback = fallbackServer.toLowerCase(Locale.ROOT);
        if (normalizedFallback.equalsIgnoreCase(originalTarget)) {
            return false;
        }

        Optional<RegisteredServer> fallback = server.getServer(normalizedFallback);
        if (fallback.isEmpty()) {
            logger.warn("Configured fallback server '{}' does not exist (reason: {})", normalizedFallback, reason);
            return false;
        }

        player.createConnectionRequest(fallback.get()).connect().whenComplete((result, error) -> {
            if (error != null || !result.isSuccessful()) {
                logger.warn("Fallback connect failed for {} to {}", player.getUsername(), normalizedFallback, error);
                player.sendPlainMessage("Could not route you to fallback server " + normalizedFallback);
            } else {
                player.sendPlainMessage("Destination unavailable, routed to fallback server " + normalizedFallback + ".");
            }
        });

        return true;
    }

    private void rebuildIndexes() {
        Map<String, MenuItemConfig> newItemsByKey = new HashMap<>();
        for (MenuItemConfig item : config.items()) {
            newItemsByKey.put(item.key().toLowerCase(Locale.ROOT), item);
        }
        this.itemsByKey = newItemsByKey;

        Map<String, ServerGroupConfig> newGroupsByKey = new HashMap<>();
        for (ServerGroupConfig group : config.groups()) {
            String groupKey = group.key().toLowerCase(Locale.ROOT);
            List<String> normalizedMembers = group.members().stream()
                .map(member -> member.toLowerCase(Locale.ROOT))
                .toList();
            newGroupsByKey.put(groupKey, new ServerGroupConfig(groupKey, normalizedMembers, group.balancingMode()));
            groupRoundRobinIndex.computeIfAbsent(groupKey, ignored -> new AtomicInteger(0));
        }
        this.groupsByKey = newGroupsByKey;
        groupRoundRobinIndex.entrySet().removeIf(entry -> !groupsByKey.containsKey(entry.getKey()));

        Set<String> managedServers = collectManagedServers();
        runtimeStatusByServer.entrySet().removeIf(entry -> !managedServers.contains(entry.getKey()));

        Set<String> targets = config.items().stream().map(item -> item.server().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        queueByTarget.entrySet().removeIf(entry -> !targets.contains(entry.getKey()));
    }

    private Set<String> collectManagedServers() {
        Set<String> managed = new HashSet<>();
        for (MenuItemConfig item : config.items()) {
            String target = item.server().toLowerCase(Locale.ROOT);
            ServerGroupConfig group = groupsByKey.get(target);
            if (group == null) {
                managed.add(target);
            } else {
                managed.addAll(group.members());
            }
        }
        return managed;
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

    private void startQueueProcessor() {
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }

        if (!config.nativeQueueEnabled()) {
            return;
        }

        long interval = Math.max(1, config.queueCheckIntervalSeconds());
        queueTask = server.getScheduler()
            .buildTask(plugin, this::processQueues)
            .repeat(interval, TimeUnit.SECONDS)
            .schedule();
    }

    private void refreshServerStatuses() {
        for (String serverName : collectManagedServers()) {
            String key = serverName.toLowerCase(Locale.ROOT);
            Optional<RegisteredServer> registered = server.getServer(serverName);
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

    private RuntimeStatus statusForServer(String serverName) {
        String key = serverName.toLowerCase(Locale.ROOT);
        RuntimeStatus status = runtimeStatusByServer.get(key);
        if (status == null) {
            int online = server.getServer(key)
                .map(RegisteredServer::getPlayersConnected)
                .map(players -> players.size())
                .orElse(0);
            return new RuntimeStatus(server.getServer(key).isPresent(), online, 0L, false);
        }
        return status;
    }

    private TargetStatus targetStatusFor(String targetKey) {
        ServerGroupConfig group = groupsByKey.get(targetKey);
        if (group == null) {
            RuntimeStatus status = statusForServer(targetKey);
            return new TargetStatus(status.online(), status.playerCount(), targetKey, false);
        }

        int totalPlayers = 0;
        boolean anyOnline = false;
        for (String member : group.members()) {
            RuntimeStatus status = statusForServer(member);
            totalPlayers += status.playerCount();
            anyOnline = anyOnline || status.online();
        }

        return new TargetStatus(anyOnline, totalPlayers, group.key(), true);
    }

    private String resolveDestination(String targetKey, boolean preferOnline) {
        return resolveDestinationInternal(targetKey, preferOnline, true);
    }

    private String resolveDestinationPreview(String targetKey, boolean preferOnline) {
        return resolveDestinationInternal(targetKey, preferOnline, false);
    }

    private String resolveDestinationInternal(String targetKey, boolean preferOnline, boolean advanceRoundRobin) {
        ServerGroupConfig group = groupsByKey.get(targetKey);
        if (group == null) {
            RuntimeStatus status = statusForServer(targetKey);
            if (preferOnline && !status.online()) {
                return null;
            }
            return targetKey;
        }

        List<String> all = group.members();
        List<String> online = all.stream().filter(member -> statusForServer(member).online()).toList();
        List<String> candidates = online.isEmpty() ? all : online;

        if (candidates.isEmpty()) {
            return null;
        }
        if (preferOnline && online.isEmpty()) {
            return null;
        }

        return pickByBalancing(group.key(), group.balancingMode(), candidates, advanceRoundRobin);
    }

    private String pickByBalancing(String groupKey, BalancingMode mode, List<String> candidates, boolean advanceRoundRobin) {
        return switch (mode) {
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case LEAST_PLAYERS -> candidates.stream()
                .min(Comparator.comparingInt(name -> statusForServer(name).playerCount()))
                .orElse(candidates.get(0));
            case FIRST_AVAILABLE -> candidates.get(0);
            case FILL -> candidates.stream()
                .max(Comparator.comparingInt(name -> statusForServer(name).playerCount()))
                .orElse(candidates.get(0));
            case ROUND_ROBIN -> {
                AtomicInteger cursor = groupRoundRobinIndex.computeIfAbsent(groupKey, ignored -> new AtomicInteger(0));
                int index = Math.floorMod(advanceRoundRobin ? cursor.getAndIncrement() : cursor.get(), candidates.size());
                yield candidates.get(index);
            }
        };
    }

    private void enqueuePlayer(Player player, String targetKey) {
        String existing = queuedTargetByPlayer.get(player.getUniqueId());
        if (existing != null && existing.equals(targetKey)) {
            int pos = queuePosition(player.getUniqueId(), targetKey);
            player.sendPlainMessage("Already queued for " + targetKey + " (position " + pos + ").");
            return;
        }

        if (existing != null) {
            removeFromQueue(player.getUniqueId(), existing);
        }

        ConcurrentLinkedDeque<QueueEntry> queue = queueByTarget.computeIfAbsent(targetKey, ignored -> new ConcurrentLinkedDeque<>());
        if (queue.size() >= config.queueMaxSizePerServer()) {
            player.sendPlainMessage("Queue for " + targetKey + " is full.");
            return;
        }

        queue.addLast(new QueueEntry(player.getUniqueId(), System.currentTimeMillis()));
        queuedTargetByPlayer.put(player.getUniqueId(), targetKey);

        if (config.queueNotifyPosition()) {
            player.sendPlainMessage("Joined queue for " + targetKey + " (position " + queue.size() + ").");
        } else {
            player.sendPlainMessage("Joined queue for " + targetKey + ".");
        }

        processQueueForTarget(targetKey);
    }

    private int queuePosition(UUID playerId, String targetKey) {
        ConcurrentLinkedDeque<QueueEntry> queue = queueByTarget.get(targetKey);
        if (queue == null) {
            return 0;
        }

        int index = 1;
        for (QueueEntry entry : queue) {
            if (entry.playerId().equals(playerId)) {
                return index;
            }
            index++;
        }
        return 0;
    }

    private void processQueues() {
        for (String targetKey : queueByTarget.keySet()) {
            processQueueForTarget(targetKey);
        }
    }

    private void processQueueForTarget(String targetKey) {
        ConcurrentLinkedDeque<QueueEntry> queue = queueByTarget.get(targetKey);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        pruneExpiredEntries(targetKey, queue);
        if (queue.isEmpty()) {
            return;
        }

        notifyQueuePositions(targetKey, queue);

        int drained = 0;
        while (drained < config.queueDrainPerCycle() && !queue.isEmpty()) {
            String destination = resolveDestination(targetKey, true);
            if (destination == null) {
                return;
            }

            QueueEntry head = queue.peekFirst();
            if (head == null) {
                return;
            }

            Optional<Player> playerOpt = server.getPlayer(head.playerId());
            if (playerOpt.isEmpty()) {
                queue.pollFirst();
                queuedTargetByPlayer.remove(head.playerId());
                lastQueuePositionNotice.remove(head.playerId());
                continue;
            }

            Player player = playerOpt.get();
            queue.pollFirst();
            queuedTargetByPlayer.remove(player.getUniqueId());
            lastQueuePositionNotice.remove(player.getUniqueId());
            connectPlayerToServer(player, destination, true, "queue");
            drained++;
        }
    }

    private void pruneExpiredEntries(String targetKey, ConcurrentLinkedDeque<QueueEntry> queue) {
        Iterator<QueueEntry> it = queue.iterator();
        while (it.hasNext()) {
            QueueEntry entry = it.next();
            if (!isExpired(entry)) {
                continue;
            }
            if (queue.remove(entry)) {
                queuedTargetByPlayer.remove(entry.playerId());
                lastQueuePositionNotice.remove(entry.playerId());
                handleQueueTimeout(entry.playerId(), targetKey);
            }
        }
    }

    private void handleQueueTimeout(UUID playerId, String targetKey) {
        Optional<Player> playerOpt = server.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            return;
        }

        Player player = playerOpt.get();
        player.sendPlainMessage("Queue timed out for " + targetKey + ".");
        connectToFallback(player, targetKey, "queue-timeout");
    }

    private boolean isExpired(QueueEntry entry) {
        long timeoutMillis = Math.max(15, config.queueEntryTimeoutSeconds()) * 1000L;
        return (System.currentTimeMillis() - entry.joinedAtMillis()) > timeoutMillis;
    }

    private void removeFromQueue(UUID playerId, String targetKey) {
        ConcurrentLinkedDeque<QueueEntry> queue = queueByTarget.get(targetKey);
        if (queue == null) {
            return;
        }
        queue.removeIf(entry -> entry.playerId().equals(playerId));
        queuedTargetByPlayer.remove(playerId);
        lastQueuePositionNotice.remove(playerId);
    }

    private void notifyQueuePositions(String targetKey, ConcurrentLinkedDeque<QueueEntry> queue) {
        if (!config.queueNotifyPosition()) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(2, config.queuePositionUpdateSeconds()) * 1000L;
        int position = 1;

        for (QueueEntry entry : queue) {
            Optional<Player> playerOpt = server.getPlayer(entry.playerId());
            if (playerOpt.isEmpty()) {
                position++;
                continue;
            }

            Long last = lastQueuePositionNotice.get(entry.playerId());
            if (last != null && (now - last) < intervalMillis) {
                position++;
                continue;
            }

            Player player = playerOpt.get();
            player.sendPlainMessage("Queue " + targetKey + ": position " + position + "/" + queue.size());
            lastQueuePositionNotice.put(entry.playerId(), now);
            position++;
        }
    }

    private record RuntimeStatus(boolean online, int playerCount, long lastHeartbeatAt, boolean reachableByPing) {
    }

    private record TargetStatus(boolean online, int playerCount, String displayServerName, boolean group) {
    }

    private record QueueEntry(UUID playerId, long joinedAtMillis) {
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
