#!/system/bin/sh
[ "$API" -ge 37 ] || abort 'This module requires Android 17 or later.'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in identity.sh post-fs-data.sh uninstall.sh; do set_perm "$MODPATH/$script" 0 0 0755; done
