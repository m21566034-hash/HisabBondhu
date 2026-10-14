@echo off
rem Builds the debug APK. Usage: build-debug.bat [release]
cd /d "%~dp0"
if defined JAVA_HOME (set "JAVA_BIN=%JAVA_HOME%\bin\java") else (set "JAVA_BIN=java")
"%JAVA_BIN%" -version 2>&1 | findstr /R /C:"version \"1[7-9]\." /C:"version \"2[01]\." >nul
if errorlevel 1 (
  echo JDK 17 ^(বা 21^) দরকার। JAVA_HOME ঠিক করুন, যেমন:
  echo   set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
  exit /b 1
)
if "%1"=="release" (call gradlew.bat assembleRelease) else (call gradlew.bat testDebugUnitTest assembleDebug)
