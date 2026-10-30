/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.utilities;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class Players {

    private Players() {
    }

    /**
     * Whether the player takes part in the world: online, or a player entity that plugins spawn without a login
     * (server-side bots), which is in no player list and so never online.
     */
    public static boolean isPresent(OfflinePlayer player) {
        return player.isOnline() || player instanceof Player && ((Player) player).isValid();
    }
}
