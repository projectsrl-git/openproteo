#!/bin/bash
# sent-list.sh - the record of the packages already sent, so that no later run sends one again.
#
# list-packages.sh reads the same file through its parameter skipList and leaves out every package
# whose name is recorded here. Two actions write it:
#
#   add        placed right AFTER a send step that sends one package: it runs only when the send
#              succeeded, and records that package.
#   reconcile  for a send of many packages from a staging folder written by send-stage.sh: it
#              records every package of the folder whose two files have BOTH been sent, says
#              which ones were sent only in part, and lists those still to send. Placed after the
#              send AND at the start of the next run, because a send that fails ends the run
#              before the step after it.
#
# Runs as an OpenProteo "bash" step. Parameters arrive as environment variables:
#   OP_action    add | reconcile
#   OP_list      the file. Created at the first line recorded, never renamed, never emptied.
#   OP_package   add: the package as it was found in its folder (a path or a name). Its NAME is
#                what is recorded and what list-packages.sh compares.
#   OP_sentAs    add: what was sent in its place (a path or a name), when that is another file -
#                the rebuilt package, version + 1. Default: the package itself.
#   OP_runId     written on the line, to know which run recorded it.
#   OP_staging   reconcile: the staging folder. One sub-folder per package, named by its base
#                name, holding a link to the package, a link to its .md5 and a file .origin.
#                The send renames each link with the suffix .sent once that file is sent and
#                verified (ftpsend renameSent=.sent).
#
# One line per package sent, five fields separated by a tab:
#   name   sent as   folder   run   date (yyyy-mm-dd hh:mm:ss)
# Lines that begin with '#' are comments. To send a package again on purpose, delete its line.
#
# reconcile reads each package folder of the staging folder, in name order:
#   <name>.sent and <base>.md5.sent   SENT: recorded (unless its name is already in the list) and
#                                     the folder removed - the two links and .origin, never what
#                                     they point to
#   <name>.sent and <base>.md5        HALF: the package was sent, its .md5 was not. Still to send:
#                                     the send finds only the .md5 and sends only that
#   <name> and <base>.md5             TO SEND. If the package or its .md5 is gone, or the package
#                                     is not the size it had when it was prepared, the folder is
#                                     removed and the package is named as unstaged: the next run
#                                     that checks will look at it again
#   anything else                     the step stops and names the folder: a link renamed by hand,
#                                     a rename that failed after a send, a file that is not ours.
#                                     A person decides
# A folder whose name begins with '.new-' was left by a preparation that stopped half way: it is
# removed. A staging folder that does not exist yet holds nothing.
#
# Published (##VAR):
#   add:        sentList (the file, absolute), sentName, sentAsName, sentTotal (lines in the
#               list), alreadyListed (true when the name was already in it: recorded again, said)
#   reconcile:  sentList, sentTotal, recordedCount, recordedNames, toSendCount, toSendDirs (the
#               package folders still to send, full paths joined with ';', in name order),
#               toSendNames, halfCount, halfNames, unstagedCount, unstagedNames
# It writes only the list and, in reconcile, the staging folder. It never touches a package.
# Exit: 0 done; 2 refused.
# Needs: bash, date, ls, rm, rmdir, sort.

set -u

say()  { printf '%s\n' "sent-list: $*"; }
fail() { printf '%s\n' "sent-list: $*" >&2; exit 2; }

action="$(printf '%s' "${OP_action:-}" | tr '[:upper:]' '[:lower:]')"
list="${OP_list:-}"
run_id="${OP_runId:-}"

nl='
'
tab="$(printf '\t')"

case "$action" in add|reconcile) ;; *) fail "action must be add or reconcile; got '${OP_action:-}'" ;; esac
[ -n "$list" ] || fail "list is required (parameter list)"
case "$list$run_id" in *"$nl"*|*"$tab"*) fail "a parameter contains a tab or a line break: it would break the list's lines" ;; esac

dir_of_list="$(dirname "$list")"
[ -d "$dir_of_list" ] || fail "the list's folder does not exist: $dir_of_list"
[ ! -e "$list" ] || [ -f "$list" ] || fail "the list is not a regular file: $list"
list="$(cd "$dir_of_list" && pwd)/${list##*/}"

# in_list <name>: the name is the first field of a line that is not a comment
in_list() {
  [ -f "$list" ] || return 1
  [ -r "$list" ] || fail "the list cannot be read: $list"
  while IFS="$tab" read -r first _; do
    case "$first" in '#'*|'') continue ;; esac
    [ "$first" = "$1" ] && return 0
  done < "$list"
  return 1
}

# record <name> <sent as> <folder>: one line, the list created with its comment line if needed.
# The one place where a line of the list is written.
record() {
  case "$1$2$3" in *"$nl"*|*"$tab"*) fail "a name or folder contains a tab or a line break: it would break the list's lines" ;; esac
  case "$1" in '#'*) fail "a name that begins with '#' cannot be recorded: it would read as a comment: $1" ;; esac
  if [ ! -f "$list" ]; then
    printf '# sent list: one line per package sent - name, sent as, folder, run, date. Delete a line to send that package again.\n' > "$list" \
      || fail "cannot create $list"
  fi
  now="$(date '+%Y-%m-%d %H:%M:%S')" || fail "date failed"
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$run_id" "$now" >> "$list" || fail "cannot write $list"
}

