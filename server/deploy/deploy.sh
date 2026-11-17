#!/usr/bin/env bash
# Deploys the call-recording API to the VPS. Run from Git Bash on the dev machine:
#
#   ./publish.sh
#   VPS=root@72.61.232.205 ./deploy.sh ../publish-out/callrec-api.tar.gz
#
# Run ./setup-vps.sh once before the first deploy. The package contains no appsettings*.json:
# the server keeps its own config in /etc/callrec/api.env. Before replacing anything it backs up
# the database (the API applies EF migrations on start) and the current build to
# /var/www/callrec/backups/<timestamp>/. Recordings in /var/www/callrec/recordings are never touched.
set -euo pipefail
cd "$(dirname "$0")"

VPS="${VPS:-root@72.61.232.205}"
API_PKG="${1:-../publish-out/callrec-api.tar.gz}"
[ -f "$API_PKG" ] || { echo "!! $API_PKG not found: run ./publish.sh first"; exit 1; }

echo "==> Uploading package to $VPS"
scp "$API_PKG" "$VPS:/tmp/callrec-api.tar.gz"

ssh "$VPS" 'bash -s' <<'REMOTE'
set -euo pipefail
trap 'echo "!! remote step failed at line $LINENO: $BASH_COMMAND"' ERR
ROOT=/var/www/callrec
PUBLISH=$ROOT/api/publish
ENV_FILE=/etc/callrec/api.env
TS=$(date +%Y%m%d-%H%M%S)
BACKUP=$ROOT/backups/$TS

[ -f "$ENV_FILE" ] || { echo "!! $ENV_FILE not found: run setup-vps.sh first"; exit 1; }
umask 077   # backups hold the whole database: root only
mkdir -p "$BACKUP"

echo "==> Backing up database"
CS=$(sed -n 's/^ConnectionStrings__Default=//p' "$ENV_FILE" | head -1 \
       | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")
field() { echo "$CS" | tr ';' '\n' | sed 's/^[[:space:]]*//' | grep -iE "^($1)[[:space:]]*=" | head -1 | cut -d= -f2- || true; }
DB_HOST=$(field 'server|host|data source|datasource'); DB_PORT=$(field port)
DB_NAME=$(field 'database|initial catalog'); DB_USER=$(field 'user|user id|uid|username')
DB_PASS=$(field 'password|pwd')
[ -n "$DB_HOST" ] && [ -n "$DB_NAME" ] && [ -n "$DB_USER" ] \
  || { echo "!! could not read the DB connection string from $ENV_FILE"; exit 1; }
command -v mysqldump >/dev/null || { echo "!! mysqldump not installed: install mysql-client first"; exit 1; }
if ! MYSQL_PWD="$DB_PASS" mysqldump -h "$DB_HOST" -P "${DB_PORT:-3306}" -u "$DB_USER" \
     --single-transaction --routines --no-tablespaces "$DB_NAME" | gzip > "$BACKUP/db.sql.gz"; then
  rm -rf "$BACKUP"
  echo "!! database backup failed: nothing was changed"; exit 1
fi
echo "    $(du -h "$BACKUP/db.sql.gz" | cut -f1) -> $BACKUP/db.sql.gz"

if [ -n "$(ls -A "$PUBLISH" 2>/dev/null)" ]; then
  echo "==> Backing up current API build"
  tar -czf "$BACKUP/api-publish.tar.gz" -C "$ROOT/api" publish
fi

# Keep build copies for the last 3 deploys only; every database backup is kept (they are small).
echo "==> Removing build copies older than the last 3 deploys (database backups are kept)"
for OLD in $(ls -1d "$ROOT"/backups/2*/ | sort | head -n -3); do
  rm -f "$OLD/api-publish.tar.gz"
done

echo "==> Installing API"
umask 022
rm -rf "$PUBLISH.new"
mkdir -p "$PUBLISH.new"
tar -xzf /tmp/callrec-api.tar.gz -C "$PUBLISH.new"
[ -f "$PUBLISH.new/RecordingServer" ] || { echo "!! package has no RecordingServer binary"; exit 1; }
chmod +x "$PUBLISH.new/RecordingServer"
chown -R www-data:www-data "$PUBLISH.new"
systemctl stop callrec-api || true
rm -rf "$PUBLISH.old"
mv "$PUBLISH" "$PUBLISH.old"
mv "$PUBLISH.new" "$PUBLISH"

echo "==> Starting API (applies database migrations)"
systemctl start callrec-api

# /healthz answers 200 once the app is up; /api/ping without a key must answer 401.
HEALTH=000; PING=000
for _ in $(seq 1 30); do
  HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:5025/healthz || true)
  [ "$HEALTH" = "200" ] && break
  sleep 2
done
[ "$HEALTH" = "200" ] && PING=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:5025/api/ping || true)

if [ "$HEALTH" != "200" ] || [ "$PING" != "401" ]; then
  echo "!! API did not come up (healthz: $HEALTH, ping without key: $PING). Recent log:"
  journalctl -u callrec-api -n 60 --no-pager || true
  echo
  echo "Rollback:  systemctl stop callrec-api && rm -rf $PUBLISH && mv $PUBLISH.old $PUBLISH && systemctl start callrec-api"
  echo "Database:  gunzip < $BACKUP/db.sql.gz | mysql $DB_NAME   (only if the migration left it broken)"
  exit 1
fi

rm -f /tmp/callrec-api.tar.gz
echo "==> Deployed. Backups in $BACKUP. Recordings: $ROOT/recordings/yyyy/MM/"
journalctl -u callrec-api -n 15 --no-pager | grep -iE "migrat|error|fail" || true
REMOTE
