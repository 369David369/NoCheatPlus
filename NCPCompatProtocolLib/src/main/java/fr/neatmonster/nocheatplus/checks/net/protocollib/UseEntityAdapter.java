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
package fr.neatmonster.nocheatplus.checks.net.protocollib;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.net.AttackFrequency;
import fr.neatmonster.nocheatplus.checks.net.NetConfig;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.ConfigManager;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.PlayerData;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;

public class UseEntityAdapter extends BaseAdapter {

    private static class LegacyReflectionSet {
        /** Hacks. */
        final Class<?> packetClass_legacy;
        final Class<?> enumClassAction_legacy;
        final Method methodGetAction_legacy;
        final Method methodName_legacy;

        /**
         * 
         * @param versionDetail
         * @throws RuntimeException
         *             if not matching/supported.
         */
        private LegacyReflectionSet(String versionDetail) {
            Class<?> packetClass = ReflectionUtil.getClass("net.minecraft.server." + versionDetail + ".PacketPlayInUseEntity");
            Class<?> actionClass = ReflectionUtil.getClass("net.minecraft.server." + versionDetail + ".EnumEntityUseAction");
            Method methodGetAction = (packetClass == null || actionClass == null) ? null : ReflectionUtil.getMethodNoArgs(packetClass, "c", actionClass);
            if (packetClass == null || actionClass == null || methodGetAction == null) {
                this.packetClass_legacy = null;
                this.enumClassAction_legacy = null;
                this.methodGetAction_legacy = null;
                this.methodName_legacy = null;
            }
            else {
                this.packetClass_legacy = packetClass;
                this.enumClassAction_legacy = actionClass;
                this.methodGetAction_legacy = methodGetAction;
                this.methodName_legacy = ReflectionUtil.getMethodNoArgs(enumClassAction_legacy, "name", String.class);
            }
            if (this.methodName_legacy == null) {
                throw new RuntimeException("Not supported.");
            }
        }

        String getActionFromNMSPacket(Object handle) {
            final Class<?> clazz = handle.getClass();
            if (clazz != packetClass_legacy) {
                return null;
            }
            final Object action = ReflectionUtil.invokeMethodNoArgs(methodGetAction_legacy, handle);
            if (action == null) {
                return null;
            }
            final Object actionName = ReflectionUtil.invokeMethodNoArgs(methodName_legacy, action);
            if (actionName instanceof String) {
                return (String) actionName;
            }
            else {
                return null;
            }
        }
    }

    private static final int INTERPRETED = 0x01;
    private static final int ATTACK = 0x02;

    private final AttackFrequency attackFrequency;

    private final LegacyReflectionSet legacySet;

    public UseEntityAdapter(Plugin plugin) {
        super(plugin, PacketType.Play.Client.USE_ENTITY);

        // Add feature tags for checks.
        if (ConfigManager.isTrueForAnyConfig(ConfPaths.NET_ATTACKFREQUENCY_ACTIVE)) {
            NCPAPIProvider.getNoCheatPlusAPI().addFeatureTags("checks", Arrays.asList(AttackFrequency.class.getSimpleName()));
        }
        attackFrequency = new AttackFrequency();

        LegacyReflectionSet set = null;
        for (String versionDetail : new String[] {"v1_7_R4", "v1_7_R1"}) {
            try {
                set = new LegacyReflectionSet(versionDetail);
                if (set != null) {
                    break;
                }
            }
            catch (RuntimeException e) {} // +-
        }
        this.legacySet = set;
        NCPAPIProvider.getNoCheatPlusAPI().addComponent(attackFrequency);
    }

    @Override
    public void onPacketReceiving(final PacketEvent event) {
        final long time = System.currentTimeMillis();
        final Player player = event.getPlayer();
        if (player == null) {
            // TODO: Warn once?
            return;
        }
        final NetConfig cc = configFactory.getConfig(player);
        final NetData data = dataFactory.getData(player);

        // Always set last received time.
        data.lastKeepAliveTime = time;

        // Quick return, if no checks are active.
        if (!cc.attackFrequencyActive) {
            return;
        }

        final PacketContainer packet = event.getPacket();

        // MIGHT: use entity, use block both on packet level?
        boolean isAttack = false;
        boolean packetInterpreted = false;
        if (legacySet != null) {
            // Attempt to extract legacy information.
            final int flags = getAction_legacy(packet);
            if ((flags & INTERPRETED) != 0 ) {
                packetInterpreted = true;
                if ((flags & ATTACK) != 0) {
                    isAttack = true;
                }
            }
        }
        if (!packetInterpreted) {
            // Handle as if latest.
            final StructureModifier<EntityUseAction> actions;
            actions = packet.getEntityUseActions();
            if (actions.size() == 1 && actions.read(0) == EntityUseAction.ATTACK) {
                isAttack = true;
                packetInterpreted = true;
            }
        }
        if (!packetInterpreted) {
            // TODO: Log warning once, if the packet could not be interpreted.
            return;
        }

        // Run checks.
        boolean cancel = false;

        // AttackFrequency
        if (isAttack) {
            final PlayerData pData = DataManager.getPlayerData(player);
            if (attackFrequency.isEnabled(player, cc, pData) && attackFrequency.check(player, time, data, cc)) {
                cancel = true;
            }
        }

        if (cancel) {
            event.setCancelled(true);
        }

    }

    private int getAction_legacy(final PacketContainer packetContainer) {
        // (For some reason the object didn't appear work with equality checks, thus compare the short string.)
        final String actionName = legacySet.getActionFromNMSPacket(packetContainer.getHandle());
        return actionName == null ? 0 : (INTERPRETED | ("ATTACK".equals(actionName) ? ATTACK : 0));
    }

}
