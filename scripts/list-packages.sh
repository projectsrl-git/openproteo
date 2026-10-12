#!/bin/bash
# list-packages.sh - list the files of one folder that match a pattern, for a LOOP to go through.
#
# Runs as an OpenProteo "bash" step. Parameters arrive as environment variables:
#   OP_dir       the folder to look in (not its subfolders)
#   OP_pattern   a file-name pattern with * and ?; default *.tar. Matched by the shell,
#                case-sensitively. Several patterns separated by a blank: "*.tar *.tar.gz"
#   OP_onEmpty   ok (default) | fail   - what to do when nothing matches
#   OP_skipList  optional: a sent list written by sent-list.sh. A file whose NAME is the first
#                field of one of its lines is left out, and named in the step log. A list that
#                does not exist yet leaves nothing out. onEmpty applies after the leaving out:
#                with everything already sent and onEmpty=ok, nothing is left to do. With a
#                skipList, a file name that begins with '#' stops the step: it could not be told
#                from a comment in the list.
#
# Published (##VAR):
#   packages       the full paths, in byte order of the names, joined with ';'
#   packageNames   the names alone, same order, joined with ';'
#   packageCount   how many
#   packagesDir    the folder, as an absolute path
#   skippedCount   how many matching files were left out because already sent (0 without skipList)
#   skippedNames   their names, joined with ';'
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
skip_list="${OP_skipList:-}"

[ -n "$dir" ] || fail "dir is required (parameter dir)"
[ -d "$dir" ] || fail "the folder does not exist: $dir"
case "$on_empty" in ok|fail) ;; *) fail "onEmpty must be ok or fail; got '${OP_onEmpty:-}'" ;; esac
case "$pattern" in */*) fail "pattern is a file-name pattern, not a path; got '$pattern'" ;; esac
dir="$(cd "$dir" && pwd)" || fail "cannot enter $dir"

names=()
nl='
'
tab="$(printf '\t')"

# the names already sent, one per line between two line breaks, for a plain 'case' lookup
sent_set="$nl"
if [ -n "$skip_list" ]; then
  if [ -e "$skip_list" ]; then
    [ -f "$skip_list" ] || fail "skipList is not a regular file: $skip_list"
    [ -r "$skip_list" ] || fail "skipList cannot be read: $skip_list"
    while IFS="$tab" read -r first _; do
      case "$first" in '#'*|'') continue ;; esac
      sent_set="$sent_set$first$nl"
    done < "$skip_list"
  else
    say "no sent list yet ($skip_list): nothing is left out"
  fi
fi
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
    case "$n" in '#'*) [ -z "$skip_list" ] || fail "a file name begins with '#' and cannot be kept in a sent list, where '#' begins a comment: $n" ;; esac
    names+=("$n")
  done
  set -f
done
set +f

list=""; name_list=""; count=0; skipped_list=""; skipped=0
if [ "${#names[@]}" -gt 0 ]; then
  # byte order, and each file once even when two patterns match it
  sorted="$(printf '%s\n' "${names[@]}" | LC_ALL=C sort -u)" || fail "sort failed"
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    case "$sent_set" in
      *"$nl$n$nl"*)
        skipped=$((skipped + 1)); skipped_list="${skipped_list:+$skipped_list;}$n"
        say "already sent, left out: $n"
        continue ;;
    esac
    list="${list:+$list;}$dir/$n"
    name_list="${name_list:+$name_list;}$n"
    count=$((count + 1))
    say "$count: $n"
  done <<EOT
$sorted
EOT
fi

if [ -n "$skip_list" ]; then
  say "$count file(s) to do, $skipped left out as already sent; pattern '$pattern' in $dir"
else
  say "$count file(s) match '$pattern' in $dir"
fi
if [ "$count" -eq 0 ] && [ "$on_empty" = fail ]; then
  fail "nothing matches '$pattern' in $dir (onEmpty=fail)"
fi

printf '##VAR packages=%s\n' "$list"
printf '##VAR packageNames=%s\n' "$name_list"
printf '##VAR packageCount=%s\n' "$count"
printf '##VAR packagesDir=%s\n' "$dir"
printf '##VAR skippedCount=%s\n' "$skipped"
printf '##VAR skippedNames=%s\n' "$skipped_list"
exit 0
