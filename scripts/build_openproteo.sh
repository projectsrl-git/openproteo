#!/usr/bin/env bash
#
# OpenProteo - build (+ optional commit and push) on a bash environment.
#
#   ./scripts/build_openproteo.sh              build, commit -F COMMIT_MSG.txt, push
#   ./scripts/build_openproteo.sh -b           build only (typical on TEST / PROD)
#   ./scripts/build_openproteo.sh -n           build and commit, do NOT push
#   ./scripts/build_openproteo.sh -m "msg"     use this message instead of COMMIT_MSG.txt
#   ./scripts/build_openproteo.sh -y           do not ask for confirmation
#
# Why the WAR is built twice when committing: the commit hash only exists AFTER the
# commit, so the first build merely proves the code compiles (nothing broken gets
# committed) and the second one stamps the real version into the WAR via
#   -Dgit.commit=<short HEAD>  -Dbuild.number=<commit count>
# which Maven filters into build-info.properties and the UI shows in the topbar.
# In build-only mode HEAD is already known, so a single stamped build is enough.
#
set -euo pipefail

APPNAME="openproteo"
COMMIT_FILE="COMMIT_MSG.txt"
DO_COMMIT=1
DO_PUSH=1
ASSUME_YES=0
MESSAGE=""

die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '\n=== %s\n' "$*"; }

usage() {
    sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--build-only) DO_COMMIT=0; DO_PUSH=0 ;;
        -n|--no-push)    DO_PUSH=0 ;;
        -y|--yes)        ASSUME_YES=1 ;;
        -m|--message)    shift; [ $# -gt 0 ] || die "-m requires a message"; MESSAGE="$1" ;;
        -h|--help)       usage ;;
        *)               die "unknown option: $1 (use -h)" ;;
    esac
    shift
done

# ---------------------------------------------------------------- preconditions
command -v git >/dev/null 2>&1 || die "git not found in PATH"
command -v mvn >/dev/null 2>&1 || die "mvn not found in PATH"

REPO="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[ -n "$REPO" ] || die "not inside a git repository"
cd "$REPO"
[ -f pom.xml ] || die "pom.xml not found in $REPO"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
info "Repository: $REPO   branch: $BRANCH"

if [ "$DO_COMMIT" -eq 1 ]; then
    if [ -n "$MESSAGE" ]; then
        :
    elif [ -s "$COMMIT_FILE" ]; then
        :
    else
        die "$COMMIT_FILE missing or empty (use -m \"message\", or -b to only build)"
    fi
fi

# ------------------------------------------------- 1) verification build (no version yet)
if [ "$DO_COMMIT" -eq 1 ]; then
    info "Verification build (nothing is committed if this fails) ..."
    mvn clean package -DskipTests
    [ -f "target/${APPNAME}.war" ] || die "target/${APPNAME}.war not produced"
fi

# ------------------------------------------------------------- 2) commit and push
if [ "$DO_COMMIT" -eq 1 ]; then
    # never commit the patch files that the chat workflow drops in the repo root
    git add -A -- . ':(exclude)*.patch'
    if git diff --cached --quiet; then
        info "Nothing to commit - skipping commit and push."
        DO_PUSH=0
    else
        info "Staged changes:"
        git diff --cached --stat
        if [ "$ASSUME_YES" -eq 0 ]; then
            printf '\nCommit%s on branch %s? [y/N] ' "$([ "$DO_PUSH" -eq 1 ] && printf ' and push')" "$BRANCH"
            read -r reply
            case "$reply" in [yY]|[yY][eE][sS]) ;; *) die "aborted by user (changes remain staged)";; esac
        fi
        if [ -n "$MESSAGE" ]; then
            git commit -m "$MESSAGE"
        else
            git commit -F "$COMMIT_FILE"
        fi
        if [ "$DO_PUSH" -eq 1 ]; then
            info "Pushing to origin/$BRANCH ..."
            git push
        else
            info "Push skipped."
        fi
    fi
fi

# ---------------------------------------- 3) stamped build: version lands in the WAR
GIT_COMMIT="$(git rev-parse --short HEAD)"
BUILD_NUMBER="$(git rev-list --count HEAD)"
info "Build with version: build ${BUILD_NUMBER}, commit ${GIT_COMMIT} ..."
mvn clean package -DskipTests -Dgit.commit="${GIT_COMMIT}" -Dbuild.number="${BUILD_NUMBER}"
[ -f "target/${APPNAME}.war" ] || die "target/${APPNAME}.war not produced"

# ------------------------------------------------------------------- 4) summary
info "Done."
if [ -f target/classes/build-info.properties ]; then
    printf 'Version stamped into the WAR:\n'
    sed 's/^/  /' target/classes/build-info.properties
    if grep -q '@' target/classes/build-info.properties; then
        printf '\nWARNING: a placeholder was not substituted - check the <resources> filtering in pom.xml\n'
    fi
fi
printf '\nWAR: %s\n' "$REPO/target/${APPNAME}.war"
printf 'Deploy it to Tomcat (stop, replace the war, remove the exploded dir and work cache, start).\n'
