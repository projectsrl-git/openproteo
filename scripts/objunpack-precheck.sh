#!/bin/bash
# objunpack-precheck.sh - look inside a Transarch object package WITHOUT unpacking its objects.
#
# Extracts only <base>.audit.json and <base>.metadata.csv from <base>.tar (or .tar.gz), counts what
# the package declares, and publishes the result as step variables, so that a workflow can validate
# the metadata and send a package that needs no correction as it is.
#
# Runs as an OpenProteo "bash" step. Parameters arrive as environment variables:
#   OP_archive       the package: a path, or a path whose file name has * or ? and matches ONE file
#   OP_outputDir     where the two files are written (the step directory)
#   OP_md5Check      require (default) | ifPresent | off   - the <base>.md5 beside the archive
#   OP_countMembers  yes (default) | no                    - list the archive to count its members
#   OP_onRefusal     fail (default) | report               - what a package that cannot be looked
#                    into does to the STEP. fail: the step fails (exit 2, or 3 for a checksum
#                    mismatch). report: the step succeeds with packageReadable=false and the reason
#                    in problem, so that a workflow going through many packages can set this one
#                    aside and go on. A wrong PARAMETER, or an output folder that cannot be used,
#                    fails the step in both modes: that is the workflow's fault, not the package's.
#
# Published (##VAR): archive, archiveName, md5Name, packageDir, submissionBaseName, metadataCsv,
#   auditJson, expectedRecords, auditFileEntries, tarMembers, csvLines, metadataDelimiter,
#   md5Present, md5Checked, packageCoherent, packageReadable, problem
#   All seventeen are published every time, empty when not known: in a loop a variable that is
#   not published keeps the value of the pass before.
#
# packageReadable=true means the audit and the metadata were extracted and the counts read.
# problem is empty when the package was read, its counts agree and its checksum was verified;
# otherwise it says which of the three is not so.
#
# packageCoherent=true means: record_count in the audit = number of file_name entries in the audit
#   = members of the archive minus three (audit, metadata, control). It says nothing about the
#   CONTENT of the metadata: that is the validate step's job.
#
# It never changes the archive or its folder. Exit: 0 done; 2 refused (parameters, package);
# 3 checksum mismatch. Needs: bash, tar, sed, grep, tr, wc, head; gzip for a .tar.gz; and one of
# md5sum, csum (AIX) or openssl when the checksum is verified.

set -u
set -o pipefail

say()  { printf '%s\n' "objunpack-precheck: $*"; }
fail() { printf '%s\n' "objunpack-precheck: $*" >&2; exit "${2:-2}"; }
var()  { printf '##VAR %s=%s\n' "$1" "$2"; }

# what is known about the package so far; published as it stands by publish
archive=""; name=""; base=""; dir=""; meta=""; audit=""; record_count=""; entries=""; members=""; csv_lines=""; delim=""
md5_present=""; md5_checked=false; coherent=false; readable=false; problem=""
publish() {
  var archive "$archive"
  var archiveName "$name"
  var md5Name "${base:+$base.md5}"
  var packageDir "$dir"
  var submissionBaseName "$base"
  var metadataCsv "$meta"
  var auditJson "$audit"
  var expectedRecords "$record_count"
  var auditFileEntries "$entries"
  var tarMembers "$members"
  var csvLines "$csv_lines"
  var metadataDelimiter "$delim"
  var md5Present "$md5_present"
  var md5Checked "$md5_checked"
  var packageCoherent "$coherent"
  var packageReadable "$readable"
  var problem "$problem"
}
# refuse <message> [exit code]: the PACKAGE cannot be looked into
refuse() {
  if [ "$on_refusal" = report ]; then
    say "NOT READABLE: $1"
    readable=false; coherent=false; meta=""; audit=""; record_count=""; entries=""; members=""; csv_lines=""; delim=""
    problem="$(printf '%s' "$1" | tr '\t\r\n' '   ')"
    publish
    exit 0
  fi
  fail "$1" "${2:-2}"
}

