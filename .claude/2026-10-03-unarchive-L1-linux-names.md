# unarchive — Batch L1: Linux hosts, name rules by detected OS

New requirement: extraction fully compatible with Linux. Gate 0 L1 answered (a): keep everything
built so far, detect the OS automatically, apply Linux rules only on Linux. Spec §21.

## What changed

* `HostRules` (new): `os.name` starting with Windows -> WINDOWS, `Linux` -> LINUX, anything else
  refused. `UnarchiveRun.hostOs` (default `os.name`, set by tests), `hostRules` published.
* Linux name rules: the Windows-only refusals are lifted (drive letters, `:`, Windows-forbidden
  characters, reserved names, trailing dot/space, case-only collisions); `\` is a character in tars
  and a separator only in zips made on MS-DOS; segments limited to 255 UTF-8 bytes; `maxPathLength`
  now `auto` = 259 characters on Windows, 4096 bytes on Linux.
* Kept on Linux, on purpose, though GNU tar is more lenient: leading `/`, `a/./b`, `a//b`, exact
  duplicates, `..` - refused.
* Windows: unchanged. The old signatures still mean the Windows rules.
* `runUnarchive` accepts `maxPathLength=auto`, publishes `hostRules`; panel seeds `auto` (field is now
  text), says the rules follow the server; USAGE.md "Windows and Linux servers".

## Measured, and what it corrected

* GNU tar on Linux extracts `:`, `CON`, `nul.txt`, trailing dot/space, case pairs, literal `\`, `C:x`
  and even a newline in a name as they are. NAME_MAX 255 bytes, PATH_MAX 4096.
* **unzip converts `\` only in zips made on MS-DOS (host 0)** - the batch-0 recommendation ("as
  unzip does, `\` separates in zips") was wrong for most zips; corrected.
* **Debian's unzip mangles a jar's UTF-8 names** (host 0 + UTF-8 flag -> `perch├й.txt`); the step
  follows the zip's declaration and writes `perché.txt`. Pinned in the suite as the one allowed
  difference.

## Verification

Windows suites unchanged: 232 + 194 (one line added to set the host). Linux suite: 140 - GNU tar /
unzip parity on every benign fixture and on Linux fixtures; Windows-only hostile fixtures now
byte-identical to GNU tar or unzip, the others still refused with nothing left; byte boundaries.
Wiring 90, panel 114, guide 43 (the batch-4 guide fails exactly the four changed things), designer
XML -> parser -> run OK, lint and mechanical agreement green. Mutations: core 19, adapter 2, panel 3 -
all caught.

## Mistakes of mine caught on the way

A harness that closed unzip's output (SIGPIPE killed it, empty reference); two wrong expectations
(Windows converts `a\b`, it does not refuse it; long_utf8 is refused on Windows by the path limit, not
the segment); a flaky test that reused one temp directory's length for another (1 run in 3 failed,
clean code included) - found because a mutation's "reason" made no sense; fixed, stable 6/6.

## Next

Batch L2: on Linux, symlinks and hardlinks created but confined, `rwx` permissions, directory times.
