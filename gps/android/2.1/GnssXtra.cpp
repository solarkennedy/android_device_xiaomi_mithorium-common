#define LOG_TAG "LocSvc_GnssXtra"

#include "GnssXtra.h"

#include <cutils/properties.h>
#include <dlfcn.h>
#include <log/log.h>

#include <algorithm>
#include <cstring>
#include <ctime>
#include <thread>
#include <vector>

namespace android {
namespace hardware {
namespace gnss {
namespace V2_1 {
namespace implementation {

namespace {

constexpr char kEnableProp[] = "persist.gotweak.gnss_xtra";
// Unset switch: on only where qmux is (libinit sets it on pepito alone), so the
// Xiaomi siblings sharing this HAL keep their old behaviour.
constexpr char kDefaultProp[] = "ro.vendor.qmux.enable";

// QMI LOC (IDL v02). The newer INJECT_XTRA_DATA (0xA7) is NOT_SUPPORTED by
// the MSM8937-era modems; the deprecated predicted-orbits message is.
constexpr unsigned kInjectPredictedOrbits = 0x0035;
constexpr unsigned kGetPredictedOrbitsValidity = 0x0037;
constexpr size_t kPartLen = 1024;
constexpr size_t kMaxFileLen = 256 * 1024;
constexpr unsigned kQmiTimeoutMs = 8000;   // as on the bench (diag-tools/xtra-probe)
constexpr unsigned kIndTimeoutMs = 10000;

constexpr auto kRefreshAfterSuccess = std::chrono::hours(24);
constexpr auto kRefreshAfterFailure = std::chrono::hours(6);
// A failed injection is retried with the same bytes after these waits before
// falling back to kRefreshAfterFailure. The framework downloads at boot and the
// modem can reject the last part then (GENERAL_FAILURE ~40 s after boot on new2,
// 2026-11-02; the same file went in 3 min later), and it downloads only once per
// boot, so without a retry a phone could go days without XTRA.
constexpr std::chrono::minutes kRetryWaits[] = {std::chrono::minutes(2), std::chrono::minutes(10)};
constexpr auto kRefreshPoll = std::chrono::minutes(30);

// Classic QCCI entry points exported by /vendor/lib64/libqmi_cci.so; resolved
// at runtime like the qmux tools do, so a missing blob disables XTRA instead
// of the whole HAL. The gnss service links libqmi_force_ipcr, which routes
// QMI LOC to the modem on pepito's ipc_router.
using qmi_client_type = void*;
using qmi_ind_cb = void (*)(qmi_client_type, unsigned int, void*, unsigned int, void*);
using init_instance_fn = int (*)(void*, uint32_t, qmi_ind_cb, void*, void*, uint32_t,
                                 qmi_client_type*);
using send_raw_sync_fn = int (*)(qmi_client_type, unsigned int, void*, unsigned int, void*,
                                 unsigned int, unsigned int*, unsigned int);
using release_fn = int (*)(qmi_client_type);

struct Qcci {
    init_instance_fn initInstance = nullptr;
    send_raw_sync_fn sendRawSync = nullptr;
    release_fn release = nullptr;
    void* locService = nullptr;

    bool load() {
        void* cci = dlopen("libqmi_cci.so", RTLD_NOW);
        if (!cci) {
            ALOGE("dlopen libqmi_cci.so failed: %s", dlerror());
            return false;
        }
        void* loc = dlopen("libloc_api_v02.so", RTLD_NOW);
        if (!loc) {
            ALOGE("dlopen libloc_api_v02.so failed: %s", dlerror());
            return false;
        }
        initInstance = reinterpret_cast<init_instance_fn>(dlsym(cci, "qmi_client_init_instance"));
        sendRawSync =
                reinterpret_cast<send_raw_sync_fn>(dlsym(cci, "qmi_client_send_raw_msg_sync"));
        release = reinterpret_cast<release_fn>(dlsym(cci, "qmi_client_release"));
        locService = dlsym(loc, "loc_qmi_idl_service_object_v02");
        if (!initInstance || !sendRawSync || !release || !locService) {
            ALOGE("missing QCCI/LOC symbols");
            return false;
        }
        return true;
    }
};

const Qcci* qcci() {
    static Qcci q;
    static bool ok = q.load();
    return ok ? &q : nullptr;
}

const char* locStatusName(long s) {
    static const char* names[] = {"SUCCESS",       "GENERAL_FAILURE",     "UNSUPPORTED",
                                  "INVALID_PARAMETER", "ENGINE_BUSY",     "PHONE_OFFLINE",
                                  "TIMEOUT",       "CONFIG_NOT_SUPPORTED", "INSUFFICIENT_MEMORY",
                                  "MAX_GEOFENCE_PROGRAMMED", "XTRA_VERSION_CHECK_FAILURE",
                                  "GNSS_DISABLED"};
    return s >= 0 && s < static_cast<long>(sizeof(names) / sizeof(names[0])) ? names[s] : "?";
}

uint64_t getLe(const uint8_t* v, int n) {
    uint64_t x = 0;
    for (int i = n - 1; i >= 0; i--) x = (x << 8) | v[i];
    return x;
}

void putTlv(std::vector<uint8_t>& out, uint8_t type, const void* value, uint16_t len) {
    out.push_back(type);
    out.push_back(len & 0xff);
    out.push_back(len >> 8);
    const uint8_t* p = static_cast<const uint8_t*>(value);
    out.insert(out.end(), p, p + len);
}

// One raw QMI LOC client. A LOC request is acked by a plain QMI response; its
// result comes later as an indication with the same message id.
//
// Never destroyed: QCCI keeps a pointer to mOsParams (its signal mutex/cond
// live in it), and releasing a client could race a late indication into
// indCb on a freed object. A client that stops answering (modem restart) is
// abandoned and replaced; that leaks one small object per modem restart.
class LocClient {
  public:
    explicit LocClient(const Qcci* q) : mQ(q) {
        if (mQ->initInstance(mQ->locService, 0xffff /* QMI_CLIENT_INSTANCE_ANY */, indCb, this,
                             mOsParams, kQmiTimeoutMs, &mClient) ||
            !mClient) {
            ALOGE("QMI LOC client init failed");
            mClient = nullptr;
        }
    }
    LocClient(const LocClient&) = delete;
    LocClient& operator=(const LocClient&) = delete;

