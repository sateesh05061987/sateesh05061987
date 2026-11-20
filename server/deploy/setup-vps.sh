#!/usr/bin/env bash
# One-time setup of the call-recording API on the LilliBlush VPS. Run from Git Bash on the dev machine:
#
#   VPS=root@72.61.232.205 ./setup-vps.sh
#
# Creates (only if missing): /var/www/callrec/{api/publish,recordings,backups}, MySQL database
# CallRecordingsDB + user callrec, /etc/callrec/api.env (connection string, API key, storage path),
# the callrec-api systemd unit and the recordings.lilliblush.com nginx site, then asks certbot
# for HTTPS. Safe to re-run: existing config, passwords and the API key are kept.
# It does not touch KATAVU's files, database, service or nginx site.
#
# Before running: add a DNS A record  recordings.lilliblush.com -> 72.61.232.205.
# After running:  ./publish.sh && ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

VPS="${VPS:-root@72.61.232.205}"
DOMAIN="${DOMAIN:-recordings.lilliblush.com}"

echo "==> Uploading service and nginx files to $VPS"
scp callrec-api.service "$VPS:/tmp/callrec-api.service"
scp callrec.nginx.conf "$VPS:/tmp/callrec.nginx.conf"

ssh "$VPS" "DOMAIN='$DOMAIN' bash -s" <<'REMOTE'
set -euo pipefail
trap 'echo "!! remote step failed at line $LINENO: $BASH_COMMAND"' ERR
ROOT=/var/www/callrec
ENV_DIR=/etc/callrec
ENV_FILE=$ENV_DIR/api.env
PORT=5025
DB_NAME=CallRecordingsDB
DB_USER=callrec

echo "==> Checking port $PORT is free"
if ss -ltnH "sport = :$PORT" | grep -q . && ! systemctl is-active --quiet callrec-api; then
  echo "!! port $PORT is already used by another program:"; ss -ltnp "sport = :$PORT"; exit 1
fi

echo "==> Folders"
umask 022
mkdir -p "$ROOT/api/publish" "$ROOT/recordings" "$ROOT/backups" "$ENV_DIR"
chown -R www-data:www-data "$ROOT/api" "$ROOT/recordings"
chmod 755 "$ROOT" "$ROOT/api" "$ROOT/api/publish" "$ENV_DIR"
chmod 750 "$ROOT/recordings"
chmod 700 "$ROOT/backups"   # database dumps: root only

NEW_KEY=""
if [ -f "$ENV_FILE" ]; then
  echo "==> $ENV_FILE exists: keeping its database password and API key"
else
  echo "==> MySQL database $DB_NAME and user $DB_USER"
  command -v mysql >/dev/null || { echo "!! mysql client not found"; exit 1; }
  # Upper + lower + digit + symbol for MySQL's validate_password (MEDIUM). '-' is safe in the
  # connection string, the env file and SQL quotes.
  DB_PASS="Cr-$(openssl rand -hex 24)-Z9"
  # Ubuntu's MySQL lets the local root user in through the socket without a password.
  mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
ALTER USER '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

  echo "==> Writing $ENV_FILE"
  NEW_KEY=$(openssl rand -hex 32)
  umask 027
  cat > "$ENV_FILE" <<ENV
ConnectionStrings__Default="Server=127.0.0.1;port=3306;Database=$DB_NAME;user=$DB_USER;password=$DB_PASS;"
Auth__ApiKey=$NEW_KEY
Storage__RootPath=$ROOT/recordings
Storage__MaxUploadBytes=209715200
Database__MigrateOnStartup=true
ENV
  chown root:www-data "$ENV_FILE"
  chmod 640 "$ENV_FILE"
fi

echo "==> systemd unit callrec-api"
install -m 644 /tmp/callrec-api.service /etc/systemd/system/callrec-api.service
systemctl daemon-reload
systemctl enable callrec-api >/dev/null   # started by deploy.sh once a build is installed

echo "==> nginx site $DOMAIN"
SITE=/etc/nginx/sites-available/callrec
if [ -f "$SITE" ]; then
  echo "    $SITE exists (certbot may have edited it): leaving it as it is"
else
  sed "s/recordings\.lilliblush\.com/$DOMAIN/" /tmp/callrec.nginx.conf > "$SITE"
  ln -sf "$SITE" /etc/nginx/sites-enabled/callrec
fi
nginx -t
systemctl reload nginx
rm -f /tmp/callrec-api.service /tmp/callrec.nginx.conf

echo "==> HTTPS certificate"
PUBLIC_IP=$(curl -s4 --max-time 5 https://api.ipify.org || true)
DNS_IP=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
if grep -q "listen 443" "$SITE"; then
  echo "    already has HTTPS"
elif [ -z "$DNS_IP" ] || { [ -n "$PUBLIC_IP" ] && [ "$DNS_IP" != "$PUBLIC_IP" ]; }; then
  echo "!! $DOMAIN resolves to '${DNS_IP:-nothing}', not this server (${PUBLIC_IP:-unknown})."
  echo "   Add the DNS A record, wait for it to resolve, then run on the VPS:"
  echo "   certbot --nginx -d $DOMAIN --redirect"
elif command -v certbot >/dev/null; then
  certbot --nginx -d "$DOMAIN" --redirect --non-interactive --agree-tos \
    || echo "!! certbot failed; run it by hand: certbot --nginx -d $DOMAIN --redirect"
else
  echo "!! certbot is not installed (apt install certbot python3-certbot-nginx), then:"
  echo "   certbot --nginx -d $DOMAIN --redirect"
fi

echo
echo "==> Setup done. Next, from the dev machine: ./publish.sh && ./deploy.sh"
if [ -n "$NEW_KEY" ]; then
  echo
  echo "    API key for the phone app (also in $ENV_FILE):"
  echo "    $NEW_KEY"
else
  echo "    API key: grep Auth__ApiKey $ENV_FILE"
fi
REMOTE
