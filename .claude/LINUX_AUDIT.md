# Linux audit of the internal executors (step L1)

Written 2026-10-04 on base `34ad944`. Step L1 of `.claude/LINUX_AND_GUI_CONFIG.md` §9. **A report:
no code is changed by it.** The fixes are step L3 and are listed in §5 with the decision taken for
each - the author cannot give feedback before L3, so each decision is the conservative one and is
argued here instead of asked.

## 1. What was done, and what "audit" means here

1. **Every Java source under `src/main/java` (35 575 lines) was scanned**, comments stripped, for the
   constructs through which a platform leaks into behaviour: path separators, line endings, default
   charsets, case-insensitive name handling, Windows-only APIs, absolute-path tests, rename / move /
   delete semantics, directory enumeration, temp files, locks, AWT. The scanner is
   `tools/scan_platform_assumptions.py`; every hit outside the code already made host-aware
   (`platform/`, `engine/ProcessTree`, `engine/StepExecutor`, `unarchive/`) was READ.
2. **Each difference the reading suggested was measured** on a real Java 8 runtime (Temurin
   1.8.0_432), on ext4 and on tmpfs (§3).
3. **Eleven packages were compiled with the Java 8 `javac` itself**, not `--release 8`: `elar`,
   `elarcheck`, `json2csv`, `mask`, `objpack`, `rename`, `tiff`, `unarchive`, `parser`, `model`,
   `platform`. They use the JDK only.

**What it is not.** The executors hosted in `engine/InternalSteps` (`sql`, `ifscopy`, `filecopy`,
`setvar`, `validate`, `csvreplace`, `encoding`, `mask`'s adapter, `split`, `safecopy`, `dequote`,
`csvsql`, `xlsx2csv`, `diff`, `sqlreport`, and the adapters of the others) **were not run**: that
class needs Jackson, POI, jt400, slf4j and Spring, and Maven Central is not reachable from the
sandbox. They were read. Of those, the author has run `csvsql` and a file copy on a real Linux
instance (`LINUX_AND_GUI_CONFIG.md` §9.1). The JDK-only executors were developed in chat mode, so
their suites have always run on Linux - on a newer JDK and as root, see `.claude/HISTORY.md`.

## 2. The result in one table

| Construct | Hits read | Verdict |
|---|---|---|
| Default charset (`new String(bytes)`, `getBytes()`, `FileReader`, one-argument stream readers, `PrintWriter(file)`) | 0 that touch file content | **clean** - every read and write names its charset |
| Line endings in OUTPUT FILES | every writer | **clean** - every CSV and report writes a literal `\r\n`; elar writes a literal LF by constant. None follows the host |
| Line endings in step logs, the audit log, the audit report | every writer | follow the host (`newLine()`, `lineSeparator()`). **Equivalent**: the audit hash is computed over the entry's fields, not its line, and every reader uses `readLine()`. Left as is |
| Path separators | 6 | **clean** - `File.separator` only to build a path, never to parse one |
| Backslash in a value used as a path | about twenty normalisation sites | normalised where the value comes from a CSV or an archive; **not** for step parameters - finding F5 |
| Rename onto an existing file | 6 | **differs** - finding F1 |
| Directory enumeration order | 26 sites | 9 sort; 4 do not and it shows - finding F3, F4 |
| Glob matching | 2 | **differs** by host, silently - finding F2 |
| Recursive delete | 5 | 3 do not follow links; 2 do - finding F6 |
| Windows-only API | 1 (`Windows-ROOT`) | finding F7, which is step L2 |
| AWT, fonts, `FileLock`, `Runtime.exec` | 0 | **clean** - nothing needs a display, fontconfig or a mandatory lock |
| Temp files | 4 | **clean** - all created inside the target directory, so no cross-device move |
| H2 URLs | 5 | **clean** - in-memory, or a file path with separators normalised |

## 3. Measured (Java 1.8.0_432, ext4 and tmpfs - same result on both)