    bool ok() const { return mClient != nullptr && !mBroken; }

    // Returns the indication's status TLV, or -1 on transport failure/timeout.
    long request(unsigned msgId, std::vector<uint8_t>& req) {
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mWant = msgId;
            mHave = false;
            mInd.clear();
        }
        uint8_t resp[64];
        unsigned got = 0;
        int rc = mQ->sendRawSync(mClient, msgId, req.empty() ? nullptr : req.data(),
                                 req.size(), resp, sizeof(resp), &got, kQmiTimeoutMs);
        if (rc) {
            ALOGE("QMI LOC 0x%04x send rc=%d", msgId, rc);
            mBroken = true;
            return -1;
        }
        // response TLV 0x02 = {result u16, error u16}
        bool haveResult = false;
        for (unsigned i = 0; i + 3 <= got;) {
            uint16_t len = resp[i + 1] | (resp[i + 2] << 8);
            if (i + 3 + len > got) break;
            if (resp[i] == 0x02 && len >= 4) {
                haveResult = true;
                if (getLe(resp + i + 3, 2)) {
                    ALOGE("QMI LOC 0x%04x rejected, error %u", msgId,
                          static_cast<unsigned>(getLe(resp + i + 5, 2)));
                    return -1;
                }
            }
            i += 3 + len;
        }
        if (!haveResult) {
            ALOGE("QMI LOC 0x%04x: no result in response", msgId);
            return -1;
        }
        std::unique_lock<std::mutex> lock(mMutex);
        if (!mCv.wait_for(lock, std::chrono::milliseconds(kIndTimeoutMs), [this] { return mHave; })) {
            ALOGE("QMI LOC 0x%04x: no indication", msgId);
            return -1;
        }
        long status = -1;
        tlvs([&](uint8_t t, const uint8_t* v, uint16_t l) {
            if (t == 0x01 && l == 4) status = static_cast<long>(getLe(v, 4));
        });
        return status;
    }

    // Walk the last indication's TLVs (call after request()).
    template <typename F>
    void tlvs(F f) {
        for (size_t i = 0; i + 3 <= mInd.size();) {
            uint16_t len = mInd[i + 1] | (mInd[i + 2] << 8);
            if (i + 3 + len > mInd.size()) break;
            f(mInd[i], mInd.data() + i + 3, len);
            i += 3 + len;
        }
    }

  private:
    static void indCb(qmi_client_type, unsigned msgId, void* buf, unsigned len, void* data) {
        auto* self = static_cast<LocClient*>(data);
        std::lock_guard<std::mutex> lock(self->mMutex);
        if (msgId != self->mWant || self->mHave) return;
        const uint8_t* p = static_cast<const uint8_t*>(buf);
        self->mInd.assign(p, p + len);
        self->mHave = true;
        self->mCv.notify_all();
    }

