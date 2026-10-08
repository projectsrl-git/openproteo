# 2026-10-08 — the two superseded objunpack templates added as alternatives

Base: `1bdf3fa`. Request: "voglio avere tutto lo scenario completo e tutti i template e script
disponibili".

- The zips of the afternoon of 2026-10-07 (`…every-package-2c0c19a`, `…check-and-correct-2c0c19a`)
  do not apply on `1bdf3fa` (`git apply --check` fails on `.claude/OBJECT_UNPACK_EXECUTOR.md` and on
  `scripts/list-packages.sh`, already present). Their templates are delivered again here.
- Added, byte for byte as in those zips: `_TEMPLATE-objunpack-precheck-resend-all.xml`,
  `_TEMPLATE-objunpack-check-quarantine.xml`, `_TEMPLATE-objunpack-correct-quarantined.xml`.
- No script, no Java change. USAGE: subsection "The same work in other shapes". Spec: §22 one
  sentence struck through, §23 (∩ U35–U37).
- Walked with the committed scripts: (a) 6, (b) 28, (c) 23, corrected templates 8 (controls from
  `2c0c19a`). Mutations not repeated.

Verified on Linux (JDK): Temurin 1.8.0_432, the walker of §22, bash 5.2.21, GNU tar 1.35.
Verified on Windows: nothing.
Not verified: AIX, the real engine, on hold, `deleteOnSuccess`, `csvsql` (emulated).
