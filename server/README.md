# Recording server

ASP.NET Core 8 Web API that receives call recordings from the Android app, stores the audio on
disk (`{Storage:RootPath}/yyyy/MM/`) and the metadata in SQL Server via EF Core.

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

`Recordings` table: `Id, FileName, RelativePath, Direction, PhoneNumber, StartedAt, DurationSeconds,
AudioSource, DeviceName, SizeBytes, FileHash, UploadedAt`. `RelativePath` (where the file sits under
the storage root) and `FileHash` (for duplicate detection) are additions to the columns you listed.

## Configuration

See `appsettings.sample.json`. Every setting can also be an environment variable
(`Auth__ApiKey`, `ConnectionStrings__Default`, `Storage__RootPath`, ...).

| Key | Meaning |
|---|---|
| `ConnectionStrings:Default` | SQL Server connection string. |
| `Auth:ApiKey` | Shared secret, at least 24 characters. The server refuses to start without it. Generate with `openssl rand -base64 32`. |
| `Storage:RootPath` | Where audio files go. Relative paths are relative to the app folder. |
| `Storage:MaxUploadBytes` | Max file size in bytes. |
| `Database:MigrateOnStartup` | `true` applies EF migrations at startup. |
| `UseHttpsRedirection` | `true` when Kestrel itself serves HTTPS (not needed behind a reverse proxy). |

Don't commit real secrets: use environment variables, `dotnet user-secrets`, or an untracked
`appsettings.Production.json` (it's in `.gitignore`).

## Build and run locally

Requires the .NET 8 SDK and a SQL Server (or `docker run -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='Your_Passw0rd!' -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest`).

```bash
cd server
dotnet build
dotnet tool restore                      # installs dotnet-ef from .config/dotnet-tools.json

export ConnectionStrings__Default='Server=localhost,1433;Database=CallRecordings;User Id=sa;Password=Your_Passw0rd!;TrustServerCertificate=True'
export Auth__ApiKey="$(openssl rand -base64 32)"
dotnet ef database update                # or set Database__MigrateOnStartup=true
dotnet run --urls http://localhost:5080

curl -H "X-Api-Key: $Auth__ApiKey" http://localhost:5080/api/ping
```

The Android app only talks HTTPS, so for testing from a phone put it behind HTTPS (see below).

### Migrations

`Migrations/` holds `InitialCreate`. After changing the model:

```bash
dotnet ef migrations add <Name>
dotnet ef migrations script --idempotent -o migrate.sql   # if you prefer applying SQL by hand
```

## Deploy with Docker Compose (SQL Server + API + automatic HTTPS)

On a Linux host with Docker, a public IP, and a DNS name pointing at it (ports 80 and 443 open):

```bash
cd server
cp .env.sample .env        # set DOMAIN, API_KEY, SQL_SA_PASSWORD
docker compose up -d --build
docker compose logs -f api
curl -H "X-Api-Key: <API_KEY>" https://<DOMAIN>/api/ping
```

Caddy gets a Let's Encrypt certificate for `DOMAIN` and proxies to the API, so the phone's
system trust store accepts it with no extra setup. Audio is in the `recordings` volume, the
database in `sqldata`. Back up both.

Want only the API container (with your own SQL Server and reverse proxy)?

```bash
docker build -t recording-server .
docker run -d -p 8080:8080 \
  -e ConnectionStrings__Default='...' -e Auth__ApiKey='...' -e Database__MigrateOnStartup=true \
  -v /srv/recordings:/data/recordings recording-server
```

If your reverse proxy (nginx, IIS, ...) has its own body-size limit, raise it to match
`MaxUploadBytes` (nginx: `client_max_body_size 210m;`).

### Without Docker (Windows/IIS or Linux service)

`dotnet publish -c Release -o out`, copy `out/` to the server, set the environment variables
above, and host it behind IIS (ASP.NET Core Hosting Bundle) or as a systemd service behind nginx,
with a valid TLS certificate.
