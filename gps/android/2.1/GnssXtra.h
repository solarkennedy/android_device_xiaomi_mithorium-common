#ifndef ANDROID_HARDWARE_GNSS_V2_1_GNSSXTRA_H
#define ANDROID_HARDWARE_GNSS_V2_1_GNSSXTRA_H

#include <android/hardware/gnss/1.0/IGnssXtra.h>
#include <hidl/Status.h>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>

namespace android {
namespace hardware {
namespace gnss {
namespace V2_1 {
namespace implementation {

using ::android::hardware::gnss::V1_0::IGnssXtra;
using ::android::hardware::gnss::V1_0::IGnssXtraCallback;
using ::android::hardware::hidl_string;
using ::android::hardware::Return;
using ::android::sp;

/*
 * XTRA (predicted orbits) injection without Qualcomm's xtra-daemon.
 *
 * The framework's PSDS downloader fetches the file (LONGTERM_PSDS_SERVER_1 in
 * /vendor/etc/gps_debug.conf) and hands it to injectXtraData(); we feed it to
 * the modem in 1 KiB parts with QMI_LOC_INJECT_PREDICTED_ORBITS_DATA over a
 * private raw QMI LOC client. Android only downloads once per boot and then
 * when the HAL asks, so a timer thread asks again a day after each good
 * injection.
 *
 * Switch: persist.gotweak.gnss_xtra; unset, it follows ro.vendor.qmux.enable
 * (on for pepito only). Off at boot = getExtensionXtra() returns nullptr, i.e.
 * exactly the old behaviour; turned off later, nothing more is injected or
 * requested.
 */
struct GnssXtra : public IGnssXtra {
    static bool enabled();

    GnssXtra();
    ~GnssXtra() = default;

    // Methods from ::android::hardware::gnss::V1_0::IGnssXtra follow.
    Return<bool> setCallback(const sp<IGnssXtraCallback>& callback) override;
    Return<bool> injectXtraData(const hidl_string& xtraData) override;

  private:
    using Clock = std::chrono::system_clock;

    void injectWorker(std::string data);
    void refreshLoop();

    std::mutex mMutex;
    std::condition_variable mCv;
    sp<IGnssXtraCallback> mCallback;
    bool mInjecting = false;
    bool mRefreshStarted = false;
    Clock::time_point mNextRequest;
};

}  // namespace implementation
}  // namespace V2_1
}  // namespace gnss
}  // namespace hardware
}  // namespace android

#endif  // ANDROID_HARDWARE_GNSS_V2_1_GNSSXTRA_H
