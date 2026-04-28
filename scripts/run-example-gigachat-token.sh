#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUTH_KEY="${GIGACHAT_AUTH_KEY:-${GIGACHAT_TOKEN:-}}"

if [[ -z "${AUTH_KEY}" ]]; then
  echo "Set GIGACHAT_AUTH_KEY (or GIGACHAT_TOKEN) before running this script." >&2
  exit 1
fi

TARGET_CLASS="${GIGACHAT_TARGET_CLASS:-com.example.app.service.UserService}"
COMPILE_FLAG="${GIGACHAT_COMPILE:-true}"
EXECUTE_FLAG="${GIGACHAT_EXECUTE:-true}"
COVERAGE_FLAG="${GIGACHAT_COVERAGE:-false}"
COVERAGE_GOALS="${GIGACHAT_COVERAGE_GOALS:-}"

cmd=(
  "${ROOT_DIR}/gradlew"
  "genAiTest"
  "-Pgigachat.path=${ROOT_DIR}/example-project"
  "-Pgigachat.class=${TARGET_CLASS}"
  "-Pgigachat.compile=${COMPILE_FLAG}"
  "-Pgigachat.execute=${EXECUTE_FLAG}"
  "-Pgigachat.coverage=${COVERAGE_FLAG}"
  "-Pgigachat.token=${AUTH_KEY}"
)

if [[ -n "${COVERAGE_GOALS}" ]]; then
  cmd+=("-Pgigachat.coverageGoals=${COVERAGE_GOALS}")
fi

if [[ -n "${GIGACHAT_ENDPOINT:-}" ]]; then
  cmd+=("-Pgigachat.endpoint=${GIGACHAT_ENDPOINT}")
fi

if [[ -n "${GIGACHAT_AUTH_URL:-}" ]]; then
  cmd+=("-Pgigachat.authUrl=${GIGACHAT_AUTH_URL}")
fi

if [[ -n "${GIGACHAT_MODEL:-}" ]]; then
  cmd+=("-Pgigachat.model=${GIGACHAT_MODEL}")
fi

exec "${cmd[@]}"
