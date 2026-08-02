# build_openproteo.sh: find the project from the script, not from the git top level

The script assumed the git top level WAS the project, so it did `cd $(git rev-parse --show-toplevel)`.
On a checkout where the repository root sits ABOVE the project (e.g. /projects/devpodtest is the repo
and /projects/devpodtest/openproteo holds the pom) it moved out of the project and failed with
"pom.xml not found in /projects/devpodtest".

Fixes:
- The project is now resolved as the directory containing pom.xml, looked up in this order: the parent
  of the script's own directory (the script lives in <project>/scripts/, symlinks resolved), then $PWD,
  then the git top level. The error message lists all three places when none matches.
- It reports the project directory and, when the repository root differs, prints a note so the nesting
  is visible rather than surprising.
- `git add -A -- . ':(exclude)*.patch'` runs after cd into the project, so in a parent repository only
  the project subtree is staged; files elsewhere in the repo are left untouched.
- Running it as `sh script.sh` would use dash, where `set -o pipefail` does not exist: the script now
  re-execs itself with bash when BASH_VERSION is unset.
- git is required only to commit/push and to stamp the version. Without a repository (plain source copy
  on a test box) `-b` still builds, printing that the WAR carries no commit or build number, while
  committing is refused with a clear message.

Verify: exercised against a stubbed mvn in throwaway repositories reproducing the reported layout --
project nested in a parent repo invoked with `sh` (works, notes the nesting), invoked from another
directory by absolute path, commit in the parent repo (only the project subtree is staged: a file
outside it stayed untracked), invocation through a symlink, and a non-git directory (build only works,
commit refused). bash -n clean.
