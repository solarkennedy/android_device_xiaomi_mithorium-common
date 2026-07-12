/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lifemode;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The Life Mode master switch. All this does is flip persist.lifemode.enabled;
 * the work happens in LifeModeController on the next screen-off, which is the
 * whole point of the feature - see PLAN-lifemode.md.
 *
 * Turning it ON with the screen on is a no-op by design (Life Mode starts at
 * the next screen-off). Turning it OFF exits immediately if we're active,
 * which can only be observed via adb: the tile isn't reachable with the screen
 * off, so in practice a user turning it off is already on the restored path.
 */
public class LifeModeTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        LifeModeController.setEnabled(this, !LifeModeController.isEnabled());
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(LifeModeController.isEnabled()
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
