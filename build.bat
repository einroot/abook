@echo off
setlocal

echo === ABook APK Builder ===
echo.

:: Find Java 17 in user profile
if exist "%USERPROFILE%\jdk-17\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\jdk-17"
) else if exist "%USERPROFILE%\jdk-17_temp\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\jdk-17_temp"
) else (
    echo ERROR: Java 17 not found in %USERPROFILE%\jdk-17
    echo Please install JDK 17 or adjust JAVA_HOME in this script.
    pause
    exit /b 1
)

echo Using Java: %JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
echo.

:: Build
call "%~dp0gradlew.bat" :app:assembleDebug --no-daemon

if %errorlevel% equ 0 (
    echo.
    echo === BUILD SUCCESSFUL ===
    echo APK: %~dp0abook-debug.apk
) else (
    echo.
    echo === BUILD FAILED ===
)

pause
