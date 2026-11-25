#!/bin/sh
# OpenProteo command line (.claude/HEADLESS.md sections 6.1 and 15).
# Copy this file, as "openproteo", beside openproteo-headless.war in the installation folder
# (OPENPROTEO_HOME). When OPENPROTEO_HOME is not set, it is the folder of this file.
#   openproteo start      start the instance in the background
#   openproteo connect    open the console
#   openproteo <command>  one command, for scripts (openproteo --help)
# OPENPROTEO_JAVA or JAVA_HOME selects Java; the exit code of the command is returned unchanged.
here=$(cd "$(dirname "$0")" && pwd)
: "${OPENPROTEO_HOME:=$here}"
export OPENPROTEO_HOME
war="$OPENPROTEO_HOME/openproteo-headless.war"
if [ ! -f "$war" ]; then
  echo "openproteo: $war not found: copy openproteo-headless.war into OPENPROTEO_HOME" >&2
  exit 30
fi
if [ -n "$OPENPROTEO_JAVA" ]; then
  java="$OPENPROTEO_JAVA"
elif [ -n "$JAVA_HOME" ]; then
  java="$JAVA_HOME/bin/java"
else
  java=java
fi
exec "$java" -jar "$war" cli "$@"