| # | Question | Result |
|---|---|---|
| E1 | `File.renameTo(target)` when `target` exists | returns `true`; the target's old content is GONE |
| E1b | `Files.move(source, target)` with no option, same case | `FileAlreadyExistsException`; the target is untouched |
| E2 | `Files.newDirectoryStream(dir, "*.csv")` over `a.csv`, `B.CSV`, `c.Csv` | matches `a.csv` only |
| E2b | the host file system's own matcher, glob `x` against `X` | `false` - so the rule can be ASKED, not assumed |
| E3 | `File.list()` over eight files | neither name order nor creation order; different on ext4 and tmpfs |

Not measurable here, and stated as read, not run: on Windows `renameTo` onto an existing file
returns `false`; the Windows glob matcher is case-insensitive; NTFS enumerates in name order.

## 4. Findings

### F1 - A processed input can silently replace an older `.done` (elarxml, json2csv, ftpsend, elar split)

`ElarRun.renameDone` (`:578`), `Json2CsvRun.renameProcessed` (`:116`), `ftpsend` `renameSent`
(`InternalSteps:620`) and `InputSplitter` (`:122`) rename with `File.renameTo` and treat `false`
as "could not rename". When `<name>.done` is already there from an earlier run, Windows returns
`false` and the step warns; **Linux replaces the older file and says nothing** (E1). The older
`.done` is the evidence of what was delivered before.

`AtomicOutput` and `SkippedRows` delete the target first and are the same on both.

**Decision (L3):** rename with `Files.move` and no option (E1b). Both hosts then refuse an existing
target and take the path that exists today for a failed rename. Windows behaviour is unchanged;
Linux stops overwriting.

### F2 - `*.csv` does not match `DATA.CSV` on Linux (filecopy, safecopy)

Both use `Files.newDirectoryStream(dir, glob)`, whose matching is the host file system's:
case-insensitive on Windows, case-sensitive on Linux (E2). A feed whose files arrive in upper case
and whose pattern is in lower case copies everything on Windows and nothing on Linux, exit 0.

`json2csv` and `elarcheck` chose the opposite (case-sensitive on every host), and `encoding`'s
batch filter is case-insensitive on every host; so the code base already has all three.

**Decision (L3):** the matching stays the host's - a rule changed on Windows would change which
files existing feeds pick up. What changes is that it stops being silent, as the contract's
«equivalente» clause requires: the step asks the host's matcher which rule it has (E2b) and, on a
case-sensitive host, logs one line saying so; and a run that matches NOTHING while files exist that
would match ignoring case says how many. No new parameter.

### F3 - `matchedFiles` is in a different order on Linux (filecopy, safecopy, encoding batch)

`filecopy` and `safecopy` in pattern mode publish `${matchedFiles}` in enumeration order
(`InternalSteps:2524`, `:2595`), and `encoding`'s batch converts in it (`:4082`). That variable is
what a `forEach` iterates, so `${itemIndex}`, the per-item log names and anything positional
downstream follow it. NTFS returns name order; Linux returns hash order, different per file system
(E3). `ftpsend` already sorts, with a comment naming exactly this.

**Decision (L3):** on a host that is NOT Windows the names are sorted before use - by upper-cased
name, then by name, which is NTFS's documented collation for the names these feeds have. **On
Windows nothing is sorted and nothing changes**: the claim about NTFS order cannot be verified
here, and a sort that disagreed with it would reorder an existing feed's items.

### F4 - tiffcompress reports in enumeration order

`TiffScan` enumerates lazily by design (it was built for directories too large to list). Its report
rows therefore follow the host's order. **Decision: left as is, and stated in `USAGE.md`.** Sorting
would defeat the design, and the report is keyed by file name, not by position.

### F5 - A backslash in a path parameter is a file-name character on Linux

A workflow written on Windows with `${stepDir}\out.csv` resolves on Linux to `/data/feeds/x/10_s\out.csv`
- one file called `10_s\out.csv` in `/data/feeds/x`. No error: the file is created, in the wrong
place, under a name with a backslash in it. Parameters are not normalised, and cannot be: a
backslash is legitimate in a regex, in a UNC text, in a Windows path that a script will hand on.

