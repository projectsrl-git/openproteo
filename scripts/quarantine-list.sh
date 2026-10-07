#!/bin/bash
# quarantine-list.sh - keep the list of packages a check set aside, for a second workflow to work.
#
# A check goes through many packages and sets aside those it will not send as they are; a
# correction then takes the list and works them - two workflows, or two phases of one. The list
# is a text file, one package per line:
#
#     <category> TAB <full path of the package> TAB <reason>
#
# category is  correct  (the package can be read; its metadata needs correcting: unpack, rebuild,
# resend) or  manual  (it cannot be handled by a workflow: wrong checksum, not a package, counts
# that do not agree). Lines beginning with # are comments. Beside it, <list>.done holds the paths
# already worked, one per line, so that the correction can be run again after a failure and go
# on from where it stopped.
#
# Runs as an OpenProteo "bash" step. Parameters arrive as environment variables:
#   OP_action    new | add | pending | done | summary
#   OP_list      the list file (all actions)
#   OP_runId     new: written in the header, to say which run made the list
#   OP_category  add: correct | manual
#   OP_package   add, done: the package's full path
#   OP_reason    add: free text; tabs and line breaks become blanks
#
#   new      starts an empty list. A list already there is KEPT, renamed <list>.<date-time>, with
#            its .done file; nothing is deleted.
#   add      appends one package. A package already in the list is not added twice.
#   pending  publishes the packages of category "correct" that are not in <list>.done.
#            A list that does not exist is an empty list.
#   done     records one package as worked.
#   summary  publishes the counts.
#
# Published (##VAR), by every action: quarantineList, quarantineTotal, quarantineCorrect,
#   quarantineManual, quarantineDone, pendingCount, pending (full paths joined with ';'),
#   previousList (new: where the earlier list went, or empty)
#
# It never touches a package. Exit: 0 done; 2 refused. Needs: bash, date, mv.

set -u

say()  { printf '%s\n' "quarantine-list: $*"; }
fail() { printf '%s\n' "quarantine-list: $*" >&2; exit 2; }

action="$(printf '%s' "${OP_action:-}" | tr '[:upper:]' '[:lower:]')"
list="${OP_list:-}"
tab="$(printf '\t')"
nl='
'
case "$action" in new|add|pending|done|summary) ;; *) fail "action must be new, add, pending, done or summary; got '${OP_action:-}'" ;; esac
[ -n "$list" ] || fail "list is required (parameter list)"
case "$list" in *"$nl"*|*"$tab"*) fail "the list's path contains a tab or a line break" ;; esac
[ ! -d "$list" ] || fail "list is a folder, not a file: $list"
done_file="$list.done"
previous=""

# a package path that can be a line of the list and an item of a LOOP
check_package() {
  [ -n "$1" ] || fail "package is required (parameter package)"
  case "$1" in
    *"$tab"*|*"$nl"*) fail "the package's path contains a tab or a line break and cannot be listed" ;;
    *\;*) fail "the package's path contains ';' and cannot be listed: $1" ;;
    " "*|*" ") fail "the package's path begins or ends with a blank and cannot be listed: '$1'" ;;
  esac
}

is_done() {   # is_done <path>
  [ -f "$done_file" ] || return 1
  while IFS= read -r d || [ -n "$d" ]; do
    [ "$d" = "$1" ] && return 0
  done < "$done_file"
  return 1
}

in_list() {   # in_list <path>
  [ -f "$list" ] || return 1
  while IFS="$tab" read -r c p r || [ -n "$c" ]; do
    case "$c" in "#"*|"") continue ;; esac
    [ "$p" = "$1" ] && return 0
  done < "$list"
  return 1
}