    const Qcci* mQ;
    alignas(16) char mOsParams[512] = {};
    qmi_client_type mClient = nullptr;
    bool mBroken = false;
    std::mutex mMutex;
    std::condition_variable mCv;
    unsigned mWant = 0;
    bool mHave = false;
    std::vector<uint8_t> mInd;
};

// Only ever called from one injectWorker at a time (GnssXtra::mInjecting).
LocClient* locClient() {
    static LocClient* client = nullptr;
    const Qcci* q = qcci();
    if (!q) return nullptr;
    if (!client || !client->ok()) client = new LocClient(q);  // old one abandoned
    return client->ok() ? client : nullptr;
}

bool injectToModem(const std::string& data) {
    LocClient* client = locClient();
    if (!client) return false;
    LocClient& loc = *client;

    const uint32_t total = data.size();
    const uint16_t parts = (total + kPartLen - 1) / kPartLen;
    for (uint16_t part = 1; part <= parts; part++) {
        const size_t off = static_cast<size_t>(part - 1) * kPartLen;
        const uint16_t len = std::min(kPartLen, total - off);
        std::vector<uint8_t> req;
        req.reserve(len + 32);
        putTlv(req, 0x01, &total, 4);   // totalSize
        putTlv(req, 0x02, &parts, 2);   // totalParts
        putTlv(req, 0x03, &part, 2);    // partNum
        std::vector<uint8_t> partData(2 + len);  // u16 count + bytes
        partData[0] = len & 0xff;
        partData[1] = len >> 8;
        memcpy(partData.data() + 2, data.data() + off, len);
        putTlv(req, 0x04, partData.data(), partData.size());
        const uint32_t format = 0;  // eQMI_LOC_PREDICTED_ORBITS_XTRA
        putTlv(req, 0x10, &format, 4);

        long status = loc.request(kInjectPredictedOrbits, req);
        if (status != 0) {
            ALOGE("XTRA part %u/%u rejected: %ld %s", part, parts, status,
                  locStatusName(status));
            return false;
        }
    }

    std::vector<uint8_t> none;
    if (loc.request(kGetPredictedOrbitsValidity, none) == 0) {
        loc.tlvs([](uint8_t t, const uint8_t* v, uint16_t l) {
            if (t != 0x10 || l < 10) return;
            time_t start = static_cast<time_t>(getLe(v, 8));  // UTC seconds
            struct tm tm;
            char when[32];
            strftime(when, sizeof(when), "%F %T", gmtime_r(&start, &tm));
            ALOGI("XTRA valid from %s UTC for %u h", when,
                  static_cast<unsigned>(getLe(v + 8, 2)));
        });
    }
    return true;
}

}  // namespace

bool GnssXtra::enabled() {
    return property_get_bool(kEnableProp, property_get_bool(kDefaultProp, false));
}

// Lives as long as the service (held by Gnss, which is never destroyed), so the
// detached threads below may keep `this`.
GnssXtra::GnssXtra() : mNextRequest(Clock::now() + kRefreshAfterSuccess) {}

Return<bool> GnssXtra::setCallback(const sp<IGnssXtraCallback>& callback) {
    std::lock_guard<std::mutex> lock(mMutex);
    mCallback = callback;
    if (!mRefreshStarted) {
        mRefreshStarted = true;
        std::thread([this] { refreshLoop(); }).detach();
    }
    return true;
}

Return<bool> GnssXtra::injectXtraData(const hidl_string& xtraData) {
    if (!enabled()) {
        ALOGI("XTRA injection switched off (%s), ignoring %zu bytes", kEnableProp,
              xtraData.size());
        return false;
    }
    if (xtraData.size() == 0 || xtraData.size() > kMaxFileLen) {
        ALOGE("XTRA data size %zu out of range", xtraData.size());
        return false;
    }
    std::lock_guard<std::mutex> lock(mMutex);
    if (mInjecting) {
        ALOGW("XTRA injection already in progress, dropping %zu bytes", xtraData.size());
        return false;
    }
    mInjecting = true;
    // Don't hold the framework's binder call for ~30 QMI round trips.
    std::thread(&GnssXtra::injectWorker, this, std::string(xtraData)).detach();
    return true;
}

void GnssXtra::injectWorker(std::string data) {
    bool ok = injectToModem(data);
    ALOGI("XTRA injection of %zu bytes %s", data.size(), ok ? "succeeded" : "failed");
    for (auto wait : kRetryWaits) {
        if (ok || !enabled()) break;
        ALOGI("retrying the XTRA injection in %lld min", static_cast<long long>(wait.count()));
        std::this_thread::sleep_for(wait);
        ok = injectToModem(data);
        ALOGI("XTRA injection retry of %zu bytes %s", data.size(), ok ? "succeeded" : "failed");
    }
    std::lock_guard<std::mutex> lock(mMutex);
    mInjecting = false;
    mNextRequest = Clock::now() + (ok ? kRefreshAfterSuccess : kRefreshAfterFailure);
}

// Checks every kRefreshPoll of awake time (the wait does not run in suspend)
// against a wall-clock deadline, and asks the framework for a new file once
// one is due: a phone asleep past the deadline asks within 30 min of waking.
void GnssXtra::refreshLoop() {
    std::unique_lock<std::mutex> lock(mMutex);
    for (;;) {
        mCv.wait_for(lock, kRefreshPoll);
        if (Clock::now() < mNextRequest || mInjecting) continue;
        mNextRequest = Clock::now() + kRefreshAfterFailure;  // until an injection lands
        sp<IGnssXtraCallback> cb = mCallback;
        if (!cb || !enabled()) continue;
        lock.unlock();
        ALOGI("requesting XTRA download");
        auto ret = cb->downloadRequestCb();
        if (!ret.isOk()) ALOGE("downloadRequestCb failed: %s", ret.description().c_str());
        lock.lock();
    }
}

}  // namespace implementation
}  // namespace V2_1
}  // namespace gnss
}  // namespace hardware
}  // namespace android
