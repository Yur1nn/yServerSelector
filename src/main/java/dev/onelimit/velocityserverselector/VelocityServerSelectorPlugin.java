package dev.onelimit.velocityserverselector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.onelimit.velocityserverselector.command.ServerCommand;
import dev.onelimit.velocityserverselector.config.ConfigService;
import dev.onelimit.velocityserverselector.config.SelectorConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

@Plugin(
    id = "velocityserverselector",
    name = "VelocityServerSelector",
    version = "1.0.0",
    authors = {"OneLimit"},
    dependencies = {
        @Dependency(id = "ycorevelocity", optional = false)
    }
)
public final class VelocityServerSelectorPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigService configService;
    private ServerSelectorService selectorService;
    private SelectorConfig config;

    @Inject
    public VelocityServerSelectorPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.configService = new ConfigService(logger, dataDirectory);
        this.config = configService.load();
        this.selectorService = new ServerSelectorService(server, logger, config);

        registerCommand();

        logger.info("VelocityServerSelector initialized.");
    }

    public void reload() {
        this.config = configService.load();
        this.selectorService.updateConfig(config);
        logger.info("VelocityServerSelector config reloaded.");
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
