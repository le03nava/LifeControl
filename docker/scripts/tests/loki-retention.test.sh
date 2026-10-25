#!/bin/bash
# ============================================
# LifeControl - Loki retention tests
# ============================================
# Loki's retention lived in the config packaged inside the image, where `retention_period: 0s` and
# `enforce_retention_period: false` mean "keep every log forever" while `max_query_length` (30d1h)
# only ever answers the last 30 days. The measured consequence on the dev host was 189 MB of logs
# that no query could reach, on a bind mount that grows until it fills the disk, and nothing in the
# repository could change it.
#
# The fix is two files and both are pinned here:
#
#   1. docker/config/loki/loki.yml declares the window (30 days, the operator's decision and the
#      longest range Loki answers) AND enables the compactor's delete worker, because the limit
#      alone is advisory -- Loki accepts `retention_period` and deletes nothing without
#      `retention_enabled: true` plus a `delete_request_store` for the pending delete requests;
#   2. the compose service mounts that file read-only and points `-config.file` at it, instead of
#      the image's `local-config.yaml`.
#
# Loki is the one observability config that is NOT installed into $VOLUMES_ROOT by setup-env.sh,
# deliberately: a retention policy decides how much host disk the logs may take and how much
# history survives, so it has to travel as a reviewed commit. A host copy would be a mutation that
# never appears in a diff. Section 5 pins that it did not get copied anyway.
#
# The checks are deliberately about the declared values, not about Loki's effective configuration:
# `GET :3100/config` can only be read from a running stack, and a suite that needs one never runs
# in CI. Read together, the declared `retention_period` and `retention_enabled` are what decides
# the effective behaviour; the packaged image is the reference for every other field
# (`docker cp <container>:/etc/loki/local-config.yaml`, since the image is distroless and has no
# shell to run `cat` in).
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/loki-retention.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
LOKI_CONFIG="$DOCKER_DIR/config/loki/loki.yml"
BASE_COMPOSE="$DOCKER_DIR/docker-compose.yml"
PROD_COMPOSE="$DOCKER_DIR/docker-compose.prod.yml"
SETUP_ENV="$DOCKER_DIR/scripts/setup-env.sh"

# The retention window is a product decision, not a tuning knob: 720h is 30 days, which is exactly
# the span `max_query_length` already allowed. Changing it means editing this constant in the same
# commit, so the number is reviewed instead of drifting.
EXPECTED_RETENTION="720h"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

# ------------------------------------------
# Readers. The file is a hand-written config of a few dozen lines and this repository's Docker
# toolchain has no YAML parser, so the two shapes needed are read directly: a top-level section,
# and a `key: value` inside it.
# ------------------------------------------

