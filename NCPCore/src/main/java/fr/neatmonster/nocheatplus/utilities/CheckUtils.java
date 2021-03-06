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
package fr.neatmonster.nocheatplus.utilities;

import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.access.ICheckConfig;
import fr.neatmonster.nocheatplus.checks.access.ICheckData;
import fr.neatmonster.nocheatplus.checks.blockbreak.BlockBreakData;
import fr.neatmonster.nocheatplus.checks.combined.CombinedData;
import fr.neatmonster.nocheatplus.checks.fight.FightData;
import fr.neatmonster.nocheatplus.checks.inventory.InventoryData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.hooks.NCPExemptionManager;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.permissions.RegisteredPermission;
import fr.neatmonster.nocheatplus.players.PlayerData;
import fr.neatmonster.nocheatplus.utilities.ds.count.ActionFrequency;


/**
 * Random auxiliary gear, some might have general quality. Contents are likely
 * to get moved to other classes. All that is in here should be set up with
 * checks and not be related to early setup stages of the plugin.
 */
public class CheckUtils {

    /**
     * Improper API access.
     *
     * @param checkType
     *            the check type
     */
    private static void improperAPIAccess(final CheckType checkType) {
        // TODO: Log once + examine stack (which plugins/things are involved).
        final String trace = Arrays.toString(Thread.currentThread().getStackTrace());
        StaticLog.logOnce(Streams.STATUS, Level.SEVERE, "Off primary thread call to hasByPass for " + checkType, trace);
    }

    /**
     * Kick and log.
     *
     * @param player
     *            the player
     * @param cc
     *            the cc
     */
    public static void kickIllegalMove(final Player player, final MovingConfig cc){
        player.kickPlayer(cc.msgKickIllegalMove);
        StaticLog.logWarning("[NCP] Disconnect " + player.getName() + " due to illegal move!");
    }

    /**
     * Guess some last-action time, likely to be replaced with centralized
     * PlayerData use.
     *
     * @param player
     *            the player
     * @param now
     *            the now
     * @param maxAge
     *            Maximum age in milliseconds.
     * @return Return timestamp or Long.MIN_VALUE if not possible or beyond
     *         maxAge.
     */
    public static final long guessKeepAliveTime(final Player player, final long now, final long maxAge){
        final int tick = TickTask.getTick();
        long ref = Long.MIN_VALUE;
        // Estimate last fight action time (important for gode modes).
        final FightData fData = FightData.getData(player); 
        ref = Math.max(ref, fData.speedBuckets.lastUpdate());
        ref = Math.max(ref, now - 50L * (tick - fData.lastAttackTick)); // Ignore lag.
        // Health regain (not unimportant).
        ref = Math.max(ref, fData.regainHealthTime);
        // Move time.
        ref = Math.max(ref, CombinedData.getData(player).lastMoveTime);
        // Inventory.
        final InventoryData iData = InventoryData.getData(player);
        ref = Math.max(ref, iData.lastClickTime);
        ref = Math.max(ref, iData.instantEatInteract);
        // BlcokBreak/interact.
        final BlockBreakData bbData = BlockBreakData.getData(player);
        ref = Math.max(ref, bbData.frequencyBuckets.lastUpdate());
        ref = Math.max(ref, bbData.fastBreakfirstDamage);
        // TODO: More, less ...
        if (ref > now || ref < now - maxAge){
            return Long.MIN_VALUE;
        }
        return ref;
    }

    /**
     * Check for config flag and exemption (hasBypass). Meant thread-safe.
     *
     * @param checkType
     *            the check type
     * @param player
     *            the player
     * @param data
     *            If data is null, the data factory will be used for the given
     *            check type.
     * @param cc
     *            If config is null, the config factory will be used for the
     *            given check type.
     * @return true, if is enabled
     */
    public static boolean isEnabled(final CheckType checkType, final Player player, 
            final ICheckConfig cc, final PlayerData pData) {
        if (cc == null) {
            if (!checkType.isEnabled(player)) {
                return false;
            }
        }
        else if (!cc.isEnabled(checkType)) {
            return false;
        }
        return !hasBypass(checkType, player, pData);
    }

