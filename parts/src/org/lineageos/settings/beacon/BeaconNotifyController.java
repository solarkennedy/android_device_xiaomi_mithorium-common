/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.beacon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.icu.text.BreakIterator;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * BLE-beacon push notifications. See PLAN-ble-beacon-notify.md.
 *
 * <p>Turns a private manufacturer-data advertisement into a notification with no
 * app, no pairing and no internet. The point of doing it in the ROM is the scan
 * shape: a filtered <em>batch</em> scan. The Pronto controller matches the filter
 * (APCF; pepito reports max_filter=16) and stores hits in its own memory, and the
 * stack flushes that store on a wakeup alarm. Batch clients all ride ONE radio duty
 * cycle, so next to the batch scan GMS Find My Device keeps running this costs no
 * extra radio time at all (CT-3, 09-22: a second regular scan measured +10 mW; a
 * batch client shares the existing one). The price is latency: hits are delivered
 * at the flush, whose interval is the smallest reportDelayMillis among the batch
 * clients (floor 20 s screen-off) with a 1,1,2,2,4 backoff on empty flushes. Each
 * flush is a full system wake, and that is the remaining cost: at a 5-minute delay
 * the CT-3 measured +6 mW (about 5 extra wakes an hour); {@link #REPORT_DELAY_MS}
 * matches Find My Device's own 20 minutes so our flushes coincide with the ones
 * already happening. The trade is 20..40 minutes of latency. Drop it to 5 minutes
 * for a doorbell-class use, and expect ~+6 mW. On a unit with no other batch
 * client the flush alarm is our own cost whatever the delay. Batch scans need offloaded
 * filtering; on a controller without it startScan fails, which we log.
 *
 * <p>Over the air (legacy 31-byte advert, company id 0xFFFF):
 * <pre>  'P' 'V' | txid | seq | text (UTF-8, to end of field)</pre>
 * If the text opens with a symbol or emoji ("\uD83D\uDEAA Garage open") that glyph is
 * lifted out and becomes the notification's icon - see {@link #renderGlyph}.
 * seq identifies the event (see {@link #DEDUPE_WINDOW_MS}); what the event means is
 * simply the text. That leaves 23 bytes of text. A transmitter that wants more makes the advert
 * scannable and puts up to 29 further bytes in the scan response's Complete Local
 * Name field; Pronto hands both packets over in the one on-found event (verified by
 * btsnoop on pepito) and we append the name to the text, for 52 bytes in all.
 * The hardware filter is company id + the two magic bytes, so one APCF slot covers
 * every transmitter. There is deliberately no authentication (PLAN §5): anyone in
 * radio range who knows the format can post a notification.
 *
 * <p>Ships OFF ({@link #PROP_ENABLED}). While off nothing is registered with the
 * Bluetooth stack and no notification channel exists; the only residue is a
 * receiver for adapter state changes and for {@link #ACTION_REEVALUATE}, which is
 * how a prop flip goes live without a reboot:
 * <pre>  setprop persist.gotweak.ble_beacon 1
 *  am broadcast -a org.lineageos.settings.beacon.REEVALUATE</pre>
 *
 * <p>Threading: everything runs on {@link #mHandler} (a private HandlerThread).
 */
public final class BeaconNotifyController {

    private static final String TAG = "BeaconNotify";

    private static final String PROP_DEVICE = "ro.vendor.xiaomi.device";
    private static final String DEVICE_PEPITO = "pepito";

    /** Pepito Tweaks toggle. Default OFF. Covered by gotweak_prop sepolicy. */
    public static final String PROP_ENABLED = "persist.gotweak.ble_beacon";
    private static final String PROP_SOLO = "persist.gotweak.ble_beacon_solo";

    /**
     * Poke to re-read {@link #PROP_ENABLED}. Exported so LineageParts (a different
     * uid) and the shell can send it, but guarded by WRITE_SECURE_SETTINGS, which
     * both hold and third-party apps cannot.
     */
    public static final String ACTION_REEVALUATE = "org.lineageos.settings.beacon.REEVALUATE";
    private static final String POKE_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private static final int COMPANY_ID = 0xFFFF; // reserved for testing / unassigned
    private static final byte[] MAGIC = { 'P', 'V' };
    private static final byte[] MAGIC_MASK = { (byte) 0xFF, (byte) 0xFF };
    private static final int HEADER_LEN = 4; // magic(2) txid seq

    // Silent by design: delivery already trails the event by minutes to hours (we
    // only hear beacons during other apps' scans), so nothing here is worth a sound,
    // and a system-UID app's channels can't be edited by the user in Settings anyway.
    private static final String CHANNEL_ID = "beacon";
    private static final String CHANNEL_NAME = "Beacon";
    private static final int GLYPH_COLOR = 0xFF757575;
    private static final int GLYPH_DP = 48;
    private static final long WAKELOCK_TIMEOUT_MS = 2000;
    private static final long REPORT_DELAY_MS = 20 * 60 * 1000L;
    private static final long POLL_MS = 3 * 60 * 1000L;

    /**
     * One event = one (txid, seq). Every sighting of it inside this window is a repeat:
     * a batch flush carries every advert heard during a burst (dozens of copies), and
     * the transmitter may deliberately re-burst for reliability. Time-boxed rather than
     * forever so a transmitter whose seq restarts (reboot, 8-bit wrap) cannot have a new
     * event mistaken for an old one.
     */
    private static final long DEDUPE_WINDOW_MS = 10 * 60 * 1000L;

    private static BeaconNotifyController sInstance;

    private final Context mAppContext;
    private final Handler mHandler;
    private final PowerManager.WakeLock mWakeLock;

    private BluetoothLeScanner mScanner; // non-null while a scan is registered
    private boolean mChannelCreated;
    // De-dupe state, indexed by txid: the last seq notified and when (elapsedRealtime).
    private final int[] mLastSeq = new int[256];
    private final long[] mLastSeqAt = new long[256];

    /** pepito-only, same gate LifeModeController uses. */
    public static boolean isSupported() {
        return DEVICE_PEPITO.equals(SystemProperties.get(PROP_DEVICE, ""));
    }

    private static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, false);
    }

    /** Called once from BootCompletedReceiver on pepito. */
    public static synchronized void register(final Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new BeaconNotifyController(context.getApplicationContext());
        sInstance.start();
    }

    private BeaconNotifyController(final Context appContext) {
        mAppContext = appContext;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mWakeLock = appContext.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
    }

    private void start() {
        // Two registrations because the senders differ. ACTION_STATE_CHANGED comes from
        // the Bluetooth app's own uid (not system_server), so a NOT_EXPORTED receiver
        // never sees it; it is a protected broadcast, so EXPORTED is safe. The poke is
        // ours, so it carries the sender-permission guard instead.
        mAppContext.registerReceiver(mStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, mHandler,
                Context.RECEIVER_EXPORTED);
        mAppContext.registerReceiver(mPokeReceiver, new IntentFilter(ACTION_REEVALUATE),
                POKE_PERMISSION, mHandler, Context.RECEIVER_EXPORTED);
        mHandler.post(this::reevaluate);
    }

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) {
                reevaluate();
            } else if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                mScanner = null; // the stack dropped our scanner with the adapter
            }
        }
    };

    private final BroadcastReceiver mPokeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            reevaluate();
        }
    };

    private void reevaluate() {
        mHandler.removeCallbacks(mPoll);
        final boolean enabled = isEnabled();
        // Only ride a scan somebody else is already running (see class comment):
        // a batch scan of our own costs ~7 mW, joining one costs nothing.
        // persist.gotweak.ble_beacon_solo=1: run our own batch scan instead of waiting
        // to join someone else's (~7 mW, hears every 20 min regardless of Find My).
        // Bench control for "is the join path capturing anything", and the mode for a
        // unit without Google services.
        final boolean want = enabled && (SystemProperties.getBoolean(PROP_SOLO, false)
                || otherBatchScanRunning());
        if (want != (mScanner != null)) {
            if (want) {
                startScan();
            } else {
                stopScan();
            }
        }
        if (enabled) {
            // postDelayed rides the handler's uptime clock, so this never wakes the
            // AP by itself; we notice a scan starting or stopping the next time the
            // phone is awake anyway.
            mHandler.postDelayed(mPoll, POLL_MS);
        }
    }

    private final Runnable mPoll = this::reevaluate;

    /**
     * Whether another client currently has a batch scan running. There is no query
     * for this on IBluetoothScan and BatteryStats blames the shared scan on whoever
     * won its parameters (us, once we join), so read the Bluetooth service's own
     * per-client bookkeeping: its dump lists each app's "Ongoing" scans.
     */
    private boolean otherBatchScanRunning() {
        final IBinder bt = ServiceManager.checkService("bluetooth_manager");
        if (bt == null) {
            return false;
        }
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            bt.dumpAsync(pipe[1].getFileDescriptor(), new String[0]);
            pipe[1].close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(pipe[0].getFileDescriptor()), StandardCharsets.UTF_8))) {
                String app = null;
                boolean ongoing = false;
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.endsWith("(Registered):") || line.endsWith("(Unregistered):")) {
                        app = line.trim();
                        ongoing = false;
                    } else if (line.contains("Ongoing ")) {
                        ongoing = true;
                    } else if (ongoing && line.contains("Batch Scan")
                            && app != null && !app.startsWith("android.uid.system")) {
                        pipe[0].close();
                        return true;
                    }
                }
            }
            pipe[0].close();
        } catch (Exception e) {
            Log.w(TAG, "bluetooth dump failed: " + e);
        }
        return false;
    }

    private void startScan() {
        final BluetoothManager bm = mAppContext.getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.i(TAG, "enabled, but Bluetooth is off; will arm on STATE_ON");
            return;
        }
        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "no LE scanner");
            return;
        }
        final ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID, MAGIC, MAGIC_MASK)
                .build();
        final ScanSettings settings = new ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(REPORT_DELAY_MS)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, mScanCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "startScan failed: " + e);
            return;
        }
        mScanner = scanner;
        Log.i(TAG, "scan armed (offloadedFiltering=" + adapter.isOffloadedFilteringSupported()
                + ")");
    }

    private void stopScan() {
        try {
            mScanner.stopScan(mScanCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "stopScan failed: " + e);
        }
        mScanner = null;
        Log.i(TAG, "scan disarmed");
    }

    // Binder callbacks land on the main thread; hop to ours.
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, final ScanResult result) {
            mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            final long realtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> handleResult(callbackType, result, realtime, uptime));
        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            final long realtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                Log.i(TAG, "batch flush: " + results.size() + " results");
                for (ScanResult r : results) {
                    handleResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r, realtime, uptime);
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Log.w(TAG, "onScanFailed: " + errorCode);
            mHandler.post(() -> mScanner = null);
        }
    };

    private void handleResult(final int callbackType, final ScanResult result,
            final long realtime, final long uptime) {
        final String addr = result.getDevice().getAddress();
        final ScanRecord record = result.getScanRecord();
        final byte[] data = record != null ? record.getManufacturerSpecificData(COMPANY_ID) : null;
        if (data == null || data.length < HEADER_LEN) {
            Log.i(TAG, "FOUND " + addr + " but payload too short; dropped");
            return;
        }
        final int txId = data[2] & 0xFF;
        final int seq = data[3] & 0xFF;
        String text = new String(data, HEADER_LEN, data.length - HEADER_LEN,
                StandardCharsets.UTF_8);
        final String more = record.getDeviceName(); // scan-response continuation, if any
        if (more != null) {
            text += more;
        }
        // rt - up = time spent suspended since boot; the stack-side age says how long
        // the report sat between the Bluetooth process and us.
        final long stackAgeMs = (SystemClock.elapsedRealtimeNanos() - result.getTimestampNanos())
                / 1_000_000L;
        Log.i(TAG, "FOUND " + addr + " rssi=" + result.getRssi() + " tx=" + txId + " seq=" + seq
                + " text=\"" + text + "\" rt=" + realtime
                + " up=" + uptime + " stackAgeMs=" + stackAgeMs);
        if (mLastSeqAt[txId] != 0 && mLastSeq[txId] == seq
                && realtime - mLastSeqAt[txId] < DEDUPE_WINDOW_MS) {
            Log.i(TAG, "repeat of tx=" + txId + " seq=" + seq + "; dropped");
            return;
        }
        mLastSeq[txId] = seq;
        mLastSeqAt[txId] = realtime;
        if (!isEnabled()) {
            return; // switched off without a poke; stay quiet until reevaluate() disarms
        }
        post(txId, seq, text);
    }

    /**
     * The first grapheme cluster of {@code text} if it is a symbol (general category So:
     * emoji, dingbats, arrows-with-meaning, Noto Sans Symbols fare), else null. Cluster
     * rather than code point so VS16, skin tones and ZWJ sequences come along whole.
     */
    private static String leadingGlyph(final String text) {
        if (text.isEmpty() || Character.getType(text.codePointAt(0)) != Character.OTHER_SYMBOL) {
            return null;
        }
        final BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        final int end = it.next();
        return end == BreakIterator.DONE ? null : text.substring(0, end);
    }

    /**
     * Draws the glyph with the system fonts. As a small icon SystemUI keeps only the
     * alpha channel, so a colour emoji shows as its silhouette in the status bar; as the
     * large icon the same bitmap keeps its colours. {@code color} only matters for
     * monochrome symbols, which would otherwise be white on a light shade.
     */
    private Bitmap renderGlyph(final String glyph, final int color) {
        final int size = Math.round(
                GLYPH_DP * mAppContext.getResources().getDisplayMetrics().density);
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * 0.8f);
        final Paint.FontMetrics fm = paint.getFontMetrics();
        new Canvas(bitmap).drawText(glyph, size / 2f,
                (size - fm.ascent - fm.descent) / 2f, paint);
        return bitmap;
    }

    private void post(final int txId, final int seq, final String text) {
        final NotificationManager nm = mAppContext.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!mChannelCreated) {
            // Lazily, so a unit that never enables the feature never grows a channel.
            final NotificationChannel c = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            c.setSound(null, null);
            c.enableVibration(false);
            nm.createNotificationChannel(c);
            // Earlier builds had one channel per severity; tidy them off units that ran them.
            for (String old : new String[] { "beacon_ok", "beacon_warning", "beacon_critical" }) {
                nm.deleteNotificationChannel(old);
            }
            mChannelCreated = true;
        }
        // A leading symbol is the icon, not part of the message.
        final String glyph = leadingGlyph(text);
        final String shown = glyph != null ? text.substring(glyph.length()).trim() : text;
        final String body = shown.isEmpty() ? "Event " + seq : shown;
        final Notification.Builder b = new Notification.Builder(mAppContext, CHANNEL_ID);
        if (glyph != null) {
            final Bitmap bitmap = renderGlyph(glyph, GLYPH_COLOR);
            b.setSmallIcon(Icon.createWithBitmap(bitmap));
            b.setLargeIcon(bitmap);
        } else {
            b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth);
        }
        final Notification n = b
                .setContentTitle("Beacon " + txId)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)) // 52 bytes wraps
                .setShowWhen(true)
                .setAutoCancel(true)
                .build();
        // One slot per event, so events stack as a history rather than replacing each
        // other. (A seq reused after the de-dupe window overwrites its old self.)
        nm.notify(TAG, (txId << 8) | seq, n);
    }
}
