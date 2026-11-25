@echo off
rem OpenProteo command line (.claude/HEADLESS.md sections 6.1 and 15).
rem Copy this file beside openproteo-headless.war in the installation folder (OPENPROTEO_HOME).
rem When OPENPROTEO_HOME is not set, it is the folder of this file.
rem   openproteo start      start the instance in the background
rem   openproteo connect    open the console
rem   openproteo <command>  one command, for scripts (openproteo --help)
rem OPENPROTEO_JAVA or JAVA_HOME selects Java; the exit code of the command is returned unchanged.
setlocal
if "%OPENPROTEO_HOME%"=="" set "OPENPROTEO_HOME=%~dp0."
set "OP_WAR=%OPENPROTEO_HOME%\openproteo-headless.war"
if not exist "%OP_WAR%" (
  echo openproteo: %OP_WAR% not found: copy openproteo-headless.war into OPENPROTEO_HOME 1>&2
  exit /b 30
)
if not "%OPENPROTEO_JAVA%"=="" (
  set "OP_JAVA=%OPENPROTEO_JAVA%"
) else if not "%JAVA_HOME%"=="" (
  set "OP_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "OP_JAVA=java"
)
"%OP_JAVA%" -jar "%OP_WAR%" cli %*
exit /b %ERRORLEVEL%
