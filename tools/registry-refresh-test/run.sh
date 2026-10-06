#!/bin/bash
# refresh() against reload(), on the real WorkflowRegistry, WorkflowScheduler, WorkflowXmlParser
# and FeedLayout. Needs a JDK only: Spring, slf4j and AuditLogger are the stubs in stubs/.
#
#   tools/registry-refresh-test/run.sh [JDK_HOME] [seed] [rounds]
#
# JDK_HOME empty = the javac/java on the PATH (then javax.annotation must exist: Java 8).
# SRC=<dir> compiles the orchestrator sources from another tree (mutate.py uses it).
# Exit code 0 = every check passed. The XML parser's own stderr chatter is dropped.
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${SRC:-$HERE/../../src/main/java/com/legalarchive/orchestrator}"
OUT="${OUT:-$(mktemp -d)}"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$OUT" "$WORK"
JAVAC="${1:+$1/bin/}javac"; JAVA="${1:+$1/bin/}java"
"$JAVAC" -encoding UTF-8 -nowarn -d "$OUT" $(find "$HERE/stubs" -name '*.java') "$HERE/RefreshTest.java" \
    "$SRC/registry/WorkflowRegistry.java" "$SRC/engine/WorkflowScheduler.java" "$SRC/config/AppProperties.java" \
    "$SRC/parser/WorkflowXmlParser.java" "$SRC/store/FeedLayout.java" "$SRC"/model/def/*.java 2>&1 | grep -v '^Note:'
[ "${PIPESTATUS[0]}" = 0 ] || { echo "DOES NOT COMPILE"; exit 3; }
"$JAVA" -cp "$OUT" RefreshTest "$WORK" "${2:-20261004}" "${3:-400}" 2>/dev/null
