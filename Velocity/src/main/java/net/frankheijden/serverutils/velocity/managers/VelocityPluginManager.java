package net.frankheijden.serverutils.velocity.managers;

import com.google.common.base.Joiner;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.frankheijden.serverutils.common.entities.CloseableResult;
import net.frankheijden.serverutils.common.entities.Result;
import net.frankheijden.serverutils.common.events.PluginEvent;
import net.frankheijden.serverutils.common.managers.AbstractPluginManager;
import net.frankheijden.serverutils.velocity.entities.VelocityLoadResult;
import net.frankheijden.serverutils.velocity.events.VelocityPluginDisableEvent;
import net.frankheijden.serverutils.velocity.events.VelocityPluginEnableEvent;
import net.frankheijden.serverutils.velocity.events.VelocityPluginLoadEvent;
import net.frankheijden.serverutils.velocity.events.VelocityPluginUnloadEvent;
import net.frankheijden.serverutils.velocity.reflection.RJavaPluginLoader;
import net.frankheijden.serverutils.velocity.reflection.RVelocityCommandManager;
import net.frankheijden.serverutils.velocity.reflection.RVelocityConsole;
import net.frankheijden.serverutils.velocity.reflection.RVelocityEventManager;
import net.frankheijden.serverutils.velocity.reflection.RVelocityPluginContainer;
import net.frankheijden.serverutils.velocity.reflection.RVelocityPluginManager;
import net.frankheijden.serverutils.velocity.reflection.RVelocityScheduler;
import org.slf4j.Logger;

public class VelocityPluginManager implements AbstractPluginManager<PluginContainer> {

    private static VelocityPluginManager instance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final VelocityPluginCommandManager pluginCommandManager;

    /**
     * Constructs a new VelocityPluginManager.
     */
    public VelocityPluginManager(ProxyServer proxy, Logger logger, VelocityPluginCommandManager pluginCommandManager) {
        instance = this;
        this.proxy = proxy;
        this.logger = logger;
        this.pluginCommandManager = pluginCommandManager;
    }

    public static VelocityPluginManager get() {
        return instance;
    }

    @Override
    public VelocityLoadResult loadPlugin(String pluginFile) {
        return loadPlugin(new File(getPluginsFolder(), pluginFile));
    }

    @Override
    public VelocityLoadResult loadPlugin(File file) {
        if (!file.exists() || file.isDirectory()) return new VelocityLoadResult(Result.NOT_EXISTS);

        Object javaPluginLoader = RJavaPluginLoader.newInstance(proxy, file.toPath().getParent());
        PluginDescription candidate = RJavaPluginLoader.loadPluginDescription(javaPluginLoader, file.toPath());
        if (proxy.getPluginManager().isLoaded(candidate.getId())) return new VelocityLoadResult(Result.ALREADY_LOADED);

        for (PluginDependency dependency : candidate.getDependencies()) {
            if (!dependency.isOptional() && !proxy.getPluginManager().isLoaded(dependency.getId())) {
                logger.error(
                        "Can't load plugin {} due to missing dependency {}",
                        candidate.getId(),
                        dependency.getId()
                );
                return new VelocityLoadResult(Result.UNKNOWN_DEPENDENCY.arg(dependency.getId()));
            }
        }

        PluginDescription realPlugin = RJavaPluginLoader.loadPlugin(javaPluginLoader, candidate);
        PluginContainer container = RVelocityPluginContainer.newInstance(realPlugin);
        proxy.getEventManager().fire(new VelocityPluginLoadEvent(container, PluginEvent.Stage.PRE));
        proxy.getEventManager().fire(new VelocityPluginLoadEvent(container, PluginEvent.Stage.POST));

        return new VelocityLoadResult(container);
    }

    @Override
    public Result enablePlugin(PluginContainer container) {
        proxy.getEventManager().fire(new VelocityPluginEnableEvent(container, PluginEvent.Stage.PRE));
        if (proxy.getPluginManager().isLoaded(container.getDescription().getId())) return Result.ALREADY_ENABLED;

        Object javaPluginLoader = RJavaPluginLoader.newInstance(
                proxy,
                container.getDescription().getSource().map(Path::getParent).orElse(null)
        );
        PluginDescription realPlugin = container.getDescription();
        Module module = RJavaPluginLoader.createModule(javaPluginLoader, container);

        AbstractModule commonModule = new AbstractModule() {
            @Override
            protected void configure() {
                bind(ProxyServer.class).toInstance(proxy);
                bind(PluginManager.class).toInstance(proxy.getPluginManager());
                bind(EventManager.class).toInstance(proxy.getEventManager());
                bind(CommandManager.class).toInstance(proxy.getCommandManager());
                for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
                    bind(PluginContainer.class)
                            .annotatedWith(Names.named(container.getDescription().getId()))
                            .toInstance(container);
                }
                bind(PluginContainer.class)
                        .annotatedWith(Names.named(realPlugin.getId()))
                        .toInstance(container);
            }
        };

        try {
            RJavaPluginLoader.createPlugin(javaPluginLoader, container, module, commonModule);
        } catch (Exception ex) {
            logger.error(
                    String.format("Can't create plugin %s", container.getDescription().getId()),
                    ex
            );
            return Result.ERROR;
        }

        logger.info(
                "Loaded plugin {} {} by {}",
                realPlugin.getId(),
                realPlugin.getVersion().orElse("<UNKNOWN>"),
                Joiner.on(", ").join(realPlugin.getAuthors())
        );

