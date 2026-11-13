set -eu
umask 077
trap 'echo PULSE_CPU_ERROR' EXIT
cpu_read_bits=$(printf '%d' 0444)
cpu_write_bits=$(printf '%d' 0222)

cpu_protected_mode() {
    cpu_bits=$(printf '%d' "0$1")
    printf '%o\n' "$((cpu_bits & cpu_read_bits))"
}

cpu_writable() {
    cpu_bits=$(printf '%d' "0$1")
    [ "$((cpu_bits & cpu_write_bits))" != 0 ]
}

cpu_identity() {
    [ -r "/proc/$1/stat" ] || return 1
    read -r cpu_stat < "/proc/$1/stat" || return 1
    cpu_stat=${cpu_stat##*) }
    set -- $cpu_stat
    [ "$1" != Z ] || return 1
    shift 19
    printf '%s\n' "$1"
}

cpu_lock() {
    cpu_polls=0
    until mkdir "$CPU_STATE/lock" 2>/dev/null; do
        if [ -f "$CPU_STATE/lock/owner" ]; then
            read -r cpu_lock_pid cpu_lock_start < "$CPU_STATE/lock/owner" || true
            cpu_lock_identity=
            [ -z "$cpu_lock_pid" ] || cpu_lock_identity=$(cpu_identity "$cpu_lock_pid" || true)
            if [ -n "$cpu_lock_pid" ] && { [ -z "$cpu_lock_identity" ] ||
                { [ -n "$cpu_lock_start" ] && [ "$cpu_lock_identity" != "$cpu_lock_start" ]; }; }; then
                rm -f "$CPU_STATE/lock/owner"
                rmdir "$CPU_STATE/lock" 2>/dev/null || true
            elif [ -z "$cpu_lock_pid" ] && [ "$cpu_polls" -ge 40 ]; then
                rm -f "$CPU_STATE/lock/owner"
                rmdir "$CPU_STATE/lock" 2>/dev/null || true
            fi
        elif [ "$cpu_polls" -ge 40 ]; then
            rmdir "$CPU_STATE/lock" 2>/dev/null || true
        fi
        cpu_polls=$((cpu_polls + 1))
        [ "$cpu_polls" -lt 50 ] || return 1
        sleep 0.1
    done
    printf '%s %s\n' "$$" "$(cpu_identity "$$")" > "$CPU_STATE/lock/owner"
}

cpu_unlock() {
    rm -f "$CPU_STATE/lock/owner"
    rmdir "$CPU_STATE/lock"
}

cpu_restore_record() {
    read -r cpu_restore_path cpu_restore_mode cpu_restore_inode cpu_restore_value < "$1" || return 1
    case "$cpu_restore_mode" in [0-7]|[0-7][0-7]|[0-7][0-7][0-7]) ;; *) return 1 ;; esac
    case "$cpu_restore_inode" in ''|*[!0-9]*) return 1 ;; esac
    if [ -e "$cpu_restore_path" ] && [ "$(stat -c %i "$cpu_restore_path")" = "$cpu_restore_inode" ]; then
        cpu_restore_current=$(stat -c %a "$cpu_restore_path")
        cpu_restore_protected=$(cpu_protected_mode "$cpu_restore_mode")
        case "$cpu_restore_current" in
            "$cpu_restore_protected"|666) chmod "$cpu_restore_mode" "$cpu_restore_path" || return 1 ;;
        esac
    fi
    rm -f "$1"
}

cpu_restore_all() {
    rm -f "$CPU_STATE"/node-*.next
    cpu_restore_failed=0
    for cpu_record in "$CPU_STATE"/node-*; do
        [ -f "$cpu_record" ] || continue
        cpu_restore_record "$cpu_record" || cpu_restore_failed=1
    done
    [ "$cpu_restore_failed" = 0 ] || return 1
    rm -f "$CPU_STATE/owner"
}

cpu_watch() {
    cpu_watch_owner=$2
    cpu_watch_start=$3
    cpu_watch_boot=$4
    printf '%s %s %s %s\n' "$$" "$(cpu_identity "$$")" "$cpu_watch_owner" "$cpu_watch_start" > "$CPU_STATE/watcher"
    touch "$CPU_STATE/ack"
    while [ "$(cpu_identity "$cpu_watch_owner" || true)" = "$cpu_watch_start" ]; do
        [ "$(cat "$CPU_STATE/owner" 2>/dev/null)" = "$cpu_watch_owner $cpu_watch_start" ] || exit 0
        sleep 1
    done
    while :; do
        cpu_lock || { sleep 1; continue; }
        if [ "$(cat "$CPU_STATE/owner" 2>/dev/null)" != "$cpu_watch_owner $cpu_watch_start" ] ||
            [ "$(cat "$CPU_STATE/boot")" != "$cpu_watch_boot" ]; then
            cpu_unlock
            exit 0
        fi
        if cpu_restore_all; then
            cpu_unlock
            exit 0
        fi
        cpu_unlock
        sleep 1
    done
}

if [ "${1:-}" = watch ]; then
    cpu_watch "$@"
    exit
fi

cpu_new_records=
cpu_finish() {
    cpu_status=$?
    trap - EXIT
    if [ "$cpu_status" != 0 ]; then
        for cpu_new_record in $cpu_new_records; do
            cpu_restore_record "$cpu_new_record" || true
        done
    fi
    cpu_unlock || cpu_status=1
    if [ "$cpu_status" != 0 ]; then
        echo PULSE_CPU_ERROR
    else
        echo PULSE_CPU_OK
    fi
    exit "$cpu_status"
}

