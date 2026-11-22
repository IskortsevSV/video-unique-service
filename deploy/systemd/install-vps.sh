#!/usr/bin/env bash
# First installation on Ubuntu 24.04. Supply secrets as local files.
set -euo pipefail
umask 077
if [[ $EUID != 0 || $# != 6 ]]; then
    echo 'Run as root: install-vps.sh APP.jar APP_SHA256 telegram-bot-api API_SHA256 telegram.properties telegram-bot-api.env' >&2
    exit 2
fi
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 && -d /run/systemd/system ]] ||
    { echo 'Ubuntu 24.04 with systemd is required' >&2; exit 1; }
units=$(cd -- "$(dirname -- "$0")" && pwd)
app=/opt/video-unique-service
config=/etc/video-unique-service
api=/usr/local/bin/telegram-bot-api
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
verify_artifact() {
    local source=$1 expected=$2 name=$3
    [[ $expected =~ ^[[:xdigit:]]{64}$ && -s $source ]]
    install -m 0600 -- "$source" "$stage/$name"
    (cd "$stage" && printf '%s  %s\n' "$expected" "$name" | sha256sum --check --status)
}
verify_artifact "$1" "$2" app.jar
verify_artifact "$3" "$4" api
[[ -s $5 && -s $6 ]]

# Publish complete files in one rename. Existing local settings and releases win.
seed_file() {
    local src=$1 dest=$2 mode=$3 group=$4 tmp
    if [[ -e $dest ]]; then
        [[ -f $dest && ! -L $dest ]]
        return
    fi
    tmp=$(mktemp "$(dirname -- "$dest")/.vuniq-install.XXXXXX")
    install -o root -g "$group" -m "$mode" -- "$src" "$tmp"
    mv -T -- "$tmp" "$dest"
}

exec 8>/run/lock/vuniq-bootstrap.lock
flock -w 60 8
packages=(ca-certificates curl ffmpeg openjdk-25-jre-headless openssh-server sudo)
missing=()
for package in "${packages[@]}"; do
    if [[ $(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true) != installed ]]; then
        missing+=("$package")
    fi
done
if ((${#missing[@]})); then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
fi
if ! id vuniq >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /var/lib/video-unique-service --no-create-home --shell /usr/sbin/nologin vuniq
fi
install -d -o root -g root -m 0755 "$app"
install -d -o root -g vuniq -m 0750 "$config"
exec 9>"$app/.deploy.lock"
flock -w 60 9
if [[ ! -e $app/java ]]; then
    ln -s "/usr/lib/jvm/java-25-openjdk-$(dpkg --print-architecture)" "$app/java"
fi
"$app/java/bin/java" -version 2>&1 | grep -E 'version "25([.+"-]|$)'
seed_file "$stage/app.jar" "$app/video-unique-service.jar" 0644 root
seed_file "$stage/api" "$api" 0755 root
seed_file "$5" "$config/telegram.properties" 0640 vuniq
seed_file "$6" "$config/telegram-bot-api.env" 0600 root
for unit in telegram-bot-api.service video-unique-service.service; do
    seed_file "$units/$unit" "/etc/systemd/system/$unit" 0644 root
done
systemctl daemon-reload
systemctl enable --now telegram-bot-api.service
# An unauthenticated request to the actual local Bot API returns its JSON 404.
for attempt in {1..40}; do
    if curl -sS --max-time 2 http://127.0.0.1:8081/ 2>/dev/null | grep -Eq '"error_code"[[:space:]]*:[[:space:]]*404'; then
        break
    fi
    if ((attempt == 40)); then echo 'Local Bot API is unavailable' >&2; exit 1; fi
    sleep 1
done
systemctl enable --now video-unique-service.service
curl --retry 35 --retry-delay 1 --retry-connrefused --max-time 3 -fsS -o /dev/null http://127.0.0.1:8080/v3/api-docs
systemctl is-active telegram-bot-api.service video-unique-service.service
