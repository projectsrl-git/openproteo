@echo off
rem Windows process-tree measurement kit. See the header of ProcTreeKit.java.
rem Optional argument: the PowerShell executable to use (default powershell.exe).
cd /d "%~dp0"
javac ProcTreeKit.java || goto :eof
java -cp . ProcTreeKit %* > proctree-report.txt 2>&1
type proctree-report.txt
echo.
echo Report written to %~dp0proctree-report.txt
