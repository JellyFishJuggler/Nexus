@echo off
set DIR=%~dp0
set GRADLE_HOME=%DIR%..\gradle-8.9
call "%GRADLE_HOME%\bin\gradle.bat" %*