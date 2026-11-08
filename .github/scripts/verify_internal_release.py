"""Confirm the uploaded version is completed on the committed Play internal track."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGE = "org.miquelmartin.blinky"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits"


def request(session, method, url, **kwargs):
    response = session.request(method, url, timeout=30, **kwargs)
    if not 200 <= response.status_code < 300:
        raise ValueError(f"Play verification {method} failed (HTTP {response.status_code}).")
    if method == "DELETE":
        return None
    data = response.json()
    if not isinstance(data, dict):
        raise ValueError("Play verification returned an unexpected response.")
    return data


def verify(session, version_code):
    if version_code < 1:
        raise ValueError("Release version code must be positive.")
    edit = request(session, "POST", BASE, json={})
    edit_id = edit.get("id")
    if not isinstance(edit_id, str) or not re.fullmatch(r"[0-9]+", edit_id):
        raise ValueError("Play verification did not return a valid edit ID.")
    url = f"{BASE}/{edit_id}"
    # A fresh edit reads committed track state; it is deleted without a commit.
    try:
        track = request(session, "GET", url + "/tracks/internal")
        if track.get("track") != "internal":
            raise ValueError("Play verification returned a different track.")
        releases = track.get("releases", [])
        if not isinstance(releases, list):
            raise ValueError("Play verification returned invalid releases.")
        completed = False
        for release in releases:
            if not isinstance(release, dict):
                raise ValueError("Play verification returned an invalid release.")
            codes = release.get("versionCodes", [])
            if not isinstance(codes, list) or not all(isinstance(code, str) for code in codes):
                raise ValueError("Play verification returned invalid version codes.")
            if release.get("status") == "completed" and str(version_code) in codes:
                completed = True
        if not completed:
            raise ValueError(f"Version code {version_code} is not completed on the internal track.")
    finally:
        request(session, "DELETE", url)


def apk_version(apk):
    tool = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0" / "aapt2"
    badging = subprocess.run([str(tool), "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
    if not match or match.group(1) != PACKAGE:
        raise ValueError("Release APK identity could not be verified.")
    return int(match.group(2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    from google.auth.exceptions import GoogleAuthError
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2.service_account import Credentials
    from requests.exceptions import RequestException

    try:
        version = apk_version(args.apk)
        credentials = Credentials.from_service_account_file(
            os.environ["PLAY_SERVICE_ACCOUNT_FILE"],
            scopes=["https://www.googleapis.com/auth/androidpublisher"],
        )
        with AuthorizedSession(credentials) as session:
            verify(session, version)
    except (OSError, KeyError, ValueError, GoogleAuthError, RequestException, subprocess.CalledProcessError) as error:
        print(f"Play internal-track verification failed ({type(error).__name__}); no availability confirmed.", file=sys.stderr)
        return 1
    print(f"Verified committed Play internal track: versionCode {version}, status completed. Device propagation can take time.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
