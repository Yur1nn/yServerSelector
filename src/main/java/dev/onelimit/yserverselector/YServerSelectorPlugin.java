package dev.onelimit.yserverselector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.onelimit.yserverselector.command.ServerCommand;
import dev.onelimit.yserverselector.config.ConfigService;
import dev.onelimit.yserverselector.config.SelectorConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

@Plugin(
    id = "yserverselector",
    name = "yServerSelector",
    version = "1.0.0",
    authors = {"OneLimit"},
    dependencies = {
        @Dependency(id = "ycorevelocity", optional = false)
    }
)
public final class YServerSelectorPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigService configService;
    private ServerSelectorService selectorService;
    private SelectorConfig config;

    @Inject
    public YServerSelectorPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.configService = new ConfigService(logger, dataDirectory);
        this.config = configService.load();
        this.selectorService = new ServerSelectorService(this, server, logger, config);

        selectorService.registerChannel();

        registerCommand();

        logger.info("yServerSelector initialized.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (selectorService != null) {
            selectorService.shutdown();
            selectorService.unregisterChannel();
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (selectorService == null || !selectorService.matchesChannel(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection serverConnection)) {
            return;
        }

        selectorService.handleBridgeMessage(serverConnection, event.getData());
    }

    public void reload() {
        this.config = configService.load();
        this.selectorService.updateConfig(config);
        logger.info("yServerSelector config reloaded.");
    }

    public SelectorConfig config() {
        return config;
    }

    private void registerCommand() {
        if (!config.commandEnabled()) {
            logger.info("/server command disabled in config.");
            return;
        }

        List<String> aliases = config.commandAliases();
        if (aliases.isEmpty()) {
            aliases = List.of("server");
        }

        String primary = aliases.get(0);
        String[] secondary = aliases.size() > 1
            ? aliases.subList(1, aliases.size()).toArray(String[]::new)
            : new String[0];

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder(primary)
            .aliases(secondary)
            .plugin(this)
            .build();

        commandManager.register(meta, new ServerCommand(this, selectorService));
    }
}

