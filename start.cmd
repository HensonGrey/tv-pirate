@echo off
rem Starts the backend on :8080 — `.\start` from PowerShell. Extra args go to Maven.
setlocal
cd /d "%~dp0"

rem A terminal opened before JAVA_HOME was set doesn't have it; read the user-level value.
if not defined JAVA_HOME (
    for /f "tokens=2,*" %%a in ('reg query HKCU\Environment /v JAVA_HOME 2^>nul') do set "JAVA_HOME=%%b"
)
if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Point it at a JDK 21 and try again.
    exit /b 1
)

call "%~dp0mvnw.cmd" spring-boot:run %*
