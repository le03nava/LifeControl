#!/bin/bash
# ============================================
# LifeControl - Prometheus scrape-target parity tests
# ============================================
# `docker/config/prometheus/prometheus.yml` and `docker/docker-compose.yml` describe the same
# network from two sides, and nothing compared them until this suite: Prometheus scrapes by
# service name on the compose network, while a human reaches the same services through the
# published host ports, which do not exist inside Prometheus' container. A job that names the
# host port, or a service the compose file does not declare, scrapes nothing -- and the only
# trace of it is a target that reads `down` while the service itself is healthy. That is the
# silent half of the defect this unit (S11/S12) exists to remove: the signal is configured and
# never arrives.
#
# This suite is offline and static. It pins:
#
#   1. the scrape job list, in both directions: every job the file declares is in the frozen
#      table below, and every table row is still declared. Removing a job drops a signal, so it
#      has to be a reviewed edit to this file, not a quiet deletion;
#   2. every target is `<service>:<port>` where `<service>` is a service in
#      `docker/docker-compose.yml` and `<port>` is the *container* port that service exposes in
#      the compose files -- never the published host port. A target that only resolves on the
#      host is the defect this suite was written for;
#   3. no target is addressed outside the compose network (`localhost`, `127.0.0.1`, `0.0.0.0`),
#      which is the one shape that works from a laptop and fails from the container.
#
# A suite that would pass over an empty file is not acceptable: the file has to declare at least
# one job, and a file that declares none fails loudly below.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/prometheus-parity.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
PROM_CONFIG="$DOCKER_DIR/config/prometheus/prometheus.yml"
PROM_REL="docker/config/prometheus/prometheus.yml"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"

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
# The freeze: one `job|target` row per scrape job.
#
# The `prometheus` row is the self-scrape, and it is frozen as `prometheus:9090` on purpose: S12
# requires a compose service name, Prometheus resolves its own service name through the compose
# DNS like any other target, and `localhost` is exactly the "outside the compose network" shape
# the third check forbids. Freezing the conforming value keeps this table the target state, so
# fixing the file does not require editing the suite.
# ------------------------------------------
EXPECTED_JOBS="prometheus|prometheus:9090
api-gateway|api-gateway:9001
lifecontrol-api|lifecontrol-api:8082
postgres-lifecontrol|postgres-exporter-lifecontrol:9187
postgres-keycloak|postgres-exporter-keycloak:9187
redis|redis-exporter:9121"

# compose_services <compose file>: the service names declared under the top-level services: key.
# Only two-space indentation distinguishes them from every nested mapping.
compose_services() {
	awk '
		/^services:[[:space:]]*$/ { inside = 1; next }
		inside && /^[^[:space:]#]/ { inside = 0 }
		inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
			name = $0
			sub(/^  /, "", name)
			sub(/:.*/, "", name)
			print name
		}
	' "$1"
}

# service_container_ports <compose file> <service>: the container side of every `ports:` mapping
# the service declares, one per line. The container side is either a literal (`9090`) or a
# `${VAR:-default}` expression, whose default is the port compose uses when the variable is not
# set -- that default is what this suite compares a target against. A bare `${VAR}` with no
# default cannot be resolved statically and is skipped instead of guessed.
service_container_ports() {
	awk -v svc="$2" '
		$0 ~ "^  " svc ":[[:space:]]*$" { in_svc = 1; next }
		in_svc && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
		!in_svc { next }
		/^    ports:[[:space:]]*$/ { in_ports = 1; next }
		in_ports && /^    [A-Za-z0-9_-]+:/ { in_ports = 0 }
		!in_ports { next }
		/^[[:space:]]*-[[:space:]]*/ {
			value = $0
			sub(/^[[:space:]]*-[[:space:]]*/, "", value)
			sub(/[[:space:]]*#.*$/, "", value)
			gsub(/"/, "", value)
			sub(/[[:space:]]+$/, "", value)
			if (value == "") {
				next
			}
			port = value
			if (port ~ /\}$/) {
				if (port !~ /:-/) {
					next
				}
				sub(/^.*:-/, "", port)
				sub(/\}$/, "", port)
			} else {
				sub(/^.*:/, "", port)
			}
			if (port != "") {
				print port
			}
		}
	' "$1"
}

