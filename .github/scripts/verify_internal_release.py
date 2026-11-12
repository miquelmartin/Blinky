"""Inspect live Play versions before upload or confirm committed internal availability."""

import argparse
from contextlib import contextmanager
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


@contextmanager
def temporary_edit(session):
    edit = request(session, "POST", BASE, json={})
    edit_id = edit.get("id")
    if not isinstance(edit_id, str) or not re.fullmatch(r"[0-9]+", edit_id):
        raise ValueError("Play verification did not return a valid edit ID.")
    url = f"{BASE}/{edit_id}"
    # Read a fresh snapshot; never commit track or release changes from inspection.
    try:
        yield url
    finally:
        request(session, "DELETE", url)


def preflight(session, version_code):
    if version_code < 1:
        raise ValueError("Release version code must be positive.")
    observed = []
    with temporary_edit(session) as url:
        tracks = request(session, "GET", url + "/tracks").get("tracks", [])
        if not isinstance(tracks, list):
            raise ValueError("Play preflight returned invalid tracks.")
        for track in tracks:
            if not isinstance(track, dict) or not isinstance(track.get("track"), str):
                raise ValueError("Play preflight returned an invalid track.")
            releases = track.get("releases", [])
            if not isinstance(releases, list):
                raise ValueError("Play preflight returned invalid releases.")
            for release in releases:
                if not isinstance(release, dict):
                    raise ValueError("Play preflight returned an invalid release.")
                codes = release.get("versionCodes", [])
                if not isinstance(codes, list) or not all(
                    isinstance(code, str) and re.fullmatch(r"[1-9][0-9]*", code) for code in codes
                ):
                    raise ValueError("Play preflight returned invalid track version codes.")
                observed.extend(int(code) for code in codes)
        for collection in ("bundles", "apks"):
            artifacts = request(session, "GET", url + "/" + collection).get(collection, [])
            if not isinstance(artifacts, list):
                raise ValueError("Play preflight returned invalid artifacts.")
            for artifact in artifacts:
                if not isinstance(artifact, dict):
                    raise ValueError("Play preflight returned an invalid artifact.")
                code = artifact.get("versionCode")
                if type(code) is not int or code < 1:
                    raise ValueError("Play preflight returned an invalid artifact version code.")
                observed.append(code)
        highest = max(observed, default=0)
        if version_code <= highest:
            raise ValueError("Candidate version must exceed every version observed in Play.")
    return highest


def verify(session, version_code):
    if version_code < 1:
        raise ValueError("Release version code must be positive.")
    with temporary_edit(session) as url:
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


def apk_version(apk):
    tool = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0" / "aapt2"
    badging = subprocess.run([str(tool), "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
    if not match or match.group(1) != PACKAGE:
        raise ValueError("Release APK identity could not be verified.")
    return int(match.group(2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--preflight", action="store_true", help="Inspect live tracks, bundles and APKs before upload; never commit an edit.")
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
            if args.preflight:
                highest = preflight(session, version)
            else:
                verify(session, version)
    except (OSError, KeyError, ValueError, GoogleAuthError, RequestException, subprocess.CalledProcessError) as error:
        operation = "preflight" if args.preflight else "internal-track verification"
        print(f"Play {operation} failed ({type(error).__name__}); no availability confirmed. Do not retry an upload or increment automatically.", file=sys.stderr)
        return 1
    if args.preflight:
        print(f"Play preflight: candidate {version} exceeds highest observed version {highest}; inspection edit deleted without commit.")
        print("Pending edits and historical consumed codes may not appear in this snapshot. Upload remains authoritative; no automatic increment or retry.")
    else:
        print(f"Verified committed Play internal track: versionCode {version}, status completed. Device propagation can take time.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
