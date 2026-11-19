#!/bin/bash
# ============================================
# LifeControl - published port binding tests
# ============================================
# Which port is published where, for every service that publishes one -- so this suite is the
# single owner of that question rather than one file per family of ports.
#
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
# The same rule reaches the two application services, for a different reason. `lifecontrol-api`
# trusts the address ranges declared for its proxy and actuator (`TRUSTED_PROXIES`,
# `ACTUATOR_ALLOWED_CIDRS`), so a port bound to every interface would make every LAN host a
# trusted peer; `api-gateway` answers its management surface to any valid realm token, so its
# published port is the whole perimeter of the actuator. Both are loopback-only, and their ports
# are frozen here beside the observability ones so one suite answers "which port is published
# where".
#
# Pinned here, for both compose files and every service in the frozen table:
#
#   1. every published mapping starts with `127.0.0.1:` -- the offending value and its `file:line`
#      are printed when one does not, naming the service so an observability port and an
#      application port are told apart;
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
# (the file keeps its name: it was the observability suite before it owned the app ports too)
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
# The freeze. One row per compose file and service that publishes a host port:
#
#   file | service | published mappings | container ports | host-side variables | host scope
#
# The host scope column is one token per mapping, in the same order as `container ports`:
# `loopback` when the host side must carry the 127.0.0.1 prefix, `any` when the mapping is
# published on every interface on purpose. Every mapping declares which it is, so a mapping is never
# loopback-bound or LAN-visible by omission. Both files are `loopback` today -- issue #890 bound the
# datastores and the application ports, and issue #892 added the API's address-range trust on top of
# the same pair -- so `any` is the state this table refuses to let back in by accident.
#
# The container ports and the variables are what every other file in the repository addresses
# (`prometheus.yml` scrapes `lifecontrol-api:8082`, `datasources.yml` points at
# `http://loki:3100`, `deploy.sh health` reads `${PROMETHEUS_PORT}` on the host, the Angular app
# and the gateway address `${API_GATEWAY_PORT}`), so changing one has to be a deliberate edit here
# as well. Tempo publishes two mappings: its HTTP port and the Zipkin port nothing sends to.
#
# `lifecontrol-api` and `api-gateway` are declared in the base file only: the prod override
# redeclares the gateway's two mappings on the prod ports and gives the API no `ports:` at all,
# which means prod inherits the base API mapping -- the loopback binding is not lost by the
# override, it is simply not restated there.
# ------------------------------------------
EXPECTED_TABLE="docker-compose.yml|loki|1|3100|LOKI_PORT|loopback
docker-compose.yml|prometheus|1|9090|PROMETHEUS_PORT|loopback
docker-compose.yml|tempo|2|3100 9411|TEMPO_PORT TEMPO_ZIPKIN_PORT|loopback loopback
docker-compose.yml|grafana|1|3000|GRAFANA_PORT|loopback
docker-compose.yml|lifecontrol-api|1|8082|LIFECONTROL_API_PORT|loopback
docker-compose.yml|api-gateway|2|9000 9001|API_GATEWAY_PORT API_GATEWAY_MANAGEMENT_PORT|loopback loopback
docker-compose.prod.yml|loki|1|3100|LOKI_PORT|loopback
docker-compose.prod.yml|prometheus|1|9090|PROMETHEUS_PORT|loopback
docker-compose.prod.yml|tempo|2|3100 9411|TEMPO_PORT TEMPO_ZIPKIN_PORT|loopback loopback
docker-compose.prod.yml|grafana|1|3000|GRAFANA_PORT|loopback
docker-compose.prod.yml|api-gateway|2|9200 9201|API_GATEWAY_PORT API_GATEWAY_MANAGEMENT_PORT|loopback loopback"

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
# 1. Host binding, mapping by mapping, in both compose files -- the four observability services
#    and the two application services. Each mapping is frozen with the host scope it is supposed to
#    have, so a port that silently changes from loopback to every-interface (or the reverse) fails
#    here rather than shipping.
#    services and the two application services, whose published ports are the edge of their own
#    trust (an address range for the API, a realm token for the gateway actuator).
# ------------------------------------------
while IFS='|' read -r file service expected_count expected_cports expected_vars expected_hosts; do
	[ -n "$file" ] || continue
	compose="$DOCKER_DIR/$file"
	if [ ! -f "$compose" ]; then
		fail "missing $file"
		continue
	fi
	ports="$(published_ports "$compose" "$service")"
	actual_count="$(printf '%s\n' "$ports" | grep -c . || true)"

	if [ "$actual_count" -eq 0 ]; then
		fail "$file: service '$service' publishes no host port at all; the mapping was removed instead of bound to 127.0.0.1 (drop the row here if a service really stops publishing)"
		continue
	fi

	if [ "$actual_count" = "$expected_count" ]; then
		pass "$file: '$service' publishes $actual_count mapping(s), as frozen in this suite"
	else
		fail "$file: service '$service' publishes $actual_count mapping(s), expected $expected_count; update this suite if the set really changed"
	fi

	# Read the expected container ports into an array so each mapping can be compared by position.
	expected_port_list="$(printf '%s' "$expected_cports" | tr ' ' '\n')"
	expected_host_list="$(printf '%s' "$expected_hosts" | tr ' ' '\n')"
	index=0
	while IFS=$'\t' read -r line value; do
		[ -n "$value" ] || continue
		index=$((index + 1))

		case "$value" in
		127.0.0.1:*)
			actual_host=loopback
			;;
		*)
			actual_host=any
			;;
		esac
		expected_host="$(printf '%s\n' "$expected_host_list" | sed -n "${index}p")"
		if [ "$actual_host" = "$expected_host" ]; then
			if [ "$actual_host" = loopback ]; then
				pass "$file:$line '$service' publishes '$value' on 127.0.0.1 only"
			else
				pass "$file:$line '$service' publishes '$value' on every interface, as this suite freezes it"
			fi
		elif [ "$expected_host" = loopback ]; then
			fail "$file:$line '$service' publishes '$value' on every host interface: this mapping is frozen as loopback-only and its host side has no 127.0.0.1 prefix"
		elif [ "$expected_host" = any ]; then
			fail "$file:$line '$service' publishes '$value' on 127.0.0.1 only: this mapping is frozen as published on every interface. If that is really the intent, say why in the table's host-scope column and in the record that decided it"
		else
			fail "$file:$line '$service' has no frozen host scope for mapping $index (expected 'loopback' or 'any', got '${expected_host:-<none>}')"
		fi

		# The container side is the last component of the mapping. It is a literal port for the
		# observability services and a `${VAR:-port}` interpolation for the application ones, and a
		# plain `${value##*:}` would split inside that interpolation's own `:-`. So the component is
		# read from the end of the string: the default inside the braces when it is interpolated,
		# the number when it is literal. That resolved port is what the frozen table records.
		container_port="$(printf '%s' "$value" | sed -E \
			-e 's/.*:\$\{[A-Za-z_][A-Za-z0-9_]*:-([0-9]+)\}$/\1/' \
			-e 's/.*:([0-9]+)$/\1/')"
		case "$container_port" in
		*:* | *'{'* | *'}'* | '')
			fail "$file:$line '$service' mapping '$value' does not end in a literal port or a \${VAR:-port} default, so its container port cannot be read"
			continue
			;;
		esac
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
