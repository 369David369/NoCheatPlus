/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.players;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationHistory;
import fr.neatmonster.nocheatplus.checks.access.CheckConfigFactory;
import fr.neatmonster.nocheatplus.checks.access.CheckDataFactory;
import fr.neatmonster.nocheatplus.checks.access.ICheckConfig;
import fr.neatmonster.nocheatplus.checks.access.ICheckData;
import fr.neatmonster.nocheatplus.checks.access.IRemoveSubCheckData;
import fr.neatmonster.nocheatplus.checks.combined.CombinedData;
import fr.neatmonster.nocheatplus.compat.BridgeMisc;
import fr.neatmonster.nocheatplus.compat.versions.BukkitVersion;
import fr.neatmonster.nocheatplus.compat.versions.GenericVersion;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.NoCheatPlusAPI;
import fr.neatmonster.nocheatplus.components.data.ICanHandleTimeRunningBackwards;
import fr.neatmonster.nocheatplus.components.registry.ComponentRegistry;
import fr.neatmonster.nocheatplus.components.registry.feature.ComponentWithName;
import fr.neatmonster.nocheatplus.components.registry.feature.ConsistencyChecker;
import fr.neatmonster.nocheatplus.components.registry.feature.IDisableListener;
import fr.neatmonster.nocheatplus.components.registry.feature.IHaveCheckType;
import fr.neatmonster.nocheatplus.components.registry.feature.INeedConfig;
import fr.neatmonster.nocheatplus.components.registry.feature.IRemoveData;
import fr.neatmonster.nocheatplus.components.registry.feature.TickListener;
import fr.neatmonster.nocheatplus.components.registry.order.RegistrationOrder.RegisterMethodWithOrder;
import fr.neatmonster.nocheatplus.components.registry.order.SetupOrder;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.ConfigFile;
import fr.neatmonster.nocheatplus.config.ConfigManager;
import fr.neatmonster.nocheatplus.event.mini.MiniListener;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.permissions.PermissionPolicy;
import fr.neatmonster.nocheatplus.permissions.PermissionRegistry;
import fr.neatmonster.nocheatplus.permissions.PermissionSettings;
import fr.neatmonster.nocheatplus.permissions.RegisteredPermission;
import fr.neatmonster.nocheatplus.players.PlayerMap.PlayerInfo;
import fr.neatmonster.nocheatplus.utilities.CheckTypeUtil;
import fr.neatmonster.nocheatplus.utilities.IdUtil;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import fr.neatmonster.nocheatplus.utilities.ds.corw.DualSet;
import fr.neatmonster.nocheatplus.utilities.ds.map.HashMapLOW;

/**
 * Central access point for a lot of functionality for managing data, especially
 * removing data for cleanup.<br>
 * Originally intended as temporary or intermediate design, this might help
 * reorganizing the API at some point.<br>
 * However i could not yet find a pleasing way for generic configuration access
 * for a centralized data management (all in one), so this might just be a
 * workarounds class for coping with the current design, until somehow resolved
 * in another way.
 * <hr>
 * ComponentRegistry:
 * <li>Supported: IRemoveData</li>
 * 
 * @author mc_dev
 *
 */
@SetupOrder(priority = -80)
public class DataManager implements INeedConfig, ComponentRegistry<IRemoveData>, ComponentWithName, ConsistencyChecker, IDisableListener{

    // TODO: Should/can some data structures share the same lock?

    private static DataManager instance = null;

    // Not static
    private int foundInconsistencies = 0;

    /** PlayerData storage. */
    private final HashMapLOW<UUID, PlayerData> playerData = new HashMapLOW<UUID, PlayerData>(100);

    /** Primary thread only (no lock for this field): UUIDs to remove upon next bulk removal. */
    private final Set<UUID> bulkPlayerDataRemoval = new LinkedHashSet<UUID>();

    private final DualSet<UUID> frequentPlayerTasks = new DualSet<UUID>();

    /**
     * Access order for playerName (exact) -> ms time of logout.
     * <hr>
     * Later this might hold central player data objects instead of the long
     * only.
     */
    private final Map<UUID, Long> lastLogout = new LinkedHashMap<UUID, Long>(50, 0.75f, true);

