# 2026-10-14 — check every package, then send the valid ones in one block

Base: `96fba1b`. Request: optimise `_TEMPLATE-objunpack-check-quarantine.xml` without Java - a loop
that only checks, then one send of the valid packages, and a sent list from which a send that
stopped half way is taken up with no package sent twice and none lost. Spec, decisions and
intersections ∩ U47-U57: `.claude/OBJECT_UNPACK_EXECUTOR.md` §26.

## What
- `workflows/_TEMPLATE-objunpack-check-then-send.xml` (new). `check-quarantine` and every other
  template unchanged.
- `scripts/send-stage.sh` (new): verdict per package; links to the package and its `.md5` in
  `<staging>/<base>/`; the extracts handed back for `deleteOnSuccess` only when prepared.
- `scripts/sent-list.sh`: new action `reconcile`; one function writes a line for `add` and
  `reconcile`; `add` unchanged.
- USAGE, objunpack: new subsection "Check everything first, then send in one block".

## Decisions (the author's, 2026-10-14)
New template; send by `forEach` with `concurrency=1`, one FTPS session per package, package then
`.md5`; no hold; after a stopped send the next run sends what was prepared without checking it
again. **Mine, flagged:** `sendValidPackages=false` in the template (∩ U47), because with no hold
the variable is the only thing between a copy of the template and a send to PROD.

## Why it is shaped this way
- The original packages are sent through links in a staging folder outside their folder, so the
  `.sent` rename of `ftpsend` marks progress without touching them (`renameNoReplace` moves the
  link, `store` follows it - both read in the code).
- `forEach` does not run `deleteOnSuccess` and drops the items' outputs: the extracts are deleted
  when a package is prepared, and progress is read from the staging folder, not from variables.
- A failed send ends the run before the step after it: the reconcile runs at the start of the
  next run too.

## Questions for Transarch (not decidable by us; they apply to `check-quarantine` as well)
1. A package that arrived without its `.md5` (the session dropped between the two): is it
   ignored until the `.md5` arrives, rejected, or ingested without a check?
2. A package stored again over a truncated one of the same name: does the second STOR replace it,
   or is it refused / kept as a duplicate?
3. A `.md5` that arrives in a later session than its package, minutes or hours after: accepted?
4. Is a second identical `.md5` (same name, same content) harmless? (∩ U53)

## Verification
Verified on Linux (JDK): Temurin 1.8.0_432 and OpenJDK 21.0.12, bash 5.2.21, GNU tar 1.35.
- Scripts, for real: 57 assertions (every state of the reconcile, every verdict of the stage, the
  staging folder refused inside the packages' folder before anything is created, through a link
  too), with `LANG` empty and `C.UTF-8`; the same 57 and 8 permission cases as uid 65534 in a traversable
  folder (a list that cannot be written keeps the folder for the next run; a list that cannot be
  read is refused rather than read as empty).
- The template walked: the real parser, `VarResolver`, the real bash runner on the repository's
  scripts, `runFtpSend` and `deleteOnSuccess` lifted by position, a fake FTPS server. 484
  assertions on Java 8 and on JDK 21; 446 with `LANG` empty; 481 as uid 65534. Seven files (three
  valid, one to correct, three for a person): 54 transitions as computed, one session per package,
  package then `.md5`, in name order, byte for byte, extracts deleted only for the prepared, the
  packages' folder unchanged; the same run again sends nothing. The FTP stopped at every position
  (0 = server down, 1-6 = each file of three packages) in three ways (half a file and the server
  back; half a file and the server gone; SIZE that does not match): every package complete in the
  end, each file stored once, never a `.md5` before its package, each name once in the sent list,
  the resume not checking again, a third run with nothing to do. A resume that fails too. Dry run
  then send. A package changed after it was prepared. `.tar.gz`. The sent list shared with
  `check-quarantine`. A comma in the staging path. 81 valid packages in 497 transitions, 82
  stopped by the limit before sending.
- Mutations on copies, anchors checked: 45 - send-stage.sh 13, sent-list.sh 14, the template 18.
  44 caught. One equivalent: not adding a just-recorded name to the in-memory set, which matters
  only if one reconcile met the same name twice - impossible, the folder is named by the base
  name. One hole found on the way and closed: comparing the packages' folder by its logical path
  survived, because no case reached a package through a link; a case now does.
- Measured: 900 packages - stage 24 ms each; reconcile of 900 to send 4 s, of 900 sent 9 s.

Verified on Windows: nothing (the template needs bash and symbolic links: Linux and AIX only).

Not verified: the engine itself (the walker's node loop and fan-out are mine, written from
`WorkflowEngine.loop` and `runForEach`; `concurrency` is modelled as 1 only); `validate` (emulated:
its result is an input of the new script, which is tested on every value); a real FTPS server
and Transarch's handling of a half-sent package; AIX (`ln -s`, `ls -lnL`, `pwd -P`, `rmdir --`
are used in their POSIX forms); the designer saving the template (the writer keeps `forEach`,
`delimiter`, `concurrency` and `optional` - read, not run).

## Follow-up
- Answers from Transarch to the four questions above.
- If the 7.5-hour worst case of ∩ U48 (server down, every package waiting for the connect
  timeout) becomes real, stopping a fan-out at its first failure needs Java.
