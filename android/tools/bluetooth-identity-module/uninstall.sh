#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/identity.sh" disable >/dev/null 2>&1
