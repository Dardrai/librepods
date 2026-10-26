#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -u
umask 077
STATE_DIR=/data/adb/librepods_bluetooth_identity
BOOT_ID_FILE=/proc/sys/kernel/random/boot_id
VENDOR=bluetooth.device_id.vendor_id
SOURCE=bluetooth.device_id.vendor_id_source
ACTION=${1:-}
LEASE=${2:-}
case "$ACTION" in status|enable|disable|boot) ;; *) exit 2 ;; esac
case "$LEASE" in ''|--stdin-lease) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ] || exit 1
[ ! -L "$STATE_DIR" ] || exit 1
mkdir -p "$STATE_DIR" || exit 1
[ "$(stat -c '%u:%a' "$STATE_DIR")" = 0:700 ] || exit 1
tree=$(find "$STATE_DIR" -mindepth 1 -maxdepth 3 -print) || exit 1
printf '%s\n' "$tree" | while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    [ ! -L "$entry" ] || exit 1
    relative=${entry#"$STATE_DIR/"}
    case "$relative" in
        baseline|baseline.new|pending|pending.new)
            [ -d "$entry" ] && [ "$(stat -c '%u:%a' "$entry")" = 0:700 ] || exit 1 ;;
        lock|enabled|enabled.new|baseline/boot|baseline/intent|baseline/vendor.present|baseline/vendor.value|baseline/source.present|baseline/source.value|baseline.new/boot|baseline.new/intent|baseline.new/vendor.present|baseline.new/vendor.value|baseline.new/source.present|baseline.new/source.value|pending/boot|pending/intent|pending/vendor.present|pending/vendor.value|pending/source.present|pending/source.value|pending.new/boot|pending.new/intent|pending.new/vendor.present|pending.new/vendor.value|pending.new/source.present|pending.new/source.value)
            [ -f "$entry" ] && [ "$(stat -c '%u:%a' "$entry")" = 0:600 ] || exit 1 ;;
        *) exit 1 ;;
    esac
done || exit 1
BUSYBOX=
for candidate in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    if [ -x "$candidate" ]; then BUSYBOX=$candidate; break; fi
done
RESET=
for candidate in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /debug_ramdisk/resetprop /sbin/resetprop; do
    if [ -x "$candidate" ]; then RESET=$candidate; break; fi
done
[ -n "$BUSYBOX" ] && [ -n "$RESET" ] || exit 1
exec 9>"$STATE_DIR/lock"
attempt=0
until "$BUSYBOX" flock -x -n 9; do
    [ "$attempt" -lt 30 ] || exit 1
    "$BUSYBOX" sleep 0.1
    attempt=$((attempt + 1))
done
BOOT=$(cat "$BOOT_ID_FILE")
case "$BOOT" in ''|*[!a-fA-F0-9-]*) exit 1 ;; esac
PENDING=$STATE_DIR/pending
BASELINE=$STATE_DIR/baseline
ROLLBACK=0
WATCHDOG=