count_lines() {
  total=0
  [ -f "$list" ] || return 0
  while IFS= read -r l; do
    case "$l" in '#'*|'') ;; *) total=$((total + 1)) ;; esac
  done < "$list"
}

# ------------------------------------------------------------------------------------------- add
if [ "$action" = add ]; then
  pkg="${OP_package:-}"
  sent_as="${OP_sentAs:-}"
  [ -n "$pkg" ] || fail "package is required (parameter package)"
  case "$pkg$sent_as" in *"$nl"*|*"$tab"*) fail "a parameter contains a tab or a line break: it would break the list's lines" ;; esac
  name="${pkg##*/}"
  [ -n "$name" ] || fail "package names a folder, not a file: $pkg"
  case "$name" in '#'*) fail "a name that begins with '#' cannot be recorded: it would read as a comment: $name" ;; esac
  case "$pkg" in */*) folder="${pkg%/*}" ;; *) folder="" ;; esac
  if [ -n "$sent_as" ]; then as_name="${sent_as##*/}"; else as_name="$name"; fi
  [ -n "$as_name" ] || fail "sentAs names a folder, not a file: $sent_as"

  already=false
  if in_list "$name"; then already=true; fi
  record "$name" "$as_name" "$folder"
  count_lines

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
fi

# ------------------------------------------------------------------------------------- reconcile
staging="${OP_staging:-}"
[ -n "$staging" ] || fail "staging is required (parameter staging)"
case "$staging" in *"$nl"*|*"$tab"*|*\;*) fail "the staging folder's path contains ';', a tab or a line break: the list of folders to send could not be split" ;; esac

recorded=0; recorded_names=""; to_send=0; to_send_dirs=""; to_send_names=""
half=0; half_names=""; unstaged=0; unstaged_names=""

# the names already in the list, read ONCE, one per line between two line breaks: with hundreds of
# packages, reading the list again for every folder would cost hundreds of passes over it
sent_set="$nl"
if [ -f "$list" ]; then
  [ -r "$list" ] || fail "the list cannot be read: $list"
  while IFS="$tab" read -r first _; do
    case "$first" in '#'*|'') continue ;; esac
    sent_set="$sent_set$first$nl"
  done < "$list"
fi

# remove_stage <folder>: its links and its .origin, then the folder. Anything else stops the step:
# rm on a link removes the link, never the file it points to, and nothing here is not a link.
remove_stage() {
  local f
  for f in "$1"/* "$1"/.[!.]* "$1"/..?*; do
    [ -e "$f" ] || [ -L "$f" ] || continue        # an unmatched pattern stays as written
    if [ -L "$f" ]; then
      rm -f -- "$f" || fail "cannot remove the link $f"
    elif [ "${f##*/}" = .origin ] && [ -f "$f" ]; then
      rm -f -- "$f" || fail "cannot remove $f"
    else
      fail "$f is not a link made by send-stage.sh: the folder $1 is left as it is"
    fi
  done
  rmdir -- "$1" || fail "cannot remove the folder $1"
}

