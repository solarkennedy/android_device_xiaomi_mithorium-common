/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.gotweaks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Follows the stock Battery Saver toggle (not a Pepito Tweaks entry itself -
 * see PLAN-perf-battery.md): when isPowerSaveMode() is true, offlines the
 * 1.4GHz cpu0-3 cluster via persist.gotweak.cpu_cluster_saver
 * (device/xiaomi/mithorium-common's init.gotweaks.rc does the actual
 * sysfs writes). Registered dynamically from BootCompletedReceiver, since
 * ACTION_POWER_SAVE_MODE_CHANGED "is only sent to registered receivers"
 * (PowerManager javadoc) - a manifest <receiver> can't catch it. Relies on
 * XiaomiParts already being android:persistent="true" to stay registered
 * for the whole uptime without a dedicated process of its own.
 *
 * The whole hook can be killed via persist.gotweak.battery_saver_cpu_hook
 * (default on) - a Pepito Tweaks toggle in LineageParts, since this is new,
 * unvalidated-on-device behavior. That toggle also directly recomputes and
 * sets PROP_CPU_CLUSTER_SAVER itself when flipped (see GoTweaksSettings.java)
 * so cores come back immediately on disable, rather than waiting for the
 * next Battery Saver change - the (hookEnabled && isPowerSaveMode()) check
 * in apply() below is duplicated there; keep both in sync if it ever changes.
 */
public class GotweaksBatterySaverReceiver extends BroadcastReceiver {

    private static final String TAG = "GotweaksBatterySaverReceiver";
    private static final String PROP_CPU_CLUSTER_SAVER =
            "persist.gotweak.cpu_cluster_saver";
    private static final String PROP_HOOK_ENABLED =
            "persist.gotweak.battery_saver_cpu_hook";

    public static void register(Context context) {
        final PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) {
            return;
        }

        apply(pm.isPowerSaveMode());

        context.registerReceiver(new GotweaksBatterySaverReceiver(),
                new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) {
            return;
        }
        apply(pm.isPowerSaveMode());
    }

    private static void apply(final boolean powerSaveMode) {
        final boolean cap = SystemProperties.getBoolean(PROP_HOOK_ENABLED, true)
                && powerSaveMode;
        Log.d(TAG, "Battery Saver " + (powerSaveMode ? "on" : "off")
                + " - setting " + PROP_CPU_CLUSTER_SAVER + "="
                + (cap ? "1" : "0"));
        SystemProperties.set(PROP_CPU_CLUSTER_SAVER, cap ? "1" : "0");
    }
}
