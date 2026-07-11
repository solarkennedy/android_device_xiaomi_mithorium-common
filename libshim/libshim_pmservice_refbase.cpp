#include <utils/RefBase.h>

extern "C" void _ZNK7android7RefBase9incStrongEPKv(
        const android::RefBase* ref, const void* id) {
    // pm-service is an Oreo-era blob that registers a stack-allocated binder
    // object. Android 16 RefBase aborts that pattern; keep the workaround local
    // to this daemon by preloading this shim only for vendor.per_mgr.
    ref->forceIncStrong(id);
}
