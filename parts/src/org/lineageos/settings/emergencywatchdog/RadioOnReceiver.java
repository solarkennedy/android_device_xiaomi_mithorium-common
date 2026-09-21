/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.emergencywatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Safety-critical power-on for the radio-cycle nudge. Fired by an
 * ELAPSED_REALTIME_WAKEUP alarm a few seconds after EmergencyWatchdogController
 * votes the radio OFF (radioCycle()).
 *
 * <p>This is a MANIFEST receiver on purpose - unlike the controller's dynamic
 * debounce receiver - so it still runs even if the controller's process was
 * killed during the radio-off window: the system restarts the app to deliver an
 * explicit-component broadcast. That is what guarantees a crash can never strand
 * the radio powered off. Clearing an already-cleared vote is a no-op, so it is
 * safe to fire even when the process was alive and would have cleared it anyway.
 */
public class RadioOnReceiver extends BroadcastReceiver {

    private static final String TAG = "EmergencyWatchdog";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return;
        }
        try {
            tm.clearRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER);
            Log.w(TAG, "radio-cycle: radio ON (cleared radio-off vote)");
        } catch (Exception e) {
            Log.e(TAG, "radio-cycle: power-on FAILED: " + e);
        }
    }
}
