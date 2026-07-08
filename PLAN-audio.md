# Audio & Sensor Boot-Block Fix Plan — pepito (PVG100)

> **2026-07-02:** This file is the historical record of the 06-06 boot-block work.
> Current audio status + staged fixes live in the top-level `PLAN-audio.md`:
> audio plays through the speaker; remaining issues are the false "Wired headphones"
> label (MBHC false LINEOUT — see correction in Issue 3 below) and the Issue 4 ACDB
> version mismatch (still reproduces, verified live).

## Current Boot Status

System_server reaches running state but never sets `sys.boot_completed`. It is killed
by the Android Watchdog after ~6 minutes and restarts via zygote in an infinite loop.

### Confirmed Blocker Chain

```
android.hardware.sensors@1.0-service (pid 892)
  → never registers android.hardware.sensors@1.0::ISensors/default
      → sensorservice never starts
          → SystemSensorManager.nativeCreate() blocks system_server forever
              → Watchdog kills system_server after ~6 min
                  → zygote restarts system_server → repeat
```

---

## Boot Blocker 1 (PRIMARY): Sensor HAL not registering

### Evidence
- `android.hardware.sensors@1.0-service` has been running since boot (PID 892, ~615s),
  sleeping, but has never registered `android.hardware.sensors@1.0::ISensors/default`.
- `sensors.qti` (PID 966) likewise sleeping without producing output.
- `system_server` thread stuck in `android.hardware.SystemSensorManager.nativeCreate(Native Method)`.
- Watchdog fires → system_server killed → zygote restarts → repeat.
- ADSP is confirmed up (`adsp: Brought out of reset`, `fastrpc_rpmsg_probe`).
- `/dev/sensors` char device exists and has correct permissions.
- No SELinux denials from `hal_sensors_default` domain.

### Root Cause (probable)
`hals.conf` → `sensors.ssc.so` → loaded from `/vendor/lib64/sensors.ssc.so`.
`sensors.ssc.so` connects to `sensors.qti` which in turn talks to the ADSP via QMI.
One of the following is preventing registration:
- Missing sensor registry/configuration files in `/persist/sensors/` or `/vendor/etc/sensors/`
- `sensors.ssc.so` waiting for a QMI service from `sensors.qti` that never becomes ready
- `sensors.ssc.so` is 64-bit only (no 32-bit) but the HAL impl expects both variants

### Diagnostic TODO
- [ ] Dump `/proc/892/stack` in permissive mode to see kernel wait channel
- [ ] Check `sensors.qti` stdout/stderr via `adb shell logcat -b all | grep -i qti`
- [ ] Check `/persist/` for sensor registry files
- [ ] Check if `sensors.ssc.so` needs to be in `/vendor/lib64/hw/` rather than `/vendor/lib64/`
- [ ] Try adding a symlink `mkdir -p /vendor/lib64/hw && ln -s ../sensors.ssc.so /vendor/lib64/hw/sensors.ssc.so` live on device to test

### Fix Option A — Stub sensor HAL (fastest path to boot)
Replace `hals.conf` with an empty file (or remove sensors.ssc reference).
`android.hardware.sensors@1.0-impl.so` with an empty HAL list will register with
zero sensors, unblocking `sensorservice` → boot completes.
Individual sensor drivers can be debugged post-boot.

**Status: ✅ DONE (2026-06-06)**

**Implementation:**
- `device/xiaomi/mithorium-common/configs/sensors/hals.conf` — empty file
- `mithorium.mk` installs it to `/vendor/etc/sensors/hals.conf`, overriding the
  vendor blob copy that contained `sensors.ssc.so`
```makefile
# Empty hals.conf so sensorservice starts with no sensors rather than blocking
# on sensors.ssc.so (which is not present for pepito). Overrides the vendor copy.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/sensors/hals.conf:$(TARGET_COPY_OUT_VENDOR)/etc/sensors/hals.conf
```

### Fix Option B — Correct sensors.ssc.so install path
If the issue is path (needs to be in `hw/` subdir), add `relative_install_path: "hw"`
to the `sensors.ssc` entry in `vendor/xiaomi/Mi8937/Android.bp`.
Rebuild and flash vendor.

