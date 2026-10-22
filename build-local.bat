@echo off
cd /d "%~dp0"

rem 自动定位 JDK：优先使用 JAVA_HOME，其次使用本脚本安装的默认路径
if "%JAVA_HOME%"=="" (
    if exist "C:\Users\MI\.workbuddy\binaries\jdk\jdk8u504-b01" (
        set "JAVA_HOME=C:\Users\MI\.workbuddy\binaries\jdk\jdk8u504-b01"
    )
)

rem 自动定位 Gradle：优先使用 GRADLE_HOME，其次使用默认安装路径
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
    set "GRADLE_BIN=C:\Users\MI\.workbuddy\binaries\gradle\gradle-8.0\bin\gradle.bat"
)

echo JAVA_HOME = %JAVA_HOME%
echo GRADLE    = %GRADLE_BIN%
echo.

call "%GRADLE_BIN%" buildJAR
pause
