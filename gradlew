#!/usr/bin/env bash
# Bootstrap Gradle tanpa wrapper jar — download distribusi sekali, cache di ~/.gradle
set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
BASE="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${GRADLE_VERSION}"
BIN="$BASE/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -x "$BIN" ]; then
  mkdir -p "$BASE"
  echo "==> Downloading Gradle ${GRADLE_VERSION}..." >&2
  curl -fsSL -o /tmp/gradle-${GRADLE_VERSION}-bin.zip \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q /tmp/gradle-${GRADLE_VERSION}-bin.zip -d "$BASE"
  rm -f /tmp/gradle-${GRADLE_VERSION}-bin.zip
fi

exec "$BIN" "$@"
