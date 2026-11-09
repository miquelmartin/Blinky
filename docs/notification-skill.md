# Personal Copilot notification skill

The Markdown-only [blinky-notify skill](../.github/skills/blinky-notify/SKILL.md)
handles explicit requests such as "notify me", "tell me on the phone", and
"ping me on Blinky". It chooses a relevant title and body, calls the existing
Python sender once, and distinguishes FCM acceptance from confirmed phone receipt.
It contains no executable scripts, credentials, phone tokens, or personal paths.

## Supported user installation

Copy the `blinky-notify` folder from `.github\skills` to
`~\.copilot\skills\blinky-notify` in your home directory. Keep only `SKILL.md`
inside the installed skill folder. This is Copilot's supported personal skill
location, shared across local repositories and folders; it does not modify any
other repository.

GitHub Copilot CLI supports reloading skills in an existing interactive session.
In the Copilot app, check **Customize > Skills / Installed**, and start a new
session if the existing one has not discovered the new skill. Files being
installed does not prove an already running session has reloaded them.

## Stable sender and private host configuration

Keep a trusted local copy of the existing `sender\send.py` and
`sender\requirements.txt` outside temporary worktrees and skill folders. On
Windows, a user-owned application-data folder such as
`%LOCALAPPDATA%\Blinky\sender` is suitable. This runtime is separate from the
Markdown-only skill: reuse the existing sender unchanged rather than adding a
second implementation. Use a dedicated local Python environment with its declared
dependencies; never rely on another repository's virtual environment.

Configure these environment variables at **user scope**, storing actual values
only on the trusted sending computer:

| Variable | Required local configuration |
|---|---|
| `BLINKY_SENDER_PYTHON` | Absolute path to that runtime's Python executable |
| `BLINKY_SENDER_SCRIPT` | Absolute path to the trusted installed `send.py` |
| `BLINKY_TOKEN_FILE` | Private token file used for the current phone installation |
| `BLINKY_PROJECT_ID` | That installation's Firebase project ID |
| `GOOGLE_APPLICATION_CREDENTIALS` | Existing dedicated FCM sender key file |

Keep the token and credential files outside Git and outside skill directories.
Do not put their values into this document or into `SKILL.md`. Keep the existing
send-only FCM identity; Play publishing credentials are unrelated and must not
be used here.

An old terminal or app process does not automatically inherit newly saved user
environment variables. The skill instructs the agent to resolve persistent user
settings and refresh only its sending subprocess. Moving these settings into Git,
passing credentials as arguments, or printing their values is not necessary.

A copied sender is a snapshot: when intentionally updating Blinky's sender, update
the trusted local runtime from the reviewed source and its matching requirements.
Do not automatically execute code downloaded from an arbitrary repository.

## Scope and limitations

The skill does not schedule notifications or send unsolicited pings. It sends
only for an explicit user request, including a specifically requested completion
notification. It does not bypass tool approval.

The user-level skill is local to the computer where it is installed. Remote/cloud
agents cannot use this computer's private sender configuration merely because they
can read the skill; they need an already authorized way to execute on the trusted
computer. Do not copy the key or token to a cloud session.

Notification content is not end-to-end encrypted. Never send credentials or
installation tokens. After a successful send, the skill confirms "Blinky just
pinged you." This acknowledges the send action, not that the phone displayed the
notification or that it was read. Send and configuration failures are still
reported plainly.

## Official references

- [Copilot personal skill locations and format](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills)
- [Copilot app discovers CLI-configured skills](https://docs.github.com/en/copilot/how-tos/github-copilot-app/customize-github-copilot-app)