### Fix Option C — Add sensors.ssc init dependency
Check if `init.qcom.sensors.sh` is missing for pepito. On other Mi8937 targets this
script may configure the sensor persist partition. Add a pepito case to
`init.xiaomi.device.sh` or create a separate init rc for sensor setup.

---

## Boot Blocker 2 (SECONDARY): Audio HAL — mixer_paths.xml not found

**Status:** Audio HAL fails on every boot. Does not prevent boot directly (APM falls back
to "default phone experience"), but results in no audio and contributes to other
service failures. *Confirmed NOT the primary boot blocker — sensor HAL is.*

### Evidence (from logcat.boot.1.txt)
```
D msm8974_platform: platform_init: Loading mixer file: mixer_paths.xml
E audio_route: Failed to open mixer_paths.xml: No such file or directory
E msm8974_platform: platform_init: Failed to init audio route controls, aborting.
E audio_hw_primary: adev_open: Failed to init platform data, aborting.
```

### Root Cause (fully confirmed)

**Sound card name:** `msm8952-snd-card-mtp` (from `pepito/audio.dtsi`, line 91)

**HAL lookup logic** (`hardware/qcom/audio/hal/msm8974/platform.c:1774`):
```
audio_extn_set_snd_card_split("msm8952-snd-card-mtp")
  → device="msm8952", snd_card="snd", form_factor="card"
HAL tries (in order):
  1. mixer_paths_snd_card.xml  → not found
  2. mixer_paths_snd.xml        → not found
  3. mixer_paths.xml            → not found → ABORT
```

**`vendor.audio.mixer_xml.path` is dead code for this HAL:**
`grep -rn "vendor.audio.mixer" hardware/qcom/audio/hal/` → zero results.
The property set by `init.xiaomi.device.rc` per device is never read.

**`init.xiaomi.device.rc` has no pepito case:**
`ro.vendor.xiaomi.device` is never set for pepito because `init.xiaomi.device.sh`
has no `"pepito"` case → no audio trigger fires.

**No `mixer_paths.xml` exists anywhere in the tree** (grep confirmed).

### Fix

**Status: ✅ Step 2 DONE (2026-06-06)**

Two changes needed:

**1. ✅ Add pepito to `device/xiaomi/Mi8937/rootdir/bin/init.xiaomi.device.sh` (done 2026-06-06):**

ACDB files from stock Palm Android 8.1 MTP set installed at
`device/xiaomi/Mi8937/audio/acdbdata/pepito/` (7 files: Bluetooth, General, Global,
Handset, Hdmi, Headset, Speaker). Installed to `/vendor/etc/acdbdata/pepito/` via
`find-copy-subdir-files` in `device.mk`. `init.xiaomi.device.sh` pepito case calls
`set_acdb_path_props pepito` which enumerates all files in that directory and sets
`persist.vendor.audio.calfile{0..6}` props at boot.

Note: `init.acdbdata.sh` (the mithorium-common DTS-based mechanism) is NOT used for
pepito — pepito's DTS compatible string lacks `qcom,mtp` and `qcom,qrd`, so that
script exits with "Unable to determine board type". The `init.xiaomi.device.sh`
mechanism (consistent with all other Mi8937 variants) is used instead.

**2. ✅ Install `mixer_paths.xml` to `/vendor/etc/`** (what the HAL's fallback looks for — done 2026-06-06):

**Use the stock Palm Android 8 file — NOT prada's.**

The stock Palm `mixer_paths.xml` (extracted from
`backup-stock-android-8.1-AML0/vendor.bin.extracted/etc/mixer_paths.xml`) is the exact
file tuned for pepito's hardware: correct speaker routing (`SPK=Switch` via internal codec),
correct mic ADC paths, correct earpiece gain, and WSA amp paths matching pepito's topology.
Prada's file (1685 lines vs Palm's 1133) has extra controls for prada-specific hardware
and tuning values calibrated for prada's speaker/mic layout — wrong for pepito.

The ALSA control names in this file come from the MSM8952 internal codec ASoC driver, which
is the same between the 4.9 (stock Android 8) and 4.19 (Lineage 23) kernels. Any controls
that were renamed/removed in 4.19 will produce logcat warnings only — not an abort.

**File installed:** `device/xiaomi/Mi8937/audio/mixer_paths/mixer_paths.xml`

