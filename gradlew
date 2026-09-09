#!/usr/bin/env sh
# Stub gradlew for local inspection; real gradle wrapper will be provisioned by CI via gradle/wrapper/gradle-wrapper.jar
# If wrapper jar missing, fall back to system gradle if available.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
elif command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle wrapper jar not found and no system gradle. In CI, setup-android + setup-java will provide it."
  echo "To generate wrapper locally, run: gradle wrapper --gradle-version 8.10"
  exit 1
fi
