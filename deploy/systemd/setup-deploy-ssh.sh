#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $EUID != 0 || $# != 1 ]]; then
    echo 'Run as root: setup-deploy-ssh.sh ACTIONS_PUBLIC_KEY.pub' >&2
    exit 2
fi
mapfile -t lines < "$1"
[[ ${#lines[@]} == 1 ]]
read -r type blob comment <<< "${lines[0]}"
[[ $type == ssh-ed25519 && $blob =~ ^[A-Za-z0-9+/]+=*$ ]] ||
    { echo 'Expected one OpenSSH Ed25519 public key' >&2; exit 1; }
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
printf '%s %s\n' "$type" "$blob" > "$stage/key.pub"
ssh-keygen -lf "$stage/key.pub" >/dev/null
handler=/usr/local/sbin/vuniq-deploy
source_handler=$(dirname -- "$0")/deploy.sh
bash -n "$source_handler"
printf 'vuniq-deploy ALL=(root) NOPASSWD: %s ""\n' "$handler" > "$stage/sudoers"
visudo -cf "$stage/sudoers"
exec 8>/run/lock/vuniq-bootstrap.lock
flock -w 60 8
account=vuniq-deploy
home_dir=/var/lib/vuniq-deploy
if ! id "$account" >/dev/null 2>&1; then
    useradd --system --user-group --home-dir "$home_dir" --no-create-home --shell /bin/sh "$account"
fi
[[ $(getent passwd "$account" | cut -d: -f6,7) == "$home_dir:/bin/sh" ]] ||
    { echo 'Existing deploy account has unexpected home or shell' >&2; exit 1; }

# The deploy account must never have write access to its key restrictions.
for dir in "$home_dir" "$home_dir/.ssh"; do
    if [[ -e $dir || -L $dir ]]; then
        [[ -d $dir && ! -L $dir && $(stat -c %u "$dir") == 0 ]] &&
            [[ -z $(find "$dir" -maxdepth 0 -perm /022 -print) ]] ||
            { echo "Unsafe directory: $dir" >&2; exit 1; }
    else
        install -d -o root -g root -m 0755 "$dir"
    fi
done
keys="$home_dir/.ssh/authorized_keys"
entry="restrict,command=\"/usr/bin/sudo -n $handler\" $type $blob"
if [[ -e $keys || -L $keys ]]; then
    [[ -f $keys && ! -L $keys && $(stat -c %u "$keys") == 0 && -z $(find "$keys" -maxdepth 0 -perm /022 -print) ]]
    cp -- "$keys" "$stage/keys"
else
    touch "$stage/keys"
fi
if ! awk -v key="$blob" -v entry="$entry" '
    {for (i=1; i<=NF; i++) if ($i == key && $0 != entry && index($0, entry " ") != 1) exit 1}
' "$stage/keys"; then
    echo 'This key already exists with different SSH options; inspect authorized_keys' >&2
    exit 1
fi
if ! grep -Fqx -- "$entry" "$stage/keys" && ! grep -Fq -- "$entry " "$stage/keys"; then
    # A file edited by hand may lack a final newline.
    sed -i -e '$a\' "$stage/keys"
    printf '%s\n' "$entry" >> "$stage/keys"
fi

publish() {
    local source=$1 dest=$2 mode=$3 tmp
    tmp=$(mktemp "$(dirname -- "$dest")/.vuniq-ssh.XXXXXX")
    install -o root -g root -m "$mode" -- "$source" "$tmp"
    mv -Tf -- "$tmp" "$dest"
}
publish "$source_handler" "$handler" 0755
publish "$stage/sudoers" "/etc/sudoers.d/$account" 0440
publish "$stage/keys" "$keys" 0644
echo 'Restricted deployment key installed'
