#!/bin/bash
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
pass() { printf 'PASS: %s\n' "$1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { printf 'FAIL: %s\n' "$1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
skip() { printf 'SKIP: %s\n' "$1"; SKIP_COUNT=$((SKIP_COUNT + 1)); }
for key in CIRCUIT_BREAKER_WINDOW_SIZE; do
	if grep -qE "^${key}=" "$DOCKER_DIR/.env.template"; then pass "$key is still declared in .env.template"; else fail "$key was removed from .env.template"; fi
done
if ! docker compose version >/dev/null 2>&1; then
	skip "docker compose is unavailable, the worker-default probe did not run"
else
	probe_dir="$(mktemp -d)"; trap 'rm -rf "$probe_dir"' EXIT
	grep -v '^WORKER_ENABLED=' "$DOCKER_DIR/.env.template" > "$probe_dir/nokey"
	sed 's/^WORKER_ENABLED=.*/WORKER_ENABLED=false/' "$DOCKER_DIR/.env.template" > "$probe_dir/off"
	sed 's/^WORKER_ENABLED=.*/WORKER_ENABLED=true/' "$DOCKER_DIR/.env.template" > "$probe_dir/on"
	worker_value() { docker compose -f "$DOCKER_DIR/docker-compose.yml" "$@" config --format json 2>/dev/null | grep -oE '"WORKER_ENABLED"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | grep -oE '"[^"]*"$' | tr -d '"'; }
	check() { local desc="$1" expected="$2"; shift 2; local actual; actual="$(worker_value "$@")"; if [ "$actual" = "$expected" ]; then pass "$desc ($actual)"; else fail "$desc (expected $expected, got ${actual:-<none>})"; fi; }
	check "dev without the key resolves the worker on" true -f "$DOCKER_DIR/docker-compose.override.yml" --env-file "$probe_dir/nokey"
	check "dev with an explicit true resolves on" true -f "$DOCKER_DIR/docker-compose.override.yml" --env-file "$probe_dir/on"
	check "dev with an explicit false resolves off (a declaration wins)" false -f "$DOCKER_DIR/docker-compose.override.yml" --env-file "$probe_dir/off"
	check "staging (base compose only) resolves off" false --env-file "$probe_dir/off"
	check "prod (base + prod) resolves off" false -f "$DOCKER_DIR/docker-compose.prod.yml" --env-file "$probe_dir/off"
fi
echo "------------------------------------------"
printf 'worker-dev-default: %s passed, %s failed, %s skipped\n' "$PASS_COUNT" "$FAIL_COUNT" "$SKIP_COUNT"
[ "$FAIL_COUNT" -eq 0 ] || exit 1
exit 0
