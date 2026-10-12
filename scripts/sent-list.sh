#!/bin/bash
# sent-list.sh - the record of the packages already sent, so that no later run sends one again.
#
# Runs as an OpenProteo "bash" step, placed right AFTER a send step: it runs only when the send
# succeeded. list-packages.sh reads the same file through its parameter skipList and leaves out
# every package whose name is recorded here.
#
# Parameters arrive as environment variables:
#   OP_action    add (the only action)
#   OP_list      the file. Created at the first add, never renamed, never emptied by a run.
#   OP_package   the package as it was found in its folder (a path or a name). Its NAME is what
#                is recorded and what list-packages.sh compares.
#   OP_sentAs    what was sent in its place (a path or a name), when that is another file - the
#                rebuilt package, version + 1. Default: the package itself.
#   OP_runId     written on the line, to know which run sent it.
#
# One line per package sent, five fields separated by a tab:
#   name   sent as   folder   run   date (yyyy-mm-dd hh:mm:ss)
# Lines that begin with '#' are comments. To send a package again on purpose, delete its line.
#
# Published (##VAR): sentList (the file, absolute), sentName, sentAsName, sentTotal (lines in
# the list), alreadyListed (true when the name was already in it: recorded again, and said).
# It writes only the list. Exit: 0 done; 2 refused.
# Needs: bash, date.

set -u

say()  { printf '%s\n' "sent-list: $*"; }
fail() { printf '%s\n' "sent-list: $*" >&2; exit 2; }

action="$(printf '%s' "${OP_action:-}" | tr '[:upper:]' '[:lower:]')"
list="${OP_list:-}"
pkg="${OP_package:-}"
sent_as="${OP_sentAs:-}"
run_id="${OP_runId:-}"

nl='
'
tab="$(printf '\t')"

[ "$action" = add ] || fail "action must be add; got '${OP_action:-}'"
[ -n "$list" ] || fail "list is required (parameter list)"
[ -n "$pkg" ] || fail "package is required (parameter package)"
case "$list$pkg$sent_as$run_id" in *"$nl"*|*"$tab"*) fail "a parameter contains a tab or a line break: it would break the list's lines" ;; esac

name="${pkg##*/}"
[ -n "$name" ] || fail "package names a folder, not a file: $pkg"
case "$name" in '#'*) fail "a name that begins with '#' cannot be recorded: it would read as a comment: $name" ;; esac
case "$pkg" in */*) folder="${pkg%/*}" ;; *) folder="" ;; esac
if [ -n "$sent_as" ]; then as_name="${sent_as##*/}"; else as_name="$name"; fi
[ -n "$as_name" ] || fail "sentAs names a folder, not a file: $sent_as"

dir_of_list="$(dirname "$list")"
[ -d "$dir_of_list" ] || fail "the list's folder does not exist: $dir_of_list"
[ ! -e "$list" ] || [ -f "$list" ] || fail "the list is not a regular file: $list"
list="$(cd "$dir_of_list" && pwd)/${list##*/}"

already=false
if [ -f "$list" ]; then
  [ -r "$list" ] || fail "the list cannot be read: $list"
  while IFS="$tab" read -r first _; do
    case "$first" in '#'*|'') continue ;; esac
    if [ "$first" = "$name" ]; then already=true; break; fi
  done < "$list"
else
  printf '# sent list: one line per package sent - name, sent as, folder, run, date. Delete a line to send that package again.\n' > "$list" \
    || fail "cannot create $list"
fi

now="$(date '+%Y-%m-%d %H:%M:%S')" || fail "date failed"
printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$as_name" "$folder" "$run_id" "$now" >> "$list" || fail "cannot write $list"

total=0
while IFS= read -r l; do
  case "$l" in '#'*|'') ;; *) total=$((total + 1)) ;; esac
done < "$list"

if [ "$already" = true ]; then
  say "WARNING: $name was already in the list - sent again, and recorded again"
fi
if [ "$as_name" = "$name" ]; then say "recorded as sent: $name"; else say "recorded as sent: $name (as $as_name)"; fi
say "$total line(s) in $list"

printf '##VAR sentList=%s\n' "$list"
printf '##VAR sentName=%s\n' "$name"
printf '##VAR sentAsName=%s\n' "$as_name"
printf '##VAR sentTotal=%s\n' "$total"
printf '##VAR alreadyListed=%s\n' "$already"
exit 0