The existing `device.mk` rule already installs it:
```makefile
$(call find-copy-subdir-files,*.xml,$(LOCAL_PATH)/audio/mixer_paths/,$(TARGET_COPY_OUT_VENDOR)/etc/)
```
This copies every `*.xml` verbatim, so `mixer_paths.xml` → `/vendor/etc/mixer_paths.xml`. No
makefile change needed.

**✅ `audio_platform_info.xml` also installed (done 2026-06-06):**
Stock Palm `audio_platform_info.xml` copied to
`device/xiaomi/Mi8937/audio/platform_info/audio_platform_info.xml` — installed to
`/vendor/etc/audio_platform_info.xml` by the existing `find-copy-subdir-files` rule.
This file provides correct ACDB device IDs, PCM device mappings, and MI2S backend config
for pepito. Without it the HAL used hardcoded defaults from another device, causing
Slimbus backend lookups and wrong ACDB IDs (observed as `dev_acdb_id[40]=0` in kernel
log and persistent PCM underruns).

**Long-term layering fix (deferred):**
Change `qcom,model` in `pepito/audio.dtsi` from `msm8952-snd-card-mtp` to
`msm8952-pepito-mtp-snd-card` so the HAL looks for `mixer_paths_pepito_mtp.xml`
first — avoiding a generic `mixer_paths.xml` that could conflict if sibling
devices are ever built from this tree. Rename the installed file to match. Deferred
until TARGET_DEVICE_PEPITO gating is wired up.

**3. Add pepito to `init.xiaomi.device.rc`:**
```rc
on property:ro.vendor.xiaomi.device=pepito
    setprop vendor.audio.mixer_xml.path /vendor/etc/mixer_paths.xml
    setprop vendor.audio.platform_info_xml.path /vendor/etc/audio_platform_info.xml
```
(Even though the HAL doesn't read these, setting them is consistent with other devices
and future-proof if the property is picked up by other audio services.)

---

## Issue 3: AUDIO_DEVICE_OUT_LINE always-connected (✅ FIXED 2026-06-06)

### Symptom
Ringtone preview in sound picker silent. Media volume slider feedback audible. Ring
stream played through speaker; music stream routed exclusively to `line` output
(HPHL/HPHR) → silent with no headphones plugged in.

### Root Cause
`audio_policy_configuration.xml` (mithorium-common) declared `AUDIO_DEVICE_OUT_LINE`
as a device port with a `route`, but did NOT list it in `attachedDevices`. Android's
audio policy manager treated it as dynamically connected and, due to some internal APM
state at boot, resolved STREAM_MUSIC to route exclusively to `line`. Confirmed via
`adb shell dumpsys audio`: `STREAM_MUSIC: Devices: line`.

~~Pepito's 3.5mm jack is reported as `AUDIO_DEVICE_OUT_WIRED_HEADSET` or
`AUDIO_DEVICE_OUT_WIRED_HEADPHONE` by the kernel's extcon/switch driver — never as
`AUDIO_DEVICE_OUT_LINE`.~~ **CORRECTION (2026-07-02): pepito has NO 3.5mm jack at all**
(USB-C only). The `AUDIO_DEVICE_OUT_LINE` events come from the WCD MBHC *false-detecting*
a line-out on floating sense lines: the ALSA "Headset Jack" input device asserts
`SW_LINEOUT_INSERT` + `SW_JACK_PHYSICAL_INSERT` at every boot. Stock Palm has no
Headset Jack input device — its DTS omits `qcom,msm-mbhc-hphl-swh`/`gnd-swh` so
`wcd_mbhc_init()` bails before jack creation. Removing the `Line` port (fix below)
protects *routing*, but WiredAccessoryManager still records the device → UI shows
"Wired headphones". Root fix (delete the mbhc DT props + a `wcd_mbhc_start` NULL guard)
is staged in the top-level `PLAN-audio.md` Issue 1. The `Line` port was inherited from
devices that have a dedicated analog dock output.

### Fix
Removed the `Line` devicePort entry and its corresponding `route` from
`device/xiaomi/mithorium-common/audio/audio_policy_configuration.xml`. Music now
routes to Speaker (and Wired Headphones when jack inserted).

---

## Issue 4: ACDB calibration version mismatch (OPEN — audio quality)

