#!/vendor/bin/sh
# [qmux] Conditionally force the gnss HAL onto the legacy ipc_router transport.
# init can't expand a property inside `setenv`, so gate it here: only when
# pepito's qmux is active (modem on ipc_router) do we preload the force-ipcr
# shim so the loc stack (libloc_api_v02 -> libqmi_cci) binds QMI_LOC (svc 16)
# on the ipc_router bus. When qmux is off (or on any other variant, where the
# property is unset), we exec the HAL unchanged. See PLAN-qmux-bridge.md.
if [ "$(getprop persist.vendor.qmux.enable)" = "1" ]; then
    export LD_PRELOAD=libqmi_force_ipcr.so
fi
exec /vendor/bin/hw/android.hardware.gnss@2.1-service-qti "$@"
