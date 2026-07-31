# pepito custom boot/recovery image rules — graft the fail-open Verified-Boot-1.0
# signature onto boot.img AND recovery.img AT BUILD TIME.
#
# Why: this device's aboot (VB1.0) rejects an unsigned boot image. The stock AOSP
# build emits UNSIGNED boot/recovery images (all-zero where the signature goes).
# EDL flashing worked only because prepare-flash.sh grafts the signature as a
# post-build step -- but the OTA zip ships the raw build boot.img, so an OTA
# replaced the working (EDL-grafted) boot with an unsigned one and the device
# hung at the PALM splash before the kernel ran. recovery.img is the same: the
# Updater's "Update recovery" toggle (off by default) would brick recovery the
# same way if enabled. Grafting here makes target-files / OTA / EDL all ship a
# boot image aboot accepts, with no post-build step.
#
# Enabled via BOARD_CUSTOM_BOOTIMG_MK (BoardConfigCommon.mk), which replaces the
# default boot AND recovery recipes -- so both are (re)defined below, reusing the
# build's own INTERNAL_*_ARGS. BOARD_AVB_ENABLE is false here, so there is no AVB
# hash footer to reproduce.
#
# The grafter lives in-tree (so it rsyncs to the build server with the device
# tree; do NOT reference the landing repo via the `scripts` symlink here). It's
# invoked from the recipe, not listed as a prereq. --no-pad keeps the in-build
# image at its real size (~23 MB) instead of padding to the 64 MB partition;
# aboot reads the signature at img_size regardless of trailing bytes.

PEPITO_BOOT_GRAFT := device/xiaomi/mithorium-common/boot-signing/sign-boot-graft.py

# ---- boot.img ----
$(INSTALLED_BOOTIMAGE_TARGET): $(MKBOOTIMG) $(INTERNAL_BOOTIMAGE_FILES)
	$(call pretty,"Target boot image (grafted): $@")
	$(MKBOOTIMG) --kernel $(call bootimage-to-kernel,$@) $(INTERNAL_BOOTIMAGE_ARGS) $(INTERNAL_MKBOOTIMG_VERSION_ARGS) $(BOARD_MKBOOTIMG_ARGS) --output $@.unsigned
	$(hide) python3 $(PEPITO_BOOT_GRAFT) $@.unsigned $@ /boot --no-pad
	$(hide) rm -f $@.unsigned
	$(call assert-max-image-size,$@,$(call get-bootimage-partition-size,$@,boot))

.PHONY: bootimage-nodeps
bootimage-nodeps: $(MKBOOTIMG)
	@echo "make $@: ignoring dependencies"
	$(MKBOOTIMG) --kernel $(call bootimage-to-kernel,$(INSTALLED_BOOTIMAGE_TARGET)) $(INTERNAL_BOOTIMAGE_ARGS) $(INTERNAL_MKBOOTIMG_VERSION_ARGS) $(BOARD_MKBOOTIMG_ARGS) --output $(INSTALLED_BOOTIMAGE_TARGET).unsigned
	$(hide) python3 $(PEPITO_BOOT_GRAFT) $(INSTALLED_BOOTIMAGE_TARGET).unsigned $(INSTALLED_BOOTIMAGE_TARGET) /boot --no-pad

# ---- recovery.img ----
# The kernel is already appended to INTERNAL_RECOVERYIMAGE_ARGS by the core
# Makefile when BOARD_CUSTOM_BOOTIMG_MK is set, so build-recoveryimage-target is
# called with an empty kernel arg. It asserts the (unsigned) size itself.
$(INSTALLED_RECOVERYIMAGE_TARGET): $(recoveryimage-deps)
	$(call pretty,"Target recovery image (grafted): $@")
	$(call build-recoveryimage-target, $@.unsigned,)
	$(hide) python3 $(PEPITO_BOOT_GRAFT) $@.unsigned $@ /recovery --no-pad
	$(hide) rm -f $@.unsigned
