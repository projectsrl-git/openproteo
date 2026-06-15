@echo off
REM Inietta src/main/resources (templates, static, application.properties) dentro un WAR
REM gia' esistente, sotto WEB-INF/classes. Sblocco immediato se il WAR e' senza risorse.
REM Uso:  patch-war-resources.bat  path\al\openproteo.war  path\a\src\main\resources
setlocal
set "WAR=%~1"
set "RES=%~2"
if "%WAR%"=="" ( echo Uso: patch-war-resources.bat openproteo.war src\main\resources & exit /b 1 )
if "%RES%"=="" ( echo Uso: patch-war-resources.bat openproteo.war src\main\resources & exit /b 1 )
rmdir /s /q _warstage 2>nul
mkdir _warstage\WEB-INF\classes
xcopy /e /i /y "%RES%\*" "_warstage\WEB-INF\classes\" >nul
jar uf "%WAR%" -C _warstage .
rmdir /s /q _warstage
echo.
echo Patched: %WAR%
echo Verifica (deve comparire la riga):
jar tf "%WAR%" | findstr templates/dashboard.html
endlocal
