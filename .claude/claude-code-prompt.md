# OpenProteo — working agreement for Claude Code

You are working directly on the OpenProteo repository (local clone at
`D:\SVILUPPO\openproteo`). You edit files in place — there is **no patch file**
to produce. Follow this agreement for **every** task. Talk to me in Italian if I
write in Italian, but write **everything that lands in the repo** (code,
comments, `.md`, commit messages) in **English**.

## 0. Orientation (start of each task)
1. Read `CLAUDE.md` at the repo root (you load it automatically — actually use it).
2. Skim `.claude/` for any spec relevant to the task and read the ones that apply.
3. Make sure you are on the current `main`: `git status`, and if needed `git pull`.
   Optionally create a branch: `git checkout -b feature/<short-name>`.
4. Do **not** re-derive the architecture from scratch — trust `CLAUDE.md` and the
   `.claude/` specs; read only the code you actually need for the change.

## 1. Hard constraints — never violate these
- **No literal `\n` or `\r` in JavaScript source** (both `.js` files and inline
  `<script>` blocks in Thymeleaf templates). The corporate DLP/proxy rewrites the
  escape sequence into a real newline and breaks the JS string. Use
  `String.fromCharCode(10)` / `(13)` or an existing `NL` constant instead.
  (This is a JS-only rule; `\n` / `\r` in **Java** source is fine.)
- **No unsafe Thymeleaf inline** — never a literal `[[` or `[(` in a template
  except inside a `/*[[${...}]]*/` comment.
- **Java 8 only.** No language/API features above Java 8.
- **No new Maven dependencies** unless the artifact is confirmed available on the
  internal UBS Nexus first (probe/gate pattern). No CDN or external network
  dependency at runtime.
- **Never log PII.**
- Configuration lives in the external `application.properties` under
  `CATALINA_HOME/config/`. The shared Tomcat connector must not be modified
  (another app shares the instance).
- **Backward compatibility is a first-class requirement**: new attributes/vars are
  additive and optional; existing workflow XML must stay byte-stable when a new
  feature is not used (only write new attributes when they are set).

## 2. Registering a new internal executor — the four-location rule
If the task adds a new internal step kind, register it in **all four** places or
it will not work:
1. the parser `exec` whitelist,
2. the parser `internal` set,
3. `WorkflowEngine.internalKind()`,
4. the `InternalSteps` dispatch.

## 3. Workflow for every task
1. **Spec first.** Before writing code, create `.claude/<feature-or-fix>.md`
   describing: the problem/goal, the chosen approach, the files you will touch,
   key decisions and trade-offs, and the backward-compatibility argument. Keep it
   concise but enough to serve as the handoff/reference doc.
2. **Implement** directly in the working tree. Prefer atomic file writes
   (tmp-file + replace) for anything risky. Keep changes minimal and focused.
3. **Verify** (this replaces the stub-compile I do in the chat sandbox — you can
   build for real):
   - `mvn -o clean package -DskipTests` (or at least `mvn -o test-compile`) →
     expect **BUILD SUCCESS**. If `-o` fails because something must resolve from
     Nexus, drop `-o`.
   - For every template you touched: extract the inline `<script>` blocks and run
     `node --check` on them; then grep the JS for literal `\n` / `\r` (must be
     **zero**) and for `[[` / `[(` outside `/*[[ ]]*/` (must be **zero**).
   - Re-check the hard constraints in section 1 against your diff.
4. **Update `CLAUDE.md`** so it reflects the new state: move the item into
   "recently completed", update architecture notes if signatures/fields changed,
   record any new convention, and remove anything now stale. `CLAUDE.md` is the
   memory bridge for the next session — keep it true.
5. **Write `COMMIT_MSG.txt`** at the repo root, English, format:
   - line 1: imperative subject, **≤ 72 chars**
   - line 2: blank
   - body: wrap at ~78 cols; explain what changed and why, list the files/areas,
     and end with the verification result (e.g. "Builds; templates pass
     node --check").
6. **Do not auto-commit.** Leave the change ready and tell me what you did; I will
   review and commit with `git commit -F COMMIT_MSG.txt` myself. Commit only if I
   explicitly ask you to.

## 4. Reporting back
When done, give me a short summary: what changed, the files touched, the
verification output (build result + node --check), the backward-compatibility
note, and anything I should watch for when I deploy (e.g. `Ctrl+F5` to reload
changed JS). Flag honestly anything you could not verify in your environment.
