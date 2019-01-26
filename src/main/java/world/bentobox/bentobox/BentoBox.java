package world.bentobox.bentobox;

import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import world.bentobox.bentobox.api.configuration.Config;
import world.bentobox.bentobox.api.events.BentoBoxReadyEvent;
import world.bentobox.bentobox.api.localization.TextVariables;
import world.bentobox.bentobox.api.user.Notifier;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.commands.BentoBoxCommand;
import world.bentobox.bentobox.hooks.MultiverseCoreHook;
import world.bentobox.bentobox.hooks.PlaceholderAPIHook;
import world.bentobox.bentobox.hooks.VaultHook;
import world.bentobox.bentobox.listeners.BannedVisitorCommands;
import world.bentobox.bentobox.listeners.BlockEndDragon;
import world.bentobox.bentobox.listeners.DeathListener;
import world.bentobox.bentobox.listeners.JoinLeaveListener;
import world.bentobox.bentobox.listeners.NetherPortals;
import world.bentobox.bentobox.listeners.PanelListenerManager;
import world.bentobox.bentobox.managers.AddonsManager;
import world.bentobox.bentobox.managers.CommandsManager;
import world.bentobox.bentobox.managers.FlagsManager;
import world.bentobox.bentobox.managers.HooksManager;
import world.bentobox.bentobox.managers.IslandDeletionManager;
import world.bentobox.bentobox.managers.IslandWorldManager;
import world.bentobox.bentobox.managers.IslandsManager;
import world.bentobox.bentobox.managers.LocalesManager;
import world.bentobox.bentobox.managers.PlaceholdersManager;
import world.bentobox.bentobox.managers.PlayersManager;
import world.bentobox.bentobox.managers.RanksManager;
import world.bentobox.bentobox.managers.SchemsManager;
import world.bentobox.bentobox.util.heads.HeadGetter;
import world.bentobox.bentobox.versions.ServerCompatibility;

/**
 * Main BentoBox class
 * @author tastybento, Poslovitch
 */
public class BentoBox extends JavaPlugin {
    private static BentoBox instance;

    // Databases
    private PlayersManager playersManager;
    private IslandsManager islandsManager;

    // Managers
    private CommandsManager commandsManager;
    private LocalesManager localesManager;
    private AddonsManager addonsManager;
    private FlagsManager flagsManager;
    private IslandWorldManager islandWorldManager;
    private RanksManager ranksManager;
    private SchemsManager schemsManager;
    private HooksManager hooksManager;
    private PlaceholdersManager placeholdersManager;
    private IslandDeletionManager islandDeletionManager;

    // Settings
    private Settings settings;

    // Notifier
    private Notifier notifier;

    private HeadGetter headGetter;

    private boolean isLoaded;

    // Metrics
    @Nullable
    private BStats metrics;

