@echo off
rem Recompiles the mod; the running dev game picks up the changes within a few seconds.
set "JAVA_HOME=%USERPROFILE%\.qfx-tools\jbrsdk-25.0.4.1-windows-x64-b635.79"
cd /d "%~dp0"
call "%~dp0gradlew.bat" classes
