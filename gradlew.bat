@echo off
set DIR=%~dp0
if exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else if exist "%DIR%gradle-8.7\bin\gradle.bat" (
  call "%DIR%gradle-8.7\bin\gradle.bat" %*
) else if exist "%DIR%gradle.zip" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%DIR%gradle.zip' -DestinationPath '%DIR%' -Force"
  call "%DIR%gradle-8.7\bin\gradle.bat" %*
) else (
  echo Gradle wrapper jar and gradle.zip not found.
  exit /b 1
)
