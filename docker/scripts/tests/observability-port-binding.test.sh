#!/bin/bash
# ============================================
# LifeControl - observability port binding tests
# ============================================
# Every observability endpoint in this stack is unauthenticated by design: Loki accepts writes,
# Tempo lets anyone read every stored trace, Grafana's home is behind a login form but its ports
# were as open as the rest, and Prometheus ran with `--web.enable-lifecycle`, which turns
# `POST /-/quit` and `POST /-/reload` on for whoever can reach the port.
#
# None of that mattered while the mappings were published as a bare `HOST:CONTAINER`, because
# Docker binds those to every interface on the host: `"${PROMETHEUS_PORT:-9090}:9090"`,
# `"${LOKI_PORT:-3100}:3100"`, `"${TEMPO_PORT:-3110}:3100"` and `"${GRAFANA_PORT:-3000}:3000"` in
# the base file, mirrored in the prod override. Anything on the LAN could inject logs, read traces
# and stop Prometheus. Binding the host side to 127.0.0.1 fixes it without an auth proxy (this
# stack has no reverse proxy and no identity for machine consumers): `deploy.sh health` and a human
# on the host still reach every port, and containers keep talking to each other by service name on
# the compose network, which never traverses the published mapping.
#
# Pinned here, for both compose files and all four services:
#
#   1. every published mapping starts with `127.0.0.1:` -- the offending value and its `file:line`
#      are printed when one does not;
#   2. no service lost its mapping (count > 0), and the count still matches the frozen table, so
#      dropping a port is a reviewed edit to this file and not a silent narrowing;
#   3. the container side of every mapping is unchanged, and so is the variable that names the host
#      side, because those are what the rest of the repository addresses;
#   4. `--web.enable-lifecycle` is gone from both files, and nothing in docker/ calls `/-/reload`
#      or `/-/quit` -- recreating the container is how Prometheus picks up a config change.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/observability-port-binding.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"

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
# The freeze. One row per compose file and observability service:
#
#   file | service | published mappings | container ports | host-side variables
#
# The container ports and the variables are what every other file in the repository addresses
# (`prometheus.yml` scrapes `lifecontrol-api:8082`, `datasources.yml` points at
# `http://loki:3100`, `deploy.sh health` reads `${PROMETHEUS_PORT}` on the host), so changing one
# has to be a deliberate edit here as well. Tempo publishes two mappings: its HTTP port and the
# Zipkin port nothing sends to.
# ------------------------------------------
EXPECTED_TABLE="docker-compose.yml|loki|1|3100|LOKI_PORT
docker-compose.yml|prometheus|1|9090|PROMETHEUS_PORT
docker-compose.yml|tempo|2|3100 9411|TEMPO_PORT TEMPO_ZIPKIN_PORT
docker-compose.yml|grafana|1|3000|GRAFANA_PORT
docker-compose.prod.yml|loki|1|3100|LOKI_PORT
docker-compose.prod.yml|prometheus|1|9090|PROMETHEUS_PORT
docker-compose.prod.yml|tempo|2|3100 9411|TEMPO_PORT TEMPO_ZIPKIN_PORT
docker-compose.prod.yml|grafana|1|3000|GRAFANA_PORT"

