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
 * and/or capping the GPU clock, each independently gated by its own simple
 * "participate in Battery Saver?" enable flag
 * (persist.gotweak.battery_saver_cpu_enable /
 * battery_saver_gpu_enable, both Pepito Tweaks toggles in LineageParts).
 * device/xiaomi/mithorium-common's init.gotweaks.rc does the actual sysfs
 * writes for both, driven purely by this receiver - neither
 * persist.gotweak.cpu_cluster_saver nor persist.gotweak.gpu_clock_cap has
 * any other driver, so there's no composition/conflict to worry about.
 *
 * Registered dynamically from BootCompletedReceiver, since
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
 * Each enable flag's toggle also directly recomputes and sets its
 * corresponding output property itself when flipped (see
 * GoTweaksSettings.java) so the effect reverses immediately instead of
 * waiting for the next Battery Saver change - the
 * (enabled && isPowerSaveMode()) check in apply() below is duplicated
 * there per-lever; keep both in sync if it ever changes.
 */
public class GotweaksBatterySaverReceiver extends BroadcastReceiver {

    private static final String TAG = "GotweaksBatterySaverReceiver";
    private static final String PROP_CPU_CLUSTER_SAVER =
            "persist.gotweak.cpu_cluster_saver";
    private static final String PROP_CPU_ENABLED =
            "persist.gotweak.battery_saver_cpu_enable";
    private static final String PROP_GPU_CLOCK_CAP =
            "persist.gotweak.gpu_clock_cap";
    private static final String PROP_GPU_ENABLED =
            "persist.gotweak.battery_saver_gpu_enable";

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
        final boolean cpuCap = SystemProperties.getBoolean(PROP_CPU_ENABLED, true)
                && powerSaveMode;
        final boolean gpuCap = SystemProperties.getBoolean(PROP_GPU_ENABLED, false)
                && powerSaveMode;
        Log.d(TAG, "Battery Saver " + (powerSaveMode ? "on" : "off")
                + " - " + PROP_CPU_CLUSTER_SAVER + "=" + (cpuCap ? "1" : "0")
                + " " + PROP_GPU_CLOCK_CAP + "=" + (gpuCap ? "1" : "0"));
        SystemProperties.set(PROP_CPU_CLUSTER_SAVER, cpuCap ? "1" : "0");
        SystemProperties.set(PROP_GPU_CLOCK_CAP, gpuCap ? "1" : "0");
    }
}
