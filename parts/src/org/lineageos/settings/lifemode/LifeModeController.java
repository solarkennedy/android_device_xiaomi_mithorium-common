/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lifemode;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.ColorDisplayManager;
import android.location.LocationManager;
import android.net.NetworkPolicyManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Life Mode - a reimplementation of the Palm PVG100's headline feature
 * (PLAN-lifemode.md). While the master toggle is on, every screen-off
 * silences the phone and stops apps chattering over the network; every
 * screen-on puts it all back exactly as it was, so nothing is missed.
 *
 * The master switch is the Life Mode QS tile (LifeModeTile); what Life Mode
 * actually *does* is configured from Pepito Tweaks in LineageParts. Both
 * halves beyond DND are opt-outable, and every knob is a persist.lifemode.*
 * property read fresh at each screen-off - so there is nothing to observe and
 * no state to keep in sync.
 *
 * Greyscale is the exception to all of that: it follows the MASTER SWITCH
 * rather than the screen, because it is the one lever meant to be *seen*. See
 * applyGrayscale().
 *
 * DND is always INTERRUPTION_FILTER_PRIORITY, never _NONE: starred contacts
 * and repeat callers still break through, which is what makes it safe to
 * leave on overnight (and matches Palm's own bypass behavior). It is
 * deliberately not a knob.
 *
 * Battery Saver is on by default, and is the cheapest lever here: flipping
 * stock Battery Saver for the duration of screen-off gets us the whole
 * framework standby package (job/alarm deferral, background restrictions) for
 * one call. It also composes with GotweaksBatterySaverReceiver next door for
 * free - that already follows ACTION_POWER_SAVE_MODE_CHANGED, so if the user
 * has the Extreme Battery Saver levers enabled, Life Mode screen-off now also
 * offlines the fast CPU cluster and/or caps the GPU. Nothing to wire up: we
 * turn Battery Saver on, the receiver hears it, the levers apply.
 *
 * Wi-Fi-off, GPS-off and Bluetooth-off are all opt-in and off by default.
 * Wi-Fi-off is safe here specifically because this device has working VoLTE -
 * calls and SMS ride LTE, so dropping Wi-Fi with the screen off doesn't make
 * the phone unreachable. GPS and Bluetooth are both things stock Life Mode
 * really did kill (per the strings still in Palm's own 8.1 SystemUI.apk), but
 * BT-off in particular has a sharp edge stock users presumably lived with:
 * it cuts audio to Bluetooth headphones the moment the screen goes off, which
 * is a completely normal thing to be doing. Hence opt-in, not default.
 *
 * Hosted inside XiaomiParts rather than an app of its own: ACTION_SCREEN_ON /
 * ACTION_SCREEN_OFF are excluded from manifest <receiver>s (since O), so
 * *something* has to be resident to hear them, and XiaomiParts is already
 * android:persistent - same reasoning, and the same registerReceiver()
 * plumbing, as GotweaksBatterySaverReceiver next door. Registered from
 * BootCompletedReceiver, which must hand us an application Context: a
 * manifest receiver's onReceive() Context is a ReceiverRestrictedContext and
 * throws on registerReceiver().
 *
 * Everything we touch (DND filter, Data Saver, Wi-Fi) is *persistent system
 * state* that outlives our process. So the snapshot is persisted too, and
 * restore() runs unconditionally at boot: without that, a reboot or crash
 * with the screen off would strand the user silenced and offline with no
 * record of what to put back. Each field is tri-state - UNTOUCHED (-1) means
 * we never changed it and must not "restore" it, which is what keeps us from
 * clobbering a Data Saver or DND setting the user chose themselves.
 */
public final class LifeModeController extends BroadcastReceiver {

    private static final String TAG = "LifeMode";

    private static final String PROP_DEVICE = "ro.vendor.xiaomi.device";
    private static final String DEVICE_PEPITO = "pepito";

    /** Master switch, owned by the QS tile. */
    public static final String PROP_ENABLED = "persist.lifemode.enabled";

    /** Behavior knobs, owned by Pepito Tweaks (LineageParts). */
    public static final String PROP_RESTRICT_DATA = "persist.lifemode.restrict_data";
    public static final String PROP_BATTERY_SAVER = "persist.lifemode.battery_saver";
    public static final String PROP_WIFI_OFF = "persist.lifemode.wifi_off";
    public static final String PROP_GPS_OFF = "persist.lifemode.gps_off";
    public static final String PROP_BT_OFF = "persist.lifemode.bt_off";
    public static final String PROP_GRAYSCALE = "persist.lifemode.grayscale";

    public static final boolean DEFAULT_RESTRICT_DATA = true;
    public static final boolean DEFAULT_BATTERY_SAVER = true;
    public static final boolean DEFAULT_WIFI_OFF = false;
    public static final boolean DEFAULT_GPS_OFF = false;
    public static final boolean DEFAULT_BT_OFF = false;
    public static final boolean DEFAULT_GRAYSCALE = true;

    /** ColorDisplayManager saturation levels: 0 = greyscale, 100 = normal. */
    private static final int SATURATION_GRAYSCALE = 0;
    private static final int SATURATION_FULL = 100;

    /** Live state + the snapshot to undo it, persisted so a reboot can't strand us. */
    private static final String PROP_ACTIVE = "persist.lifemode.active";
    private static final String PROP_SAVED_FILTER = "persist.lifemode.saved_filter";
    private static final String PROP_SAVED_RESTRICT = "persist.lifemode.saved_restrict";
    private static final String PROP_SAVED_BATTERY_SAVER =
            "persist.lifemode.saved_battery_saver";
    private static final String PROP_SAVED_WIFI = "persist.lifemode.saved_wifi";
    private static final String PROP_SAVED_GPS = "persist.lifemode.saved_gps";
    private static final String PROP_SAVED_BT = "persist.lifemode.saved_bt";

    /** Snapshot sentinel: we did not change this, so do not restore it. */
    private static final int UNTOUCHED = -1;

    public static boolean isSupported() {
        return DEVICE_PEPITO.equals(SystemProperties.get(PROP_DEVICE, ""));
    }

    public static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, false);
    }

    public static void register(final Context context) {
        final Context appContext = context.getApplicationContext();

        // Boot recovery: if we went down while active, put the world back
        // before anything else. No-op in the normal case.
        restore(appContext);

        // Greyscale is the one lever that is NOT persistent system state - it's a
        // runtime colour transform, so a reboot silently drops it. Re-assert it
        // here if Life Mode is still on.
        applyGrayscale(appContext);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        appContext.registerReceiver(new LifeModeController(), filter);
    }

    /** Called by the tile. Turning the master off while active exits immediately. */
    public static void setEnabled(final Context context, final boolean enabled) {
        SystemProperties.set(PROP_ENABLED, enabled ? "1" : "0");
        if (!enabled) {
            restore(context.getApplicationContext());
        }
        applyGrayscale(context.getApplicationContext());
    }

    /**
     * Greyscale follows the MASTER SWITCH, not the screen - it is the one lever
     * here that is meant to be seen. Grey while Life Mode is on, colour when it's
     * off; the point is that picking the phone up is deliberately less rewarding,
     * which is Digital Wellbeing's Bedtime Mode behavior (GMS-only, so we drive it
     * ourselves). Applying it on screen-off like everything else would be pointless
     * - nobody is looking.
     *
     * Deliberately NOT the accessibility daltonizer's Monochromacy mode, which is
     * the other way to grey the screen: that's colour-correction, and commandeering
     * it would stomp on a user who actually needs it. ColorDisplayManager's global
     * saturation is the orthogonal knob.
     *
     * Also the only lever that needs no snapshot: saturation is a runtime transform
     * with a known default (100), not persistent system state, so there is nothing
     * to strand and nothing to put back - worst case a reboot resets it to colour
     * on its own, which register() then corrects.
     */
    private static void applyGrayscale(final Context context) {
        final ColorDisplayManager cdm =
                context.getSystemService(ColorDisplayManager.class);
        if (cdm == null) {
            return;
        }
        final boolean grey = isEnabled()
                && SystemProperties.getBoolean(PROP_GRAYSCALE, DEFAULT_GRAYSCALE);
        try {
            cdm.setSaturationLevel(grey ? SATURATION_GRAYSCALE : SATURATION_FULL);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot set saturation: " + e.getMessage());
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final Context appContext = context.getApplicationContext();
        final String action = intent.getAction();

        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            if (isEnabled()) {
                enter(appContext);
            }
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            restore(appContext);
        }
    }

    private static synchronized void enter(final Context context) {
        if (SystemProperties.getBoolean(PROP_ACTIVE, false)) {
            // Already in Life Mode - a repeat SCREEN_OFF must never re-snapshot,
            // or the snapshot becomes our own Life Mode values and restore()
            // has nothing to put back.
            return;
        }

        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NetworkPolicyManager npm = NetworkPolicyManager.from(context);
        final PowerManager pm = context.getSystemService(PowerManager.class);
        final WifiManager wm = context.getSystemService(WifiManager.class);

        int savedFilter = UNTOUCHED;
        if (hasPolicyAccess(context, nm)) {
            final int current = nm.getCurrentInterruptionFilter();
            if (current != NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                savedFilter = current;
            }
        }

        int savedRestrict = UNTOUCHED;
        if (SystemProperties.getBoolean(PROP_RESTRICT_DATA, DEFAULT_RESTRICT_DATA)
                && npm != null && !npm.getRestrictBackground()) {
            savedRestrict = 0;
        }

        int savedBatterySaver = UNTOUCHED;
        if (SystemProperties.getBoolean(PROP_BATTERY_SAVER, DEFAULT_BATTERY_SAVER)
                && pm != null && !pm.isPowerSaveMode()) {
            savedBatterySaver = 0;
        }

        int savedWifi = UNTOUCHED;
        if (SystemProperties.getBoolean(PROP_WIFI_OFF, DEFAULT_WIFI_OFF)
                && wm != null && wm.isWifiEnabled()) {
            savedWifi = 1;
        }

        final LocationManager lm = context.getSystemService(LocationManager.class);
        int savedGps = UNTOUCHED;
        if (SystemProperties.getBoolean(PROP_GPS_OFF, DEFAULT_GPS_OFF)
                && lm != null && lm.isLocationEnabled()) {
            savedGps = 1;
        }

        final BluetoothAdapter bt = getBluetoothAdapter(context);
        int savedBt = UNTOUCHED;
        if (SystemProperties.getBoolean(PROP_BT_OFF, DEFAULT_BT_OFF)
                && bt != null && bt.isEnabled()) {
            savedBt = 1;
        }

        // Persist the snapshot and mark ourselves active BEFORE touching
        // anything, so a crash midway through still leaves restore() able to
        // undo whatever did land.
        SystemProperties.set(PROP_SAVED_FILTER, Integer.toString(savedFilter));
        SystemProperties.set(PROP_SAVED_RESTRICT, Integer.toString(savedRestrict));
        SystemProperties.set(PROP_SAVED_BATTERY_SAVER, Integer.toString(savedBatterySaver));
        SystemProperties.set(PROP_SAVED_WIFI, Integer.toString(savedWifi));
        SystemProperties.set(PROP_SAVED_GPS, Integer.toString(savedGps));
        SystemProperties.set(PROP_SAVED_BT, Integer.toString(savedBt));
        SystemProperties.set(PROP_ACTIVE, "1");

        Log.d(TAG, "enter: filter=" + savedFilter + " restrict=" + savedRestrict
                + " batterySaver=" + savedBatterySaver + " wifi=" + savedWifi
                + " gps=" + savedGps + " bt=" + savedBt);

        if (savedFilter != UNTOUCHED) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        }
        if (savedRestrict != UNTOUCHED) {
            npm.setRestrictBackground(true);
        }
        if (savedBatterySaver != UNTOUCHED) {
            pm.setPowerSaveModeEnabled(true);
        }
        if (savedWifi != UNTOUCHED) {
            wm.setWifiEnabled(false);
        }
        if (savedGps != UNTOUCHED) {
            setLocationEnabled(lm, false);
        }
        if (savedBt != UNTOUCHED) {
            setBluetoothEnabled(bt, false);
        }
    }

    private static synchronized void restore(final Context context) {
        if (!SystemProperties.getBoolean(PROP_ACTIVE, false)) {
            return;
        }

        final int savedFilter = SystemProperties.getInt(PROP_SAVED_FILTER, UNTOUCHED);
        final int savedRestrict = SystemProperties.getInt(PROP_SAVED_RESTRICT, UNTOUCHED);
        final int savedBatterySaver =
                SystemProperties.getInt(PROP_SAVED_BATTERY_SAVER, UNTOUCHED);
        final int savedWifi = SystemProperties.getInt(PROP_SAVED_WIFI, UNTOUCHED);
        final int savedGps = SystemProperties.getInt(PROP_SAVED_GPS, UNTOUCHED);
        final int savedBt = SystemProperties.getInt(PROP_SAVED_BT, UNTOUCHED);

        Log.d(TAG, "restore: filter=" + savedFilter + " restrict=" + savedRestrict
                + " batterySaver=" + savedBatterySaver + " wifi=" + savedWifi
                + " gps=" + savedGps + " bt=" + savedBt);

        if (savedFilter != UNTOUCHED) {
            final NotificationManager nm =
                    context.getSystemService(NotificationManager.class);
            if (hasPolicyAccess(context, nm)) {
                nm.setInterruptionFilter(savedFilter);
            }
        }
        if (savedRestrict != UNTOUCHED) {
            final NetworkPolicyManager npm = NetworkPolicyManager.from(context);
            if (npm != null) {
                npm.setRestrictBackground(savedRestrict != 0);
            }
        }
        if (savedBatterySaver != UNTOUCHED) {
            final PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null) {
                pm.setPowerSaveModeEnabled(savedBatterySaver != 0);
            }
        }
        if (savedWifi != UNTOUCHED) {
            final WifiManager wm = context.getSystemService(WifiManager.class);
            if (wm != null) {
                wm.setWifiEnabled(savedWifi != 0);
            }
        }
        if (savedGps != UNTOUCHED) {
            setLocationEnabled(context.getSystemService(LocationManager.class),
                    savedGps != 0);
        }
        if (savedBt != UNTOUCHED) {
            setBluetoothEnabled(getBluetoothAdapter(context), savedBt != 0);
        }

        SystemProperties.set(PROP_ACTIVE, "0");
    }

    private static BluetoothAdapter getBluetoothAdapter(final Context context) {
        final BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    /**
     * Location and Bluetooth are the two levers that can legitimately refuse us,
     * and both are reached from a broadcast receiver - an uncaught
     * SecurityException here would take the whole controller (and with it every
     * other lever, including the restore path) down with it. So they are the only
     * two we guard.
     *
     * setLocationEnabledForUser() wants WRITE_SECURE_SETTINGS, which is
     * signature|privileged: we get it without a privapp-permissions entry only
     * because sharedUserId=android.uid.system exempts us, exactly as LineageParts
     * does for the same permission. BluetoothAdapter.disable() wants
     * BLUETOOTH_CONNECT, which is a *runtime* permission - declaring it is not the
     * same as holding it, and the system UID is what carries us here too.
     *
     * If either ever stops being granted the knob quietly does nothing rather than
     * killing Life Mode, and says so in the log.
     */
    private static void setLocationEnabled(final LocationManager lm, final boolean enabled) {
        if (lm == null) {
            return;
        }
        try {
            lm.setLocationEnabledForUser(enabled, Process.myUserHandle());
        } catch (SecurityException e) {
            Log.w(TAG, "cannot toggle location: " + e.getMessage());
        }
    }

    private static void setBluetoothEnabled(final BluetoothAdapter bt, final boolean enabled) {
        if (bt == null) {
            return;
        }
        try {
            if (enabled) {
                bt.enable();
            } else {
                bt.disable();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cannot toggle bluetooth: " + e.getMessage());
        }
    }

    /**
     * DND access is a per-package grant, not a permission - holding
     * ACCESS_NOTIFICATION_POLICY isn't enough, setInterruptionFilter() throws
     * without it. We can hand it to ourselves because XiaomiParts is
     * sharedUserId=android.uid.system: that satisfies
     * isCallerSystemOrSystemUiOrShell() in NotificationManagerService, which
     * short-circuits the MANAGE_NOTIFICATIONS check the API otherwise requires
     * (so we don't need to hold it). The grant sticks, so this is a first-run
     * cost only, and shows up as a normal entry under
     * Settings > Special app access > Do Not Disturb access.
     */
    private static boolean hasPolicyAccess(final Context context,
            final NotificationManager nm) {
        if (nm == null) {
            return false;
        }
        if (nm.isNotificationPolicyAccessGranted()) {
            return true;
        }
        nm.setNotificationPolicyAccessGranted(context.getPackageName(), true);
        final boolean granted = nm.isNotificationPolicyAccessGranted();
        if (!granted) {
            Log.w(TAG, "no DND policy access; Life Mode will not silence");
        }
        return granted;
    }
}
