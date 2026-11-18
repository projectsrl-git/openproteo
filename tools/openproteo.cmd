@echo off
rem OpenProteo command line (.claude/HEADLESS.md section 6.1).
rem Copy this file beside openproteo-headless.war. Set OPENPROTEO_CONTROL to the control
rem directory of the instance (or pass --control <dir>); OPENPROTEO_JAVA or JAVA_HOME selects Java.
rem The exit code of the command is returned unchanged.
setlocal
set "OP_WAR=%~dp0openproteo-headless.war"
if not exist "%OP_WAR%" (
  echo openproteo: %OP_WAR% not found: copy openproteo-headless.war beside this file 1>&2
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
