@echo off
setlocal

rem ==== Пути к инструментам (поправь, если лежат в другом месте) ====
set "JAVA_HOME=C:\Users\%USERNAME%\AppData\Local\MoonForgeBuild\jdk-17"
set "ANDROID_SDK=C:\Users\%USERNAME%\AppData\Local\Android\Sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [!] Не найден JDK 17: %JAVA_HOME%
    echo     Поправь JAVA_HOME в начале этого файла.
    exit /b 1
)
if not exist "%ANDROID_SDK%" (
    echo [!] Не найден Android SDK: %ANDROID_SDK%
    echo     Поправь ANDROID_SDK в начале этого файла.
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ANDROID_HOME=%ANDROID_SDK%"
set "ANDROID_SDK_ROOT=%ANDROID_SDK%"

cd /d "%~dp0"

echo.
echo === Сборка MC Monitor ===
call gradlew.bat assembleDebug --console=plain
if errorlevel 1 (
    echo.
    echo [!] Сборка не удалась.
    exit /b 1
)

copy /y "app\build\outputs\apk\debug\app-debug.apk" "MC-Monitor.apk" >nul
if errorlevel 1 (
    echo [!] APK собрался, но скопировать не вышло.
    exit /b 1
)

echo.
echo [OK] Готово: %~dp0MC-Monitor.apk
for %%F in ("MC-Monitor.apk") do echo      размер: %%~zF байт
echo.
echo Перекинь файл на телефон и установи.
endlocal