        RVelocityPluginManager.registerPlugin(proxy.getPluginManager(), container);
        container.getInstance().ifPresent(pluginInstance -> {
            RVelocityEventManager.registerInternally(proxy.getEventManager(), container, pluginInstance);
            RVelocityEventManager.fireForPlugin(
                    proxy.getEventManager(),
                    new ProxyInitializeEvent(),
                    pluginInstance
            ).join();

            ConsoleCommandSource console = proxy.getConsoleCommandSource();
            PermissionsSetupEvent event = new PermissionsSetupEvent(
                    console,
                    s -> PermissionFunction.ALWAYS_TRUE
            );
            PermissionFunction permissionFunction = RVelocityEventManager.fireForPlugin(
                    proxy.getEventManager(),
                    event,
                    pluginInstance
            ).join().createFunction(console);

            if (permissionFunction == null) {
                logger.error(
                        "A plugin permission provider {} provided an invalid permission function for the console."
                                + " This is a bug in the plugin, not in Velocity."
                                + " Falling back to the default permission function.",
                        event.getProvider().getClass().getName()
                );
                permissionFunction = PermissionFunction.ALWAYS_TRUE;
            }

            RVelocityConsole.setPermissionFunction(console, permissionFunction);
        });

        proxy.getEventManager().fire(new VelocityPluginEnableEvent(container, PluginEvent.Stage.POST));
        return Result.SUCCESS;
    }

    @Override
    public Result disablePlugin(PluginContainer container) {
        proxy.getEventManager().fire(new VelocityPluginDisableEvent(container, PluginEvent.Stage.PRE));
        Object pluginInstance = container.getInstance().orElse(null);
        if (pluginInstance == null) return Result.NOT_EXISTS;

        RVelocityEventManager.fireForPlugin(
                proxy.getEventManager(),
                pluginInstance,
                new ProxyShutdownEvent()
        );

        proxy.getEventManager().fire(new VelocityPluginDisableEvent(container, PluginEvent.Stage.POST));
        return Result.SUCCESS;
    }

    @Override
    public Result reloadPlugin(String pluginName) {
        Optional<PluginContainer> pluginOptional = proxy.getPluginManager().getPlugin(pluginName);
        if (!pluginOptional.isPresent()) return Result.NOT_EXISTS;
        return reloadPlugin(pluginOptional.get());
    }

    @Override
    public Result reloadPlugin(PluginContainer plugin) {
        CloseableResult result = unloadPlugin(plugin);
        if (result.getResult() != Result.SUCCESS) return result.getResult();
        result.tryClose();

        File file = getPluginFile(plugin.getDescription().getId());
        if (file == null) return Result.FILE_DELETED;

        VelocityLoadResult loadResult = loadPlugin(file);
        if (!loadResult.isSuccess()) return loadResult.getResult();

        return enablePlugin(loadResult.get());
    }

    @Override
    public CloseableResult unloadPlugin(String pluginName) {
        Optional<PluginContainer> pluginOptional = proxy.getPluginManager().getPlugin(pluginName);
        if (!pluginOptional.isPresent()) return new CloseableResult(Result.NOT_EXISTS);
        return unloadPlugin(pluginOptional.get());
    }

    @Override
    public CloseableResult unloadPlugin(PluginContainer container) {
        proxy.getEventManager().fire(new VelocityPluginUnloadEvent(container, PluginEvent.Stage.PRE));
        Optional<?> pluginInstanceOptional = container.getInstance();
        if (!pluginInstanceOptional.isPresent()) return new CloseableResult(Result.INVALID_PLUGIN);
        Object pluginInstance = pluginInstanceOptional.get();

        proxy.getEventManager().unregisterListeners(pluginInstance);
        for (ScheduledTask task : RVelocityScheduler.getTasksByPlugin(proxy.getScheduler()).removeAll(pluginInstance)) {
            task.cancel();
        }

        String pluginId = container.getDescription().getId();
        for (String alias : pluginCommandManager.getPluginCommands().removeAll(pluginId)) {
            proxy.getCommandManager().unregister(alias);
        }

        RVelocityPluginManager.getPlugins(proxy.getPluginManager()).remove(pluginId);
        RVelocityPluginManager.getPluginInstances(proxy.getPluginManager()).remove(pluginInstance);

        List<Closeable> closeables = new ArrayList<>();

        ClassLoader loader = pluginInstance.getClass().getClassLoader();
        if (loader instanceof Closeable) {
            closeables.add((Closeable) loader);
        }

        proxy.getEventManager().fire(new VelocityPluginUnloadEvent(container, PluginEvent.Stage.POST));
        return new CloseableResult(closeables);
    }

    @Override
    public List<PluginContainer> getPlugins() {
        return new ArrayList<>(proxy.getPluginManager().getPlugins());
    }

    @Override
    public String getPluginName(PluginContainer plugin) {
        return plugin.getDescription().getId();
    }

    @Override
    public File getPluginFile(PluginContainer plugin) {
        return plugin.getDescription().getSource()
                .map(Path::toFile)
                .orElse(null);
    }

    @Override
    public File getPluginFile(String pluginName) {
        Object javaPluginLoader = RJavaPluginLoader.newInstance(instance.proxy, getPluginsFolder().toPath());

        for (File file : getPluginJars()) {
            PluginDescription desc = RJavaPluginLoader.loadPluginDescription(javaPluginLoader, file.toPath());

            if (desc.getId().equals(pluginName)) {
                return file;
            }
        }
        return null;
    }

    @Override
    public PluginContainer getPlugin(String pluginName) {
        return proxy.getPluginManager().getPlugin(pluginName).orElse(null);
    }

    @Override
    public Object getInstance(PluginContainer plugin) {
        return plugin.getInstance().orElse(null);
    }

    @Override
    public Set<String> getCommands() {
        return RVelocityCommandManager.getDispatcher(proxy.getCommandManager()).getRoot().getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());
    }
}
