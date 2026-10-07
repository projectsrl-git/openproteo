#!/bin/bash
# list-packages.sh - list the files of one folder that match a pattern, for a LOOP to go through.
#
# Runs as an OpenProteo "bash" step. Parameters arrive as environment variables:
#   OP_dir       the folder to look in (not its subfolders)
#   OP_pattern   a file-name pattern with * and ?; default *.tar. Matched by the shell,
#                case-sensitively. Several patterns separated by a blank: "*.tar *.tar.gz"
#   OP_onEmpty   ok (default) | fail   - what to do when nothing matches
#
# Published (##VAR):
#   packages       the full paths, in byte order of the names, joined with ';'
#   packageNames   the names alone, same order, joined with ';'
#   packageCount   how many
#   packagesDir    the folder, as an absolute path
#
# Only regular files are listed. A name with ';' or a line break in it, or one that begins or
# ends with a blank, cannot be put in the list and stops the step, naming the file: the LOOP
# splits the list on ';' and trims each item, so it would go through names that do not exist.
# It reads the folder and changes nothing. Exit: 0 done; 2 refused.
# Needs: bash, sort.

set -u

say()  { printf '%s\n' "list-packages: $*"; }
fail() { printf '%s\n' "list-packages: $*" >&2; exit 2; }

dir="${OP_dir:-}"
pattern="${OP_pattern:-*.tar}"
on_empty="$(printf '%s' "${OP_onEmpty:-ok}" | tr '[:upper:]' '[:lower:]')"

[ -n "$dir" ] || fail "dir is required (parameter dir)"
[ -d "$dir" ] || fail "the folder does not exist: $dir"
case "$on_empty" in ok|fail) ;; *) fail "onEmpty must be ok or fail; got '${OP_onEmpty:-}'" ;; esac
case "$pattern" in */*) fail "pattern is a file-name pattern, not a path; got '$pattern'" ;; esac
dir="$(cd "$dir" && pwd)" || fail "cannot enter $dir"

names=()
nl='
'
tab="$(printf '\t')"
case "$dir" in *\;*|*"$nl"*) fail "the folder's path contains ';' or a line break: the list could not be split" ;; esac
set -f                                   # the patterns are split on blanks here, not expanded
for p in $pattern; do
  set +f
  for f in "$dir"/$p; do
    [ -f "$f" ] || continue              # an unmatched pattern stays as written, and is not a file
    n="${f##*/}"
    case "$n" in
      *\;*)    fail "a file name contains ';' and cannot be listed: $n" ;;
      *"$nl"*) fail "a file name contains a line break and cannot be listed" ;;
      " "*|*" "|"$tab"*|*"$tab") fail "a file name begins or ends with a blank and cannot be listed: '$n'" ;;
    esac
    names+=("$n")
  done
  set -f
done
set +f

list=""; name_list=""; count=0
if [ "${#names[@]}" -gt 0 ]; then
  # byte order, and each file once even when two patterns match it
  sorted="$(printf '%s\n' "${names[@]}" | LC_ALL=C sort -u)" || fail "sort failed"
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    list="${list:+$list;}$dir/$n"
    name_list="${name_list:+$name_list;}$n"
    count=$((count + 1))
    say "$count: $n"
  done <<EOT
$sorted
EOT
fi

say "$count file(s) match '$pattern' in $dir"
if [ "$count" -eq 0 ] && [ "$on_empty" = fail ]; then
  fail "nothing matches '$pattern' in $dir (onEmpty=fail)"
fi

printf '##VAR packages=%s\n' "$list"
printf '##VAR packageNames=%s\n' "$name_list"
printf '##VAR packageCount=%s\n' "$count"
printf '##VAR packagesDir=%s\n' "$dir"
exit 0
