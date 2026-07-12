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
 * Our own "extreme" Battery Saver: follows the stock Battery Saver toggle
 * (not a Pepito Tweaks entry itself - see PLAN-perf-battery.md) and, while
 * it's on, applies levers no stock Android battery saver reaches because
 * they're root-only - offlining the 1.4GHz cpu0-3 cluster
 * (persist.gotweak.cpu_cluster_saver) and capping the GPU clock
 * (persist.gotweak.battery_saver_gpu_cap). device/xiaomi/mithorium-common's
 * init.gotweaks.rc does the actual sysfs writes for both. Registered
 * dynamically from BootCompletedReceiver, since
 * ACTION_POWER_SAVE_MODE_CHANGED "is only sent to registered receivers"
 * (PowerManager javadoc) - a manifest <receiver> can't catch it. Relies on
 * XiaomiParts already being android:persistent="true" to stay registered
 * for the whole uptime without a dedicated process of its own.
 *
 * register() must use context.getApplicationContext(), not the Context
 * BootCompletedReceiver.onReceive() was handed directly: a manifest-
 * registered receiver's onReceive() gets a ReceiverRestrictedContext, which
 * throws ReceiverCallNotAllowedException on any registerReceiver() call
 * (caught on the 2026-07-11 validation flash - BootCompletedReceiver
 * crashed, the hook never registered, so cpu_cluster_saver never followed
 * Battery Saver at all).
 *
 * The GPU lever is OR-composed at the init.rc layer with the independent
 * manual "Cap GPU clock" Pepito Tweaks toggle (persist.gotweak.gpu_clock_cap):
 * each has its own "turn on" trigger, and the "turn off" trigger only fires
 * when both are 0, so this hook toggling off never clobbers a standing
 * manual preference (and vice versa). The CPU cluster lever has no other
 * driver today, so it's a plain boolean with no such composition needed.
 *
 * The whole hook can be killed via persist.gotweak.battery_saver_hook
 * (default on) - a Pepito Tweaks toggle in LineageParts, since this is new,
 * unvalidated-on-device behavior. That toggle also directly recomputes and
 * sets both properties itself when flipped (see GoTweaksSettings.java) so
 * the effect reverses immediately instead of waiting for the next Battery
 * Saver change - the (hookEnabled && isPowerSaveMode()) check in apply()
 * below is duplicated there; keep both in sync if it ever changes.
 */
public class GotweaksBatterySaverReceiver extends BroadcastReceiver {

    private static final String TAG = "GotweaksBatterySaverReceiver";
    private static final String PROP_CPU_CLUSTER_SAVER =
            "persist.gotweak.cpu_cluster_saver";
    private static final String PROP_GPU_CAP =
            "persist.gotweak.battery_saver_gpu_cap";
    private static final String PROP_HOOK_ENABLED =
            "persist.gotweak.battery_saver_hook";

    public static void register(Context context) {
        final Context appContext = context.getApplicationContext();

        final PowerManager pm = appContext.getSystemService(PowerManager.class);
        if (pm == null) {
            return;
        }

        apply(pm.isPowerSaveMode());

        appContext.registerReceiver(new GotweaksBatterySaverReceiver(),
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
                + " - setting " + PROP_CPU_CLUSTER_SAVER + "/" + PROP_GPU_CAP
                + "=" + (cap ? "1" : "0"));
        SystemProperties.set(PROP_CPU_CLUSTER_SAVER, cap ? "1" : "0");
        SystemProperties.set(PROP_GPU_CAP, cap ? "1" : "0");
    }
}
