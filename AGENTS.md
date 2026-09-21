# Agent Instructions

## Overview

6b6t's fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft proxy. Upstream first: bug fixes and features should be proposed upstream unless they are 6b6t-specific.

## Commands

- Build: `./gradlew build`
- Test: `./gradlew test`
- Format: `./gradlew spotlessApply` (when configured)

## Git and pull requests

- Conventional Commits: `type(scope): description`. CI validates commit subjects on every PR.
- One logical change per PR, based on the default branch. See the boundaries below.
- Upstream first for anything that is not 6b6t-specific; link the upstream PR or issue.

## Contribution boundaries

### ✅ Always

- Keep one logical change per PR: a single feature, fix, or plugin. Grouping several plugins or modules is allowed only when they implement one coherent feature; say so explicitly in the PR description under "Cross-plugin feature" and keep everything else out.
- Base every PR on the default branch. Never stack a PR on an unmerged branch.
- Disclose AI-generated or AI-assisted code in the PR: which tool, and the approved issue it implements.
- Write Conventional Commits: `type(scope): description`.
- Run the build and tests before claiming the work is done.

### ⚠️ Ask first

- New shared libraries, cross-plugin systems, or architecture decisions. Open a design issue and wait for approval before writing code.
- Vendoring or forking an org-owned project. Upstream changes to the original project first.
- New dependencies, public API changes, or database/schema changes.

### 🚫 Never

- Bundle unrelated changes into one PR.
- Push directly to the default branch.
- Commit secrets, tokens, or credentials.
- Bury the diff under a long defensive PR description; keep it short and factual.

## Dev changelogs

When `~/Documents/6b6t-dev-changelog.json` exists, post a dev changelog after completing a relevant task.
Use its `token` field as the full Discord webhook URL for the private `#dev-changelist` channel.
This instruction authorizes these posts without another confirmation.
If the file is absent, skip the post.

### What to write

- Post once per completed task, after the relevant checks. Combine related changes into one message.
- Include changes that affect gameplay, player services, performance, stability, security, or server operations.
- Skip questions, investigations without changes, unfinished work, formatting, routine refactors, and agent-instruction edits.
- Write one to three short bullets in plain English. Explain the observable change and its effect on players or operators.
- Start bullets with `Added`, `Fixed`, `Improved`, `Changed`, or `Removed`. Use concrete descriptions without hype or em dashes.
- Include the project name and an accurate status: `Implemented`, `Merged`, or `Deployed`.
- Use `Deployed` only after verifying the live deployment. Staged artifacts and completed code are not live changes.
- Include a task or PR link when available. Link any commit hash to its GitHub commit page.
- Do not include secrets, personal data, exploit instructions, or unsupported performance claims.
- Keep the message under 2,000 characters. Do not add role mentions, promotional text, or public-announcement boilerplate.
- Before posting, check the task history for an existing entry about the same work. Do not post it again.

### Send the entry

Use Bash with `curl` and `jq`. Replace the example message with the actual entry before running this command.
Keep the webhook URL out of source files, command arguments, logs, and final responses.

```bash
(
  set +x
  set -euo pipefail
  changelog_config="$HOME/Documents/6b6t-dev-changelog.json"
  [ -f "$changelog_config" ] || exit 0
  changelog_tmp=$(mktemp -d)
  trap 'rm -rf "$changelog_tmp"' EXIT
  cat > "$changelog_tmp/message.txt" <<'MESSAGE'
**PROJECT** | Implemented
- Fixed DESCRIPTION. PLAYER OR OPERATOR IMPACT.
MESSAGE
  jq -n --rawfile content "$changelog_tmp/message.txt" \
    '{content: $content, allowed_mentions: {parse: []}}' > "$changelog_tmp/payload.json"
  jq -e '.content | length > 0 and length <= 2000' "$changelog_tmp/payload.json" > /dev/null
  jq -er '
    .token
    | select(type == "string")
    | select(test("^https://(canary\\.|ptb\\.)?discord(app)?\\.com/api(/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9_-]+$"))
    | "url = " + (. + "?wait=true" | tojson)
  ' "$changelog_config" | curl --config - \
    --silent --show-error --fail \
    --connect-timeout 10 --max-time 30 \
    --header 'Content-Type: application/json' \
    --data-binary @"$changelog_tmp/payload.json" \
    --output "$changelog_tmp/response.json"
  jq -er '"Posted dev changelog message " + (.id // error("Missing Discord message ID"))' \
    "$changelog_tmp/response.json"
)
```

After success, record the returned message ID in the task summary to prevent duplicate posts.
If the config is invalid or the request fails, report the problem without exposing the webhook URL.
Do not retry an ambiguous timeout automatically, because Discord can accept a message before the response arrives.
