/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.emergencywatchdog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * "Emergency calls only" recovery watchdog. See PLAN-emergency-watchdog.md and
 * PLAN-emergency-only.md.
 *
 * <p>BACKGROUND. At a marginal Verizon fringe (~-110 dBm and below) LTE cell
 * acquisition fails ~88% of attempts - measured identical on both our A16 build
 * and stock A8, so this is RF physics, not a ROM regression. The modem hits
 * max-registration-failure, temporarily backoff-forbids the home PLMN
 * (reg_sim.c), and sits "emergency calls only", thrashing acquisition until an
 * attempt gets lucky. An airplane toggle "fixes it in seconds" only because it
 * clears that backoff list and forces a fresh attempt from a clean state.
 *
 * <p>WHAT THIS IS. A usability band-aid that automates the airplane toggle:
 * when the cellular side is genuinely stuck emergency-only for longer than a
 * normal backoff would take to self-clear, nudge a fresh re-acquire. It is NOT
 * a bug fix and cannot create coverage - it only recovers from the stuck state.
 *
 * <p>NUDGE, GATED. The real recovery action is OFF by default
 * ({@link #PROP_NUDGE}); until it is enabled the watchdog is a DRY RUN that only
 * detects and logs "WOULD-NUDGE", touching nothing. Enabling it performs the
 * Phase-1 mechanism - network re-selection - which is the gentlest candidate
 * (forces a fresh PLMN search / re-attach without a radio restart, so it should
 * not disturb Wi-Fi calling). If that does not clear the reg_sim DENIED/backoff,
 * the next lever is a radio power cycle (heavier; must verify WFC survives).
 *
 * <p>BATTERY. Detection is event-driven off {@link
 * TelephonyCallback.ServiceStateListener} - the modem generates those events
 * regardless of whether anyone listens (they already wake the AP), so listening
 * is free; there is no polling loop and no wakelock. The debounce timer is a
 * WAKEUP alarm ({@link AlarmManager#setExactAndAllowWhileIdle} +
 * {@link AlarmManager#ELAPSED_REALTIME_WAKEUP}) so a stick that happens while the
 * phone sleeps on the counter still gets recovered ON TIME - otherwise an
 * incoming call is missed and a non-wakeup timer would sleep right through it
 * (measured on new2 2026-09-20: a non-wakeup alarm fired 22 s late awake and not
 * at all asleep). The wakeup cost is bounded: the alarm is CANCELLED the instant
 * service returns, so a stick that self-recovers costs zero wakeups; only a
 * genuinely sustained stick fires, at most once per episode, further gated by
 * cooldown + per-boot cap. That is a few wakeups a day, nothing like a poll loop.
 * When the toggle is off (the default) we arm no timers and do no work at all.
 *
 * <p>BLIP TOLERANCE. Real sticks thrash: a genuine ~3 s in-service flicker was
 * seen mid-stick on new2 (2026-09-20). We do not let such a flicker reset the
 * clock - if emergency-only returns within {@link #BLIP_TOLERANCE_MS} we count it
 * as the same episode - so a thrashing stick fires promptly instead of being
 * perpetually deferred, while a SUSTAINED return to service ends the episode.
 *
 * <p>Hosted inside XiaomiParts because it is already {@code android:persistent}
 * and runs as {@code android.uid.system} - so it stays resident to receive the
 * callbacks and the alarm broadcast, and holds the privileged phone / exact-alarm
 * / MODIFY_PHONE_STATE permissions without a privapp-permissions allowlist entry,
 * the same way LifeModeController leans on the system UID for its levers.
 *
 * <p>Threading: the telephony callback and the alarm broadcast are both funneled
 * onto {@link #mHandler} (a private HandlerThread), so all mutable state below is
 * touched from that one thread only and needs no locking. Binder getters
 * ({@code getServiceState}/{@code getCallState}) and the nudge run there too, off
 * the main thread.
 */
public final class EmergencyWatchdogController extends TelephonyCallback
        implements TelephonyCallback.ServiceStateListener {

    private static final String TAG = "EmergencyWatchdog";
    private static final boolean DEBUG = false;

    private static final String PROP_DEVICE = "ro.vendor.xiaomi.device";
    private static final String DEVICE_PEPITO = "pepito";

    /**
     * Enable toggle, owned by Pepito Tweaks (LineageParts' GoTweaksSettings).
     * Read FRESH at every decision point so flipping the toggle takes effect
     * live, with no reboot and nothing to observe - the same read-fresh pattern
     * the persist.lifemode.* knobs use. Lives under the persist.gotweak.*
     * namespace (gotweak_prop) so the existing Pepito Tweaks write path and its
     * sepolicy already cover it. Ships DISABLED; may default on once validated.
     */
    public static final String PROP_ENABLED = "persist.gotweak.emergency_watchdog";

    /**
     * Real-nudge master switch, default OFF: with it off the watchdog is a DRY
     * RUN (detect + log "WOULD-NUDGE", touch nothing). Flip on to perform the
     * actual recovery. Read fresh at decision time so it can be toggled live for
     * the Phase-1 bake-off: `setprop persist.gotweak.ewd_nudge 1`.
     */
    private static final String PROP_NUDGE = "persist.gotweak.ewd_nudge";

    /**
     * Nudge mechanism: "rescan" (gentle, default - re-assert automatic selection)
     * or "radio" (power-cycle the radio, like a hands-free airplane toggle; heavier
     * but the only lever proven to clear the modem's stuck state). Live-tunable:
     * `setprop persist.gotweak.ewd_nudge_mech radio`.
     */
    private static final String PROP_NUDGE_MECH = "persist.gotweak.ewd_nudge_mech";
    private static final String MECH_RADIO = "radio";

    /** How long to hold the radio off during a radio-cycle nudge (ms), tunable. */
    private static final String PROP_RADIO_OFF_MS = "persist.gotweak.ewd_radio_off_ms";
    private static final int RADIO_OFF_DEFAULT_MS = 3000;

    /** Explicit action for the safety power-on alarm (delivered to RadioOnReceiver). */
    static final String ACTION_RADIO_ON =
            "org.lineageos.settings.emergencywatchdog.ACTION_RADIO_ON";

    /**
     * Debounce: only treat a stick as real after it persists this long. Shipping
     * default is 90 s - ABOVE the longest self-recovery backoff we measured
     * (stock up to 62 s; A16 7-21 s) - so we never pre-empt a recovery that would
     * have happened on its own; the point is to catch the multi-minute sticks.
     * Overridable LIVE via persist.gotweak.ewd_debounce_s (>=5 s floor), e.g.
     * `setprop persist.gotweak.ewd_debounce_s 15` to exercise the fire path
     * without waiting for a rare multi-minute stick.
     */
    private static final int DEBOUNCE_DEFAULT_S = 90;
    private static final int DEBOUNCE_MIN_S = 5;
    private static final String PROP_DEBOUNCE_S = "persist.gotweak.ewd_debounce_s";

    /** Anti-storm: minimum gap between nudges. Live-tunable; testing: set low. */
    private static final String PROP_COOLDOWN_S = "persist.gotweak.ewd_cooldown_s";
    private static final int COOLDOWN_DEFAULT_S = 900; // 15 min

    /** Anti-storm: max nudges per boot. Live-tunable. */
    private static final String PROP_MAX_PER_BOOT = "persist.gotweak.ewd_max_per_boot";
    private static final int MAX_PER_BOOT_DEFAULT = 6;

    /**
     * A return to service shorter than this does NOT end the stuck episode: real
     * sticks thrash and briefly flicker in-service (a ~3 s blip was observed on
     * new2). If emergency-only comes back within this window we continue counting
     * from the original stuck-since instead of resetting, so a thrashing stick
     * still fires. A longer return is treated as a genuine recovery.
     */
    private static final long BLIP_TOLERANCE_MS = 10_000L;

    /**
     * After a nudge, ignore emergency-only for this long before calling it a
     * failure: a radio-cycle briefly powers the radio off and then re-acquires,
     * which transiently looks emergency-only. Genuine recovery within the window
     * is still detected (and timed) via the return to service.
     */
    private static final long POST_NUDGE_GRACE_MS = 8_000L;

    /** Private action for the debounce wakeup alarm's PendingIntent broadcast. */
    private static final String ACTION_DEBOUNCE =
            "org.lineageos.settings.emergencywatchdog.ACTION_DEBOUNCE";

    private static long debounceMs() {
        return Math.max(DEBOUNCE_MIN_S,
                SystemProperties.getInt(PROP_DEBOUNCE_S, DEBOUNCE_DEFAULT_S)) * 1000L;
    }

    private static boolean nudgeEnabled() {
        return SystemProperties.getBoolean(PROP_NUDGE, false);
    }

    private static EmergencyWatchdogController sInstance;

    private final Context mAppContext;
    private final TelephonyManager mTelephonyManager;
    private final AlarmManager mAlarmManager;
    private final Handler mHandler;
    private PendingIntent mDebounceIntent;

    // Touched only on mHandler.
    private boolean mArmed;
    private long mStuckSinceElapsed;    // start of the current episode; 0 = none
    private long mLeftStuckAtElapsed;   // when we last transitioned out of stuck
    private long mLastNudgeElapsed;     // for cooldown; 0 = never nudged
    private int mNudgesThisBoot;
    private long mNudgedAtElapsed;      // when the last nudge fired; for recovery timing
    private boolean mAwaitingRecovery;  // a nudge fired; watching for the recovery

    private final BroadcastReceiver mDebounceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            // Delivered on the main thread; hop to mHandler so all state stays
            // single-threaded.
            mHandler.post(EmergencyWatchdogController.this::onDebounceElapsed);
        }
    };

    private enum State { IN_SERVICE, EMERGENCY_LIMITED, DEAD_ZONE, POWER_OFF }

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
        sInstance = new EmergencyWatchdogController(context.getApplicationContext());
        sInstance.start();
    }

    private EmergencyWatchdogController(final Context appContext) {
        mAppContext = appContext;
        mTelephonyManager = appContext.getSystemService(TelephonyManager.class);
        mAlarmManager = appContext.getSystemService(AlarmManager.class);
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    private void start() {
        if (mTelephonyManager == null || mAlarmManager == null) {
            Log.w(TAG, "telephony/alarm service missing; not starting");
            return;
        }
        final Intent intent = new Intent(ACTION_DEBOUNCE).setPackage(mAppContext.getPackageName());
        mDebounceIntent = PendingIntent.getBroadcast(mAppContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mAppContext.registerReceiver(mDebounceReceiver, new IntentFilter(ACTION_DEBOUNCE),
                Context.RECEIVER_NOT_EXPORTED);

        // Safety backstop for the radio-cycle nudge: if a prior cycle was interrupted
        // (e.g. crash during the off-window) and left the radio voted off, clear that
        // vote now so we can never boot stranded with no service - unless the user
        // actually has airplane mode on. Clearing a non-existent vote is a no-op.
        try {
            if (Settings.Global.getInt(mAppContext.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) == 0) {
                mTelephonyManager.clearRadioPowerOffForReason(
                        TelephonyManager.RADIO_POWER_REASON_USER);
            }
        } catch (Exception e) {
            Log.w(TAG, "boot radio-off recovery skipped: " + e);
        }

        // Register unconditionally (even when disabled): onServiceStateChanged is
        // pushed by the modem whether or not we listen, so a passive listener costs
        // nothing and adds no radio work. We gate every ACTION on isEnabled() read
        // fresh, so while disabled we arm no timers and do no work - dormant, yet
        // instantly live the moment the toggle flips.
        final Executor executor = r -> mHandler.post(r);
        mTelephonyManager.registerTelephonyCallback(executor, this);
        Log.i(TAG, "registered (enabled=" + isEnabled() + ", nudge=" + nudgeEnabled()
                + ", debounce=" + (debounceMs() / 1000) + "s)");
    }

    @Override
    public void onServiceStateChanged(final ServiceState serviceState) {
        final State state = classify(serviceState);
        if (DEBUG) {
            Log.d(TAG, "ss: " + describe(serviceState, state));
        }

        if (!isEnabled()) {
            cancelDebounce();
            mStuckSinceElapsed = 0;
            return;
        }

        if (state == State.EMERGENCY_LIMITED) {
            onStuck(serviceState);
        } else {
            onNotStuck(state);
        }
    }

    /** Emergency-only with a cell present: start (or continue) the stuck clock. */
    private void onStuck(final ServiceState serviceState) {
        final long now = SystemClock.elapsedRealtime();
        if (mAwaitingRecovery) {
            if (now - mNudgedAtElapsed < POST_NUDGE_GRACE_MS) {
                // Expected re-acquisition right after a nudge (a radio-cycle briefly
                // powers the radio off, then searches). Not a failure yet - keep
                // waiting to reach service rather than re-arming immediately.
                return;
            }
            mAwaitingRecovery = false;
            Log.w(TAG, "nudge did not recover (still stuck "
                    + ((now - mNudgedAtElapsed) / 1000) + "s after)");
        }
        if (mArmed) {
            return; // already counting; keep the original deadline
        }
        final boolean continuation = mStuckSinceElapsed != 0
                && (now - mLeftStuckAtElapsed) <= BLIP_TOLERANCE_MS;
        if (!continuation) {
            mStuckSinceElapsed = now; // fresh episode
        }
        mArmed = true;
        final long deadline = mStuckSinceElapsed + debounceMs();
        // WAKEUP + allow-while-idle: fires ON TIME even in Doze / screen-off.
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                Math.max(deadline, now), mDebounceIntent);
        Log.i(TAG, "armed: emergency-only, cell present"
                + (continuation ? " (continued through blip)" : "")
                + ", fires in " + (Math.max(0, deadline - now) / 1000) + "s, op="
                + serviceState.getOperatorNumeric());
    }

    /**
     * Anything that is not emergency-only (in-service, dead zone, radio off).
     * Cancel the wakeup so a self-recovering stick costs zero wakeups, but keep
     * mStuckSinceElapsed and record when we left, so a brief flicker back to
     * emergency-only within BLIP_TOLERANCE_MS continues the same episode rather
     * than resetting the clock.
     */
    private void onNotStuck(final State state) {
        if (state == State.IN_SERVICE && mAwaitingRecovery) {
            mAwaitingRecovery = false;
            final long dt = (SystemClock.elapsedRealtime() - mNudgedAtElapsed) / 1000;
            Log.w(TAG, "recovered " + dt + "s after nudge");
        }
        if (mArmed) {
            cancelDebounce();
            mLeftStuckAtElapsed = SystemClock.elapsedRealtime();
            final long stuckFor = (mLeftStuckAtElapsed - mStuckSinceElapsed) / 1000;
            Log.i(TAG, "paused: " + state + " (was emergency-only ~" + stuckFor + "s)");
        }
    }

    private void cancelDebounce() {
        if (!mArmed) {
            return;
        }
        mArmed = false;
        mAlarmManager.cancel(mDebounceIntent);
    }

    private void onDebounceElapsed() {
        mArmed = false;
        if (!isEnabled()) {
            Log.i(TAG, "fire: disabled since arming; skip");
            mStuckSinceElapsed = 0;
            return;
        }

        final ServiceState serviceState = mTelephonyManager.getServiceState();
        final State state = serviceState == null ? State.DEAD_ZONE : classify(serviceState);
        final long stuckFor = (SystemClock.elapsedRealtime() - mStuckSinceElapsed) / 1000;

        if (state != State.EMERGENCY_LIMITED) {
            Log.i(TAG, "fire: recovered/no-cell (" + state + ") after " + stuckFor + "s; skip");
            mStuckSinceElapsed = 0;
            return;
        }

        final int callState = mTelephonyManager.getCallState();
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            // Never nudge during a call (an emergency call can be in progress in
            // exactly this state). Reset; a later ss change re-arms after the call.
            Log.i(TAG, "fire: call not idle (" + callState + ") after " + stuckFor + "s; skip");
            mStuckSinceElapsed = 0;
            return;
        }

        // ===== THE DECISION =====
        if (!nudgeEnabled()) {
            Log.w(TAG, "WOULD-NUDGE: stuck emergency-only " + stuckFor
                    + "s, call idle, cell present -- nudge disabled, no action -- "
                    + describe(serviceState, state));
            mStuckSinceElapsed = 0;
            return;
        }

        // Anti-storm: per-boot cap, then cooldown.
        final int maxPerBoot = SystemProperties.getInt(PROP_MAX_PER_BOOT, MAX_PER_BOOT_DEFAULT);
        if (mNudgesThisBoot >= maxPerBoot) {
            Log.w(TAG, "skip nudge: per-boot cap reached (" + mNudgesThisBoot + "/"
                    + maxPerBoot + ") after " + stuckFor + "s stuck");
            mStuckSinceElapsed = 0;
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final long cooldownMs = Math.max(0,
                SystemProperties.getInt(PROP_COOLDOWN_S, COOLDOWN_DEFAULT_S)) * 1000L;
        if (mLastNudgeElapsed != 0 && (now - mLastNudgeElapsed) < cooldownMs) {
            final long left = (cooldownMs - (now - mLastNudgeElapsed)) / 1000;
            Log.w(TAG, "skip nudge: cooldown (" + left + "s left) after " + stuckFor + "s stuck");
            mStuckSinceElapsed = 0;
            return;
        }

        nudge(stuckFor, serviceState);
        mStuckSinceElapsed = 0;
    }

    /**
     * The recovery action. Phase-1 mechanism = network re-selection (the gentlest
     * candidate): force selection mode back to automatic, which triggers a fresh
     * PLMN search / re-attach without a radio restart, so it should not disturb
     * Wi-Fi calling. Guarded so a failure can never take the process (mHandler
     * thread) down. If this proves not to clear the reg_sim DENIED/backoff, the
     * next lever is a radio power cycle (setRadioPower off/on) - heavier, and its
     * WFC survival must be verified first.
     */
    private void nudge(final long stuckFor, final ServiceState serviceState) {
        mLastNudgeElapsed = SystemClock.elapsedRealtime();
        mNudgedAtElapsed = mLastNudgeElapsed;
        mNudgesThisBoot++;
        mAwaitingRecovery = true;
        final String mech = SystemProperties.get(PROP_NUDGE_MECH, "rescan");
        try {
            if (MECH_RADIO.equals(mech)) {
                radioCycle();
                Log.w(TAG, "NUDGE #" + mNudgesThisBoot + " (radio-cycle) after stuck " + stuckFor
                        + "s -- radio OFF, ON in " + (radioOffMs() / 1000) + "s -- "
                        + describe(serviceState, State.EMERGENCY_LIMITED));
            } else {
                mTelephonyManager.setNetworkSelectionModeAutomatic();
                Log.w(TAG, "NUDGE #" + mNudgesThisBoot + " (rescan) after stuck " + stuckFor
                        + "s -- setNetworkSelectionModeAutomatic -- "
                        + describe(serviceState, State.EMERGENCY_LIMITED));
            }
        } catch (Exception e) {
            mAwaitingRecovery = false;
            Log.e(TAG, "NUDGE (" + mech + ") FAILED: " + e);
        }
    }

    private static long radioOffMs() {
        return Math.max(1000, Math.min(10000,
                SystemProperties.getInt(PROP_RADIO_OFF_MS, RADIO_OFF_DEFAULT_MS)));
    }

    /**
     * Radio power-cycle nudge: schedule the power-ON first (so it is guaranteed
     * pending before the radio goes off, even if the next call throws), then vote
     * the radio OFF. The ON is a WAKEUP alarm to RadioOnReceiver (a manifest
     * receiver), so it fires on time even in Doze AND survives a process death
     * during the off-window - the radio can never be stranded off. This is the
     * hands-free equivalent of an airplane toggle; verify WFC survives it before
     * trusting it (the WFC lane warns airplane-cycling can wipe runtime WFC
     * provisioning).
     */
    private void radioCycle() {
        final Intent onIntent =
                new Intent(ACTION_RADIO_ON).setClass(mAppContext, RadioOnReceiver.class);
        final PendingIntent onPi = PendingIntent.getBroadcast(mAppContext, 1, onIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + radioOffMs(), onPi);
        mTelephonyManager.requestRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER);
    }

    /**
     * Classify from the WWAN transport specifically, NOT the aggregate
     * ServiceState: under Wi-Fi calling the aggregate can read in-service via
     * IWLAN while the cellular side is stuck, which would hide exactly the
     * condition we care about.
     */
    private State classify(final ServiceState serviceState) {
        final NetworkRegistrationInfo ps = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        final NetworkRegistrationInfo cs = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        final boolean registered = (ps != null && ps.isRegistered())
                || (cs != null && cs.isRegistered());
        if (registered) {
            return State.IN_SERVICE;
        }
        if (serviceState.getState() == ServiceState.STATE_POWER_OFF) {
            return State.POWER_OFF;
        }
        // emergency-only == camped on a cell but limited to emergency service:
        // the modem CAN see a cell (signal present), it is just backoff-forbidden
        // from attaching. That is our nudge target. Not-registered with NO
        // emergency == searching / no serving cell == a real dead zone, where a
        // nudge is pointless (and wastes energy), so we do not treat it as stuck.
        final boolean emergency = (cs != null && cs.isEmergencyEnabled())
                || (ps != null && ps.isEmergencyEnabled());
        return emergency ? State.EMERGENCY_LIMITED : State.DEAD_ZONE;
    }

    private static String describe(final ServiceState serviceState, final State state) {
        final NetworkRegistrationInfo ps = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        final NetworkRegistrationInfo cs = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        return state + " [agg=" + serviceState.getState()
                + " op=" + serviceState.getOperatorNumeric()
                + " ps=" + regStr(ps) + " cs=" + regStr(cs) + "]";
    }

    private static String regStr(final NetworkRegistrationInfo nri) {
        if (nri == null) {
            return "null";
        }
        return "reg" + nri.getRegistrationState()
                + (nri.isEmergencyEnabled() ? "+emg" : "")
                + "/rat" + nri.getAccessNetworkTechnology();
    }
}