# section_block <file> <section>: the lines of one top-level section, up to the next top-level key.
section_block() {
	awk -v sect="$2" '
		$0 ~ "^" sect ":[[:space:]]*$" { in_sect = 1; next }
		in_sect && /^[^[:space:]#]/ { exit }
		in_sect { print }
	' "$1"
}

# section_value <file> <section> <key>: the last value declared for <key> inside <section>, with a
# trailing comment stripped. Empty when the key is absent.
section_value() {
	section_block "$1" "$2" | awk -v key="$3" '
		$0 ~ "^[[:space:]]*" key ":[[:space:]]*" {
			value = $0
			sub(/^[[:space:]]*[^:]*:[[:space:]]*/, "", value)
			sub(/[[:space:]]*#.*$/, "", value)
			sub(/[[:space:]]+$/, "", value)
			last = value
		}
		END { print last }
	'
}

# service_block <file> <service>: the lines of one compose service.
service_block() {
	awk -v svc="$2" '
		$0 ~ "^  " svc ":[[:space:]]*$" { in_svc = 1; print; next }
		in_svc && /^  [A-Za-z0-9_-]+:/ { exit }
		in_svc { print }
	' "$1"
}

# check_value <description> <expected> <actual>
check_value() {
	if [ "$2" = "$3" ]; then
		pass "$1 = $3"
	else
		fail "$1 expected '$2', got '${3:-<absent>}'"
	fi
}

# ------------------------------------------
# 1. The config exists and still describes the storage layout the compose mount assumes.
#
# `COMMON.path_prefix` is the mount target `/loki`; the container listens on 3100, which is the
# container side of every published Loki port. The schema is frozen at what grafana/loki:3.7.8
# writes: a newer schema than the image supports does not start, and this suite is where that
# decision gets seen.
# ------------------------------------------
if [ ! -f "$LOKI_CONFIG" ]; then
	fail "missing docker/config/loki/loki.yml"
else
	check_value "server.http_listen_port" "3100" "$(section_value "$LOKI_CONFIG" server http_listen_port)"
	check_value "common.path_prefix" "/loki" "$(section_value "$LOKI_CONFIG" common path_prefix)"
	check_value "schema_config store" "tsdb" "$(section_value "$LOKI_CONFIG" schema_config store)"
	check_value "schema_config object_store" "filesystem" "$(section_value "$LOKI_CONFIG" schema_config object_store)"
	check_value "schema_config schema (what grafana/loki:3.7.8 writes)" "v13" \
		"$(section_value "$LOKI_CONFIG" schema_config schema)"
fi

# ------------------------------------------
# 2. The retention window is declared and is not the packaged "keep everything" default.
#
# The three failure modes get their own message because they mean different things: absent is a
# config that lost the block, `0s` is the packaged default coming back, and any other value is
# someone changing the policy without changing this suite.
# ------------------------------------------
if [ ! -f "$LOKI_CONFIG" ]; then
	fail "cannot check retention: docker/config/loki/loki.yml is missing"
else
	retention="$(section_value "$LOKI_CONFIG" limits_config retention_period)"
	if [ -z "$retention" ]; then
		fail "limits_config.retention_period is not declared: Loki would fall back to the packaged '0s' and keep logs forever"
	elif [ "$retention" = "0s" ]; then
		fail "limits_config.retention_period is '0s': that is the unbounded packaged default this file exists to replace"
	elif [ "$retention" != "$EXPECTED_RETENTION" ]; then
		fail "limits_config.retention_period is '$retention', expected '$EXPECTED_RETENTION' (30 days); update this suite if the policy really changed"
	else
		pass "limits_config.retention_period = $retention (30 days, the longest range Loki answers)"
	fi

	check_value "compactor.retention_enabled" "true" \
		"$(section_value "$LOKI_CONFIG" compactor retention_enabled)"
	check_value "compactor.delete_request_store" "filesystem" \
		"$(section_value "$LOKI_CONFIG" compactor delete_request_store)"

	# The pre-2.6 spelling of that switch, `limits_config.enforce_retention_period`, is not merely
	# deprecated in 3.7.8: the key does not exist, and declaring it fails the parse with `field
	# enforce_retention_period not found in type validation.plain`, so the container never starts.
	# It is a plausible "fix" for the `enforce_retention_period: false` that `GET :3100/config`
	# keeps printing out of `query_engine`, so the absence is pinned rather than remembered.
	legacy="$(section_block "$LOKI_CONFIG" limits_config | grep -c -E '^[[:space:]]*enforce_retention_period:')"
	if [ "$legacy" -eq 0 ]; then
		pass "limits_config declares no legacy enforce_retention_period ($legacy), which grafana/loki:3.7.8 rejects at parse time"
	else
		fail "limits_config declares the legacy enforce_retention_period $legacy time(s): grafana/loki:3.7.8 fails to start on that key; use compactor.retention_enabled"
	fi
fi

# ------------------------------------------
# 3. Each compose file mounts that file read-only at /etc/loki/config.yml.
#
# Both compose files declare their own `loki` service (the prod override replaces the base one's
# volumes), so a mount missing from either one silently restores the packaged config in that
# environment.
# ------------------------------------------
EXPECTED_MOUNT="./config/loki/loki.yml:/etc/loki/config.yml:ro"

for compose in "$BASE_COMPOSE" "$PROD_COMPOSE"; do
	name="${compose#"$REPO_ROOT/"}"
	if [ ! -f "$compose" ]; then
		fail "missing $name"
		continue
	fi
	block="$(service_block "$compose" loki)"
	if [ -z "$block" ]; then
		fail "$name declares no 'loki' service"
		continue
	fi
	actual_mount="$(printf '%s\n' "$block" | grep -F -- "loki.yml:" | sed 's/^[[:space:]]*-[[:space:]]*//' | tr -d '"' | head -1)"
	if [ "$actual_mount" = "$EXPECTED_MOUNT" ]; then
		pass "$name mounts $actual_mount"
	else
		fail "$name does not mount docker/config/loki/loki.yml read-only at /etc/loki/config.yml (found '${actual_mount:-<none>}')"
	fi
done

# ------------------------------------------
# 4. The compose command reads the mounted file, not the image's packaged config.
#
# The mount alone changes nothing while `-config.file` still points at `/etc/loki/local-config.yaml`.
# ------------------------------------------
EXPECTED_COMMAND="-config.file=/etc/loki/config.yml"

for compose in "$BASE_COMPOSE" "$PROD_COMPOSE"; do
	name="${compose#"$REPO_ROOT/"}"
	[ -f "$compose" ] || continue
	block="$(service_block "$compose" loki)"
	[ -n "$block" ] || continue
	actual_command="$(printf '%s\n' "$block" | grep -E '^[[:space:]]*command:' | head -1 | sed 's/^[[:space:]]*command:[[:space:]]*//' | tr -d '[]"' | sed 's/[[:space:]]*$//')"
	if [ "$actual_command" = "$EXPECTED_COMMAND" ]; then
		pass "$name loki command reads $actual_command"
	else
		fail "$name loki command is '${actual_command:-<none>}', expected '$EXPECTED_COMMAND'"
	fi
done

# ------------------------------------------
# 5. setup-env.sh does not install it into $VOLUMES_ROOT.
#
# This is the inverse of the prometheus/tempo/grafana rule, and it is the point of the change: a
# host copy is written once, reported as "already present" forever after, and never appears in a
# diff. Loki's retention therefore has to be the exception.
# ------------------------------------------
if [ ! -f "$SETUP_ENV" ]; then
	fail "missing docker/scripts/setup-env.sh"
else
	copied="$(grep -c 'config/loki' "$SETUP_ENV")"
	if [ "$copied" -eq 0 ]; then
		pass "setup-env.sh installs no Loki config into VOLUMES_ROOT ($copied references to config/loki)"
	else
		fail "setup-env.sh references config/loki $copied time(s): Loki's retention must stay a repository mount, not a host copy"
	fi
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