    /**
     * Check for exemption by permissions, API access, possibly other. Meant
     * thread-safe.
     * 
     * @see #hasBypass(CheckType, Player, ICheckData, boolean)
     *
     * @param checkType
     *            the check type
     * @param player
     *            the player
     * @param pData
     *            Must not be null.
     * @return true, if successful
     */
    public static boolean hasBypass(final CheckType checkType, final Player player, final PlayerData pData) {
        // TODO: Checking for the thread might be a temporary measure.
        return hasBypass(checkType, player, pData, Bukkit.isPrimaryThread());
    }

    /**
     * Check for exemption by permissions, API access, possibly other. Meant
     * thread-safe.
     *
     * @param checkType
     *            the check type
     * @param player
     *            the player
     * @param pData
     *            Must not be null.
     * @param isPrimaryThread
     *            If set to true, this must be the primary server thread as
     *            returned by Bukkit.isPrimaryThread().
     * @return true, if successful
     */
    public static boolean hasBypass(final CheckType checkType, final Player player, final PlayerData pData, 
            final boolean isPrimaryThread) {
        // TODO: Thread testing should be removed, once thread-safe read is implemented for exemption too.
        /*
         * TODO: Thread context information may be part of a CheckPipeline
         * object later on (alongside PlayerData and other policy stuff),
         * replacing some of the arguments (IsPrimaryThread must be rock solid
         * information.).
         */

        // TODO: Refine this error message +- put in place where it needs to be.
        if (!isPrimaryThread && !CheckTypeUtil.needsSynchronization(checkType)) {
            /*
             * Checking for exemption can't cause harm anymore, however even
             * fetching data or configuration might still lead to everything
             * exploding.
             */
            improperAPIAccess(checkType);
        }
        // Exemption check.
        if (NCPExemptionManager.isExempted(player, checkType)) {
            return true;
        }
        // Check permission policy/cache regardless of the thread context.
        final RegisteredPermission permission =  checkType.getPermission();
        if (permission != null && pData.hasPermission(permission, player)) {
            return true;
        }
        return false;
    }

    /**
     * Static relay for the check-specific convenience methods, logging with
     * standard format ([check_type] [player_name] ...).
     *
     * @param player
     *            May be null.
     * @param checkType
     *            the check type
     * @param message
     *            the message
     */
    public static void debug(final Player player, final CheckType checkType, final String message) {
        NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, getLogMessagePrefix(player, checkType) + message);
    }

    /**
     * Get the standard log message prefix with a trailing space.
     *
     * @param player
     *            May be null.
     * @param checkType
     *            the check type
     * @return the log message prefix
     */
    public static String getLogMessagePrefix(final Player player, final CheckType checkType) {
        String base = "[" + checkType + "] ";
        if (player != null) {
            base += "[" + player.getName() + "] ";
        }
        return base;
    }

    /**
     * Convenience method to get a Random instance from the generic registry
     * (NoCheatPlusAPI).
     *
     * @return the random
     */
    public static Random getRandom() {
        return NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(Random.class);
    }

    /**
     * Update and then reduce all given ActionFrequency instances by the given
     * amount, capped at a maximum of 0 for the resulting first bucket score.
     * 
     * @param amount
     *            The amount to subtract.
     * @param freqs
     */
    public static void reduce(final long time, final float amount, final ActionFrequency... freqs) {
        for (int i = 0; i < freqs.length; i++) {
            final ActionFrequency freq = freqs[i];
            freq.update(time);
            freq.setBucket(0, Math.max(0f, freq.bucketScore(0) - amount));
        }
    }

    /**
     * Update and then reduce all given ActionFrequency instances by the given
     * amount, without capping the result.
     * 
     * @param amount
     *            The amount to subtract.
     * @param freqs
     */
    public static void subtract(final long time, final float amount, final ActionFrequency... freqs) {
        for (int i = 0; i < freqs.length; i++) {
            final ActionFrequency freq = freqs[i];
            freq.update(time);
            freq.setBucket(0, freq.bucketScore(0) - amount);
        }
    }

}