> **Still reproduces 2026-07-02** (verified live during speaker playback: identical
> `topology id 0x0 ret -95` / `cal_block is NULL` / `dev_acdb_id[40] is 0` /
> `delay_usec 0` sequence). Option A (prada nightly set) remains the recommended
> next step — see top-level `PLAN-audio.md` Issue 2.

### Symptom
Kernel log at every audio stream open:
```
afe_send_port_topology_id: AFE set topology id 0x0 enable for port 0x1000 ret -95
q6asm_send_cal: cal_block is NULL
send_afe_cal_type: dev_acdb_id[40] is 0
send_afe_cal_type cal_block not found!!
afe_send_hw_delay: port_id 0x1000 rate 48000 delay_usec 0 status 0
```
Audio plays but DSP runs completely unprocessed: no speaker EQ, no echo cancellation,
no hardware delay compensation, `delay_usec=0`.

### Root Cause
The Palm Android 8.1 ACDB files (MTP_*.acdb) were generated for the Android 8
`acdb_loader` binary, which uses an older internal ACDB block format/version tag. The
Lineage 23 `acdb_loader` (Android 12/13 era) sends a different version when querying
calibration blocks, so it cannot locate calibration data for AFE port 0x1000
(AFE_PORT_ID_PRIMARY_MI2S_RX). The files are loaded (calfile0-6 props are set, acdb
loader opens them), but no calibration blocks are matched.

`-95 (ENOTSUP)` on the topology set is a secondary symptom: without a valid ACDB
device ID, the topology ID is 0x0 (invalid), and the ADSP firmware rejects it.

### Fix Options

**Option A — Use prada/santoni ACDB files from the Lineage 23 nightly (preferred):**
The nightly vendor extraction already has ACDB files for prada/santoni/land/ulysse
under `/vendor/etc/acdbdata/`. These were generated for the Android 12/13-era
`acdb_loader` that Lineage 23 ships. Extract a prada or santoni set from the nightly
vendor.img, install as `/vendor/etc/acdbdata/pepito/`, and accept that the tuning is
for prada/santoni hardware rather than pepito's. Speaker EQ/mic gain will be
approximate but DSP processing will function. Audio quality will be noticeably better
than no calibration.

**Option B — Re-extract ACDB files from an Android 12+ Palm dump (ideal but unclear):**
If Palm ever shipped a newer Android version (or if a community port exists with
updated blobs), extract ACDB files from that. Unlikely to exist for PVG-100.

**Option C — Downgrade acdb_loader to Android 8 compatible version:**
Extract `acdb_loader` from the Palm stock vendor and use it instead of Lineage's.
Risk: ABI mismatch with Lineage's kernel ACDB ioctl interface (kernel-side ACDB
driver may have changed between 4.9 and 4.19).

**Recommended next step:** Try Option A — pull prada's ACDB set from a mounted
Lineage 23 nightly vendor.img and drop it in `audio/acdbdata/pepito/` replacing
the current Palm Android 8 files. See `PLAN-vendor-extract.md` for mount workflow.

---

## Other Known Issues (not boot blockers)

### Radio spam — lineage.hardware.radio.config@1.0::IRadioConfig/default

**Status: ✅ DONE (2026-06-06)**

Root causes identified and fixed:

1. **`qcrild3` crash-loop**: pepito is dual-SIM (dsds) — no 3rd SIM instance. qcrild3 was in
   `class main` with no `disabled` flag, so it auto-started and crash-looped at ~1Hz, filling
   the ramoops ring buffer and making early crash debugging impossible.

2. **`ro.baseband` unset**: `init.class_main.sh` (via `qcom-c_main-sh`) manages the decision
   between `vendor.qcrild` and `vendor.ril-daemon`, but the entire `case "$baseband"` block
   was skipped because `ro.baseband` was never set for pepito. With `ro.baseband` empty,
   neither service was started by the script.

3. **Simultaneous qcrild + ril-daemon auto-start**: With no `disabled` flag on qcrild services,
   both `vendor.qcrild` and `vendor.ril-daemon` auto-started from class main. They race for
   modem resources, preventing qcrild from completing initialization and registering
   `lineage.hardware.radio.config@1.0::IRadioConfig/default` via the c_shim mechanism.

**Fixes applied:**

- `vendor/xiaomi/Mi8937/proprietary/vendor/etc/init/qcrild.rc`: Added `disabled` to all three
  qcrild services. `init.xiaomi.rc` already has `on boot: enable vendor.qcrild/qcrild2` which
  clears the disabled flag, and `init.class_main.sh` starts them after stopping ril-daemon.
  qcrild3 stays disabled forever (never started for dsds config).

