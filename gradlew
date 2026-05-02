#!/bin/bash
# Gradle wrapper - uses local gradle installation
export JAVA_HOME=/usr/lib/jvm/java-17-konajdk-17.0.17-1.oc9
export ANDROID_HOME=/opt/android-sdk
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

# Use installed gradle
GRADLE_HOME=/tmp/gradle-8.5
exec "$GRADLE_HOME/bin/gradle" --project-dir="$(cd "$(dirname "$0")" && pwd)" "$@"