    /**
     * Keeping track of online players. Currently id/name mappings are not kept
     * on logout, but might be later.
     */
    // TODO: Switch to UUIDs as keys, get data by uuid when possible, use PlayerMap for getting the UUID.
    private final PlayerMap playerMap;

    /**
     * IRemoveData instances.
     */
    // TODO: might use a map for those later (extra or not).
    private final ArrayList<IRemoveData> iRemoveData = new ArrayList<IRemoveData>();

    /**
     * Execution histories of the checks.
     */
    // TODO: Move to PlayerData / CheckTypeTree (NodeS).
    private final Map<CheckType, Map<String, ExecutionHistory>> executionHistories = new HashMap<CheckType, Map<String,ExecutionHistory>>();

    /** Flag if data expiration is active at all. */
    private boolean doExpireData = false;

    /**
     * Duration in milliseconds for expiration of logged off players data. In
     * the config minutes are used as unit.
     */
    private long durExpireData = 0;

    /** Data and execution history. */
    private boolean deleteData = true;
    /** Violation history and execution history. */
    private boolean deleteHistory = false;

    /**
     * Reference for passing to PlayerData for handling permission caching and
     * policies.
     */
    // TODO: Per world registries, update with world change (!).
    private final PermissionRegistry permissionRegistry;

    private final TickListener tickListener = new TickListener() {

        private int delayRareTasks = 0;

        @Override
        public void onTick(final int tick, final long timeLast) {
            if (rareTasks(tick, timeLast)) {
                delayRareTasks = 10;
            }
            else {
                if (delayRareTasks == 0) {
                }
                else {
                    delayRareTasks --;
                }
            }
            frequentTasks(tick, timeLast);
        }
    };

