@ECHO OFF
SETLOCAL
SET "APP_HOME=%~dp0"
SET "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
IF EXIST "%WRAPPER_JAR%" GOTO run
WHERE gradle >NUL 2>&1
IF ERRORLEVEL 1 (
  ECHO Gradle Wrapper JAR missing. Install Gradle 8.13 and retry. 1>&2
  EXIT /B 127
)
PUSHD "%APP_HOME%"
CALL gradle --no-daemon wrapper --gradle-version 8.13 --distribution-type bin
SET "WRAPPER_EXIT=%ERRORLEVEL%"
POPD
IF NOT "%WRAPPER_EXIT%"=="0" EXIT /B %WRAPPER_EXIT%
IF NOT EXIST "%WRAPPER_JAR%" EXIT /B 1
:run
IF DEFINED JAVA_HOME (
 SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
 SET "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
EXIT /B %ERRORLEVEL%
