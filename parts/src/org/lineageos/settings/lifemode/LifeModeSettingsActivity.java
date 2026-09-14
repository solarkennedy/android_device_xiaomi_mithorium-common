/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lifemode;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Long-press target for the Life Mode QS tile.
 *
 * SystemUI resolves ACTION_QS_TILE_PREFERENCES against the *tile's own package*
 * (CustomTile.getLongClickIntent), so the settings screen being in LineageParts
 * is not reachable directly - this is the trampoline that bridges the two apps.
 * With no such activity at all, a long-press would fall through to SystemUI's
 * "App info" fallback, which is useless here.
 *
 * NoDisplay + finish() in onCreate: nothing of this activity is ever seen, the
 * user just lands on the Life Mode screen.
 *
 * Ships disabled (see AndroidManifest) for the same reason the tile does - on the
 * sibling Mi8937 variants there is no Life Mode - and is enabled alongside the
 * tile by BootCompletedReceiver. That is load-bearing rather than tidiness:
 * SystemUI resolves with flags 0, so a disabled component simply doesn't resolve.
 */
public class LifeModeSettingsActivity extends Activity {

    private static final String TAG = "LifeModeSettings";

    private static final String ACTION_LIFE_MODE_SETTINGS =
            "org.lineageos.lineageparts.LIFE_MODE_SETTINGS";
    private static final String LINEAGEPARTS_PACKAGE = "org.lineageos.lineageparts";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = new Intent(ACTION_LIFE_MODE_SETTINGS)
                .setPackage(LINEAGEPARTS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // A build without our LineageParts fork. Nothing useful to show, and
            // crashing out of a tile long-press would be worse than doing nothing.
            Log.w(TAG, "No Life Mode settings screen to open", e);
        }

        finish();
    }
}