case "$action" in
  new)
    parent="$(dirname "$list")"
    [ -d "$parent" ] || mkdir -p "$parent" || fail "cannot create the folder of the list: $parent"
    if [ -e "$list" ]; then
      stamp="$(date +%Y%m%d-%H%M%S)"            # when it was set aside (date -r means something else on AIX)
      previous="$list.$stamp"; n=1
      while [ -e "$previous" ] || [ -e "$previous.done" ]; do n=$((n + 1)); previous="$list.$stamp-$n"; done
      mv "$list" "$previous" || fail "cannot set the earlier list aside: $list"
      if [ -e "$done_file" ]; then mv "$done_file" "$previous.done" || fail "cannot set the earlier .done file aside: $done_file"; fi
      say "an earlier list was kept as $previous"
    elif [ -e "$done_file" ]; then
      # a .done with no list is of no use to anyone, and would hide packages of the new list
      previous="$done_file.orphan-$(date +%Y%m%d-%H%M%S)"
      mv "$done_file" "$previous" || fail "cannot set aside $done_file"
      say "a .done file without its list was kept as $previous"
    fi
    {
      printf '# objunpack quarantine list - made %s by run %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${OP_runId:-?}"
      printf '# category<TAB>package<TAB>reason   category: correct = unpack, rebuild, resend; manual = not for a workflow\n'
    } > "$list" || fail "cannot write $list"
    say "new list: $list"
    ;;
  add)
    category="$(printf '%s' "${OP_category:-}" | tr '[:upper:]' '[:lower:]')"
    case "$category" in correct|manual) ;; *) fail "category must be correct or manual; got '${OP_category:-}'" ;; esac
    package="${OP_package:-}"; check_package "$package"
    [ -f "$list" ] || fail "the list does not exist: $list (start it with action=new)"
    reason="$(printf '%s' "${OP_reason:-}" | tr '\t\r\n' '   ')"
    if in_list "$package"; then
      say "already in the list, not added again: $package"
    else
      printf '%s\t%s\t%s\n' "$category" "$package" "$reason" >> "$list" || fail "cannot write $list"
      say "$category: $package${reason:+ - $reason}"
    fi
    ;;
  done)
    package="${OP_package:-}"; check_package "$package"
    [ -f "$list" ] || fail "the list does not exist: $list"
    in_list "$package" || fail "not in the list, so it cannot be recorded as worked: $package"
    if is_done "$package"; then
      say "already recorded as worked: $package"
    else
      printf '%s\n' "$package" >> "$done_file" || fail "cannot write $done_file"
      say "worked: $package"
    fi
    ;;
  pending|summary)
    [ -f "$list" ] || say "the list does not exist: $list - nothing is pending"
    ;;
esac

# the counts, and what is pending, as the files stand now
total=0; n_correct=0; n_manual=0; n_done=0; n_pending=0; pending=""
if [ -f "$list" ]; then
  lineno=0
  while IFS="$tab" read -r c p r || [ -n "$c" ]; do
    lineno=$((lineno + 1))
    case "$c" in "#"*|"") continue ;; esac
    case "$c" in
      correct|manual) ;;
      *) fail "line $lineno of $list: the category is '$c', not correct or manual" ;;
    esac
    [ -n "$p" ] || fail "line $lineno of $list has no package"
    case "$p" in *\;*|" "*|*" ") fail "line $lineno of $list: the package's path contains ';' or has a blank at one end: '$p'" ;; esac
    total=$((total + 1))
    if [ "$c" = manual ]; then n_manual=$((n_manual + 1)); continue; fi
    n_correct=$((n_correct + 1))
    if is_done "$p"; then
      n_done=$((n_done + 1))
    else
      n_pending=$((n_pending + 1)); pending="${pending:+$pending;}$p"
    fi
  done < "$list"
fi
say "$total in the list: $n_correct to correct ($n_done worked, $n_pending pending), $n_manual for a person"

printf '##VAR quarantineList=%s\n' "$list"
printf '##VAR quarantineTotal=%s\n' "$total"
printf '##VAR quarantineCorrect=%s\n' "$n_correct"
printf '##VAR quarantineManual=%s\n' "$n_manual"
printf '##VAR quarantineDone=%s\n' "$n_done"
printf '##VAR pendingCount=%s\n' "$n_pending"
printf '##VAR pending=%s\n' "$pending"
printf '##VAR previousList=%s\n' "$previous"
exit 0