- `device/xiaomi/mithorium-common/vendor.prop`: Added `ro.baseband=msm`, enabling the
  `init.class_main.sh` radio startup logic (stops ril-daemon, starts qcrild, starts qcrild2
  for dsds dual-SIM).

**How the lineage interface registration works:**
`libril-qc-hal-qmi.so` DT_NEEDs `android.hardware.radio.c_shim@1.0.so` which overrides
`android::hardware::radio::config::V1_0::IRadioConfig::descriptor` to
`"lineage.hardware.radio.config@1.0::IRadioConfig"`. When qcrild calls
`RadioConfigImpl::registerAsService("default")`, it registers as `lineage@1.0`.
The `android.hardware.radio.config@1.1-service.wrapper` then calls `getService()` on
`lineage@1.0` and wraps it as `android.hardware.radio.config@1.1`.

**Residual ctl.interface_start errors:** hwservicemanager may still log
"Could not find service for interface lineage.hardware.radio.config@1.0" when lazily
starting clients — this is because no RC file declares the interface for lazy-loading.
These are warnings only; `getService()` calls succeed because qcrild registered directly.

### GPS — loc_launcher disabled
- GPS conf files not yet extracted for pepito
- `loc_launcher` service disabled

### VINTF manifest target-level
- Currently "7" (Android 13), should be "8" (Android 14/LineageOS 21+)

---

## Priority Order

1. ✅ **Fix sensor HAL** — stubbed `hals.conf` (2026-06-06); sensors.ssc debug deferred post-boot
2. ✅ **Fix audio HAL mixer_paths.xml + platform_info + ACDB files** — Palm stock files installed (2026-06-06)
3. ✅ **Fix radio spam** — disabled qcrild3, added ro.baseband=msm (2026-06-06)
4. ✅ **Fix AUDIO_DEVICE_OUT_LINE routing** — removed Line port from audio policy (2026-06-06); rebuild pending
5. **Fix ACDB calibration version mismatch** — replace Palm Android 8 ACDB files with prada/santoni set from Lineage 23 nightly (see Issue 4, Option A) — *still open 2026-07-02*
6. ✅ **Verify boot completes cleanly** — `sys.boot_completed=1` verified 2026-06-10 (see PLAN.md)
7. **Fix GPS, VINTF, etc.** — polish (GPS gated on modem health — see PLAN-radio.md)
8. ✅ **Debug sensors.ssc.so** — prox/ALS working 2026-06-29 via base-file `sensor_def_qcomdev.conf` fix; BHy accel/gyro staged (see PLAN-sensors.md)
9. **Kill false "Wired headphones"** — delete mbhc DT props (stock parity) + `wcd_mbhc_start` guard; staged in top-level `PLAN-audio.md` Issue 1 (2026-07-02)

---

## Relevant File Paths

| File | Purpose |
|------|---------|
| `kernel/xiaomi/msm8937/.../pepito/audio.dtsi:91` | `qcom,model = "msm8952-snd-card-mtp"` |
| `hardware/qcom/audio/hal/msm8974/platform.c:1774` | mixer_paths lookup logic |
| `hardware/qcom/audio/hal/audio_extn/utils.c:483` | `resolve_config_file()` search paths |
| `device/xiaomi/Mi8937/rootdir/bin/init.xiaomi.device.sh` | Sets `ro.vendor.xiaomi.device` (no pepito case) |
| `device/xiaomi/Mi8937/rootdir/etc/init.xiaomi.device.rc` | Per-device audio/camera/IR triggers (no pepito case) |
| `device/xiaomi/Mi8937/audio/mixer_paths/prada_mixer_paths_mtp.xml` | Prada baseline (MSM8940+intcodec+MTP) |
| `device/xiaomi/Mi8937/audio/platform_info/prada_audio_platform_info_intcodec.xml` | Prada baseline |
| `vendor/xiaomi/Mi8937/proprietary/vendor/lib64/sensors.ssc.so` | SSC sensor HAL blob |
| `vendor/xiaomi/mithorium-common/proprietary/vendor/etc/sensors/hals.conf` | Points to `sensors.ssc.so` |
