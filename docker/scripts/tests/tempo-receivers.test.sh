#!/bin/bash
# ============================================
# LifeControl - Tempo receiver reachability tests
# ============================================
# Tempo's receivers are addressed by other containers on the compose network, and since Tempo 3.x
# an OTLP receiver declared without an explicit `endpoint` silently inherits the OpenTelemetry
# Collector's `localhost` default: the socket exists, but only inside Tempo's own container, so
# every export from the API and the gateway fails with `ConnectException: Connection refused`
# while the container still reports healthy.
#
# That is exactly what happened when TEMPO_IMAGE moved grafana/tempo:2.3.0 -> 3.1.0 (commit
# f299b70) with this block unchanged:
#
#     distributor:
#       receivers:
#         otlp:
#           protocols:
#             grpc: {}
#             http: {}
#         zipkin: {}
#
# The startup log said it plainly (`endpoint=127.0.0.1:4317`), but nothing in the repository read
# that log, and the trace store simply stayed empty. The mode of failure is silent, so the pin has
# to be a command: this suite fails when any receiver under `distributor.receivers` lacks an
# explicit, non-loopback endpoint, or when the endpoints stop matching the expected table below.
# The table is a deliberate freeze: changing a receiver's socket means editing this table in the
# same commit, so it is a reviewed decision instead of a drift.
#
# It also pins the removal of the Zipkin receiver, which the same image bump made worth deciding
# explicitly: the compose file publishes 9411 on the host, nothing in this stack sends Zipkin, and
# a declared receiver would make that published port an unauthenticated ingestion endpoint. A
# review of this change raised that as a CRITICAL finding; re-adding the receiver means editing
# this file deliberately, not by accident.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/tempo-receivers.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
TEMPO_CONFIG="$DOCKER_DIR/config/tempo/tempo.yml"

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
# 1. Every receiver declares an explicit, container-reachable endpoint.
#
# The parser is indentation-based on purpose: this repository has no YAML parser in its Docker
# toolchain, and the block is hand-written and tiny. It reads the two shapes the file uses --
# a leaf declared inline (`zipkin: {}`) and a leaf nested under a protocol (`http:` then
# `endpoint:`) -- and reports one line per leaf as `<receiver>[.<protocol>]<TAB><endpoint>`.
# A receiver that yields no leaf is reported as unparseable rather than silently ignored.
# ------------------------------------------
parse_receivers() {
	awk '
	function emit(path, endpoint) {
		printf "%s\t%s\n", path, (endpoint == "" ? "-" : endpoint)
	}
	function receiver_has_leaf(name,   path) {
		for (path in leaves) {
			if (path == name || index(path, name ".") == 1) {
				return 1
			}
		}
		return 0
	}
	/^distributor:[[:space:]]*$/ { in_distributor = 1; next }
	in_distributor && /^[^[:space:]]/ { in_distributor = 0 }
	in_distributor && /^  receivers:[[:space:]]*$/ { in_receivers = 1; next }
	in_receivers && /^  [^[:space:]]/ { in_receivers = 0; next }
	!in_receivers { next }
	/^    [A-Za-z0-9_-]+:/ {
		name = $0
		sub(/^    /, "", name)
		value = name
		sub(/^[^:]*:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		sub(/:.*/, "", name)
		receiver = name
		receivers[name] = 1
		if (value == "{}") {
			leaves[receiver] = ""
			emit(receiver, "")
		}
		next
	}
	/^      endpoint:[[:space:]]*/ {
		value = $0
		sub(/^[[:space:]]*endpoint:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		leaves[receiver] = value
		emit(receiver, value)
		next
	}
	/^        [A-Za-z0-9_-]+:/ {
		name = $0
		sub(/^        /, "", name)
		value = name
		sub(/^[^:]*:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		sub(/:.*/, "", name)
		protocol = name
		if (value == "{}") {
			leaves[receiver "." protocol] = ""
			emit(receiver "." protocol, "")
		}
		next
	}
	/^          endpoint:[[:space:]]*/ {
		value = $0
		sub(/^[[:space:]]*endpoint:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		leaves[receiver "." protocol] = value
		emit(receiver "." protocol, value)
		next
	}
	END {
		for (name in receivers) {
			if (!receiver_has_leaf(name)) {
				printf "@unparsed\t%s\n", name
			}
		}
	}
	' "$1"
}

# Expected sockets, frozen. `otlp.grpc` and `otlp.http` are the receivers the API and the gateway
# export to.
EXPECTED_ENDPOINTS="otlp.grpc=0.0.0.0:4317
otlp.http=0.0.0.0:4318"

# Receivers that must not come back without a deliberate decision. The Zipkin receiver is the one:
# nothing in this stack sends Zipkin and the compose file publishes its port on the host, so
# declaring it would expose an unauthenticated ingestion endpoint.
FORBIDDEN_RECEIVERS="zipkin"

is_forbidden() {
	printf '%s\n' "$FORBIDDEN_RECEIVERS" | grep -qx "${1%%.*}"
}

if [ ! -f "$TEMPO_CONFIG" ]; then
	fail "missing docker/config/tempo/tempo.yml"
else
	PARSED="$(parse_receivers "$TEMPO_CONFIG")"

	# Every declared leaf, with the two rules that made this suite exist.
	while IFS=$'\t' read -r leaf endpoint; do
		[ -n "$leaf" ] || continue
		case "$leaf" in
			@unparsed)
				fail "could not read any receiver leaf out of '$endpoint' -- check the block's indentation"
				continue
				;;
		esac
		if is_forbidden "$leaf"; then
			fail "receiver '$leaf' must not be declared: nothing sends Zipkin and the compose file publishes its port on the host, so it would be an unauthenticated ingestion endpoint"
			continue
		fi
		if [ "$endpoint" = "-" ]; then
			fail "receiver '$leaf' declares no explicit endpoint (Tempo 3.x would bind it to localhost)"
			continue
		fi
		case "$endpoint" in
			127.0.0.1:*|localhost:*)
				fail "receiver '$leaf' binds $endpoint, which no other container can reach"
				;;
			*)
				pass "receiver '$leaf' declares endpoint $endpoint"
				;;
		esac
	done <<< "$PARSED"

	# The frozen table, both directions: a changed socket and a new receiver are reviewed edits.
	while IFS='=' read -r leaf expected; do
		[ -n "$leaf" ] || continue
		actual="$(printf '%s\n' "$PARSED" | awk -F'\t' -v leaf="$leaf" '$1 == leaf { print $2 }')"
		if [ "$actual" = "$expected" ]; then
			pass "endpoint table: $leaf = $expected"
		else
			fail "endpoint table: $leaf expected $expected, got '${actual:--}'"
		fi
	done <<< "$EXPECTED_ENDPOINTS"

	while IFS=$'\t' read -r leaf endpoint; do
		[ -n "$leaf" ] || continue
		case "$leaf" in
			@unparsed) continue ;;
		esac
		is_forbidden "$leaf" && continue
		if ! printf '%s\n' "$EXPECTED_ENDPOINTS" | awk -F= -v leaf="$leaf" '$1 == leaf { found = 1 } END { exit !found }'; then
			fail "receiver '$leaf' is not in the expected table: add it (and its endpoint) to this suite"
		fi
	done <<< "$PARSED"
fi

# ------------------------------------------
# 2. The Zipkin receiver is absent, and the published port stays as it was.
#    Re-adding the receiver means editing section 1's forbidden list in the same commit.
# ------------------------------------------
if grep -qE '^[[:space:]]+zipkin:' "$TEMPO_CONFIG" 2>/dev/null; then
	fail "docker/config/tempo/tempo.yml declares the Zipkin receiver again; decide the host exposure first"
else
	pass "the Zipkin receiver is not declared (its published host port accepts nothing)"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
