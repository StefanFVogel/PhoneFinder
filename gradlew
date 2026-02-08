#!/bin/sh

#
# Gradle wrapper script for POSIX
#

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

die() {
    echo "$*" >&2
    exit 1
}

warn() {
    echo "$*" >&2
}

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine APP_HOME
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

# Add default JVM options here
GRADLE_OPTS="$GRADLE_OPTS"

# Download gradle-wrapper.jar if missing
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar"
    echo "Downloading Gradle Wrapper..."
    if command -v curl > /dev/null 2>&1; then
        curl -sL "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget > /dev/null 2>&1; then
        wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR"
    else
        die "ERROR: Cannot download gradle-wrapper.jar. Please install curl or wget."
    fi
fi

exec java $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
