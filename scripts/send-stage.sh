#!/bin/bash
# send-stage.sh - after a package has been checked: prepare it for a send in one block, or say why
# it goes to the quarantine list.
#
# Runs as an OpenProteo "bash" step, once per package, after objunpack-precheck.sh and the
# validate step on its metadata. It decides from what they published:
#
#   send     the package was read, its checksum verified, its counts agree, and no metadata check
#            failed. It is PREPARED: a folder <staging>/<base>/ is made, holding a symbolic link to
#            the package, a symbolic link to its <base>.md5, and a file .origin that names the two
#            and records the package's size. An FTPS step with forEach over those folders sends
#            them later, one session per package, the package first and its .md5 after it.
#   correct  read, verified and coherent; its metadata failed a check.
#   manual   anything else: not read, checksum not verified, counts that do not agree.
#
# Nothing is copied and the package's folder is never written: the links are made in the staging
# folder, which must not be the package's folder or inside it. A package already prepared - by an
# earlier run that did not send it, or sent it only in part - is left exactly as it is, so that
# what was already sent is not sent again.
#
# Parameters arrive as environment variables:
#   OP_staging       the staging folder; created when missing
#   OP_package       the package's full path, as the list gave it
#   OP_readable      ${PRECHECK.packageReadable}
#   OP_md5Checked    ${PRECHECK.md5Checked}
#   OP_coherent      ${PRECHECK.packageCoherent}
#   OP_problem       ${PRECHECK.problem}: the reason of a package for a person
#   OP_checksFailed  ${PRECHECK_CSV.checksFailed}. Read ONLY when the first three are true: when
#                    they are not, the validate step did not run on this package and the value is
#                    the one of the package before
#   OP_checksTotal   ${PRECHECK_CSV.checksTotal}, for the reason
#   OP_extractDir    optional: the folder where the pre-check extracted this package's audit and
#                    metadata. Published back as deleteExtract when the package is prepared, and
#                    empty otherwise, so that the step's deleteOnSuccess removes it only then
#
# Published (##VAR), every time: verdict (send | correct | manual), staged (true | false),
#   stagedDir, quarantineCategory and quarantineReason (empty for send), deleteExtract.
# Exit: 0 done; 2 refused - a wrong parameter, a staging folder that cannot be used, a folder of
# that name already holding another package.
# Needs: bash, ln, ls, mkdir, mv.

set -u

say()  { printf '%s\n' "send-stage: $*"; }
fail() { printf '%s\n' "send-stage: $*" >&2; exit 2; }

staging="${OP_staging:-}"
pkg="${OP_package:-}"
readable="${OP_readable:-}"
md5_checked="${OP_md5Checked:-}"
coherent="${OP_coherent:-}"
problem="${OP_problem:-}"
checks_failed="${OP_checksFailed:-}"
checks_total="${OP_checksTotal:-}"
extract_dir="${OP_extractDir:-}"

nl='
'
tab="$(printf '\t')"

[ -n "$staging" ] || fail "staging is required (parameter staging)"
[ -n "$pkg" ] || fail "package is required (parameter package)"
case "$staging$pkg$extract_dir" in *"$nl"*|*"$tab"*|*\;*) fail "a path contains ';', a tab or a line break: it could not be kept in a list" ;; esac

verdict=""; category=""; reason=""; staged=false; staged_dir=""; delete_extract=""

if [ "$readable" = true ] && [ "$md5_checked" = true ] && [ "$coherent" = true ]; then
  case "$checks_failed" in
    ''|*[!0-9]*) fail "checksFailed is '$checks_failed', not a number: did the validate step run on this package?" ;;
  esac
  if [ "$checks_failed" -eq 0 ]; then
    verdict=send
  else
    verdict=correct; category=correct
    reason="$checks_failed of ${checks_total:-?} metadata checks failed"
  fi
else
  verdict=manual; category=manual
  reason="$problem"
  [ -n "$reason" ] || reason="not read, checksum not verified, or counts that do not agree (readable=$readable, md5Checked=$md5_checked, coherent=$coherent)"
fi
reason="$(printf '%s' "$reason" | tr '\t\r\n' '   ')"