cpu_begin() {
    [ ! -L "$CPU_STATE" ]
    if [ ! -d "$CPU_STATE" ]; then mkdir -m 700 "$CPU_STATE"; fi
    [ "$(stat -c %u "$CPU_STATE")" = "$(id -u)" ]
    [ "$(stat -c %a "$CPU_STATE")" = 700 ]
    cpu_lock
    trap cpu_finish EXIT
    rm -f "$CPU_STATE"/node-*.next
    cpu_boot=$(cat /proc/sys/kernel/random/boot_id)
    if [ "$(cat "$CPU_STATE/boot" 2>/dev/null)" != "$cpu_boot" ]; then
        rm -f "$CPU_STATE"/node-* "$CPU_STATE/owner" "$CPU_STATE/watcher" "$CPU_STATE/ack"
        echo "$cpu_boot" > "$CPU_STATE/boot"
    fi
    if [ -f "$CPU_STATE/owner" ]; then
        read -r cpu_owner_pid cpu_owner_start < "$CPU_STATE/owner"
        if [ "$(cpu_identity "$cpu_owner_pid" || true)" != "$cpu_owner_start" ]; then
            cpu_restore_all
        else
            [ "$cpu_owner_pid" = "$CPU_OWNER_PID" ]
        fi
    fi
}

cpu_arm() {
    cpu_app_start=$(cpu_identity "$CPU_OWNER_PID")
    if [ ! -f "$CPU_STATE/owner" ]; then
        printf '%s %s\n' "$CPU_OWNER_PID" "$cpu_app_start" > "$CPU_STATE/owner.next"
        mv "$CPU_STATE/owner.next" "$CPU_STATE/owner"
    fi
    if [ -f "$CPU_STATE/watcher" ]; then
        read -r cpu_watcher_pid cpu_watcher_start cpu_watcher_owner cpu_watcher_owner_start < "$CPU_STATE/watcher" || true
        if [ "$cpu_watcher_owner" = "$CPU_OWNER_PID" ] && [ "$cpu_watcher_owner_start" = "$cpu_app_start" ] &&
            [ "$(cpu_identity "$cpu_watcher_pid" || true)" = "$cpu_watcher_start" ]; then return; fi
    fi
    rm -f "$CPU_STATE/ack"
    setsid sh "$0" watch "$CPU_OWNER_PID" "$cpu_app_start" "$cpu_boot" </dev/null >/dev/null 2>&1 &
    cpu_launch_polls=0
    until [ -f "$CPU_STATE/ack" ]; do
        cpu_launch_polls=$((cpu_launch_polls + 1))
        [ "$cpu_launch_polls" -lt 30 ] || return 1
        sleep 0.1
    done
    rm -f "$CPU_STATE/ack"
}

cpu_key() {
    printf '%s/node-%s-%s\n' "$CPU_STATE" "$(basename "$(dirname "$1")")" "$(basename "$1")"
}

cpu_release() {
    cpu_release_record=$(cpu_key "$1")
    [ ! -f "$cpu_release_record" ] || cpu_restore_record "$cpu_release_record"
}

cpu_write() {
    cpu_path=$1
    cpu_value=$2
    cpu_protect=$3
    cpu_entry=$(cpu_key "$cpu_path")
    cpu_mode=$(stat -c %a "$cpu_path")
    cpu_inode=$(stat -c %i "$cpu_path")
    if [ -f "$cpu_entry" ]; then
        read -r cpu_saved_path cpu_original cpu_saved_inode cpu_previous < "$cpu_entry"
        [ "$cpu_saved_path" = "$cpu_path" ]
        if [ "$cpu_saved_inode" != "$cpu_inode" ]; then
            rm -f "$cpu_entry"
        else
            cpu_protected=$(cpu_protected_mode "$cpu_original")
            [ "$cpu_mode" = "$cpu_protected" ] || [ "$cpu_mode" = 666 ]
        fi
    fi
    if [ ! -f "$cpu_entry" ]; then
        cpu_writable "$cpu_mode"
        cpu_original=$cpu_mode
        cpu_previous=0
    fi
    if [ "$cpu_protect" = 1 ]; then
        cpu_arm
        cpu_protected=$(cpu_protected_mode "$cpu_original")
        if [ "$cpu_previous" = "$cpu_value" ] && [ "$cpu_mode" = "$cpu_protected" ]; then return; fi
        if [ ! -f "$cpu_entry" ]; then
            printf '%s %s %s 0\n' "$cpu_path" "$cpu_original" "$cpu_inode" > "$cpu_entry.next"
            mv "$cpu_entry.next" "$cpu_entry"
            cpu_new_records="$cpu_new_records $cpu_entry"
        fi
        chmod 666 "$cpu_path"
        echo "$cpu_value" > "$cpu_path"
        chmod "$cpu_protected" "$cpu_path"
        printf '%s %s %s %s\n' "$cpu_path" "$cpu_original" "$cpu_inode" "$cpu_value" > "$cpu_entry.next"
        mv "$cpu_entry.next" "$cpu_entry"
    else
        cpu_current=$(cat "$cpu_path")
        cpu_needs_write=1
        case "$cpu_path" in
            */scaling_max_freq) [ "$cpu_current" -lt "$cpu_value" ] || cpu_needs_write=0 ;;
            *) [ "$cpu_current" != "$cpu_value" ] || cpu_needs_write=0 ;;
        esac
        if [ "$cpu_needs_write" = 1 ]; then
            chmod 666 "$cpu_path"
            echo "$cpu_value" > "$cpu_path"
            chmod "$cpu_original" "$cpu_path"
        fi
        cpu_release "$cpu_path"
    fi
}
