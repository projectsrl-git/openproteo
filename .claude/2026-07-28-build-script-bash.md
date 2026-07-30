# scripts/build_openproteo.sh - build (+ commit and push) on bash, for TEST and PROD

The Windows .bat covers the chat patch workflow; this is the bash equivalent for the test and
production boxes, and it stamps the version exactly the same way.

## Usage
    ./scripts/build_openproteo.sh          build, commit -F COMMIT_MSG.txt, push (asks first)
    ./scripts/build_openproteo.sh -b       build only - the normal case on TEST / PROD
    ./scripts/build_openproteo.sh -n       build and commit, do not push
    ./scripts/build_openproteo.sh -m "..." use this message instead of COMMIT_MSG.txt
    ./scripts/build_openproteo.sh -y       skip the confirmation prompt
    ./scripts/build_openproteo.sh -h       help

## Why two builds when committing
The commit hash only exists AFTER the commit, so the first build merely proves the code compiles
(nothing broken gets committed) and the second stamps the real identity into the WAR with
`-Dgit.commit=<short HEAD> -Dbuild.number=<git rev-list --count HEAD>`, which Maven filters into
build-info.properties and the topbar shows. With `-b` HEAD is already known, so one build is enough
-- that is why build-only is the right mode on TEST and PROD, where you pull and rebuild rather than
commit.

## Guards
`set -euo pipefail`; git and mvn must be on PATH; must run inside the repo (it cd's to the top
level) and pom.xml must exist; COMMIT_MSG.txt must be non-empty unless -m or -b is used; patch files
are excluded from the commit (`git add -A -- . ':(exclude)*.patch'`, matching the .bat); if nothing
is staged the commit and push are skipped and only the stamped build runs; an interactive
confirmation shows the staged diffstat first (bypass with -y); the WAR is checked after each build.
Finally it prints the stamped build-info.properties and WARNS if a placeholder was not substituted
-- that check alone would have caught the `${git.commit}` delimiter bug immediately.

## Verify
Syntax checked with `bash -n`, then exercised end to end against a stubbed `mvn` in a throwaway git
repo: build-only (single stamped build), commit+no-push (two builds, commit created, `*.patch` kept
out, only sources committed because target/ and *.war are gitignored -- confirmed with
`git check-ignore -v`), nothing-to-commit (commit and push skipped, stamped build still runs),
missing COMMIT_MSG.txt (clean error), unsubstituted placeholder (warning fired), and -h.

## Note
The script is committed with mode 100755. If a transfer loses the bit, restore it with
`chmod +x scripts/build_openproteo.sh` (or run it as `bash scripts/build_openproteo.sh`).
