/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.notificationled;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

/**
 * The PVG100's white notification LED costs about 10 mA while lit, against a
 * ~3 mA screen-off floor, so it ships with "Blink light" off. The
 * SettingsProvider default only applies to a fresh database, and upgraded
 * phones have the AOSP default (on) from before the LED existed, so turn it off
 * once here; after that it's the user's setting.
 */
public final class NotificationLedDefaults {

    private static final String TAG = "NotificationLedDefaults";

    private static final String PROP_DEVICE = "ro.vendor.xiaomi.device";
    private static final String DEVICE_PEPITO = "pepito";

    private static final String PREFS = "notification_led";
    private static final String KEY_DEFAULT_APPLIED = "default_off_applied";

    private NotificationLedDefaults() {}

    public static boolean isSupported() {
        return DEVICE_PEPITO.equals(SystemProperties.get(PROP_DEVICE, ""));
    }

    /** Called from BootCompletedReceiver on pepito. */
    public static void applyOnce(final Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_DEFAULT_APPLIED, false)) {
            return;
        }
        if (Settings.System.putInt(context.getContentResolver(),
                Settings.System.NOTIFICATION_LIGHT_PULSE, 0)) {
            prefs.edit().putBoolean(KEY_DEFAULT_APPLIED, true).apply();
            Log.i(TAG, "Notification light defaulted to off");
        }
    }
}