**Decision (L3):** no value is changed. On a host that is not Windows, a resolved parameter that
begins with `/` and contains `\` gets one WARNING line in the step log naming the parameter. That
shape is a Linux absolute path joined with a Windows separator, and is almost never intended.

### F6 - Two recursive deletes follow symbolic links

`RunStore.deleteTree` (`:73`) and `WorkflowPorter.deleteRecursive` (`:440`) recurse with
`File.listFiles()`, which lists THROUGH a link to a directory: they would empty the directory a
link points at. Their targets are directories the application writes itself (`_logs/<runId>`, the
import staging area), where no link should exist, so the exposure is small - but on Linux a bash
step can create a link anywhere it can write. The three other recursive deletes use
`Files.walkFileTree`, which does not follow links.

**Decision (L3):** both rewritten on `walkFileTree`. No behaviour change where there is no link.

### F7 - A new FTPS target defaults to the Windows trust store

Step L2; four places (`LINUX_AND_GUI_CONFIG.md` §3 and §3.1 item 1). **Delivered 2026-10-04**, see
`LINUX_AND_GUI_CONFIG.md` §9.4: the page proposes a mode by host for a NEW target and marks what
cannot work here; the four model defaults were deliberately left as they are.

### F8 - Names are case-sensitive on Linux, and nothing can change that

A script referenced as `prepare.ps1` and stored as `Prepare.ps1`, an input named in upper case and
looked up in lower: found on Windows, not on Linux. Inherent to the host, not a defect. The
Platform page shows the case rule of the feed base directory. **Decision: `USAGE.md` says it, in
the section on moving a workflow from Windows.**

## 5. Step L3 - what will be changed, in one place

| # | Change | Windows | Linux |
|---|---|---|---|
| F1 | rename with `Files.move`, no replace | unchanged | stops overwriting a `.done` |
| F2 | log the host's matching rule; say when case alone prevented a match | unchanged (case-insensitive host: nothing logged) | one line; one more when nothing matched |
| F3 | sort enumeration when the host is not Windows | unchanged | deterministic order |
| F5 | WARNING line for `/...\...` parameters | unchanged | one line |
| F6 | recursive deletes on `walkFileTree` | unchanged without links | does not follow a link |
| F4, F8 | documentation only | - | - |

None adds a parameter. None changes what an existing Windows feed produces.

**Delivered 2026-10-04** on base `3456bd1`, as listed. `platform/HostFiles` holds the four
operations; the call sites changed are `ElarRun.renameDone`, `Json2CsvRun.renameProcessed`,
`InputSplitter`, and in `InternalSteps` the `ftpsend` rename, `filecopy` and `safecopy` in pattern
mode, the `encoding` batch, and one check at the start of every internal step (F5); `StepExecutor`
for external steps (F5); `RunStore.deleteTree` and `WorkflowPorter.deleteRecursive` (F6). How each
was verified, and what was not, is in `.claude/2026-10-04-linux-fixes-l3.md`.

One thing F2 does beyond its line in the table: the count of files left behind for case alone is
logged whenever it is above zero, not only when nothing matched - a run that takes eight files and
leaves a ninth for its capital letters is the more likely case, and the quieter one.

## 6. Not audited

- Scripts and workflows themselves: a `.ps1` that calls a Windows-only cmdlet, a path with a drive
  letter. They are the author's data.
- `templates/` and `static/js/`: the browser side has no host.
- JDBC drivers on the STANDALONE artifact. `USAGE.md` says where the driver goes for a servlet
  container (`CATALINA_HOME/lib`); it does not say where it goes for `openproteo-standalone.war`,
  and this audit did not find out. ~~To be answered by reading the launcher configuration in L3.~~
  **Read in L3, not solved:** the standalone artifact is a Spring Boot repackage with the default
  layout, whose launcher takes its classpath from inside the archive and reads no external
  directory. So on the standalone, on any OS, a `sql` step can use only what is bundled (jt400, H2).
  The clean fix is the core of batch 5 without its upload page - a drivers directory loaded through
  a class loader of its own and a delegating `Driver` - proposed as step **L4** in
  `LINUX_AND_GUI_CONFIG.md` §9.5. Not done here: it adds a file-only parameter and deserves its own
  measurement.
- The contract's own statements against the code (`CLAUDE.md` was reorganised, not audited).
