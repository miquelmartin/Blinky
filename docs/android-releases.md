# Signed Android releases for testers

Blinky follows Elsewhere's **Google Play internal testing** distribution route.
The **Android internal testing release** GitHub Action is started manually on `main`
by `miquelmartin`. It builds a signed APK and signed AAB, retains both plus checksums
for seven days, and uploads **only the AAB** to Play's internal track with status
`completed`. No production promotion, push/tag trigger or recurring automation is included.

Google Play requires an **AAB** for a new app. The retained APK is useful for private
sideload testing, not a Play upload. Play tester enrollment and their device's update
settings govern automatic updates; this workflow does not invite testers, send its
own notices or force an installation.

## One-time Play Console setup

Create the Play Console app for **`org.miquelmartin.blinky`**, complete required app
details/declarations, configure Play App Signing and its upload certificate, and set
up an internal-testing email list or Google Group. Give testers the Play opt-in link.
Do not reuse Elsewhere's private keys or credentials.

For the new Blinky app, establish its first internal release in Play Console if the
Publishing API cannot yet publish it (for example, while the app is still a draft).
Use the signed AAB from the workflow artifact if a first automated upload fails.
Complete any required review/setup in the Console; there is deliberately no silent
fallback to draft or a different track. The internal action uploads the next eligible
release, not an app-registration or tester-provisioning operation.

Create a **dedicated Blinky Play uploader** Google service account with Android
Publisher API enabled in its project. In Play Console, invite that service account
and grant app-scoped internal-testing release access only for Blinky. Do not grant
production-release or account-admin access. The FCM sender's send-only key is **not**
the Play uploader credential and should not be reused for this purpose.

## Create a separate upload signing key

Keep the keystore outside Git and back it up securely. With your JDK's `keytool`:

```powershell
keytool -genkeypair -storetype PKCS12 `
  -keystore "$env:USERPROFILE\blinky-private\blinky-upload.p12" `
  -alias blinky-upload -keyalg RSA -keysize 3072 -validity 10000
```

Create the private directory first if needed. Enter the password and certificate
information locally when prompted. **PKCS12 uses the same store/key password** here.
Never put passwords on the command line, in Git, in chat or in an artifact.
For Play's upload-certificate registration, export the public certificate locally:

```powershell
keytool -exportcert -rfc -storetype PKCS12 `
  -keystore "$env:USERPROFILE\blinky-private\blinky-upload.p12" `
  -alias blinky-upload `
  -file "$env:USERPROFILE\blinky-private\blinky-upload-certificate.pem"
```

The public certificate is not the private key. Register the upload certificate in
Play App Signing as required and preserve the original private keystore/password.
Once released, do not casually generate a replacement key.

The existing development APK is debug-signed. To switch from it to Play's signed
installation, testers normally need to uninstall the debug app first, which clears
its local state; reopen the new app and copy its **new FCM token**.
With Play App Signing, Play signs installed APKs with its app-signing key, which may
differ from this upload key. A sideloaded APK signed with the upload key may therefore
also require uninstalling before switching to the Play-installed app. Future Play
updates work under the stable Play app-signing identity.

## Configure GitHub's protected release environment

Create the **`android-testing`** environment under repository Settings > Environments.
It can be configured before the workflow lands on `main`. Allow deployments from the **`main` branch
only**; use branch-only rules, not a tag pattern that happens to be named `main`.
Optionally require the repository owner to approve deployments. The workflow also
checks the exact ref and actor, so a dispatch on another branch or by another actor
does not run the release job.

Add these **environment secrets** in `miquelmartin/Blinky`, not Elsewhere:

| Secret | Value |
|---|---|
| `APK_SIGNING_CERT` | Base64-encoded contents of Blinky's **PKCS12** upload keystore; name matches Elsewhere even though it is not just a public certificate |
| `APK_KEY_PASSWORD` | The keystore/key password |
| `APK_KEY_ALIAS` | `blinky-upload` (or the alias you created) |
| `GOOGLE_SERVICES_JSON` | Full **Android client** `google-services.json` for `org.miquelmartin.blinky`, not any private service-account key |
| `GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON` | Full JSON key of the dedicated app-scoped Play uploader |

GitHub's UI lets you enter secrets privately. With authenticated `gh`, these commands
pipe file contents straight to GitHub without printing them:

```powershell
$repo = "miquelmartin/Blinky"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\blinky-private\blinky-upload.p12")) |
  gh secret set APK_SIGNING_CERT --env android-testing --repo $repo
gh secret set APK_KEY_PASSWORD --env android-testing --repo $repo
gh secret set APK_KEY_ALIAS --env android-testing --repo $repo --body blinky-upload
Get-Content -Raw .\app\google-services.json |
  gh secret set GOOGLE_SERVICES_JSON --env android-testing --repo $repo
Get-Content -Raw "$env:USERPROFILE\blinky-private\blinky-play-uploader.json" |
  gh secret set GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON --env android-testing --repo $repo
```

The password command prompts for the secret locally; do not supply it as `--body`
on the command line. Use the UI if your terminal cannot provide a private interactive
prompt. Base64 is **not encryption**; never print or commit the encoded keystore.

The workflow checks secret presence, refuses a service-account key as Android client
config, checks the application ID and validates the PKCS12 alias/password. Signing and
Play credentials are written only into runner temporary storage, and client config
into the ignored app path. An always-run cleanup removes them. Signed APK/AAB/checksum
artifacts never include private configuration files.

## Release the next version

Increment **`versionCode`** in `app/build.gradle.kts` for every new Play release,
including after a code was consumed by an upload even if publication later failed.
Update the user-visible `versionName` as appropriate. This is the same manual version
ownership as Elsewhere; the action does not invent or reset version codes.

Land the desired source and version bump on `main`, then select Actions >
**Android internal testing release** > Run workflow > **main**, or:

```powershell
gh workflow run android-release.yml --repo miquelmartin/Blinky --ref main
```

The workflow serializes releases and does not cancel an upload in progress. Inspect
the run's build/upload result and the Play internal-track release. Testers who opted
in can install or update from Play, subject to Play processing, account eligibility,
review requirements and device update settings. Availability is not proof of device
installation or notification delivery.

## Local and configuration-independent checks

```powershell
python -m unittest discover -s .\sender -p "test_*.py" -v
python -m unittest discover -s .\.github\scripts -p "test_*.py" -v
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug :app:lintDebug
```

For local signed release packaging, supply the **existing** private keystore through
`APK_SIGNING_STORE_FILE`, and set `APK_KEY_PASSWORD` and `APK_KEY_ALIAS` privately in
that process. With the real ignored Android client config present:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleRelease :app:bundleRelease :app:lintRelease
```

Without complete signing configuration or client config, release packaging fails
explicitly rather than generating an unsigned or debug-signed release. Debug builds
retain their configuration-independent behavior.

Local build/test/workflow validation does not verify GitHub environment secrets,
Play account provisioning, upload-key acceptance or a real Play publication. Those
require the one-time setup and a successful authorized workflow run.

The implementation was checked locally with actionlint, sender/secret-handling unit
tests, Android debug build/lint, and release APK/AAB build/lint/signature verification
using a **disposable validation keystore** outside Git. That keystore and its signed
output artifacts were deleted; they are not Blinky's upload key or distributable
release. An actual user-owned signed bundle and Play upload still require the setup above.