    @Override
    public void onEnable(){
        if (!ServerCompatibility.getInstance().checkCompatibility(this).isCanLaunch()) {
            // The server's most likely incompatible.
            // For safety reasons, we must stop BentoBox from loading.

            getServer().getLogger().severe("Aborting BentoBox enabling.");
            getServer().getLogger().severe("BentoBox cannot be loaded on this server.");
            getServer().getLogger().severe("You must use a compatible server software (Spigot) and run on a supported version (1.13.2).");

            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Not loaded
        isLoaded = false;
        // Store the current millis time so we can tell how many ms it took for BSB to fully load.
        final long startMillis = System.currentTimeMillis();

        // Save the default config from config.yml
        saveDefaultConfig();
        setInstance(this);
        // Load Flags
        flagsManager = new FlagsManager(this);

        // Load settings from config.yml. This will check if there are any issues with it too.
        settings = new Config<>(this, Settings.class).loadConfigObject("");
        if (settings == null) {
            // Settings did no load correctly. Disable plugin.
            logError("Settings did not load correctly - disabling plugin - please check config.yml");
            getPluginLoader().disablePlugin(this);
            return;
        }
        // Start Database managers
        playersManager = new PlayersManager(this);
        // Check if this plugin is now disabled (due to bad database handling)
        if (!this.isEnabled()) {
            return;
        }
        islandsManager = new IslandsManager(this);
        ranksManager = new RanksManager();

        // Start head getter
        headGetter = new HeadGetter(this);

        // Load Notifier
        notifier = new Notifier();

        // Set up command manager
        commandsManager = new CommandsManager();

        // Load BentoBox commands
        new BentoBoxCommand();

        // Start Island Worlds Manager
        islandWorldManager = new IslandWorldManager(this);
        // Load schems manager
        schemsManager = new SchemsManager(this);

        // Locales manager must be loaded before addons
        localesManager = new LocalesManager(this);

        // Load hooks
        hooksManager = new HooksManager(this);
        hooksManager.registerHook(new VaultHook());
        hooksManager.registerHook(new PlaceholderAPIHook());
        hooksManager.registerHook(new MultiverseCoreHook());

        // Load addons. Addons may load worlds, so they must go before islands are loaded.
        addonsManager = new AddonsManager(this);
        addonsManager.loadAddons();
        // Enable addons
        addonsManager.enableAddons();

        getServer().getScheduler().runTask(instance, () -> {
            // Register Listeners
            registerListeners();

            // Load islands from database - need to wait until all the worlds are loaded
            islandsManager.load();

            // Save islands & players data every X minutes
            instance.getServer().getScheduler().runTaskTimer(instance, () -> {
                playersManager.saveAll();
                islandsManager.saveAll();
            }, getSettings().getDatabaseBackupPeriod() * 20 * 60L, getSettings().getDatabaseBackupPeriod() * 20 * 60L);

            // Make sure all flag listeners are registered.
            flagsManager.registerListeners();

            // Load metrics
            if (settings.isMetrics()) {
                metrics = new BStats(this);
                metrics.registerMetrics();
            }

            // Make sure all worlds are already registered to Multiverse.
            islandWorldManager.registerWorldsToMultiverse();

            // Setup the Placeholders manager
            placeholdersManager = new PlaceholdersManager(this);

            // Show banner
            User.getInstance(Bukkit.getConsoleSender()).sendMessage("successfully-loaded",
                    TextVariables.VERSION, instance.getDescription().getVersion(),
                    "[time]", String.valueOf(System.currentTimeMillis() - startMillis));

            // Fire plugin ready event - this should go last after everything else
            isLoaded = true;
            Bukkit.getServer().getPluginManager().callEvent(new BentoBoxReadyEvent());
        });
    }

    /**
     * Register listeners
     */
    private void registerListeners() {
        PluginManager manager = getServer().getPluginManager();
        // Player join events
        manager.registerEvents(new JoinLeaveListener(this), this);
        // Panel listener manager
        manager.registerEvents(new PanelListenerManager(), this);
        // Nether portals
        manager.registerEvents(new NetherPortals(this), this);
        // End dragon blocking
        manager.registerEvents(new BlockEndDragon(this), this);
        // Banned visitor commands
        manager.registerEvents(new BannedVisitorCommands(this), this);
        // Death counter
        manager.registerEvents(new DeathListener(this), this);
        // Island Delete Manager
        islandDeletionManager = new IslandDeletionManager(this);
        manager.registerEvents(islandDeletionManager, this);
    }

    @Override
    public void onDisable() {
        if (addonsManager != null) {
            addonsManager.disableAddons();
        }
        // Save data
        if (playersManager != null) {
            playersManager.shutdown();
        }
        if (islandsManager != null) {
            islandsManager.shutdown();
        }
        // Save settings - ensures admins always have the latest config file
        if (settings != null) {
            new Config<>(this, Settings.class).saveConfigObject(settings);
        }
    }

    /**
     * Returns the player database
     * @return the player database
     */
    public PlayersManager getPlayers(){
        return playersManager;
    }

    /**
     * Returns the island database
     * @return the island database
     */
    public IslandsManager getIslands(){
        return islandsManager;
    }

    private static void setInstance(BentoBox plugin) {
        BentoBox.instance = plugin;
    }

    public static BentoBox getInstance() {
        return instance;
    }

    /**
     * @return the Commands manager
     */
    public CommandsManager getCommandsManager() {
        return commandsManager;
    }

    /**
     * @return the Locales manager
     */
    public LocalesManager getLocalesManager() {
        return localesManager;
    }

    /**
     * @return the Addons manager
     */
    public AddonsManager getAddonsManager() {
        return addonsManager;
    }

    /**
     * @return the Flags manager
     */
    public FlagsManager getFlagsManager() {
        return flagsManager;
    }

    /**
     * @return the ranksManager
     */
    public RanksManager getRanksManager() {
        return ranksManager;
    }

    /**
     * @return the Island World Manager
     */
    public IslandWorldManager getIWM() {
        return islandWorldManager;
    }

    /**
     * @return the settings
     */
    public Settings getSettings() {
        return settings;
    }

    /**
     * @return the notifier
     */
    public Notifier getNotifier() {
        return notifier;
    }

    /**
     * @return the headGetter
     */
    public HeadGetter getHeadGetter() {
        return headGetter;
    }

    public void log(String string) {
        getLogger().info(() -> string);
    }

    public void logDebug(Object object) {
        getLogger().info(() -> "DEBUG: " + object);
    }

    public void logError(String error) {
        getLogger().severe(() -> error);
    }

    public void logWarning(String warning) {
        getLogger().warning(() -> warning);
    }

    /**
     * @return the schemsManager
     */
    public SchemsManager getSchemsManager() {
        return schemsManager;
    }

    /**
     * Returns whether BentoBox is fully loaded or not.
     * This basically means that all managers are instantiated and can therefore be safely accessed.
     * @return whether BentoBox is fully loaded or not.
     */
    public boolean isLoaded() {
        return isLoaded;
    }

    /**
     * @return the HooksManager
     */
    public HooksManager getHooks() {
        return hooksManager;
    }

    /**
     * Convenience method to get the VaultHook.
     * @return the Vault hook
     */
    public Optional<VaultHook> getVault() {
        return Optional.ofNullable((VaultHook) hooksManager.getHook("Vault").orElse(null));
    }

    /**
     * @return the PlaceholdersManager.
     */
    public PlaceholdersManager getPlaceholdersManager() {
        return placeholdersManager;
    }

    /**
     * @return the islandDeletionManager
     * @since 1.1
     */
    public IslandDeletionManager getIslandDeletionManager() {
        return islandDeletionManager;
    }

    /**
     * @return an optional of the Bstats instance
     * @since 1.1
     */
    @NonNull
    public Optional<BStats> getMetrics() {
        return Optional.ofNullable(metrics);
    }

    /* (non-Javadoc)
     * @see org.bukkit.plugin.java.JavaPlugin#getDefaultWorldGenerator(java.lang.String, java.lang.String)
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return addonsManager.getDefaultWorldGenerator(worldName, id);
    }
}
