# Signed Android releases for testers

Blinky follows Elsewhere's **Google Play internal testing** distribution route.
The **Android internal testing release** GitHub Action is started manually on `main`
by `miquelmartin`. Both operations build a signed APK and signed AAB and retain both
plus checksums for seven days:

- **`build-only`** produces the bootstrap artifacts without accessing Play credentials
  or making a Play request.
- **`internal`** (default) also uploads **only the AAB** to Play's internal track with
  status `completed`.

No production promotion, push/tag trigger or recurring automation is included.

Google Play requires an **AAB** for a new app. The retained APK is useful for private
sideload testing, not a Play upload. Play tester enrollment and their device's update
settings govern automatic updates; this workflow does not invite testers, send its
own notices or force an installation.

## One-time Play Console setup

Create the Play Console app for **`org.miquelmartin.blinky`**, complete required app
details/declarations, configure Play App Signing and its upload certificate, and set
up an internal-testing email list or Google Group. Give testers the Play opt-in link.
Do not reuse Elsewhere's private keys or credentials.

### Required first manual upload

Creating the Console app alone does **not** bootstrap the Publishing API. Google's
[Edits documentation](https://developers.google.com/android-publisher/edits) requires
an existing app with at least one artifact uploaded **through Play Console**. Its text
uses the legacy term APK; for this new app, use a signed **AAB**, as required by the
[current release instructions](https://support.google.com/googleplay/android-developer/answer/9859348).
The [pinned upload action also documents the first manual APK/AAB requirement](https://github.com/r0adkll/upload-google-play/blob/e738b9dd8f2476ea806d921b64aacd24f34515a5/README.md#faq).

1. Obtain `app-release.aab`, signed with the **same Blinky upload keystore** stored
   in the GitHub environment. The current source is version **0.1.1 / code 2**.
   A debug APK or a disposable-validation-key bundle is not the bootstrap artifact.
2. In Play Console select Blinky, then **Test and release > Testing > Internal
   testing > Create new release**. If creation is disabled, resolve the Dashboard's
   indicated blocking tasks.
3. Follow the first-release Play App Signing prompts. Use Play's default generated
   app-signing key; the certificate on the upload-signed AAB identifies your separate
   upload key. Do **not** upload the private PKCS12 file or its password into a listing.
4. Upload the signed AAB and confirm its package is **`org.miquelmartin.blinky`**,
   version code and upload certificate. A first artifact fixes the package name.
5. Add release name/notes, save and review the release, and complete the Console's
   internal rollout or review/publication flow. Configure the internal tester email
   list and opt-in link separately. Do not launch a production release.

The manual artifact upload establishes the API's existing-package prerequisite.
Console consents and publishing/app-state blockers are separate: a saved draft alone
is **not proof** that a subsequent API upload with `status: completed` is eligible.
Complete the Console's required first-release steps and surface any remaining
draft/app-state error rather than silently changing status or track.

[Internal testing can start before full app setup is complete](https://support.google.com/googleplay/android-developer/answer/9845334#internal_test);
do not require a production launch or production-access testing merely to bootstrap
the internal track. Actual Console blockers take precedence for this app.

After manually uploading code **2**, the next new automated bundle must use a higher
code (normally **3**), even if the manual release remains a draft. Do not re-upload
code 2 from the workflow; version codes are consumed by upload, not just publication.

Use the manual workflow's **`build-only`** operation to obtain this bootstrap bundle.
It requires only `APK_SIGNING_CERT`, `APK_KEY_PASSWORD`, `APK_KEY_ALIAS` and
`GOOGLE_SERVICES_JSON`; it neither loads nor validates a Play uploader secret and
skips the Play upload step. After the workflow is on `main` and those four protected
environment secrets are correct:

```powershell
gh workflow run android-release.yml --repo miquelmartin/Blinky --ref main -f operation=build-only
```

Download the `blinky-build-only-<run number>` artifact. It contains
`bundle/release/app-release.aab`, `apk/release/app-release.apk` and
`blinky-checksums.txt`. **Upload the AAB**, not the APK or artifact ZIP, through the
Console steps above. Artifact creation alone does not consume its version code;
the manual Play upload does.

Alternatively build locally with the same keystore and privately supplied password.
No GitHub secret needs to be downloaded or displayed for either route. A build-only
run is not a Play release and does not notify or update testers.

The internal action uploads the next eligible release, not an app-registration or
tester-provisioning operation.

Create a **dedicated Blinky Play uploader** Google service account with Android
Publisher API enabled in its project. In Play Console, invite that service account
and grant app-scoped internal-testing release access only for Blinky. Do not grant
production-release or account-admin access. No Cloud project/developer-account link is
required under the [current API setup instructions](https://developers.google.com/android-publisher/getting_started).
The FCM sender's send-only key is **not**
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
| `GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON` | Full JSON key of the dedicated app-scoped Play uploader; required only for `internal`, never for `build-only` |

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

The workflow checks operation-specific secret presence, refuses a service-account key as Android client
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
gh workflow run android-release.yml --repo miquelmartin/Blinky --ref main -f operation=internal
```

The workflow serializes releases and does not cancel an upload in progress. Inspect
the run's build/upload result and the Play internal-track release. Testers who opted
in can install or update from Play, subject to Play processing, account eligibility,
review requirements and device update settings. Availability is not proof of device
installation or notification delivery.

After uploading, the workflow creates a fresh API edit to read **committed**
internal-track state. It requires the APK's exact version code with status
`completed`, then deletes that verification edit without committing any changes.
This confirms Play is serving that release on the internal track; Play/device
propagation can still take time. A failed upload or availability check must not be
reported as an available release. Do not rerun an upload with a consumed code merely
to repeat verification.

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