# size_of <folder> <name>: the size of the file the link points to, from ls (not wc, which may
# read the whole file on some hosts). The name has no blank in it: it is a submission name.
size_of() {
  local out
  out="$(cd "$1" && ls -lnL -- "$2" 2>/dev/null)" || return 1
  set -f; set -- $out; set +f
  [ $# -ge 5 ] || return 1
  printf '%s' "$5"
}

if [ ! -e "$staging" ]; then
  say "no staging folder yet ($staging): nothing to reconcile"
else
  [ -d "$staging" ] || fail "staging is not a folder: $staging"
  staging="$(cd "$staging" && pwd)" || fail "cannot enter $staging"
  [ -r "$staging" ] || fail "the staging folder cannot be read: $staging"

  entries=()
  for e in "$staging"/* "$staging"/.[!.]* "$staging"/..?*; do
    [ -e "$e" ] || [ -L "$e" ] || continue
    entries+=("${e##*/}")
  done
  sorted=""
  if [ "${#entries[@]}" -gt 0 ]; then
    sorted="$(printf '%s\n' "${entries[@]}" | LC_ALL=C sort)" || fail "sort failed"
  fi

  while IFS= read -r e; do
    [ -n "$e" ] || continue
    d="$staging/$e"
    case "$e" in
      .new-*)
        { [ -d "$d" ] && [ ! -L "$d" ]; } || fail "$d is not a folder made by send-stage.sh"
        say "a preparation that stopped half way left $e: removed"
        remove_stage "$d"
        continue ;;
    esac
    { [ -d "$d" ] && [ ! -L "$d" ]; } || fail "$d is not a package folder made by send-stage.sh: a person decides what it is"
    origin="$d/.origin"
    [ -f "$origin" ] || fail "$d has no .origin file: it was not made by send-stage.sh"
    pkg=""; md5=""; size=""
    while IFS="$tab" read -r k v; do
      case "$k" in package) pkg="$v" ;; md5) md5="$v" ;; size) size="$v" ;; esac
    done < "$origin"
    { [ -n "$pkg" ] && [ -n "$md5" ]; } || fail "$origin does not name the package and its .md5"
    name="${pkg##*/}"
    case "$name" in
      *.tar.gz) base="${name%.tar.gz}" ;;
      *.tar)    base="${name%.tar}" ;;
      *) fail "$origin names '$name', which is not a .tar or .tar.gz" ;;
    esac
    [ "$base" = "$e" ] || fail "$d holds $name: the folder should be called $base"
    mname="$base.md5"

    # what the folder holds, and nothing else
    for f in "$d"/* "$d"/.[!.]* "$d"/..?*; do
      [ -e "$f" ] || [ -L "$f" ] || continue
      case "${f##*/}" in
        .origin) ;;
        "$name"|"$name.sent"|"$mname"|"$mname.sent")
          [ -L "$f" ] || fail "$f is not a link: send-stage.sh makes only links there. A person decides" ;;
        *) fail "$d holds ${f##*/}, which send-stage.sh does not make: a person decides" ;;
      esac
    done
    t_link=false; t_sent=false; m_link=false; m_sent=false
    [ -L "$d/$name" ] && t_link=true
    [ -L "$d/$name.sent" ] && t_sent=true
    [ -L "$d/$mname" ] && m_link=true
    [ -L "$d/$mname.sent" ] && m_sent=true
    case "$t_link$t_sent$m_link$m_sent" in
      falsetruefalsetrue)
        if case "$sent_set" in *"$nl$name$nl"*) true ;; *) false ;; esac; then
          say "sent, already in the list: $name"
        else
          record "$name" "$name" "${pkg%/*}"
          sent_set="$sent_set$name$nl"
          recorded=$((recorded + 1)); recorded_names="${recorded_names:+$recorded_names;}$name"
          say "sent, recorded: $name"
        fi
        remove_stage "$d"
        ;;
      falsetruetruefalse)
        [ -f "$d/$mname" ] || fail "$name was sent and its .md5 was not, and the .md5 is no longer at $md5: a person decides"
        half=$((half + 1)); half_names="${half_names:+$half_names;}$name"
        to_send=$((to_send + 1)); to_send_dirs="${to_send_dirs:+$to_send_dirs;}$d"; to_send_names="${to_send_names:+$to_send_names;}$name"
        say "HALF SENT: $name was sent, $mname was not - only $mname is left to send"
        ;;
      truefalsetruefalse)
        why=""
        if [ ! -f "$d/$name" ]; then why="the package is no longer at $pkg"
        elif [ ! -f "$d/$mname" ]; then why="the .md5 is no longer at $md5"
        elif [ -n "$size" ]; then
          now_size="$(size_of "$d" "$name")" || now_size="?"
          [ "$now_size" = "$size" ] || why="the package was $size bytes when it was prepared and is $now_size now"
        fi
        if [ -n "$why" ]; then
          say "WARNING: $name is no longer prepared: $why. The next run that checks will look at it again"
          remove_stage "$d"
          unstaged=$((unstaged + 1)); unstaged_names="${unstaged_names:+$unstaged_names;}$name"
        else
          to_send=$((to_send + 1)); to_send_dirs="${to_send_dirs:+$to_send_dirs;}$d"; to_send_names="${to_send_names:+$to_send_names;}$name"
          say "to send: $name"
        fi
        ;;
      *)
        held=""
        [ "$t_link" = true ] && held="$held $name"
        [ "$t_sent" = true ] && held="$held $name.sent"
        [ "$m_link" = true ] && held="$held $mname"
        [ "$m_sent" = true ] && held="$held $mname.sent"
        fail "$d holds${held:- no link}: not a state a send leaves (was a link renamed by hand, or did a rename fail after a send? the FTPS step log says 'could not rename'). A person decides; nothing was recorded for it"
        ;;
    esac
  done <<EOT
$sorted
EOT
fi

count_lines
say "$recorded recorded now, $to_send to send ($half of them half sent), $unstaged no longer prepared; $total line(s) in $list"

printf '##VAR sentList=%s\n' "$list"
printf '##VAR sentTotal=%s\n' "$total"
printf '##VAR recordedCount=%s\n' "$recorded"
printf '##VAR recordedNames=%s\n' "$recorded_names"
printf '##VAR toSendCount=%s\n' "$to_send"
printf '##VAR toSendDirs=%s\n' "$to_send_dirs"
printf '##VAR toSendNames=%s\n' "$to_send_names"
printf '##VAR halfCount=%s\n' "$half"
printf '##VAR halfNames=%s\n' "$half_names"
printf '##VAR unstagedCount=%s\n' "$unstaged"
printf '##VAR unstagedNames=%s\n' "$unstaged_names"
exit 0
