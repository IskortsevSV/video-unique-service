#!/usr/bin/env bash
# Destructive scenarios belong exclusively to a disposable GitHub-hosted Linux VM.
set -euo pipefail
umask 077
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted && $EUID != 0 && $# == 2 ]]
units=$(cd -- "$(dirname -- "$0")" && pwd)
a=$(realpath "$1")
binary=$(realpath "$2")
app=/opt/video-unique-service/video-unique-service.jar
config=/etc/video-unique-service
state=/var/lib/video-unique-service/state
for path in /opt/video-unique-service "$config" /var/lib/video-unique-service /var/lib/vuniq-deploy /usr/local/sbin/vuniq-deploy /usr/local/bin/telegram-bot-api; do
    [[ ! -e $path ]] || { echo "Occupied test path: $path" >&2; exit 1; }
done
for user in vuniq vuniq-deploy; do
    if id "$user" >/dev/null 2>&1; then echo "Account $user already exists" >&2; exit 1; fi
done
for unit in video-unique-service telegram-bot-api; do
    [[ $(systemctl show "$unit" -p LoadState --value) == not-found ]]
done
[[ -z $(ss -Hlt '( sport = :8080 or sport = :8081 )') ]]
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
b=$tmp/candidate.jar
cp "$a" "$b"
printf 'Stand release B\n' > "$tmp/qa-version"
jar uf "$b" -C "$tmp" qa-version
hash() { sha256sum "$1" | cut -d' ' -f1; }
sha_a=$(hash "$a")
sha_b=$(hash "$b")
sha_api=$(hash "$binary")
[[ $sha_a != "$sha_b" ]]
printf 'telegram.enabled=false\n' > "$tmp/app.properties"
printf 'TELEGRAM_BOT_ID=321\nTELEGRAM_API_ID=12345\nTELEGRAM_API_HASH=%032d\n' 1 > "$tmp/api.env"

install_vps() {
    sudo bash "$units/install-vps.sh" "$1" "$2" "$binary" "$sha_api" "$tmp/app.properties" "$tmp/api.env"
}
install_key() { sudo bash "$units/setup-deploy-ssh.sh" "$tmp/$1.pub"; }
pid() { systemctl show "$1" -p MainPID --value; }
check_active() {
    [[ $(hash "$app") == "$1" ]]
    systemctl is-active --quiet video-unique-service
    curl -fsS http://127.0.0.1:8080/v3/api-docs | jq -e '.paths["/api/videos/unique"].post != null' >/dev/null
}
install_vps "$a" "$sha_a"
check_active "$sha_a"
systemctl is-enabled video-unique-service telegram-bot-api
# Exercise the full REST path with a real, disposable video.
ffmpeg -v error -f lavfi -i color=size=64x64:duration=1 -c:v libx264 "$tmp/in.mp4"
curl -fsS -D "$tmp/headers" -o "$tmp/out.mp4" -F "file=@$tmp/in.mp4;type=video/mp4" http://127.0.0.1:8080/api/videos/unique
tr -d '\r' < "$tmp/headers" > "$tmp/headers.txt"
grep -Fxi "X-Hash-Before: $(hash "$tmp/in.mp4")" "$tmp/headers.txt"
grep -Fxi "X-Hash-After: $(hash "$tmp/out.mp4")" "$tmp/headers.txt"
[[ $(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$tmp/out.mp4") == h264 ]]
ssh-keygen -q -t ed25519 -N '' -f "$tmp/first"
ssh-keygen -q -t ed25519 -N '' -f "$tmp/actions"
install_key first
install_key actions
keys=/var/lib/vuniq-deploy/.ssh/authorized_keys
[[ $(wc -l < "$keys") == 2 ]]
pid_a=$(pid video-unique-service)
pid_api=$(pid telegram-bot-api)
printf 'preserved\n' | sudo -u vuniq tee "$state/bootstrap-check" >/dev/null
snapshot() {
    sudo sha256sum "$config/telegram.properties" "$config/telegram-bot-api.env" "$state/bootstrap-check" "$keys"
}
before=$(snapshot)
printf '# changed input\n' >> "$tmp/app.properties"
printf '# changed input\n' >> "$tmp/api.env"
install_vps "$b" "$sha_b"
install_key actions
[[ $(snapshot) == "$before" && $(pid video-unique-service) == "$pid_a" && $(pid telegram-bot-api) == "$pid_api" ]]
check_active "$sha_a"
echo 'PASS repeat bootstrap preserves JAR, configuration, state, keys and both PIDs'

sudo systemctl start ssh
read -r type hostkey _ < /etc/ssh/ssh_host_ed25519_key.pub
printf '127.0.0.1 %s %s\n' "$type" "$hostkey" > "$tmp/known_hosts"
remote() {
    ssh -T -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
        -o "UserKnownHostsFile=$tmp/known_hosts" -i "$tmp/actions" vuniq-deploy@127.0.0.1 "$@"
}
# A requested shell command still goes through the restricted stdin protocol.
if printf 'invalid\n' | remote id > "$tmp/ssh.log" 2>&1; then exit 1; fi
grep -F 'Expected deploy' "$tmp/ssh.log"
deploy() {
    { printf 'deploy %s %s\n' "$2" "$GITHUB_SHA"; cat "$1"; } | remote
}
if deploy "$b" "$sha_a" > "$tmp/bad-hash.log" 2>&1; then exit 1; fi
grep -F 'FAILED' "$tmp/bad-hash.log"
check_active "$sha_a"
[[ $(pid video-unique-service) == "$pid_a" && ! -e $app.previous ]]
echo 'PASS invalid checksum rejected before restart'

deploy "$b" "$sha_b"
check_active "$sha_b"
[[ $(hash "$app.previous") == "$sha_a" && $(pid video-unique-service) != "$pid_a" ]]
echo 'PASS release B deployed'

printf 'invalid JAR for recovery test\n' > "$tmp/broken.jar"
if deploy "$tmp/broken.jar" "$(hash "$tmp/broken.jar")" > "$tmp/recovery.log" 2>&1; then exit 1; fi
grep -F 'Previous JAR restored and healthy' "$tmp/recovery.log"
check_active "$sha_b"
[[ $(hash "$app.previous") == "$sha_a" ]]
echo 'PASS automatic rollback restored release B and retained release A'

printf 'rollback\n' | remote
check_active "$sha_a"
[[ $(hash "$app.previous") == "$sha_b" ]]
echo 'PASS manual rollback restored release A'
