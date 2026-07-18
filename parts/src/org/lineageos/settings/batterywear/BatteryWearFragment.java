/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.batterywear;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.lineageos.settings.R;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads the fuel-gauge's per-SoC-bucket cycle-count histogram (exposed by the
 * qpnp-fg driver at /sys/class/power_supply/bms/cycle_counts) and renders it as
 * one bar per 12.5% battery-level band — a "where did this battery get cycled"
 * wear profile.
 */
public class BatteryWearFragment extends PreferenceFragmentCompat {

    private static final String NODE = "/sys/class/power_supply/bms/cycle_counts";
    private static final int BUCKETS = 8;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context ctx = getPreferenceManager().getContext();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
        setPreferenceScreen(screen);

        final int[] counts = readBuckets();
        if (counts == null) {
            final Preference unavailable = new Preference(ctx);
            unavailable.setSelectable(false);
            unavailable.setTitle(R.string.battery_wear_unavailable);
            screen.addPreference(unavailable);
            return;
        }

        int max = 1;
        for (int c : counts) {
            if (c > max) {
                max = c;
            }
        }

        final Preference header = new Preference(ctx);
        header.setSelectable(false);
        header.setTitle(R.string.battery_wear_header_title);
        header.setSummary(getString(R.string.battery_wear_header_summary, max));
        screen.addPreference(header);

        for (int i = 0; i < BUCKETS; i++) {
            final int lo = i * 100 / BUCKETS;
            final int hi = (i + 1) * 100 / BUCKETS;
            final BucketBarPreference bar = new BucketBarPreference(ctx);
            bar.setBand(getString(R.string.battery_wear_band_fmt, lo, hi), counts[i], max);
            screen.addPreference(bar);
        }

        final Preference footer = new Preference(ctx);
        footer.setSelectable(false);
        footer.setSummary(R.string.battery_wear_footer);
        screen.addPreference(footer);
    }

    private static int[] readBuckets() {
        final int[] out = new int[BUCKETS];
        try (BufferedReader reader = new BufferedReader(new FileReader(NODE))) {
            final String line = reader.readLine();
            if (line == null) {
                return null;
            }
            final String[] parts = line.trim().split("\\s+");
            for (int i = 0; i < BUCKETS && i < parts.length; i++) {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                    // leave as 0
                }
            }
        } catch (IOException e) {
            return null;
        }
        return out;
    }
}
