#!/usr/bin/env bash
# Run the hosting checks against your repository before opening a hosting request.
#
# Usage:
#   ./bin/check-hosting.sh --repository-url https://github.com/your-org/your-cool-plugin \
#       --github-user your-github-user --jenkins-user your-jenkins-account
#
# Run with --help for all options. Exit codes:
#   0 no Required issues, 1 Required issues found, 2 missing GitHub token, 3 some checks could not complete
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REQUIRED_JAVA=25

java_major() {
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = \([0-9]*\).*/\1/p'
}

# Find a Java 25+ runtime: JAVA_HOME, then java on PATH, then macOS java_home
JAVA=""
for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
  if [[ -n "$candidate" && -x "$candidate" ]] && (( $(java_major "$candidate") >= REQUIRED_JAVA )); then
    JAVA="$candidate"
    break
  fi
done
if [[ -z "$JAVA" && -x /usr/libexec/java_home ]]; then
  if home="$(/usr/libexec/java_home -v "$REQUIRED_JAVA+" 2>/dev/null)"; then
    JAVA="$home/bin/java"
  fi
fi
if [[ -z "$JAVA" ]]; then
  echo "Java $REQUIRED_JAVA or newer is required, set JAVA_HOME to a Java $REQUIRED_JAVA+ installation." >&2
  exit 2
fi
export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"

if [[ -z "${GITHUB_OAUTH:-}" ]]; then
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    export GITHUB_OAUTH="$GITHUB_TOKEN"
  elif command -v gh >/dev/null && token="$(gh auth token 2>/dev/null)"; then
    export GITHUB_OAUTH="$token"
  fi
fi

JAR="$(ls "$ROOT"/target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "$JAR" || -n "${REBUILD:-}" ]]; then
  echo "Building the hosting checker (set REBUILD=1 to force a rebuild after pulling updates)..." >&2
  MVN="mvn"
  [[ -x "$ROOT/mvnw" ]] && MVN="$ROOT/mvnw"
  (cd "$ROOT" && "$MVN" -B -q package -DskipTests -Dspotbugs.skip -Dspotless.check.skip >&2)
  JAR="$(ls "$ROOT"/target/repository-permissions-updater-*-bin/repository-permissions-updater-*.jar | head -n 1)"
fi

# The checks read banned-dependencies.lst relative to the working directory
cd "$ROOT"
exec "$JAVA" -jar "$JAR" precheck-hosting "$@"