# scrape_jobs <prometheus config>: one `<job><TAB><target>` per static target.
scrape_jobs() {
	awk '
		/^[[:space:]]*-[[:space:]]*job_name:[[:space:]]*/ {
			job = $0
			sub(/^[[:space:]]*-[[:space:]]*job_name:[[:space:]]*/, "", job)
			gsub(/[[:space:]"]/, "", job)
			next
		}
		/^[[:space:]]*(-[[:space:]]*)?targets:[[:space:]]*\[/ {
			line = $0
			sub(/^[^[]*\[/, "", line)
			sub(/\].*$/, "", line)
			count = split(line, parts, ",")
			for (i = 1; i <= count; i++) {
				target = parts[i]
				gsub(/[[:space:]"]/, "", target)
				if (target != "") {
					printf "%s\t%s\n", job, target
				}
			}
		}
	' "$1"
}

if [ ! -f "$PROM_CONFIG" ]; then
	fail "missing $PROM_REL"
elif [ ! -f "$COMPOSE_BASE" ]; then
	fail "missing docker/docker-compose.yml"
else
	JOBS="$(scrape_jobs "$PROM_CONFIG")"
	BASE_SERVICES="$(compose_services "$COMPOSE_BASE")"

	# 0. A file with no job at all would make every loop below a no-op, which is the one way a
	#    parity suite can be green and useless.
	if [ -z "$JOBS" ]; then
		fail "$PROM_REL declares no scrape target: nothing would be collected and this suite would still pass"
	else
		pass "$PROM_REL declares $(printf '%s\n' "$JOBS" | wc -l | tr -d ' ') scrape target(s)"
	fi

	# 1 + 2 + 3. Every frozen job, its exact target, its host and its container port.
	while IFS='|' read -r job expected_target; do
		[ -n "$job" ] || continue
		actual_target="$(printf '%s\n' "$JOBS" | awk -F'\t' -v j="$job" '$1 == j { print $2 }' | head -1)"
		if [ -z "$actual_target" ]; then
			fail "$PROM_REL declares no job '$job': the signal it scrapes is no longer collected"
			continue
		fi
		if [ "$actual_target" = "$expected_target" ]; then
			pass "job '$job' scrapes $expected_target"
		else
			fail "$PROM_REL job '$job' scrapes '$actual_target', expected '$expected_target'"
		fi

		host="${actual_target%%:*}"
		port="${actual_target##*:}"

		# (3) A loopback target works from the host and nowhere else.
		case "$host" in
			localhost | 127.0.0.1 | 0.0.0.0)
				fail "$PROM_REL job '$job' targets '$actual_target', which is outside the compose network: inside the container it addresses Prometheus' own loopback, not a service"
				continue
				;;
		esac

		# (2a) The host has to be a compose service, or the name does not resolve in-network.
		if printf '%s\n' "$BASE_SERVICES" | grep -qx "$host"; then
			pass "job '$job' targets '$host', which docker-compose.yml declares as a service"
		else
			fail "$PROM_REL job '$job' targets '$actual_target': '$host' is not a service in docker/docker-compose.yml, so the name does not resolve on the compose network"
			continue
		fi

		# (2b) The port has to be the container port compose exposes, not the published host
		# port: Prometheus scrapes inside the network, where the host mapping does not exist.
		matched=0
		checked=0
		for compose in "$DOCKER_DIR"/docker-compose*.yml; do
			[ -f "$compose" ] || continue
			ports="$(service_container_ports "$compose" "$host")"
			[ -n "$ports" ] || continue
			checked=$((checked + 1))
			while IFS= read -r container_port; do
				[ -n "$container_port" ] || continue
				if [ "$container_port" = "$port" ]; then
					matched=1
					pass "$(basename "$compose"): job '$job' scrapes container port $port, which '$host' exposes"
				fi
			done <<< "$ports"
		done
		if [ "$checked" -eq 0 ]; then
			fail "$PROM_REL job '$job' targets '$host', but no compose file publishes a port for that service, so its container port cannot be verified"
		elif [ "$matched" -eq 0 ]; then
			fail "$PROM_REL job '$job' scrapes '$actual_target' (port $port), which is not a container port '$host' exposes: a published host port is not reachable from inside the network"
		fi
	done <<< "$EXPECTED_JOBS"

	# The reverse direction: a job declared in Prometheus but absent from the table is a target
	# nobody reviewed, and a job dropped from the file is a signal nobody noticed losing.
	while IFS=$'\t' read -r job target; do
		[ -n "$job" ] || continue
		if ! printf '%s\n' "$EXPECTED_JOBS" | awk -F'|' -v j="$job" '$1 == j { found = 1 } END { exit !found }'; then
			fail "$PROM_REL declares job '$job' ($target), which is not in this suite's frozen table: add it (and its target) to this suite in the same commit"
		fi
	done <<< "$JOBS"
fi

echo "------------------------------------------"
printf 'prometheus-parity: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
