# unarchive — Batch L2: links, permissions, folder times on Linux

Second and last Linux batch. Spec §21.6. Windows behaviour unchanged.

## What changed (Linux hosts only)

* Symlinks created when they stay inside the archive's folder: `LinkGuard.lexical` per link, and
  `LinkGuard.verify` before the commit, which resolves every link through the archive's other links
  inside the staging folder (chains and dangling tails that escape only when followed are caught).
* Nothing written through a link: symlinks are registered in `NameIndex` as files.
* Hard links only to a regular file already extracted from the same archive.
* Permission bits restored as the reference tool does as a non-root user: tar `& ~umask` (GNU tar),
  zip as stored (unzip); never setuid/setgid/sticky or owner. The umask is read from the fresh
  staging folder.
* Folder modes and times applied after the content, deepest first.
* Cleanup (failed staging, `replace`, sweep) restores owner rwx on folders before deleting them.
* Tar entries now carry their `mode`, zip entries their `unixMode`. New rules: `LINK_ESCAPE`,
  `LINK_TARGET_MISSING`, `BAD_LINK_TARGET`.

## Measured

GNU tar (non-root, and root with `--no-same-permissions --no-same-owner`, identical): mode minus
umask, setuid dropped, `../outside` symlinks created, `555` folders applied after their content,
folder times restored. unzip: modes as stored, setuid dropped. `rm -rf` of a tree with a `555` folder
fails for a non-root user — which is what the cleanup fix is for.

## Verification

Links suite 77: GNU tar's archive of a real tree == GNU tar's extraction (types, link targets, content,
modes, file and folder times, hard-link inode groups); zip -ry == unzip; 12 hostile fixtures refused,
also under `skip`. Run as a NON-root user, 6/6, with a positive control (cleanup without chmod fails
3). Windows suites 232 + 194, Linux L1 140, wiring 90, panel 114, guide 45. Mutations: 17, all caught,
two only visible as a non-root user.

## Changed on purpose in existing suites

Linux L1: a confined zip symlink is now created, as unzip does. Wiring: on the Linux sandbox
`b_links.tar` with skip skips only the FIFO.

## Mistakes of mine caught on the way

A reference root created with `createTempDirectory` (0700) compared with ours; a chain example that
does not escape (`up/up/x` dangles inside, the kernel agrees). A first version that did not compile
(duplicate `@Override`) where `javac=0` was printed by `tail`, not javac.
