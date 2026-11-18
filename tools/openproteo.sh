#!/bin/sh
# OpenProteo command line (.claude/HEADLESS.md section 6.1).
# Copy this file beside openproteo-headless.war. Set OPENPROTEO_CONTROL to the control
# directory of the instance (or pass --control <dir>); OPENPROTEO_JAVA or JAVA_HOME selects Java.
# The exit code of the command is returned unchanged.
here=$(cd "$(dirname "$0")" && pwd)
war="$here/openproteo-headless.war"
if [ ! -f "$war" ]; then
  echo "openproteo: $war not found: copy openproteo-headless.war beside this file" >&2
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
