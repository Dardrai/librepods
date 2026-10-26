#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/identity.sh" boot >/dev/null 2>&1
