#!/vendor/bin/sh

WPA_CONF=/data/vendor/wifi/wpa/wpa_supplicant.conf
WPA_SEED=/vendor/etc/wifi/wpa_supplicant.conf

if [ ! -f "$WPA_CONF" ] && [ -f "$WPA_SEED" ]; then
    cp "$WPA_SEED" "$WPA_CONF"
fi

if [ -f "$WPA_CONF" ]; then
    chown wifi:wifi "$WPA_CONF"
    chmod 0660 "$WPA_CONF"
    restorecon "$WPA_CONF"
fi
