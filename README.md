# Blinky

A native Kotlin Android app and Python sender for personal notifications. The sender
talks directly to **Firebase Cloud Messaging HTTP v1**. No custom backend, database,
or server deployment is required. Google's FCM infrastructure is still a server
dependency, and your trusted computer must be running whenever you send a message.

**Application ID: `org.miquelmartin.blinky`**. Android 8.0 (API 26) or newer, Google
Play services, and internet access are required. Sender: Python 3.10 or newer.

## Android build and Firebase configuration

Install Android Studio or Android SDK command-line tools, SDK platform **36** and
build tools **35.0.0**. Use JDK **17 or 21**. The Gradle wrapper pins Gradle 8.13;
the project pins AGP 8.13.2, Kotlin 2.2.21, Google Services 4.5.0 and Firebase BoM 35.1.0.

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK, or create an ignored
`local.properties` in the repository root with your SDK path:

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```

Download [Android Studio](https://developer.android.com/studio), then open **More
Actions > SDK Manager** and install Android SDK Platform 36, Build-Tools 35.0.0 and
Platform-Tools. Accept the SDK license prompts. The usual Windows SDK directory is
`%LOCALAPPDATA%\Android\Sdk`.

Register the Android app in your Firebase project with **`org.miquelmartin.blinky`**.
Download its client **`google-services.json`** into **`app\google-services.json`**.
It is ignored by Git. This client configuration is bundled into the configured APK;
it is **not a service-account credential**. Never put a private key, OAuth access
token or sender credentials into the Android app or its resources.

From the repository root in PowerShell:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug :app:lintDebug
```

On macOS/Linux use `sh ./gradlew` with the same arguments. The APK output is
`app\build\outputs\apk\debug\app-debug.apk`. Install it with Android Studio or:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

If `adb` is not on your Windows PATH, use the SDK executable explicitly. Enable
Developer options and USB debugging on your phone, connect it, and accept the
computer's debugging authorization prompt:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The `-d` option targets a single physical USB device rather than an emulator. The
debug APK is for personal sideloading, not a signed production/Play Store release.

**Without `app\google-services.json`**, the Google Services plugin is not applied.
The real app ID is preserved, and the build can run without fake Firebase config.
This APK opens and explains missing configuration, but **cannot register or receive
messages**. Add your actual client configuration, rebuild and reinstall to enable FCM.

Open Blinky and allow the notification permission when Android prompts. Blinky
checks permission on each app open; repeated denial can suppress native dialogs.
When that happens, or when the **Messages** channel is disabled, use the discreet
**Open settings** notice. Android versions before 13 do not have this runtime dialog.

The single top-right **Copy token** action fetches the current FCM token, then
copies it. It does not copy a cached token if retrieval fails, or claim success if
the clipboard fails. Save the copied token to a private text file **outside this
repository** on your trusted computer. Fetching does not rotate or delete a token;
tokens can change after reinstall, data clearing or Firebase rotation.

The dark Pocket Console screen shows actual received messages newest first, grouped
by local day, with title, body and local receipt time. History is stored in a private
SQLite database and persists across app restarts, including messages received while
notifications were disabled. The count is total received entries, not unread or
confirmed delivery. No sample messages are populated.

Version **0.2.0 / code 3** keeps the same package and signing configuration as code 2.
A normal Play update preserves installation state and the existing token. Earlier
versions stored only a combined last-message string without a timestamp, so it is
not backfilled as invented history; the new history starts with receipts after the
update. No automatic history pruning or cross-device synchronization is performed.

## Trusted Python sender setup

Enable **Firebase Cloud Messaging API** (`fcm.googleapis.com`) in the same Google Cloud
project. The sending identity needs `cloudmessaging.messages.create`; the supported
predefined role is **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`).
Grant it only in the target project.

The chosen unattended-sending workflow uses a **dedicated local service-account JSON
key** through Google-supported **Application Default Credentials (ADC)**. Grant its
service account a custom role containing only `cloudmessaging.messages.create`, or
the predefined role above if a custom role is not available. Store the key **outside
the repository and APK**, for example in a private folder in your user profile.
Restrict file access to your Windows account and do not sync it to a shared folder.
Never paste its contents into chat.

Set the path in the PowerShell process that will run the sender:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = "$env:USERPROFILE\blinky-private\service-account.json"
```