archive_param="${OP_archive:-}"
out="${OP_outputDir:-}"
md5_mode="$(printf '%s' "${OP_md5Check:-require}" | tr '[:upper:]' '[:lower:]')"
count_members="$(printf '%s' "${OP_countMembers:-yes}" | tr '[:upper:]' '[:lower:]')"
on_refusal="$(printf '%s' "${OP_onRefusal:-fail}" | tr '[:upper:]' '[:lower:]')"

[ -n "$archive_param" ] || fail "archive is required (parameter archive)"
[ -n "$out" ] || fail "outputDir is required (parameter outputDir, normally \${stepDir})"
case "$md5_mode" in require|ifpresent|off) ;; *) fail "md5Check must be require, ifPresent or off; got '${OP_md5Check:-}'" ;; esac
case "$count_members" in yes|true|no|false) ;; *) fail "countMembers must be yes or no; got '${OP_countMembers:-}'" ;; esac
case "$on_refusal" in fail|report) ;; *) fail "onRefusal must be fail or report; got '${OP_onRefusal:-}'" ;; esac

# ---- the archive: exactly one file ------------------------------------------------------------
archive="$archive_param"
adir="$(dirname "$archive_param")"
leaf="$(basename "$archive_param")"
[ -d "$adir" ] || refuse "the folder of archive does not exist: $adir"
dir="$(cd "$adir" && pwd)" || refuse "cannot enter $adir"
case "$leaf" in
  *[\*\?]*)
    # the pattern is matched by the shell, case-sensitively, in that folder only
    matches=()
    for f in "$dir"/$leaf; do [ -f "$f" ] && matches+=("$f"); done
    [ "${#matches[@]}" -eq 1 ] || refuse "archive '$leaf' must match exactly one file in $dir; it matches ${#matches[@]}"
    archive="${matches[0]}"
    ;;
  *)
    archive="$dir/$leaf"
    [ -f "$archive" ] || refuse "archive not found: $archive"
    ;;
esac
name="$(basename "$archive")"
case "$name" in
  *.tar.gz) base="${name%.tar.gz}"; gz=yes ;;
  *.tar)    base="${name%.tar}";    gz=no ;;
  *) refuse "'$name' is not a .tar or .tar.gz" ;;
esac
if ! printf '%s' "$base" | grep -Eq '^tf[0-9]{7}\.[0-9]{8}\.S[0-9]{1,3}\.V[0-9]{1,3}$'; then
  bad="$name"; base=""
  refuse "'$bad' is not a submission archive name (tf0000001.20250101.S001.V001.tar)"
fi

mkdir -p "$out" || fail "cannot create $out"
out="$(cd "$out" && pwd)" || fail "cannot enter $out"
[ "$out" != "$dir" ] || fail "outputDir is the archive's own folder; this step writes nothing there"
audit_file="$out/$base.audit.json"
meta_file="$out/$base.metadata.csv"
{ [ ! -e "$audit_file" ] && [ ! -e "$meta_file" ]; } || fail "$out already holds $base.audit.json or $base.metadata.csv; nothing is replaced"

say "$name ($(( $(wc -c < "$archive") )) bytes) in $dir"

# ---- checksum ---------------------------------------------------------------------------------
md5file="$dir/$base.md5"
md5_present=false; [ -f "$md5file" ] && md5_present=true
if [ "$md5_mode" = off ]; then
  say "md5Check=off: the checksum file is not read"
elif [ "$md5_present" = false ]; then
  [ "$md5_mode" = require ] && refuse "no checksum file $md5file (md5Check=ifPresent or off goes on without one)"
  say "WARNING: no checksum file $base.md5: the archive was NOT verified"