# size_of <folder> <name>: from ls, not wc, which may read the whole file on some hosts
size_of() {
  local out
  out="$(cd "$1" && ls -lnL -- "$2" 2>/dev/null)" || return 1
  set -f; set -- $out; set +f
  [ $# -ge 5 ] || return 1
  printf '%s' "$5"
}

if [ "$verdict" = send ]; then
  name="${pkg##*/}"
  case "$name" in
    *.tar.gz) base="${name%.tar.gz}" ;;
    *.tar)    base="${name%.tar}" ;;
    *) fail "'$name' is not a .tar or .tar.gz" ;;
  esac
  printf '%s' "$base" | grep -Eq '^tf[0-9]{7}\.[0-9]{8}\.S[0-9]{1,3}\.V[0-9]{1,3}$' \
    || fail "'$name' is not a submission archive name"
  pdir="$(dirname "$pkg")"
  [ -d "$pdir" ] || fail "the package's folder does not exist: $pdir"
  pdir="$(cd "$pdir" && pwd)" || fail "cannot enter $pdir"
  pkg="$pdir/$name"
  md5="$pdir/$base.md5"
  [ -f "$pkg" ] || fail "the package is not there: $pkg"
  [ -f "$md5" ] || fail "its checksum file is not there: $md5"

  # BEFORE anything is created: the staging folder must not be the package's folder or inside it.
  # Compared on physical paths, so that a link does not hide that they are the same folder; for a
  # staging folder that does not exist yet, from its nearest existing ancestor.
  case "$staging" in /*) ;; *) fail "staging must be an absolute path; got '$staging'" ;; esac
  probe="$staging"; rest=""
  while [ ! -d "$probe" ]; do
    rest="/${probe##*/}$rest"
    probe="$(dirname "$probe")"
  done
  sphys="$(cd "$probe" && pwd -P)" || fail "cannot enter $probe"
  [ "$sphys" != / ] || sphys=""
  sphys="$sphys$rest"
  pphys="$(cd "$pdir" && pwd -P)" || fail "cannot enter $pdir"
  case "$sphys/" in
    "$pphys"/*) fail "the staging folder $staging is the package's folder or inside it: nothing is ever written there" ;;
  esac
  mkdir -p "$staging" || fail "cannot create the staging folder $staging"
  staging="$(cd "$staging" && pwd)" || fail "cannot enter $staging"
  folder="$staging/$base"
  staged_dir="$folder"

  if [ -e "$folder" ] || [ -L "$folder" ]; then
    { [ -d "$folder" ] && [ ! -L "$folder" ]; } || fail "$folder exists and is not a folder"
    [ -f "$folder/.origin" ] || fail "$folder exists and has no .origin: it was not made by this script"
    was=""
    while IFS="$tab" read -r k v; do [ "$k" = package ] && was="$v"; done < "$folder/.origin"
    [ "$was" = "$pkg" ] || fail "$folder already holds another package with the same name: $was (this one is $pkg)"
    say "already prepared, left as it is: $name"
  else
    tmp="$staging/.new-$base.$$"
    mkdir "$tmp" || fail "cannot create $tmp"
    ln -s "$pkg" "$tmp/$name" || fail "cannot make the link to $pkg"
    ln -s "$md5" "$tmp/$base.md5" || fail "cannot make the link to $md5"
    size="$(size_of "$tmp" "$name")" || fail "cannot read the size of $pkg"
    printf 'package\t%s\nmd5\t%s\nsize\t%s\n' "$pkg" "$md5" "$size" > "$tmp/.origin" || fail "cannot write $tmp/.origin"
    mv "$tmp" "$folder" || fail "cannot move $tmp to $folder"
    say "prepared for the send: $name ($size bytes)"
  fi
  staged=true
  delete_extract="$extract_dir"
else
  say "$category: ${pkg##*/} - $reason"
fi

printf '##VAR verdict=%s\n' "$verdict"
printf '##VAR staged=%s\n' "$staged"
printf '##VAR stagedDir=%s\n' "$staged_dir"
printf '##VAR quarantineCategory=%s\n' "$category"
printf '##VAR quarantineReason=%s\n' "$reason"
printf '##VAR deleteExtract=%s\n' "$delete_extract"
exit 0
