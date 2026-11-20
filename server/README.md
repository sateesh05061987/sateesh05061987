# KATAVU Usage server

The server side of **KATAVU Usage**: an ASP.NET Core (.NET 10) Web API that receives call recordings from the Android app. It stores the
audio on disk (`{Storage:RootPath}/yyyy/MM/`) and the metadata in MySQL via EF Core (Pomelo).
It runs on the **LilliBlush VPS** next to KATAVU and is set up the same way: nginx → systemd →
self-contained build, MySQL, config in `/etc/<app>/api.env`, deployed with `deploy.sh` from Git Bash.

| | KATAVU | KATAVU Usage |
|---|---|---|
| Address | `katavu.lilliblush.com` | `recordings.lilliblush.com` |
| Internal port | 127.0.0.1:5024 | 127.0.0.1:5025 |
| systemd unit | `katavu-api` | `callrec-api` |
| App folder | `/var/www/katavu/api/publish` | `/var/www/callrec/api/publish` |
| Config | `/etc/katavu/api.env` | `/etc/callrec/api.env` |
| Database | `CaneLedgerDB` | `CallRecordingsDB` (user `callrec`) |
| **Recordings** | | **`/var/www/callrec/recordings/yyyy/MM/*.m4a`** |
| Backups | `/var/www/katavu/backups/<ts>/` | `/var/www/callrec/backups/<ts>/` |

## Web app

`https://recordings.lilliblush.com/` lists every recording, newest first. You can search by phone number,
play a recording in the browser, download it, or delete it (after a confirmation; this removes the file and
its database row). It's one static page (`wwwroot/index.html`, no build step) served by this API, so it
deploys with `deploy.sh` like everything else.

- **Login:** one admin user, set with `deploy/set-admin-password.sh`. It generates a password (or set
  `ADMIN_PASSWORD=...`, `ADMIN_USER=...`), hashes it on your PC (PBKDF2-SHA256), writes only the hash to
  `/etc/callrec/api.env` (`Admin__Username`, `Admin__PasswordHash`) and restarts the API. Run it again to change
  the password. Until it has been run, the web login is disabled and the phone app still works.
- **Sessions:** an HttpOnly, Secure, SameSite=Strict cookie that lasts 12 hours (sliding). Cookie keys are kept in
  `/var/www/callrec/recordings/.keys` (owner-only), so restarts and deploys don't sign you out.
- **Limits:** 10 login attempts per 15 minutes per address. Requests that change data from the browser must
  also send `X-Requested-With`.
- The phone app keeps using `X-Api-Key`; every `/api` endpoint accepts either the key or a signed-in cookie.

## API

Every `/api/*` endpoint requires the header `X-Api-Key: <Auth:ApiKey>` (401 otherwise).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/recordings` | `multipart/form-data`: `file` (.m4a), `direction` (`in`/`out`), `phoneNumber`, `startedAt` (ISO 8601), `durationSeconds`, `audioSource` (`CALL`/`VREC`/`MIC`), `deviceName`. Returns **201** with the record, **409** `{existingId}` if the same file (SHA-256) is already stored, **400** on validation errors, **413** if too large. |
| `GET` | `/api/recordings?page=1&pageSize=50&phoneNumber=555` | Paged list, newest first. `pageSize` max 200. |
| `GET` | `/api/recordings/{id}` | One record. |
| `GET` | `/api/recordings/{id}/audio` | Streams the file (`audio/mp4`, supports `Range` for seeking). |
| `DELETE` | `/api/recordings/{id}` | Deletes the row and the file. 204. |
| `GET` | `/api/ping` | Used by the app's "Test connection" button. |
| `GET` | `/healthz` | Unauthenticated health check. |

Upload checks: size limit (`Storage:MaxUploadBytes`, default 200 MB, enforced at Kestrel and in the
form reader), extension must be `.m4a`, content must start with an MP4 `ftyp` box, duplicates are
rejected by SHA-256 (unique index, so concurrent duplicates are caught too).

`Recordings` table (MySQL): `Id, FileName, RelativePath, Direction, PhoneNumber, StartedAt, DurationSeconds,
AudioSource, DeviceName, SizeBytes, FileHash, UploadedAt`. `RelativePath` (where the file sits under
the storage root) and `FileHash` (for duplicate detection) are additions to the columns you listed.
MySQL has no offset-aware date type, so `startedAt` / `uploadedAt` are stored and returned in UTC
(`09:30+05:30` comes back as `04:00+00:00`, the same moment).

## Configuration

On the VPS everything comes from `/etc/callrec/api.env` (written by `setup-vps.sh`). The deployed package
has no `appsettings*.json`, as with KATAVU. Locally, use environment variables or `appsettings.Development.json`
(see `appsettings.sample.json`).

| Setting (env var) | Meaning |
|---|---|
| `ConnectionStrings__Default` | MySQL connection string, e.g. `Server=127.0.0.1;port=3306;Database=CallRecordingsDB;user=callrec;password=...;` |
| `Auth__ApiKey` | Shared secret, at least 24 characters. The server refuses to start without it. |
| `Storage__RootPath` | Where audio files go (VPS: `/var/www/callrec/recordings`). |
| `Storage__MaxUploadBytes` | Max file size in bytes (default 200 MB; nginx allows 210 MB). |
| `Database__MigrateOnStartup` | `true` applies EF migrations at startup (on for the VPS). |

## Build and run locally

Requires the .NET 10 SDK and a MySQL 8 (`docker run -d -e MYSQL_ROOT_PASSWORD=pw -p 3306:3306 mysql:8.0`).

```bash
cd server
dotnet build
dotnet tool restore                      # dotnet-ef from .config/dotnet-tools.json

