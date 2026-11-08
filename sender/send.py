"""Send a visible Blinky message through FCM HTTP v1 using trusted-computer ADC."""

import argparse
import json
import os
from pathlib import Path
import re
import sys

SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


def build_message(token: str, title: str, body: str) -> dict:
    token = token.strip()
    if not token or any(character.isspace() for character in token):
        raise ValueError("The token file must contain one non-empty FCM token.")
    if not title.strip() or not body.strip():
        raise ValueError("Title and body must both contain text.")
    data = {"title": title, "body": body}
    # Conservatively include JSON framing in the 4096-byte FCM data limit.
    if len(json.dumps(data, ensure_ascii=False).encode("utf-8")) > 4096:
        raise ValueError("Title and body exceed the 4096-byte FCM data limit.")
    return {"message": {"token": token, "data": data, "android": {"priority": "HIGH"}}}


def validate_project(project: str) -> str:
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project):
        raise ValueError("Use the Firebase project ID (6-30 lowercase letters/digits/hyphens), not its display name.")
    return project


def deliver(session, project: str, payload: dict) -> str:
    response = session.post(
        f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
        json=payload,
        timeout=30,
    )
    try:
        result = response.json()
    except ValueError as error:
        raise ValueError(f"FCM returned HTTP {response.status_code} with a non-JSON response.") from error
    if not 200 <= response.status_code < 300:
        detail = result.get("error", {}) if isinstance(result, dict) else {}
        code = detail.get("status", "UNKNOWN") if isinstance(detail, dict) else "UNKNOWN"
        message = detail.get("message", "No error detail provided.") if isinstance(detail, dict) else "No error detail provided."
        raise ValueError(f"FCM rejected the message (HTTP {response.status_code}, {code}): {message}")
    name = result.get("name") if isinstance(result, dict) else None
    if not isinstance(name, str) or not name:
        raise ValueError("FCM returned success without a message ID; acceptance could not be confirmed.")
    return name


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=os.environ.get("FCM_PROJECT_ID"), help="Firebase project ID, or FCM_PROJECT_ID.")
    parser.add_argument("--token-file", type=Path, required=True, help="Private text file containing the installation token.")
    parser.add_argument("--title", required=True)
    parser.add_argument("--body", required=True)
    args = parser.parse_args(argv)
    if not args.project:
        parser.error("--project or FCM_PROJECT_ID is required.")
    try:
        project = validate_project(args.project)
        payload = build_message(args.token_file.read_text(encoding="utf-8-sig"), args.title, args.body)
    except (OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    try:
        import google.auth
        from google.auth.exceptions import GoogleAuthError
        from google.auth.transport.requests import AuthorizedSession
        from requests.exceptions import RequestException
    except ImportError:
        print("Error: Install sender dependencies with python -m pip install -r sender/requirements.txt.", file=sys.stderr)
        return 1

    try:
        credentials, _ = google.auth.default(scopes=SCOPES)
        with AuthorizedSession(credentials) as session:
            name = deliver(session, project, payload)
    except (GoogleAuthError, RequestException, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    print(f"FCM accepted the message: {name}")
    print("Acceptance is not proof of phone delivery. Confirm the notification on the phone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