# published_ports <file> <service>: one `line<TAB>mapping` per entry of the service's `ports:`.
# Quotes and trailing comments are stripped so the value printed is the mapping itself.
published_ports() {
	awk -v svc="$2" '
		$0 ~ "^  " svc ":[[:space:]]*$" { in_svc = 1; next }
		in_svc && /^  [A-Za-z0-9_-]+:/ { exit }
		!in_svc { next }
		/^    ports:[[:space:]]*$/ { in_ports = 1; next }
		in_ports && /^    [A-Za-z0-9_-]+:/ { in_ports = 0 }
		!in_ports { next }
		/^[[:space:]]*-[[:space:]]*/ {
			value = $0
			sub(/^[[:space:]]*-[[:space:]]*/, "", value)
			sub(/[[:space:]]*#.*$/, "", value)
			gsub(/^"|"[[:space:]]*$/, "", value)
			gsub(/^\x27|\x27[[:space:]]*$/, "", value)
			sub(/[[:space:]]+$/, "", value)
			if (value != "") {
				printf "%d\t%s\n", NR, value
			}
		}
	' "$1"
}

# ------------------------------------------
# 1. Loopback binding, mapping by mapping, in both compose files.
# ------------------------------------------
while IFS='|' read -r file service expected_count expected_cports expected_vars; do
	[ -n "$file" ] || continue
	compose="$DOCKER_DIR/$file"
	if [ ! -f "$compose" ]; then
		fail "missing $file"
		continue
	fi
	ports="$(published_ports "$compose" "$service")"
	actual_count="$(printf '%s\n' "$ports" | grep -c . || true)"

	if [ "$actual_count" -eq 0 ]; then
		fail "$file: service '$service' publishes no host port at all; the mapping was removed instead of bound to 127.0.0.1"
		continue
	fi

	if [ "$actual_count" = "$expected_count" ]; then
		pass "$file: '$service' publishes $actual_count mapping(s), as frozen in this suite"
	else
		fail "$file: service '$service' publishes $actual_count mapping(s), expected $expected_count; update this suite if the set really changed"
	fi

	# Read the expected container ports into an array so each mapping can be compared by position.
	expected_port_list="$(printf '%s' "$expected_cports" | tr ' ' '\n')"
	index=0
	while IFS=$'\t' read -r line value; do
		[ -n "$value" ] || continue
		index=$((index + 1))

		case "$value" in
		127.0.0.1:*)
			pass "$file:$line '$service' publishes '$value' on 127.0.0.1 only"
			;;
		*)
			fail "$file:$line '$service' publishes '$value' on every host interface: the host side has no 127.0.0.1 prefix"
			;;
		esac

		# The container side is the last colon-separated component; `${VAR:-default}` never
		# contains a colon, so the last one is always the port.
		container_port="${value##*:}"
		expected_container_port="$(printf '%s\n' "$expected_port_list" | sed -n "${index}p")"
		if [ "$container_port" = "$expected_container_port" ]; then
			pass "$file:$line '$service' still targets container port $container_port"
		else
			fail "$file:$line '$service' targets container port '${container_port:-<none>}', expected '${expected_container_port:-<not in the frozen table>}'"
		fi
	done <<< "$ports"

	# The host-side variable names are what .env files and deploy.sh address; a literal port
	# number would silently stop honouring them.
	for variable in $expected_vars; do
		if printf '%s\n' "$ports" | grep -qF "\${$variable"; then
			pass "$file: '$service' still names its host side \${$variable...}"
		else
			fail "$file: service '$service' no longer uses \${$variable...} on its host side, so the environment variable is ignored"
		fi
	done
done <<< "$EXPECTED_TABLE"

# ------------------------------------------
# 2. `--web.enable-lifecycle` is gone from both files.
#
# It is what made the published port dangerous: with it, anyone reaching Prometheus can quit or
# reload it. Nothing in this repository uses either endpoint.
# ------------------------------------------
for file in docker-compose.yml docker-compose.prod.yml; do
	compose="$DOCKER_DIR/$file"
	if [ ! -f "$compose" ]; then
		fail "missing $file"
		continue
	fi
	hits="$(grep -n -- '--web.enable-lifecycle' "$compose" | grep -cv -E '^[0-9]+:[[:space:]]*#' || true)"
	if [ "$hits" -eq 0 ]; then
		pass "$file: no --web.enable-lifecycle on any command line ($hits occurrences outside comments)"
	else
		fail "$file: --web.enable-lifecycle is back on $hits command line(s); POST /-/quit and /-/reload would be reachable"
	fi
done

# ------------------------------------------
# 3. Nothing in docker/ exercises the lifecycle endpoints, which is why the flag could go.
#
# `docker/scripts/tests/` is excluded on purpose: this suite has to name the flag to look for it,
# and a test asserting on an absent string would otherwise report itself.
# ------------------------------------------
callers="$(grep -rn -E --exclude-dir=tests '(/-/reload|/-/quit)' "$DOCKER_DIR" | grep -cv '^[^:]*:[0-9]*:[[:space:]]*#' || true)"
if [ "$callers" -eq 0 ]; then
	pass "no non-comment reference to /-/reload or /-/quit remains anywhere in docker/ ($callers)"
else
	fail "$callers non-comment reference(s) to /-/reload or /-/quit remain in docker/: removing the flag breaks them"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
