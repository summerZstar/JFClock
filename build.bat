@echo off
setlocal enabledelayedexpansion

rem =====================================================================
rem  JFClock local build script (no gradle wrapper in this repo).
rem  Keep this file pure ASCII: cmd.exe parses .bat in the console's
rem  codepage, and UTF-8 Chinese under GBK(936) silently eats commands.
rem  Keep CRLF line endings and single-colon :labels: cmd.exe cannot
rem  find ::double-colon labels with goto, and LF-only .bat files make
rem  goto misbehave (labels silently unreachable).
rem
rem  Usage:
rem    build.bat              build debug APK
rem    build.bat test         run unit tests
rem    build.bat check        unit tests + debug APK   (recommended)
rem    build.bat release      build release APK (unsigned, not installable)
rem    build.bat clean        clean build outputs
rem    build.bat all          clean + tests + debug APK
rem    build.bat <task> ...   anything else is passed to gradle as-is,
rem                           e.g. build.bat assembleDebug --stacktrace
rem =====================================================================

cd /d "%~dp0"

rem Keep the window open when this file is launched by double-clicking it in
rem Explorer (cmd runs it via "cmd /c"). When started from an existing
rem terminal, %cmdcmdline% has no "/c" and the script exits quietly.
set "PAUSE_AT_END="
echo %cmdcmdline% | find /i "/c" >nul 2>&1 && set "PAUSE_AT_END=1"

set "JAVA_HOME=C:\Users\Administrator\.jdks\ms-17.0.17"
set "ANDROID_HOME=D:\soft\devtool\android-sdk"
set "GRADLE_BAT=D:\soft\devtool\gradle-dist\gradle-8.6\bin\gradle.bat"

if not exist "%JAVA_HOME%\bin\java.exe" goto no_jdk
if not exist "%ANDROID_HOME%"          goto no_sdk
if not exist "%GRADLE_BAT%"            goto no_gradle

if not exist "local.properties" (
    >  local.properties echo sdk.dir=%ANDROID_HOME:\=/%
    echo [info] wrote local.properties pointing at %ANDROID_HOME%
)

set "TASKS=assembleDebug"
set "MODE=debug"
if /i "%~1"=="test"    (set "TASKS=testDebugUnitTest"            & set "MODE=test"    & shift)
if /i "%~1"=="clean"   (set "TASKS=clean"                        & set "MODE=clean"   & shift)
if /i "%~1"=="check"   (set "TASKS=testDebugUnitTest assembleDebug" & set "MODE=check" & shift)
if /i "%~1"=="all"     (set "TASKS=clean testDebugUnitTest assembleDebug" & set "MODE=all" & shift)
if /i "%~1"=="release" (set "TASKS=assembleRelease"              & set "MODE=release" & shift)

set "EXTRA="
:collect
if "%~1"=="" goto run
set "EXTRA=!EXTRA! %~1"
shift
goto collect

:run
echo ==============================================================
echo  JDK    : %JAVA_HOME%
echo  SDK    : %ANDROID_HOME%
echo  Gradle : %GRADLE_BAT%
echo  Tasks  : %TASKS%%EXTRA% --offline
echo ==============================================================

call "%GRADLE_BAT%" --offline %TASKS%%EXTRA%
if not "%errorlevel%"=="0" goto fail

echo.
echo --------------------------------------------------------------
if "%MODE%"=="test" (
    echo Unit tests passed. Report: app\build\reports\tests\testDebugUnitTest\index.html
    goto done
)
if "%MODE%"=="clean" (
    echo Cleaned app\build.
    goto done
)
if "%MODE%"=="release" (
    echo Release APK built. NOTE: no signingConfig in app\build.gradle, so this
    echo artifact is unsigned and cannot be installed on a phone directly:
    echo   app\build\outputs\apk\release\app-release-unsigned.apk
    goto done
)
set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [warn] build succeeded but APK not found at %APK%
    goto done
)
for %%F in ("%APK%") do echo APK   : app\build\outputs\apk\debug\app-debug.apk  (%%~zF bytes, built %%~tF)
if "%MODE%"=="check" echo Tests : passed too.
echo Install : adb install -r app\build\outputs\apk\debug\app-debug.apk
goto done

:fail
echo.
echo --------------------------------------------------------------
echo [FAILED] gradle exit code %errorlevel%
echo   - "Could not resolve" / missing dependency: drop --offline and run once
echo     online (deps are cached in %USERPROFILE%\.gradle).
echo   - "Android resource linking failed": a referenced attr/style/string/color
echo     is missing. Watch out when rewriting res\values\attrs.xml - it also
echo     declares the 8 theme attrs used by themes.xml.
echo   - "SDK location not found": fix sdk.dir in local.properties.
echo   - "JAVA_HOME is set to an invalid directory": update JAVA_HOME above.
if defined PAUSE_AT_END (
    echo.
    echo Build failed - press any key to close this window . . .
    pause >nul
)
exit /b 1

:no_jdk
echo [ERROR] JDK not found: %JAVA_HOME%
echo         Check C:\Users\Administrator\.jdks for the actual folder name.
exit /b 1

:no_sdk
echo [ERROR] Android SDK not found: %ANDROID_HOME%
exit /b 1

:no_gradle
echo [ERROR] Gradle not found: %GRADLE_BAT%
exit /b 1

:done
if defined PAUSE_AT_END (
    echo.
    echo Press any key to close this window . . .
    pause >nul
)
endlocal
exit /b 0
