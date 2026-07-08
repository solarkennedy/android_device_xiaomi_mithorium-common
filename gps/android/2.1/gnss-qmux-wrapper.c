// gnss-qmux-wrapper — [qmux] conditionally force the gnss HAL onto the legacy
// ipc_router transport. The gnss@2.1 service execs this instead of the HAL
// binary. When pepito's qmux is enabled (modem on ipc_router) we preload the
// force-ipcr shim so the loc stack (libloc_api_v02 -> libqmi_cci) binds
// QMI_LOC (svc 16) on the ipc_router bus; otherwise (and on every other
// mithorium variant, where the property is unset) we exec the HAL unchanged.
//
// A compiled binary (not a shell script) so the HAL's SELinux domain need not
// execute a shell — it only re-execs its own exec_type. See PLAN-qmux-bridge.md.
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/system_properties.h>

#define GNSS_HAL "/vendor/bin/hw/android.hardware.gnss@2.1-service-qti"

int main(void) {
    char val[PROP_VALUE_MAX] = {0};
    if (__system_property_get("persist.vendor.qmux.enable", val) > 0 &&
        strcmp(val, "1") == 0) {
        setenv("LD_PRELOAD", "libqmi_force_ipcr.so", 1);
    }
    char *argv[] = { (char *)GNSS_HAL, NULL };
    execv(GNSS_HAL, argv);
    return 127;
}