else
  declared="$(tr -d ' \t\r\n' < "$md5file" | tr '[:upper:]' '[:lower:]')"
  printf '%s' "$declared" | grep -Eq '^[0-9a-f]{32}$' || refuse "$base.md5 does not hold a bare MD5 (32 hex characters)"
  if command -v md5sum >/dev/null 2>&1; then raw="$(md5sum "$archive")"
  elif command -v csum >/dev/null 2>&1; then raw="$(csum -h MD5 "$archive")"
  elif command -v openssl >/dev/null 2>&1; then raw="$(openssl md5 "$archive")"
  else raw=""; fi
  if [ -z "$raw" ]; then
    [ "$md5_mode" = require ] && fail "none of md5sum, csum, openssl is available to verify the checksum (md5Check=off skips it)"
    say "WARNING: no md5 tool on this host: the archive was NOT verified"
  else
    # the hash is the first run of 32 hex characters that is not part of a longer one
    actual="$(printf '%s\n' "$raw" | tr '[:upper:]' '[:lower:]' | tr -c '0-9a-f\n' ' ' | tr ' ' '\n' | grep -E '^[0-9a-f]{32}$' | head -1)"
    [ -n "$actual" ] || fail "could not read an MD5 from the checksum tool's output"
    [ "$actual" = "$declared" ] || refuse "$name has MD5 $actual but $base.md5 says $declared" 3
    md5_checked=true
    say "checksum verified: $actual"
  fi
fi

# ---- the two files, by their exact names: no other member is written ---------------------------
say "extracting $base.audit.json and $base.metadata.csv into $out"
if [ "$gz" = yes ]; then
  gzip -dc "$archive" | ( cd "$out" && tar -xf - "$base.audit.json" "$base.metadata.csv" ) \
    || refuse "tar could not extract the audit and metadata files from $name"
else
  ( cd "$out" && tar -xf "$archive" "$base.audit.json" "$base.metadata.csv" ) \
    || refuse "tar could not extract the audit and metadata files from $name (are they named $base.audit.json and $base.metadata.csv?)"
fi
[ -f "$audit_file" ] || refuse "$base.audit.json was not in $name"
[ -f "$meta_file" ]  || refuse "$base.metadata.csv was not in $name"
audit="$audit_file"; meta="$meta_file"

# ---- what the audit declares ------------------------------------------------------------------
record_count="$(tr ',{}' '\n\n\n' < "$audit" | sed -n 's/.*"record_count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1)"
[ -n "$record_count" ] || refuse "$base.audit.json has no numeric record_count"
entries=$(( $(tr ',{}' '\n\n\n' < "$audit" | grep -c '"file_name"[[:space:]]*:') ))

members=""
case "$count_members" in yes|true)
  if [ "$gz" = yes ]; then members=$(( $(gzip -dc "$archive" | tar -tf - | wc -l) )) || refuse "cannot list $name"
  else members=$(( $(tar -tf "$archive" | wc -l) )) || refuse "cannot list $name"; fi ;;
esac

csv_lines=$(( $(wc -l < "$meta") ))

# the delimiter: the character after the object_id header (a BOM and a quote before it are skipped)
IFS= read -r first < "$meta" || true
first="${first#$'\xef\xbb\xbf'}"
delim=""
case "$first" in
  \"object_id\"?*) delim="${first:11:1}" ;;
  object_id?*)     delim="${first:9:1}" ;;
esac
case "$delim" in
  $'\t') delim=tab ;;
  [A-Za-z0-9_\"]|$'\r'|"") delim="" ;;
esac
[ -n "$delim" ] || say "WARNING: $base.metadata.csv does not start with an object_id column: the delimiter was not read"

coherent=true
[ "$record_count" -eq "$entries" ] || coherent=false
if [ -n "$members" ] && [ "$members" -ne $(( record_count + 3 )) ]; then coherent=false; fi
readable=true

say "record_count=$record_count, file_name entries=$entries, archive members=${members:-not counted} (expected $(( record_count + 3 ))), metadata lines=$csv_lines"
if [ "$coherent" != true ]; then
  say "the counts do NOT agree: this package is not a candidate for sending as it is"
  problem="the counts do not agree: record_count=$record_count, file_name entries=$entries, archive members=${members:-not counted} (expected $(( record_count + 3 )))"
elif [ "$md5_checked" != true ]; then
  problem="the checksum was not verified (md5Check=$md5_mode, checksum file present: $md5_present)"
fi

publish
exit 0
