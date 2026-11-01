@echo off
rem Starts a dev Minecraft with Dramatic Faces, Simple Voice Chat and Mod Menu.
rem Code changes are swapped into the running game after "reload.bat" (hot reload).
set "JAVA_HOME=%USERPROFILE%\.qfx-tools\jbrsdk-25.0.4.1-windows-x64-b635.79"
cd /d "%~dp0"
call "%~dp0gradlew.bat" runClient
