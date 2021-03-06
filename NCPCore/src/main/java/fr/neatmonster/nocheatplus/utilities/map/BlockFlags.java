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
package fr.neatmonster.nocheatplus.utilities.map;

import org.bukkit.Material;

// TODO: Auto-generated Javadoc
/**
 * Utilities for block-flags.<br>
 * Later the flag constant definitions and parsing might be moved here.
 * @author mc_dev
 *
 */
public class BlockFlags {

    /**
     * Set flags of id same as already set with flags for the given material.
     * (Uses BlockProperties.)
     *
     * @param id
     *            the id
     * @param mat
     *            the mat
     */
    public static void setFlagsAs(String id, Material mat) {
        BlockProperties.setBlockFlags(id, BlockProperties.getBlockFlags(mat));
    }

    /**
     * Set flags of id same as already set with flags for the given material.
     * (Uses BlockProperties.)
     *
     * @param id
     *            the id
     * @param otherId
     *            the other id
     */
    public static void setFlagsAs(String id, String otherId) {
        BlockProperties.setBlockFlags(id, BlockProperties.getBlockFlags(otherId));
    }

    /**
     * Add flags to existent flags. (Uses BlockProperties.)
     *
     * @param id
     *            Id of the block.
     * @param flags
     *            Block flags.
     */
    public static void addFlags(String id, long flags) {
        BlockProperties.setBlockFlags(id, BlockProperties.getBlockFlags(id) | flags);
    }

    /**
     * Add flags to existent flags. (Uses BlockProperties.)
     *
     * @param blockType
     *            Bukkit Material type. Id of the block.
     * @param flags
     *            Block flags.
     */
    public static void addFlags(Material blockType, long flags) {
        BlockProperties.setBlockFlags(blockType, BlockProperties.getBlockFlags(blockType) | flags);
    }

    /**
     * Remove the given flags from existent flags. (Uses BlockProperties.)
     *
     * @param id
     *            the id
     * @param flags
     *            the flags
     */
    public static void removeFlags(String id, long flags) {
        BlockProperties.setBlockFlags(id, BlockProperties.getBlockFlags(id) & ~flags);
    }

}