present() {
    getprop | (
        while IFS= read -r line; do
            case "$line" in "[$1]: ["*) exit 0 ;; esac
        done
        exit 1
    )
}
intent() { [ "$(cat "$STATE_DIR/enabled" 2>/dev/null)" = 1 ]; }
clear_snapshot() {
    for file in boot vendor.present vendor.value source.present source.value intent; do rm -f "$1/$file"; done
    [ ! -d "$1" ] || rmdir "$1"
}
snapshot() {
    directory=$1
    mkdir "$directory" || return 1
    printf '%s\n' "$BOOT" >"$directory/boot" || return 1
    if intent; then printf '1\n'; else printf '0\n'; fi >"$directory/intent" || return 1
    for item in vendor source; do
        if [ "$item" = vendor ]; then key=$VENDOR; else key=$SOURCE; fi
        if present "$key"; then printf '1\n'; else printf '0\n'; fi >"$directory/$item.present" || return 1
        getprop "$key" >"$directory/$item.value" || return 1
        # The native int-property parser does not admit multiline values.
        [ "$(wc -l <"$directory/$item.value")" -eq 1 ] || return 1
        [ "$(wc -c <"$directory/$item.value")" -le 93 ] || return 1
    done
}
restore() {
    directory=$1
    [ "$(cat "$directory/boot" 2>/dev/null)" = "$BOOT" ] || return 1
    failed=0
    for item in vendor source; do
        if [ "$item" = vendor ]; then key=$VENDOR; else key=$SOURCE; fi
        saved=$(cat "$directory/$item.present" 2>/dev/null)
        value=$(cat "$directory/$item.value" 2>/dev/null)
        case "$saved" in
            1) if ! present "$key" || [ "$(getprop "$key")" != "$value" ]; then
                   setprop "$key" "$value" || failed=1
               fi
               present "$key" && [ "$(getprop "$key")" = "$value" ] || failed=1 ;;
            0) if present "$key"; then "$RESET" --delete "$key" || failed=1; fi
               if present "$key"; then failed=1; fi ;;
            *) failed=1 ;;
        esac
    done
    [ "$failed" = 0 ]
}
save_intent() {
    case "$1" in 0|1) ;; *) return 1 ;; esac
    printf '%s\n' "$1" >"$STATE_DIR/enabled.new" && mv "$STATE_DIR/enabled.new" "$STATE_DIR/enabled"
}
on_exit() {
    result=$1
    trap - EXIT INT TERM HUP
    if [ "$ROLLBACK" = 1 ]; then
        if restore "$PENDING" && save_intent "$(cat "$PENDING/intent")"; then
            clear_snapshot "$PENDING" || result=1
        else result=1; fi
    fi
    if [ -n "$WATCHDOG" ]; then kill "$WATCHDOG" 2>/dev/null || :; wait "$WATCHDOG" 2>/dev/null || :; fi
    exit "$result"
}
trap 'on_exit $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
if [ "$LEASE" = --stdin-lease ]; then
    owner=$$
    exec 8<&0
    ( while IFS= read -r unused; do :; done; kill -TERM "$owner" ) <&8 8<&- 9>&- >/dev/null 2>&1 &
    WATCHDOG=$!
    exec 8<&-
fi

# A killed writer leaves a journal; never restore an earlier boot's defaults after an OTA.
if [ -d "$PENDING" ]; then
    if [ "$(cat "$PENDING/boot" 2>/dev/null)" = "$BOOT" ]; then
        restore "$PENDING" && save_intent "$(cat "$PENDING/intent")" || exit 1
    fi
    clear_snapshot "$PENDING" || exit 1
fi
clear_snapshot "$STATE_DIR/pending.new" || exit 1
clear_snapshot "$STATE_DIR/baseline.new" || exit 1

matches_number() {
    value=$(printf '%s' "$1" | sed 's/^[[:space:]]*//')
    case "$value" in
        0x*|0X*) value=${value#??}; value=$(printf '%s' "$value" | tr 'A-F' 'a-f'); expected=$3 ;;
        *) value=${value#+}; expected=$2 ;;
    esac
    while [ "${value#0}" != "$value" ]; do value=${value#0}; done
    [ "$value" = "$expected" ]
}
active() { matches_number "$(getprop "$VENDOR")" 76 4c && matches_number "$(getprop "$SOURCE")" 1 1; }
status() {
    printf 'version=1\n'
    if intent; then printf 'configured=1\n'; else printf 'configured=0\n'; fi
    if active; then printf 'active=1\n'; else printf 'active=0\n'; fi
}
if [ "$ACTION" = status ]; then status; exit 0; fi
if [ "$ACTION" = boot ] && ! intent; then exit 0; fi

snapshot "$STATE_DIR/pending.new" && mv "$STATE_DIR/pending.new" "$PENDING" || exit 1
ROLLBACK=1
if [ "$ACTION" = enable ] || [ "$ACTION" = boot ]; then
    if ! intent || [ "$(cat "$BASELINE/boot" 2>/dev/null)" != "$BOOT" ]; then
        snapshot "$STATE_DIR/baseline.new" || exit 1
        clear_snapshot "$BASELINE" && mv "$STATE_DIR/baseline.new" "$BASELINE" || exit 1
    fi
    setprop "$VENDOR" 76 && setprop "$SOURCE" 1 && active && save_intent 1 || exit 1
else
    if intent; then
        # Do not overwrite another configuration's changes while this profile was active.
        if active; then restore "$BASELINE" || exit 1; fi
    fi
    save_intent 0 || exit 1
fi
ROLLBACK=0
clear_snapshot "$PENDING" || exit 1
status