export ConnectionStrings__Default='Server=127.0.0.1;port=3306;Database=CallRecordingsDB;user=root;password=pw;'
export Auth__ApiKey="$(openssl rand -hex 32)"
export Database__MigrateOnStartup=true
dotnet run --urls http://localhost:5080
curl -H "X-Api-Key: $Auth__ApiKey" http://localhost:5080/api/ping
```

After changing the model: `dotnet ef migrations add <Name>` (no database needed; the MySQL version is fixed
in `AppDbContext.ServerVersion`, as in KATAVU).

## Deploy to the LilliBlush VPS (72.61.232.205)

Everything runs from **Git Bash on your PC** (needs the .NET 10 SDK and SSH access as `root@72.61.232.205`,
the same as KATAVU's `deploy.sh`). The scripts are in `deploy/`.

### First time

1. **DNS:** add an A record `recordings` → `72.61.232.205` for `lilliblush.com`, and wait until
   `nslookup recordings.lilliblush.com` shows that IP.
2. **Set up the server once:**
   ```bash
   cd server/deploy
   ./setup-vps.sh
   ```
   This creates the folders, the MySQL database and user (with a random password), `/etc/callrec/api.env`
   (connection string, a random API key, storage path), the `callrec-api` systemd unit and the nginx site,
   then gets an HTTPS certificate with certbot. **It prints the API key at the end. Copy it for the phone.**
   It doesn't touch KATAVU's files, database, service or nginx site. You can run it again safely: it keeps
   the existing passwords and key. It stops if port 5025 is already in use.
3. **Deploy:**
   ```bash
   ./publish.sh     # builds ../publish-out/callrec-api.tar.gz (self-contained linux-x64)
   ./deploy.sh      # uploads, backs up, installs, starts, checks
   ```
4. **Phone:** Settings → Server URL `https://recordings.lilliblush.com`, paste the API key →
   **Test connection** → **Save**.

### Every update

```bash
cd server/deploy && ./publish.sh && ./deploy.sh
```

`deploy.sh` first backs up the database (`mysqldump`) and the current build to
`/var/www/callrec/backups/<timestamp>/`. If that backup fails it stops and changes nothing. Then it swaps
in the new build, starts it (EF migrations run on start) and checks `/healthz` (200) and `/api/ping` without
a key (401). If the check fails it prints the log and the exact rollback command. Build copies are kept for
the last 3 deploys and database backups are all kept. Recordings are never touched.

### On the VPS

```bash
systemctl status callrec-api                 # running?
journalctl -u callrec-api -f                 # logs
grep Auth__ApiKey /etc/callrec/api.env       # the API key again
ls /var/www/callrec/recordings/2026/11/      # this month's recordings
du -sh /var/www/callrec/recordings           # disk used
```

To change the API key, edit `Auth__ApiKey` in `/etc/callrec/api.env`, run `systemctl restart callrec-api`,
and enter the new key on the phone.

Back up `/var/www/callrec/recordings` (the audio) as well as the database. `deploy.sh` only backs up the database.

### Docker (elsewhere)

The `Dockerfile` still builds a plain image if you ever want to run it somewhere other than LilliBlush:
`docker run -p 8080:8080 -e ConnectionStrings__Default=... -e Auth__ApiKey=... -e Database__MigrateOnStartup=true -v /srv/recordings:/data/recordings <image>`.
