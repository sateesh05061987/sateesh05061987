#!/usr/bin/env bash
# Sets (or changes) the web-app login at https://recordings.lilliblush.com. Run from Git Bash:
#
#   ./set-admin-password.sh                 # user "admin", random password, printed once
#   ADMIN_USER=sateesh ./set-admin-password.sh
#   ADMIN_PASSWORD='my own password' ./set-admin-password.sh
#
# The password is hashed (PBKDF2-SHA256) on this machine; only the hash goes to the VPS, into
# /etc/callrec/api.env as Admin__Username / Admin__PasswordHash. Then the API is restarted.
set -euo pipefail
cd "$(dirname "$0")/.."

VPS="${VPS:-root@72.61.232.205}"
ADMIN_USER="${ADMIN_USER:-admin}"
GENERATED=""
if [ -z "${ADMIN_PASSWORD:-}" ]; then
  ADMIN_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)"
  GENERATED=1
fi
[ "${#ADMIN_PASSWORD}" -ge 10 ] || { echo "!! password must be at least 10 characters"; exit 1; }
case "$ADMIN_USER" in *[!A-Za-z0-9._-]*|"") echo "!! username may only use letters, digits, . _ -"; exit 1 ;; esac

echo "==> Hashing password"
HASH=$(printf '%s\n' "$ADMIN_PASSWORD" | dotnet run --project RecordingServer.csproj -c Release -- --hash-password)
case "$HASH" in pbkdf2-sha256\$*) ;; *) echo "!! hashing failed: $HASH"; exit 1 ;; esac

echo "==> Writing login to $VPS:/etc/callrec/api.env and restarting callrec-api"
# Both values are checked to hold only safe characters, so they can go in the remote command.
# Single quotes in the env file stop systemd expanding the '$' in the hash.
case "$HASH" in *[!A-Za-z0-9+/=\$-]*) echo "!! unexpected characters in hash"; exit 1 ;; esac
ssh "$VPS" "USER_NAME='$ADMIN_USER' HASH='$HASH' bash -s" <<'REMOTE'
set -euo pipefail
ENV_FILE=/etc/callrec/api.env
[ -f "$ENV_FILE" ] || { echo "!! $ENV_FILE not found: run setup-vps.sh first"; exit 1; }
sed -i '/^Admin__Username=/d; /^Admin__PasswordHash=/d' "$ENV_FILE"
printf "Admin__Username=%s\nAdmin__PasswordHash='%s'\n" "$USER_NAME" "$HASH" >> "$ENV_FILE"
SINCE=$(date '+%Y-%m-%d %H:%M:%S')
systemctl restart callrec-api
for _ in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:5025/healthz)" = 200 ] && break; sleep 1
done
# Only this start's log: earlier starts (e.g. a deploy before any login was set) also log the warning.
if journalctl -u callrec-api --since "$SINCE" --no-pager | grep -q "web app login is disabled"; then
  echo "!! the API did not pick up the login"; exit 1
fi
echo "    done"
REMOTE

echo
echo "==> Web app: https://recordings.lilliblush.com"
echo "    Username: $ADMIN_USER"
if [ -n "$GENERATED" ]; then echo "    Password: $ADMIN_PASSWORD   (shown once; run this script again to change it)"; fi