    private final MiniListener<?>[] miniListeners = new MiniListener<?>[] {
        /*
         * TODO: Constants in a class 'ListenerTags', plus a plan
         * (system.data.player.nocheatplus, system.nocheatplus.data ??,
         * nocheatplus.system.data.player...). (RegistryTags for other?).
         */
        new MiniListener<PlayerQuitEvent>() {
            @Override
            @EventHandler(priority = EventPriority.MONITOR)
            @RegisterMethodWithOrder(tag = "system.nocheatplus.datamanager", afterTag = ".*")
            public void onEvent(final PlayerQuitEvent event) {
                playerLeaves(event.getPlayer());
            }
        },
        new MiniListener<PlayerKickEvent>() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            @RegisterMethodWithOrder(tag = "system.nocheatplus.datamanager", afterTag = ".*")
            public void onEvent(final PlayerKickEvent event) {
                playerLeaves(event.getPlayer());
            }
        },
        new MiniListener<PlayerJoinEvent>() {
            @EventHandler(priority = EventPriority.LOWEST)
            @RegisterMethodWithOrder(tag = "system.nocheatplus.datamanager", beforeTag = ".*")
            public void onEvent(final PlayerJoinEvent event) {
                playerJoins(event);
            }
        },
        new MiniListener<PlayerChangedWorldEvent>() {
            @EventHandler(priority = EventPriority.LOWEST)
            @RegisterMethodWithOrder(tag = "system.nocheatplus.datamanager", afterTag = ".*")
            public void onEvent(final PlayerChangedWorldEvent event) {
                playerChangedWorld(event);
            }
        },
    };

    /**
     * Sets the static instance reference.
     */
    public DataManager(final PermissionRegistry permissionRegistry) {
        instance = this;
        if (ServerVersion.isMinecraftVersionUnknown()) {
            // True hacks.
            BukkitVersion.init();
        }
        final String version = ServerVersion.getMinecraftVersion();
        if (GenericVersion.compareVersions(version, "1.8") >= 0 || version.equals("1.7.10") && Bukkit.getServer().getVersion().toLowerCase().indexOf("spigot") != -1) {
            // Safe to assume Spigot, don't store Player instances.
            playerMap = new PlayerMap(false);
        }
        else {
            // Likely an older version without efficient mapping.
            playerMap = new PlayerMap(true);
        }
        this.permissionRegistry = permissionRegistry;
    }

    /**
     * Check the logged out players for if any data can be removed.<br>
     * Currently only "dumb" full removal is performed. Later it is thinkable to
     * remove "as much as reasonable".
     */
    public void checkExpiration() {
        if (!doExpireData || durExpireData <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Set<CheckDataFactory> factories = new LinkedHashSet<CheckDataFactory>();
        final Set<Entry<UUID, Long>> entries = lastLogout.entrySet();
        final Iterator<Entry<UUID, Long>> iterator = entries.iterator();
        while (iterator.hasNext()) {
            final Entry<UUID, Long> entry = iterator.next();
            final long ts = entry.getValue();
            if (now - ts <= durExpireData) {
                break;
            }
            final UUID playerId = entry.getKey();
            // TODO: LEGACY handling: switch to UUIDs here for sure.
            legacyPlayerDataExpirationRemovalByName(playerId, factories, deleteData);
            bulkPlayerDataRemoval.add(playerId); // For bulk removal.
            iterator.remove();
        }
        // Bulk removal of PlayerData.
        if (!bulkPlayerDataRemoval.isEmpty()) {
            doBulkPlayerDataRemoval(); // Using this method allows checking for delayed removal etc.
        }
    }

    private final void legacyPlayerDataExpirationRemovalByName(final UUID playerId, 
            final Set<CheckDataFactory> factories, final boolean deleteData) {
        final String playerName = DataManager.getPlayerName(playerId);
        if (playerName == null) {
            // TODO: WARN
            return;
        }
        // TODO: Validity of name?
        if (deleteData) {
            factories.clear();
            for (final CheckType type : CheckType.values()) {
                final CheckDataFactory factory = type.getDataFactory();
                if (factory != null) {
                    factories.add(factory);
                }
            }
            for (final CheckDataFactory factory : factories) {
                factory.removeData(playerName);
            }
            clearComponentData(CheckType.ALL, playerName);
        }
        if (deleteData || deleteHistory) {
            removeExecutionHistory(CheckType.ALL, playerName);
        }
        if (deleteHistory) {
            ViolationHistory.removeHistory(playerName);
        }
    }

    /**
     * Called by the rareTasksListener (OnDemandTickListener).
     * @return "Did something" - true if data was removed or similar, i.e. reset the removal delay counter. False if nothing relevant had been done.
     */
    private final boolean rareTasks(final int tick, final long timeLast) {
        boolean something = false;
        if (!bulkPlayerDataRemoval.isEmpty()) {
            doBulkPlayerDataRemoval();
            something = true;
        }
        // TODO: Process rarePlayerTasks
        return something;
    }

    /**
     * On tick.
     */
    private final void frequentTasks(final int tick, final long timeLast) {
        frequentPlayerTasks.mergePrimaryThread();
        final Iterator<UUID> it = frequentPlayerTasks.iteratorPrimaryThread();
        while (it.hasNext()) {
            final PlayerData pData = getOrCreatePlayerData(it.next(), null, false);
            if (pData.processTickFrequent(tick, timeLast)) {
                it.remove();
            }
        }
    }

    /**
     * Primary thread only. This checks for if players are/should be online.
     */
    private final void doBulkPlayerDataRemoval() {
        int size = bulkPlayerDataRemoval.size();
        if (size > 0) {
            // Test for online players.
            final Iterator<UUID> it = bulkPlayerDataRemoval.iterator();
            while (it.hasNext()) {
                final UUID playerId = it.next();
                boolean skip = !lastLogout.containsKey(playerId);
                // TODO: Also remove fake players, thus test for logged in too.
                /*
                 * TODO: Multi stage removal: (1) non essential like permission
                 * cache, (2) essential like set-back location, (3) all. In
                 * addition things will get shifty, once we use PlayerData
                 * during asynchronous login - possibly we'll need parked data
                 * then, also considering offline servers.
                 */
                if (skip) {
                    it.remove();
                    size --;
                    final PlayerData data = playerData.get(playerId);
                    if (data != null) {
                        // Should be online, keep essential data.
                        data.removeData(true);
                    }
                    continue;
                }
            }
            // Actually remove data.
            if (size > 0) {
                playerData.remove(bulkPlayerDataRemoval);
                bulkPlayerDataRemoval.clear();
                if (ConfigManager.getConfigFile().getBoolean(ConfPaths.LOGGING_EXTENDED_STATUS)) {
                    NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.STATUS, "Bulk PlayerData removal: " + size);
                }
            }
        }
    }

    /**
     * Get the exact player name, stored internally.
     * @param playerId
     */
    public static String getPlayerName(final UUID playerId) {
        final PlayerInfo info = instance.playerMap.getPlayerInfo(playerId);
        if (info != null && info.exactName != null) {
            return info.exactName;
        }
        final PlayerData data = instance.playerData.get(playerId);
        if (data != null && data.playerName != null) {
            return data.playerName;
        }
        return null;
    }

    private void playerJoins(final PlayerJoinEvent event) {
        final long timeNow = System.currentTimeMillis();
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        lastLogout.remove(playerId);
        final PlayerData pData = getOrCreatePlayerData(playerId, player.getName(), true);
        pData.onPlayerJoin(timeNow);
        CombinedData.getData(player).lastJoinTime = timeNow; 
        addOnlinePlayer(player);
    }

    /**
     * Quit or kick.
     * @param player
     */
    private void playerLeaves(final Player player) {
        final long timeNow = System.currentTimeMillis();
        final UUID playerId = player.getUniqueId();
        lastLogout.put(playerId, timeNow);
        final PlayerData pData = playerData.get(playerId);
        if (pData != null) {
            pData.onPlayerLeave(timeNow);
        }
        // TODO: put lastLogoutTime to PlayerData !
        CombinedData.getData(player).lastLogoutTime = timeNow;
        removeOnlinePlayer(player);
    }

    private void playerChangedWorld(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        final PlayerData pData = getOrCreatePlayerData(playerId, player.getName(), true);
        pData.onPlayerChangedWorld(event.getFrom(), player.getWorld());
    }

    @Override
    public void onReload() {
        // present.
        adjustSettings();
    }

    /**
     * Fetch settings from the current default config.
     */
    private void adjustSettings() {
        final ConfigFile config = ConfigManager.getConfigFile();
        doExpireData = config.getBoolean(ConfPaths.DATA_EXPIRATION_ACTIVE);
        durExpireData = config.getLong(ConfPaths.DATA_EXPIRATION_DURATION, 1, 1000000, 60) * 60000L; // in minutes
        deleteData = config.getBoolean(ConfPaths.DATA_EXPIRATION_DATA, true); // hidden.
        deleteHistory = config.getBoolean(ConfPaths.DATA_EXPIRATION_HISTORY);
        // TODO: Per world permission registries: need world configs (...).
        Set<RegisteredPermission> changedPermissions = null;
        try {
            // TODO: Only update if changes are there - should have a config-path hash+size thing (+ setting).
            changedPermissions = permissionRegistry.updateSettings(PermissionSettings.fromConfig(config, 
                    ConfPaths.PERMISSIONS_POLICY_DEFAULT, ConfPaths.PERMISSIONS_POLICY_RULES));
        }
        catch (Exception e) {
            StaticLog.logSevere("Failed to read the permissions setup. Relay to ALWAYS policy.");
            StaticLog.logSevere(e);
            permissionRegistry.updateSettings(new PermissionSettings(null, null, new PermissionPolicy()));
        }
        // Invalidate all already fetched permissions.
        final Iterator<Entry<UUID, PlayerData>> it = playerData.iterator();
        while (it.hasNext()) {
            it.next().getValue().adjustSettings(changedPermissions);
        }
    }

    /**
     * Used by checks to register the history for external access.<br>
     * NOTE: This method is not really meant ot be used from outside NCP.
     * 
     * @param type
     * @param histories
     */
    public static void registerExecutionHistory(CheckType type, Map<String, ExecutionHistory> histories) {
        instance.executionHistories.put(type, histories);
    }

    /**
     * Access method to the the execution history for check type for a player.
     * 
     * @param type
     * @param playerName
     *            Exact case for player name.
     * @return null if not present.
     */
    public static ExecutionHistory getExecutionHistory(final CheckType type, final String playerName) {
        final Map<String, ExecutionHistory> map = instance.executionHistories.get(type);
        if (map != null) {
            return map.get(playerName);
        }
        return null;
    }

    /**
     * Remove the execution history for a player for the given check type.
     * 
     * @param type
     * @param playerName
     * @return
     */
    public static boolean removeExecutionHistory(final CheckType type, final String playerName) {
        boolean removed = false;
        // TODO: design ...
        for (final CheckType refType : CheckTypeUtil.getWithDescendants(type)) {
            final Map<String, ExecutionHistory> map = instance.executionHistories.get(refType);
            if (map != null && map.remove(playerName) != null) {
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Remove data and history of all players for the given check type and sub
     * checks.
     * 
     * @param checkType
     */
    public static void clearData(final CheckType checkType) {
        final Set<CheckDataFactory> factories = new HashSet<CheckDataFactory>();
        for (final CheckType type : CheckTypeUtil.getWithDescendants(checkType)) {
            final Map<String, ExecutionHistory> map = instance.executionHistories.get(type);
            if (map != null) {
                map.clear();
            }
            final CheckDataFactory factory = type.getDataFactory();
            if (factory != null) {
                factories.add(factory);
            }
        }
        for (final CheckDataFactory factory : factories) {
            // TODO: Support precise removal ?
            factory.removeAllData();
        }
        for (final IRemoveData rmd : instance.iRemoveData) {
            if (checkType == CheckType.ALL) {
                // Not sure this is really good, though.
                rmd.removeAllData();
            }
            else if (rmd instanceof IHaveCheckType) {
                final CheckType refType = ((IHaveCheckType) rmd).getCheckType();
                if (refType == checkType || CheckTypeUtil.isAncestor(checkType, refType)) {
                    rmd.removeAllData();
                }
            }
        }
        ViolationHistory.clear(checkType);
        if (checkType == CheckType.ALL) {
            instance.bulkPlayerDataRemoval.addAll(instance.playerData.getKeys());
            instance.doBulkPlayerDataRemoval(); // Only removes offline player data.
        }
    }

    /**
     * Adjust to the system time having run backwards. This is much like
     * clearData(CheckType.ALL), with the exception of calling
     * ICanHandleTimeRunningBackwards.handleTimeRanBackwards for data instances
     * which implement this.
     */
    public static void handleSystemTimeRanBackwards() {
        // Collect data factories and clear execution history.
        final Set<CheckDataFactory> factories = new HashSet<CheckDataFactory>();
        for (final CheckType type : CheckTypeUtil.getWithDescendants(CheckType.ALL)) {
            final Map<String, ExecutionHistory> map = instance.executionHistories.get(type);
            if (map != null) {
                map.clear();
            }
            final CheckDataFactory factory = type.getDataFactory();
            if (factory != null) {
                factories.add(factory);
            }
        }
        for (final CheckDataFactory factory : factories) {
            if (factory instanceof ICanHandleTimeRunningBackwards) {
                ((ICanHandleTimeRunningBackwards) factory).handleTimeRanBackwards();
            }
            else {
                factory.removeAllData();
            }
        }
        for (final IRemoveData rmd : instance.iRemoveData) {
            if (rmd instanceof ICanHandleTimeRunningBackwards) {
                ((ICanHandleTimeRunningBackwards) rmd).handleTimeRanBackwards();
            }
            else {
                rmd.removeAllData();
            }
        }
        ViolationHistory.clear(CheckType.ALL);
        // PlayerData
        final Iterator<Entry<UUID, PlayerData>> it = instance.playerData.iterator();
        while (it.hasNext()) {
            it.next().getValue().handleTimeRanBackwards();
        }
    }

    /**
     * Restore the default debug flags within player data, as given in
     * corresponding configurations. This only yields the correct result, if the
     * the data uses the same configuration for initialization which is
     * registered under the same check type.
     */
    public static void restoreDefaultDebugFlags() {
        final Player[] players = BridgeMisc.getOnlinePlayers();
        for (final CheckType checkType : CheckType.values()) {
            final CheckConfigFactory configFactory = checkType.getConfigFactory();
            if (configFactory == null) {
                continue;
            }
            final CheckDataFactory dataFactory = checkType.getDataFactory();
            if (dataFactory == null) {
                continue;
            }
            for (int i = 0; i < players.length; i++) {
                final Player player = players[i];
                final ICheckConfig config = configFactory.getConfig(player);
                if (config == null) {
                    continue;
                }
                final ICheckData data = dataFactory.getData(player);
                if (data == null) {
                    continue;
                }
                if (config.getDebug() != data.getDebug()) {
                    data.setDebug(config.getDebug());
                }
            }
        }
    }

    /**
     * Remove the player data for a given player and a given check type.
     * CheckType.ALL and null will be interpreted as removing all data.<br>
     * 
     * @param playerName
     *            Exact player name.
     * @param checkType
     *            Check type to remove data for, null is regarded as ALL.
     * @return If any data was present.
     */
    public static boolean removeData(final String playerName, CheckType checkType) {

        final PlayerData pd = getPlayerData(playerName);
        // TODO: Once working, use the most correct name from PlayerData.
        final UUID playerId = pd == null ? getUUID(playerName) : pd.playerId;

        if (checkType == null) {
            checkType = CheckType.ALL;
        }
        boolean had = false;

        // Check extended registered components.
        // TODO: "System data" might not be wise to erase for online players.
        if (clearComponentData(checkType, playerName)) {
            had = true;
        }

        // Collect factories.
        final Set<CheckDataFactory> factories = new HashSet<CheckDataFactory>();
        for (CheckType otherType : CheckTypeUtil.getWithDescendants(checkType)) {
            final CheckDataFactory otherFactory = otherType.getDataFactory();
            if (otherFactory != null) {
                factories.add(otherFactory);
            }
        }
        // Remove data.
        for (final CheckDataFactory factory : factories) {
            if (removeDataPrecisely(playerId, playerName, checkType, factory)) {
                had = true;
            }
        }

        if (checkType == CheckType.ALL) {
            // TODO: Fetch/use UUID early, and check validity of name.
            if (playerId != null) {
                instance.bulkPlayerDataRemoval.add(playerId);
            }
        }

        return had;
    }

    /**
     * Attempt to only remove the data, relevant to the given CheckType.
     * 
     * @param playerId
     * @param playerName
     * @param checkType
     * @param factory
     * @return If any data has been removed.
     */
    private static boolean removeDataPrecisely(final UUID playerId, final String playerName, 
            final CheckType checkType, final CheckDataFactory factory) {
        final ICheckData data = factory.getDataIfPresent(playerId, playerName);
        if (data == null) {
            return false;
        }
        else {
            // Attempt precise removal.
            final boolean debug = data.getDebug();
            String debugText = debug ? "[" + checkType + "] [" + playerName + "] Data removal: " : null;
            boolean res = false;
            if (data instanceof IRemoveSubCheckData 
                    && ((IRemoveSubCheckData) data).removeSubCheckData(checkType)) {
                if (debug) {
                    debugText += "Removed (sub) check data, keeping the data object.";
                }
                res = true;
            }
            else {
                // Just remove.
                if (factory.removeData(playerName) == null) {
                    // Is this even possible?
                    if (debug) {
                        debugText += "Could not remove data, despite present!";
                    }
                }
                else {
                    if (debug) {
                        debugText += "Removed the entire data object.";
                    }
                    res = true;
                }
            }
            if (debug) {
                NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, debugText);
            }
            return res;
        }
    }

    /**
     * Clear player related data, only for registered components (not execution
     * history, violation history, normal check data).<br>
     * That should at least go for chat engine data.
     * 
     * @param CheckType
     * @param PlayerName
     * @return If something was removed.
     */
    public static boolean clearComponentData(final CheckType checkType, final String PlayerName) {
        boolean removed = false;
        for (final IRemoveData rmd : instance.iRemoveData) {
            if (checkType == CheckType.ALL) {
                // Not sure this is really good, though.
                if (rmd.removeData(PlayerName) != null) {
                    removed = true;
                }
            }
            else if (rmd instanceof IHaveCheckType) {
                final CheckType refType = ((IHaveCheckType) rmd).getCheckType();
                if (refType == checkType || CheckTypeUtil.isAncestor(checkType, refType)) {
                    if (rmd.removeData(PlayerName) != null) {
                        removed = true;
                    }
                }
            }
        }
        return removed;
    }

    /**
     * Clear all cached CheckConfig instances.<br>
     * This does not cleanup ConfigManager, i.e. stored yml-versions.
     */
    public static void clearConfigs() {
        final Set<CheckConfigFactory> factories = new LinkedHashSet<CheckConfigFactory>();
        for (final CheckType checkType : CheckType.values()) {
            final CheckConfigFactory factory = checkType.getConfigFactory();
            if (factory != null) {
                factories.add(factory);
            }
        }
        for (final CheckConfigFactory factory : factories) {
            factory.removeAllConfigs();
        }
    }

    /**
     * This gets an online player by exact player name or lower-case player name
     * only [subject to change].
     * 
     * @param playerName
     * @return
     */
    public static Player getPlayerExact(final String playerName) {
        return instance.playerMap.getPlayerExact(playerName);
    }

    /**
     * Retrieve the UUID for a given input (name or UUID string of with or
     * without '-'). Might later also query a cache, if appropriate. Convenience
     * method for use with commands.
     * 
     * @param input
     * @return
     */
    public static UUID getUUID(final String input) {
        // TODO: Use player map.
        final Player player = getPlayer(input);
        if (player != null) {
            return player.getUniqueId();
        }
        return IdUtil.UUIDFromStringSafe(input);
    }

    /**
     * Get an online player by UUID.
     * 
     * @param id
     * @return
     */
    public static Player getPlayer(final UUID id) {
        return instance.playerMap.getPlayer(id);
    }

    /**
     * This gets the online player with the exact name, but transforms the input
     * to lower case.
     * 
     * @param playerName
     * @return
     */
    public static Player getPlayer(final String playerName) {
        return instance.playerMap.getPlayer(playerName);
    }

    @Override
    public boolean addComponent(IRemoveData obj) {
        if (iRemoveData.contains(obj)) {
            return false;
        }
        else {
            iRemoveData.add((IRemoveData) obj);
            return true;
        }
    }

    @Override
    public void removeComponent(IRemoveData obj) {
        iRemoveData.remove((IRemoveData) obj);
    }

    /**
     * Initializing with online players.
     */
    public void onEnable() {
        TickTask.addTickListener(tickListener);
        final NoCheatPlusAPI api = NCPAPIProvider.getNoCheatPlusAPI();
        for (final MiniListener<?> listener : miniListeners) {
            api.addComponent(listener, false);
        }
        for (final Player player : BridgeMisc.getOnlinePlayers()) {
            addOnlinePlayer(player);
        }
    }

    /**
     * Cleanup method, removes all data and config, but does not call
     * ConfigManager.cleanup.
     */
    @Override
    public void onDisable() {
        // TODO: Process pending set backs etc. -> iterate playerData -> onDisable.
        clearData(CheckType.ALL);
        playerData.clear(); // Also clear for online players.
        iRemoveData.clear();
        clearConfigs();
        lastLogout.clear();
        executionHistories.clear();
        playerMap.clear();
        // Finally alert (summary) if inconsistencies found.
        if (foundInconsistencies > 0) {
            StaticLog.logWarning("DataMan found " + foundInconsistencies + " inconsistencies (warnings suppressed).");
            foundInconsistencies = 0;
        }
    }

    /**
     * Add mappings for player names variations.
     * @param player
     */
    private void addOnlinePlayer(final Player player) {
        playerMap.updatePlayer(player);
    }

    /**
     * Remove mappings for player names variations.
     * @param player
     */
    private void removeOnlinePlayer(final Player player) {
        // TODO: Consider to only remove the Player instance? Yes do so... and remove the mapping if the full data expires only.
        playerMap.remove(player);
    }

    @Override
    public String getComponentName() {
        return "NoCheatPlus_DataManager";
    }

    @Override
    public void checkConsistency(final Player[] onlinePlayers) {
        // Check online player tracking consistency.
        int missing = 0;
        int changed = 0;
        for (int i = 0; i < onlinePlayers.length; i++) {
            final Player player = onlinePlayers[i];
            final UUID id = player.getUniqueId();
            //			if (player.isOnline()) {
            // TODO: Add a consistency check method !?
            if (!playerMap.hasPlayerInfo(id)) {
                missing ++;
                // TODO: Add the player [problem: messy NPC plugins?]?
            }
            if (playerMap.storesPlayerInstances() && player != playerMap.getPlayer(id)) {
                changed ++;
                // Update the reference.
                addOnlinePlayer(player);
                //				}
            }
        }

        // TODO: Consider checking lastLogout for too long gone players.

        // TODO: Later the map size will not work, if we keep name/id mappings after logout. Other checking methods are possible.
        final int storedSize = this.playerMap.size();
        if (missing != 0 || changed != 0 || onlinePlayers.length != storedSize) {
            foundInconsistencies ++;
            if (!ConfigManager.getConfigFile().getBoolean(ConfPaths.DATA_CONSISTENCYCHECKS_SUPPRESSWARNINGS)) {
                final List<String> details = new LinkedList<String>();
                if (missing != 0) {
                    details.add("missing online players (" + missing + ")");
                }
                if (onlinePlayers.length != storedSize) {
                    // TODO: Consider checking for not online players and remove them.
                    details.add("wrong number of online players (" + storedSize + " instead of " + onlinePlayers.length + ")");
                }
                if (changed != 0) {
                    details.add("changed player instances (" + changed + ")");
                }

                StaticLog.logWarning("DataMan inconsistencies: " + StringUtil.join(details, " | "));
            }
        }
    }

    /**
     * GetOrCreate means controlled by a flag in this case.
     * <hr>
     * Future may be to get rid of the static methods here, which may exist
     * within NCPStaticSomething then.
     * 
     * @param playerId
     * @param playerName
     * @param create
     *            Create if not present.
     * @return
     */
    public PlayerData getOrCreatePlayerData(final UUID playerId, final String playerName, final boolean create) {
        final PlayerData data = playerData.get(playerId);
        if (!create || data != null) {
            return data;
        }
        else {
            // Creating this should be mostly harmless.
            final PlayerData newData = new PlayerData(playerId, playerName, permissionRegistry);
            final PlayerData oldData =  playerData.putIfAbsent(playerId, newData);
            return oldData == null ? newData : oldData;
        }
    }

    /**
     * Get a PlayerData instance in any case - always creates a PlayerData
     * instance, if none is present. This method should be preferred, as it
     * hides details.
     * 
     * @param player
     * @return
     */
    public static PlayerData getPlayerData(final Player player) {
        return getPlayerData(player.getUniqueId(), player.getName(), true);
    }

    /**
     * Get PlayerData instances, controlling if data is created, in case it is
     * not present.
     * 
     * @param playerName
     * @param create
     * @return
     */
    public static PlayerData getPlayerData(final UUID playerId, final String playerName, final boolean create) {
        return instance.getOrCreatePlayerData(playerId, playerName, create);
    }

    /**
     * Get the player data, if present.
     * 
     * @param playerName
     * @return The PlayerData instance if present, null otherwise.
     */
    public static PlayerData getPlayerData(final String playerName) {
        final UUID playerId = getUUID(playerName);
        return playerId == null ? null : instance.playerData.get(playerId);
    }

    /**
     * Get the player data, if present.
     * 
     * @param playerID
     * @return The PlayerData instance if present, null otherwise.
     */
    public static PlayerData getPlayerData(final UUID playerID) {
        return instance.playerData.get(playerID);
    }

    /**
     * Check if player instances are stored for efficiency (legacy).
     * 
     * @return
     */
    public boolean storesPlayerInstances() {
        return playerMap.storesPlayerInstances();
    }

    /**
     * Might yield false negatives, should be reasonable on performance.
     * 
     * @param playerId
     * @return
     */
    public static boolean isFrequentPlayerTaskScheduled(final UUID playerId) {
        // TODO : Efficient impl / optimized methods?
        if (Bukkit.isPrimaryThread()) {
            return instance.frequentPlayerTasks.containsPrimaryThread(playerId);
        }
        else {
            return instance.frequentPlayerTasks.containsAsynchronous(playerId);
        }
    }

    protected static void registerFrequentPlayerTaskPrimaryThread(final UUID playerId) {
        instance.frequentPlayerTasks.addPrimaryThread(playerId);
    }

    protected static void registerFrequentPlayerTaskAsynchronous(final UUID playerId) {
        instance.frequentPlayerTasks.addAsynchronous(playerId);
    }

    /**
     * 
     */
    public static void clearAllExemptions() {
        final Iterator<Entry<UUID, PlayerData>> it = instance.playerData.iterator();
        while (it.hasNext()) {
            it.next().getValue().clearAllExemptions();
        }
    }

}
