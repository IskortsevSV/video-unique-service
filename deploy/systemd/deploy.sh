#!/usr/bin/env bash
set -euo pipefail
umask 077
# Complete recovery even if the SSH client disconnects during restart.
trap '' PIPE
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

app_dir=/opt/video-unique-service
service=video-unique-service.service
health_url=http://127.0.0.1:8080/v3/api-docs
app="$app_dir/video-unique-service.jar"
previous="$app.previous"

health() {
    local pid current
    for ((attempt = 0; attempt < 40; attempt++)); do
        if systemctl is-active --quiet "$service" && curl -fsS --max-time 2 -o /dev/null "$health_url"; then
            pid=$(systemctl show -p MainPID --value "$service")
            sleep 3
            current=$(systemctl show -p MainPID --value "$service")
            if [[ "$pid" != 0 && "$pid" == "$current" ]] &&
                systemctl is-active --quiet "$service" && curl -fsS --max-time 2 -o /dev/null "$health_url"; then
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

exec 9>"$app_dir/.deploy.lock"
flock -w 5 9 || { echo 'Another deployment is running.' >&2; exit 1; }
work_dir=$(mktemp -d "$app_dir/.deploy.XXXXXX")
restore=0

finish() {
    local status=$?
    trap - EXIT
    trap '' HUP INT TERM
    if ((restore)); then
        echo 'New release failed its check. Restoring the previous JAR.' >&2
        if systemctl stop "$service" &&
            mv -f "$work_dir/old.jar" "$app" &&
            systemctl reset-failed "$service" &&
            systemctl start "$service" && health; then
            echo 'Previous JAR restored and healthy.' >&2
        else
            echo "Rollback failed. Inspect $service and $work_dir." >&2
            exit 2
        fi
        status=1
    fi
    rm -f "$work_dir/incoming.jar" "$work_dir/new.jar" "$work_dir/old.jar"
    rmdir "$work_dir"
    exit "$status"
}
trap finish EXIT

IFS=' ' read -r -t 15 -n 115 operation expected_hash commit extra
case "$operation" in
    deploy)
        [[ "$expected_hash" =~ ^[0-9a-f]{64}$ && "$commit" =~ ^[0-9a-f]{40}$ && -z "$extra" ]]
        max_bytes=125829120
        timeout 120 head -c "$((max_bytes + 1))" > "$work_dir/incoming.jar"
        size=$(stat -c %s "$work_dir/incoming.jar")
        ((size > 0 && size <= max_bytes))
        printf '%s  %s\n' "$expected_hash" "$work_dir/incoming.jar" | sha256sum -c -
        candidate="$work_dir/incoming.jar"
        ;;
    rollback)
        [[ -z "$expected_hash$commit$extra" ]]
        if [[ ! -f "$previous" ]]; then
            echo 'No previous release is available.' >&2
            exit 1
        fi
        candidate="$previous"
        commit=rollback
        ;;
    *)
        echo 'Expected deploy <sha256> <commit> or rollback.' >&2
        exit 1
        ;;
esac

cp -p "$app" "$work_dir/old.jar"
install -m 0644 "$candidate" "$work_dir/new.jar"
restore=1
systemctl stop "$service"
mv -f "$work_dir/new.jar" "$app"
systemctl reset-failed "$service"
systemctl start "$service"
health
mv -f "$work_dir/old.jar" "$previous"
restore=0

read -r active_hash _ < <(sha256sum "$app")
printf '%s %s %s\n' "$(date -u +%FT%TZ)" "$commit" "$active_hash" >> "$app_dir/releases.log"
echo "Service healthy. Release $commit SHA-256 $active_hash"
