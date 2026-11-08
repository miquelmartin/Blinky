"""Restore GitHub environment secrets without logging their contents."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import subprocess
import sys
from collections.abc import Mapping

PACKAGE = "org.miquelmartin.blinky"
BUILD_SECRETS = (
    "APK_SIGNING_CERT",
    "APK_KEY_PASSWORD",
    "APK_KEY_ALIAS",
    "GOOGLE_SERVICES_JSON",
)
REQUIRED_SECRETS = (*BUILD_SECRETS, "GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON")


def parse_object(value: str, name: str) -> dict:
    try:
        parsed = json.loads(value)
    except ValueError:
        raise ValueError(f"{name} must contain valid JSON.") from None
    if not isinstance(parsed, dict):
        raise ValueError(f"{name} must contain a JSON object.")
    return parsed


def contains_private_key(value: object) -> bool:
    if isinstance(value, dict):
        return "private_key" in value or any(contains_private_key(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_private_key(item) for item in value)
    return False


def validate_secrets(environment: Mapping[str, str], *, build_only: bool = False) -> tuple[bytes, dict, dict | None]:
    required = BUILD_SECRETS if build_only else REQUIRED_SECRETS
    missing = [name for name in required if not environment.get(name, "").strip()]
    if missing:
        raise ValueError("Missing android-testing secrets: " + ", ".join(missing))
    try:
        certificate = base64.b64decode("".join(environment["APK_SIGNING_CERT"].split()), validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("APK_SIGNING_CERT must be the base64-encoded PKCS12 keystore.") from None
    if not certificate:
        raise ValueError("APK_SIGNING_CERT decoded to an empty file.")

    client = parse_object(environment["GOOGLE_SERVICES_JSON"], "GOOGLE_SERVICES_JSON")
    if client.get("type") == "service_account" or contains_private_key(client):
        raise ValueError("GOOGLE_SERVICES_JSON must be Android client configuration, never a private service-account key.")
    project = client.get("project_info")
    clients = client.get("client")
    if not isinstance(project, dict) or not project.get("project_id") or not isinstance(clients, list):
        raise ValueError("GOOGLE_SERVICES_JSON is missing Firebase Android client configuration.")
    matching_package = False
    for item in clients:
        info = item.get("client_info") if isinstance(item, dict) else None
        android = info.get("android_client_info") if isinstance(info, dict) else None
        if isinstance(android, dict) and android.get("package_name") == PACKAGE:
            matching_package = True
    if not matching_package:
        raise ValueError(f"GOOGLE_SERVICES_JSON must include the Android application {PACKAGE}.")

    play = None
    if not build_only:
        play = parse_object(
            environment["GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"],
            "GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON",
        )
        if play.get("type") != "service_account" or not all(
            isinstance(play.get(name), str) and play[name].strip()
            for name in ("private_key", "client_email", "project_id")
        ):
            raise ValueError("GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON must be a dedicated Play uploader service-account key.")
    return certificate, client, play


def write_private(path: Path, content: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(content)


def prepare(environment: Mapping[str, str], *, build_only: bool = False) -> None:
    certificate, client, play = validate_secrets(environment, build_only=build_only)
    required_paths = ("RUNNER_TEMP", "GITHUB_WORKSPACE", "GITHUB_ENV")
    if any(not environment.get(name) for name in required_paths):
        raise ValueError("Run this helper only in GitHub Actions with RUNNER_TEMP, GITHUB_WORKSPACE and GITHUB_ENV.")
    temporary = Path(environment["RUNNER_TEMP"])
    workspace = Path(environment["GITHUB_WORKSPACE"])
    store = temporary / "blinky-upload-key.p12"
    play_path = temporary / "blinky-play-service-account.json"
    client_path = workspace / "app" / "google-services.json"
    try:
        write_private(store, certificate)
        result = subprocess.run(
            ["keytool", "-list", "-storetype", "PKCS12", "-keystore", str(store),
             "-storepass:env", "APK_KEY_PASSWORD", "-alias", environment["APK_KEY_ALIAS"]],
            env=dict(environment), capture_output=True, text=True, timeout=30,
        )
        if result.returncode:
            raise ValueError("Cannot open the PKCS12 signing key/alias. Check APK_SIGNING_CERT, APK_KEY_PASSWORD and APK_KEY_ALIAS.")
        if play is not None:
            write_private(play_path, json.dumps(play).encode("utf-8"))
        write_private(client_path, json.dumps(client).encode("utf-8"))
        with Path(environment["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
            output.write(f"APK_SIGNING_STORE_FILE={store}\n")
            if play is not None:
                output.write(f"PLAY_SERVICE_ACCOUNT_FILE={play_path}\n")
    except (OSError, ValueError, subprocess.SubprocessError):
        for path in (store, play_path, client_path):
            path.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-only", action="store_true", help="Prepare signing/client configuration without any Play credentials.")
    args = parser.parse_args(argv)
    try:
        prepare(os.environ, build_only=args.build_only)
    except ValueError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError) as error:
        print(f"::error::Private release configuration could not be prepared ({type(error).__name__}).", file=sys.stderr)
        return 1
    print("Release configuration validated. Private values were not printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
