#!/bin/sh
# Temporary bootstrap for unavailable wrapper JAR. Once it exists, use the official wrapper.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
    if ! command -v gradle >/dev/null 2>&1; then
        echo 'Gradle Wrapper JAR is missing. Install Gradle 8.13, then run ./gradlew again.' >&2
        exit 127
    fi
    echo 'Bootstrapping the official Gradle 8.13 Wrapper from installed Gradle...' >&2
    (cd "$APP_HOME" && gradle --no-daemon wrapper --gradle-version 8.13 --distribution-type bin) || exit $?
    if [ ! -f "$JAR" ]; then echo 'Failed to produce gradle-wrapper.jar' >&2; exit 1; fi
fi
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
exec "$JAVA" -Xmx64m -Xms64m -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
