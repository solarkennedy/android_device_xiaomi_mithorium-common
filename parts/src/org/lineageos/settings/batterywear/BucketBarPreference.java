/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.batterywear;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.lineageos.settings.R;

/**
 * A non-interactive preference row showing one SoC band's label, a horizontal
 * bar proportional to its cycle count, and the count itself.
 */
public class BucketBarPreference extends Preference {

    private CharSequence mBandLabel;
    private int mValue;
    private int mMax = 1;

    public BucketBarPreference(Context context) {
        super(context);
        init();
    }

    public BucketBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.battery_wear_bucket);
        setSelectable(false);
    }

    public void setBand(CharSequence label, int value, int max) {
        mBandLabel = label;
        mValue = value;
        mMax = Math.max(1, max);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);

        final TextView label = (TextView) holder.findViewById(R.id.band_label);
        if (label != null) {
            label.setText(mBandLabel);
        }

        final TextView count = (TextView) holder.findViewById(R.id.band_count);
        if (count != null) {
            count.setText(String.valueOf(mValue));
        }

        final ProgressBar bar = (ProgressBar) holder.findViewById(R.id.band_bar);
        if (bar != null) {
            bar.setMax(mMax);
            bar.setProgress(mValue);
        }
    }
}