If you already saved it as a persistent **user** environment variable, restart the
terminal/app or refresh the current PowerShell process without printing its value:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = [Environment]::GetEnvironmentVariable("GOOGLE_APPLICATION_CREDENTIALS", "User")
```

Existing app processes do not automatically inherit changes to user environment
variables; a new Python process launched by an old terminal inherits that terminal's
old environment unless you refresh it first.

For unattended execution, configure that environment variable in the scheduled
process, or use a private local launcher outside Git. The sender uses the standard
Google library to load the key and obtain/refresh OAuth access tokens; it does not
implement OAuth itself. No credentials or configuration file belong in Git.

**Keyless alternative**, if you later prefer interactive user ADC:

```text
gcloud auth application-default login --scopes=https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/firebase.messaging
gcloud auth application-default set-quota-project YOUR_PROJECT_ID
```

The quota-project command requires `serviceusage.services.use` (for example, the
Service Usage Consumer role). Ordinary `gcloud auth login` alone does **not** configure
ADC. The Python library obtains and refreshes OAuth tokens, not this code.

Organization policies may prohibit key creation; user ADC or service-account
impersonation avoids that requirement. Unset `GOOGLE_APPLICATION_CREDENTIALS` to
switch back to user ADC. Rotate/revoke a JSON key if it is exposed.

From the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\sender\requirements.txt
.\.venv\Scripts\python.exe .\sender\send.py `
  --project YOUR_PROJECT_ID `
  --token-file "$env:USERPROFILE\blinky-private\fcm-token.txt" `
  --title "Hello from Blinky" `
  --body "This message was sent directly through FCM."
```

Use the Firebase **project ID**, not its display name, Android app ID or project
number. `FCM_PROJECT_ID` is an optional environment alternative to `--project`.
The token file contains just the installation token, optionally with a trailing newline.
Treat it as private routing information; avoid public logs or shared clipboard history.
Python never runs on the phone. No sender configuration or credentials belong in Git.

## Signed tester releases

The manually dispatched [Android release workflow](.github/workflows/android-release.yml)
builds a signed APK and AAB. Select **`build-only`** for first-upload/bootstrap artifacts
without Play access, or **`internal`** to publish the AAB to **Google Play internal testing**.
See [signing, GitHub secrets and tester setup](docs/android-releases.md) before running
it. Play enrollment/update settings govern tester updates; this is not a forced
installation service. The FCM sender credential is separate from Play publishing.

The sender uses **data-only, high-priority** messages with string `title` and `body`.
The Android service displays them through the same channel in foreground and background.
The Firebase console's notification composer sends a different message type: the
Firebase SDK displays background notification messages automatically, and those do
not update the app's received-message history through the service.

## Checks and real delivery test

Sender checks use only the standard library, no credentials or network:

```powershell
python -m unittest discover -s .\sender -p "test_*.py" -v
python -m compileall -q .\sender
```

Android build and lint can run without client config. That confirms compilation and
packaging only, **not Firebase registration or delivery**. For a real smoke test,
rebuild with config, install and open the app, grant permission, copy its token and
send from the authenticated computer. Confirm notifications with the app open, in the
background and with the phone locked. Tap a notification to open Blinky.
Test permission denial and a disabled Messages channel: received messages should
still appear in history, but the notification should not be displayed. Re-enable
and send again; suppressed messages are not replayed.
**FCM acceptance is not proof of phone delivery.**

Tokens, history and the legacy last-message preferences are excluded from cloud backup and device
transfer. Obtain the new installation's token on each phone rather than restoring
another installation's saved routing token.

FCM is best-effort, not guaranteed or exact-once. Force-stop blocks delivery until
reopening; swiping from recents is normally not force-stop. Network loss, Doze, vendor
battery policies and Play services problems may delay messages. High priority is
for visible notifications, not generic background jobs; repeated non-visible delivery
can cause deprioritization. System settings can mute sound or heads-up presentation.
Received-message history is local only, without delivery receipts or server storage.
FCM's default expiry is up to four weeks. This MVP adds no end-to-end
encryption; do not send secrets.

Errors exit nonzero: `UNREGISTERED` means retrieve a new phone token;
`SENDER_ID_MISMATCH` means the token belongs to another project; 403/`PERMISSION_DENIED`
means check API enablement, ADC identity, IAM and quota-project permission. Empty or
oversized messages are rejected locally. Transient failures are not silently retried,
which avoids duplicate visible messages.

History, token-action and permission decision unit tests run with
`:app:testDebugUnitTest`. With an emulator attached, `:app:connectedDebugAndroidTest`
also verifies SQLite persistence and preservation of existing installation state.
Instrumentation fixtures use a separate test database and do not populate app history.

## Copilot phone notifications

The Markdown-only [blinky-notify skill](.github/skills/blinky-notify/SKILL.md)
lets Copilot use the existing sender when you explicitly ask "notify me",
"tell me on the phone", or "ping me on Blinky". See
[personal skill installation and private runtime setup](docs/notification-skill.md)
to make it available across local repositories without embedding configuration
or relying on a temporary worktree. It sends no unsolicited or scheduled messages.
