# KATAVU Usage (Android)

KATAVU Usage: a sideloaded call recorder (minSdk 26, package `com.katavu.usage`) that uploads every finished recording to your own server
(see `../server`).

> **About this project.** Your real app's code wasn't in the repository, so this is a new project
> with a **placeholder** `CallRecorderService`. The upload feature lives in
> `app/src/main/java/com/katavu/usage/upload/` and needs only two calls from a recorder.
> See [Merging into your existing app](#merging-into-your-existing-app).

## Configuration (hardcoded)

The server URL and API key are baked into the APK at build time, so there is **no in-app settings
screen**. They come from `android/apikey.properties` (git-ignored, so the key never reaches the public
repo); `apikey.properties.sample` shows the shape, and the URL also has a default in `app/build.gradle.kts`:

```
API_KEY=<the server API key>
SERVER_URL=https://recordings.lilliblush.com
```

The key ends up in the APK, so anyone who has the APK can read it — fine for a private sideloaded app,
but don't share the APK. To rotate: change `Auth__ApiKey` on the server (`deploy/set-admin-password.sh`
is for the web login; the API key is in `/etc/callrec/api.env`), update `apikey.properties`, rebuild.

The app icon and colours are KATAVU's (deep green / gold, sugarcane-bunch launcher icon). The launcher
icons are generated from KATAVU's `katavu-icon.svg`; `make-icons.mjs` (git-ignored, machine-specific
paths) regenerates them with `sharp`.

## What it does

- **Settings** (toolbar → Settings): server URL, API key, "delete local copy after upload",
  **Test connection** (calls `GET /api/ping` with the key). Stored in SharedPreferences.
- **After each recording** a WorkManager job (unique work `upload:<fileName>`, needs a network
  connection, exponential backoff from 30 s) POSTs the file as `multipart/form-data` to
  `{serverUrl}/api/recordings` with header `X-Api-Key` and fields `file, direction, phoneNumber,
  startedAt (ISO 8601 with offset), durationSeconds, audioSource, deviceName`.
- **Status** is kept per file in `filesDir/upload_state.json`.
- **KATAVU usage dashboard** is the home screen: it shows **KATAVU** app usage — users with their
  role, organization, last login and whether they're signed in; recent logins; and recent activity —
  from the server (`GET /api/katavu/usage`, which reads KATAVU's database read-only). The app records
  and uploads calls in the background, but call recordings are managed in the web app, not shown here.
- **On app open** recordings on disk with no entry yet are registered (metadata parsed from the
  file name, or estimated from the file) and everything not yet uploaded is queued.
- **HTTPS only**: `usesCleartextTraffic=false`, a network security config with
  `cleartextTrafficPermitted=false`, an OkHttp client with TLS-only connection specs and no
  redirects, and the settings screen rejects `http://` URLs.

Retry rules: network errors, 408, 429 and 5xx are retried (up to 10 attempts). 409 (already on
server) counts as success. 401/403/400/413 mark the item Failed. Saving the settings re-queues
failed items, since a fixed URL or key usually solves those.

## Build

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35). With Android Studio just
open the `android/` folder. From the command line:

```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # or set ANDROID_HOME
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # uploader tests against an HTTPS MockWebServer
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone: open the app → **Grant permissions** → **Enable recorder** (turn on the
accessibility service) → **Settings**, enter `https://recordings.lilliblush.com` and the API key printed by `setup-vps.sh` → **Test connection** → **Save**.

### Server certificate

The app trusts the system CAs, so the Let's Encrypt certificate that certbot gets for
`recordings.lilliblush.com` (see `../server/README.md`) works out of the box. If you use your own CA, install it on the phone and uncomment
`<certificates src="user" />` in `res/xml/network_security_config.xml`.

## Merging into your existing app

1. Copy the `upload/` package (adjust the package name), the `ui/SettingsActivity` +
   `res/layout/activity_settings.xml`, the strings/colors, and `res/xml/network_security_config.xml`.
2. Add the dependencies from `app/build.gradle.kts` (WorkManager, OkHttp, coroutines, Material) and
   in the manifest add `INTERNET`, `ACCESS_NETWORK_STATE`, `android:usesCleartextTraffic="false"`,
   `android:networkSecurityConfig="@xml/network_security_config"` and the `SettingsActivity`.
3. In your `CallRecorderService`, add:
   ```kotlin
   UploadScheduler.onRecordingStarted(file)              // right after MediaRecorder.start()
   UploadScheduler.onRecordingFinished(context, file,    // after stop()/release(), file closed
       RecordingMetadata(direction = "in" /* or "out" */, phoneNumber = number,
           startedAtMillis = startedAt, durationSeconds = seconds,
           audioSource = "CALL" /* or "VREC" / "MIC" */))
   ```
   Nothing else in your recording logic needs to change.
4. In your `MainActivity.onCreate`: `UploadScheduler.enqueueAllPending(this)`. Show status from
   `UploadStateStore.get(this).entries` (a `StateFlow<Map<fileName, UploadEntry>>`), as in
   `ui/MainActivity.kt` + `ui/RecordingsAdapter.kt`.
5. Files already on disk are named however your app names them. `RecordingFiles.inferMetadata`
   understands `yyyyMMdd_HHmmss_{in|out}_{number}_{CALL|VREC|MIC}.m4a`. For other names it
   guesses (direction "in" unless the name contains "out"), so adapt it to your naming scheme.

## Placeholder recorder

`recorder/CallRecorderService.kt` follows the phone state broadcast and records with the first
audio source that starts (VOICE_CALL → VOICE_RECOGNITION → MIC). It writes `*.m4a.part` and renames
when done. On most modern phones VOICE_CALL is not available to normal apps, so expect VREC/MIC.
