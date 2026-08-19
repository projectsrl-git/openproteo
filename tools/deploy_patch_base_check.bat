@echo off
rem ===========================================================================
rem  deploy_openproteo_patch.bat - base-commit guard
rem
rem  Block to paste into the deploy script AFTER the patch has been extracted
rem  and its path is known (the line that prints  Patch:  "...\<name>.patch"),
rem  and BEFORE  git apply --check  runs.
rem
rem  Why: the script picks the most recent zip in D:\downloads and cannot know
rem  which commit that zip was generated against. Twice now a patch built on an
rem  older main reached the machine; git apply refused it, correctly, but only
rem  after the attempt. The suffix in the filename moves that discovery earlier,
rem  and this block makes it automatic instead of depending on someone
rem  remembering to look.
rem
rem  NO LABELS AND NO GOTO, deliberately. The repository stores every file with
rem  LF endings (.gitattributes: * text=auto eol=lf), and cmd.exe reads a .bat
rem  by byte offset: a goto in an LF-only file can land in the wrong place. The
rem  existing .bat files here are LF and work because they never jump. This one
rem  does not either, so the question does not arise.
rem
rem  Expects:  PATCHFILE  = full path of the extracted .patch
rem  Requires: the repository as the current directory
rem ===========================================================================

setlocal enabledelayedexpansion

rem --- the commit the working repository is actually on ---
set "HEADSHA="
for /f "delims=" %%h in ('git rev-parse --short HEAD') do set "HEADSHA=%%h"
if "!HEADSHA!"=="" (
  echo ERRORE: git rev-parse non ha risposto. Sei nella cartella del repo?
  endlocal
  exit /b 1
)

rem --- the last dash-separated segment of the patch name ---
rem  Every '-' becomes a space and the loop assigns each token in turn, so the
rem  last assignment is the last segment. No jumping, no counting.
for %%f in ("%PATCHFILE%") do set "PATCHNAME=%%~nf"
set "BASESHA="
for %%a in (!PATCHNAME:-= !) do set "BASESHA=%%a"

rem --- is it a commit hash? exactly seven characters, all hexadecimal ---
rem  ~6,1 is not empty and ~7 is: that is "exactly seven", without a length loop.
set "ISBASE=0"
if not "!BASESHA:~6,1!"=="" if "!BASESHA:~7!"=="" set "ISBASE=1"
if "!ISBASE!"=="1" for /f "delims=0123456789abcdefABCDEF" %%x in ("!BASESHA!") do set "ISBASE=0"

if "!ISBASE!"=="0" (
  rem  A patch named without a suffix must still work: every zip delivered before
  rem  this convention lacks one, and refusing them would turn a safety net into
  rem  an obstacle.
  echo === AVVISO: "!PATCHNAME!" non dichiara il commit di base.
  echo ===          Non posso verificare l'allineamento con HEAD [!HEADSHA!].
  echo ===          Convenzione: ^<argomento^>-^<commit^>.patch
) else (
  echo === Patch dichiarata sulla base !BASESHA!; HEAD e' !HEADSHA! ...
  if /i "!BASESHA!"=="!HEADSHA!" (
    echo === Base allineata.
  ) else (
    echo.
    echo ERRORE: patch generato su !BASESHA!, ma questo repo e' su !HEADSHA!.
    echo         Non applico nulla. Due cause possibili, con rimedi opposti:
    echo           - il repo e' indietro             -^> git pull, poi riprova
    echo           - il patch e' su una base vecchia -^> chiedine la rigenerazione
    echo                                                sulla base !HEADSHA!
    echo.
    endlocal
    exit /b 1
  )
)

endlocal
