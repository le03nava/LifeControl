#!/bin/bash
# ============================================
# LifeControl - Grafana datasource parity tests
# ============================================
# `docker/config/grafana/datasources.yml` is installed into $VOLUMES_ROOT by `setup-env.sh` and
# mounted at `/etc/grafana/provisioning/datasources`, so the URLs in it are the only path between
# every dashboard and the three signals: metrics (Prometheus), logs (Loki) and traces (Tempo).
# Nothing compared those URLs against the compose network until this suite. A URL that names a
# host or a port compose does not declare renders an empty panel in every dashboard at once --
# Grafana answers 200, the query returns nothing, and the panel looks like "no traffic" rather
# than "the datasource is unreachable". That is the silent half of the defect S12 exists for.
#
# This suite is offline and static. It pins:
#
#   1. the datasource list, in both directions: the three datasources below stay declared with
#      their type and URL, and no fourth one appears without a reviewed edit to this file;
#   2. every `url` resolves to a service in `docker/docker-compose.yml` on that service's
#      *container* port -- not the published host port, which does not exist from inside the
#      Grafana container;
#   3. no URL is addressed outside the compose network (`localhost`, `127.0.0.1`, `0.0.0.0`);
#   4. exactly one datasource is `isDefault: true`, and it is the Prometheus one.
#
# Why (4) matters here, and why this suite is the counterpart of the dashboards: the provisioned
# dashboards in `docker/config/grafana/dashboards/json/` deliberately carry no `datasource` block,
# so every panel queries Grafana's **default** datasource. A pinned uid would not work either:
# Grafana assigns a provisioned datasource a random uid per instance, so a uid written into a
# dashboard reads "Datasource not found" in any environment whose Grafana volume was provisioned
# earlier. Leaving the panels unqualified and pinning the default here is what keeps one dashboard
# file valid in dev, staging and prod.
#
# A suite that would pass over an empty file is not acceptable: the file has to declare at least
# one datasource, and a file that declares none fails loudly below.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/grafana-datasources-parity.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
DATASOURCES="$DOCKER_DIR/config/grafana/datasources.yml"
DATASOURCES_REL="docker/config/grafana/datasources.yml"
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
# The freeze: `name|type|url` per datasource. The name is the Grafana datasource name, the type
# is what Grafana must load it as (a Prometheus URL provisioned as `loki` answers nothing), and
# the URL is the in-network address the container actually reaches.
# ------------------------------------------
EXPECTED_DATASOURCES="Prometheus|prometheus|http://prometheus:9090
Loki|loki|http://loki:3100
Tempo|tempo|http://tempo:3100"

# compose_services <compose file>: the service names declared under the top-level services: key.
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
# the service declares, one per line. The container side is either a literal (`3000`) or a
# `${VAR:-default}` expression, whose default is the port compose uses when the variable is not
# set. A bare `${VAR}` with no default cannot be resolved statically and is skipped instead of
# guessed.
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

# parse_datasources <datasources file>: one `name|type|url|isDefault` per datasource, in file
# order, with `-` for a key the entry does not declare. Indentation-based on purpose: the file is
# hand-written and tiny, and the Docker toolchain has no YAML parser. A `datasources:` block that
# yields no entry prints nothing, which the caller turns into a failure.
parse_datasources() {
	awk '
	function flush() {
		if (name != "") {
			printf "%s|%s|%s|%s\n", name, (type == "" ? "-" : type), (url == "" ? "-" : url), (is_default == "" ? "-" : is_default)
		}
		name = ""
		type = ""
		url = ""
		is_default = ""
	}
	/^datasources:[[:space:]]*$/ { inside = 1; next }
	inside && /^[^[:space:]#]/ { inside = 0 }
	!inside { next }
	/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/ {
		flush()
		value = $0
		sub(/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		gsub(/"/, "", value)
		name = value
		next
	}
	/^[[:space:]]+type:[[:space:]]*/ {
		value = $0
		sub(/^[[:space:]]+type:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		gsub(/"/, "", value)
		type = value
		next
	}
	/^[[:space:]]+url:[[:space:]]*/ {
		value = $0
		sub(/^[[:space:]]+url:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		gsub(/"/, "", value)
		url = value
		next
	}
	/^[[:space:]]+isDefault:[[:space:]]*/ {
		value = $0
		sub(/^[[:space:]]+isDefault:[[:space:]]*/, "", value)
		sub(/[[:space:]]*$/, "", value)
		gsub(/"/, "", value)
		is_default = value
		next
	}
	END { flush() }
	' "$1"
}

if [ ! -f "$DATASOURCES" ]; then
	fail "missing $DATASOURCES_REL"
elif [ ! -f "$COMPOSE_BASE" ]; then
	fail "missing docker/docker-compose.yml"
else
	PARSED="$(parse_datasources "$DATASOURCES")"
	BASE_SERVICES="$(compose_services "$COMPOSE_BASE")"

	# 0. A file with no datasource would make every loop below a no-op, which is the one way a
	#    parity suite can be green and useless.
	if [ -z "$PARSED" ]; then
		fail "$DATASOURCES_REL declares no datasource: every dashboard would render empty and this suite would still pass"
	else
		pass "$DATASOURCES_REL declares $(printf '%s\n' "$PARSED" | wc -l | tr -d ' ') datasource(s)"
	fi

	# 1. The frozen table, both directions.
	while IFS='|' read -r name expected_type expected_url; do
		[ -n "$name" ] || continue
		entry="$(printf '%s\n' "$PARSED" | awk -F'|' -v n="$name" '$1 == n { print; exit }')"
		if [ -z "$entry" ]; then
			fail "$DATASOURCES_REL no longer declares the datasource '$name': every dashboard querying it renders empty"
			continue
		fi
		actual_type="${entry#*|}"
		actual_type="${actual_type%%|*}"
		actual_url="${entry#*|*|}"
		actual_url="${actual_url%%|*}"
		if [ "$actual_type" = "$expected_type" ]; then
			pass "datasource '$name' keeps type '$expected_type'"
		else
			fail "$DATASOURCES_REL datasource '$name' has type '$actual_type', expected '$expected_type'"
		fi
		if [ "$actual_url" = "$expected_url" ]; then
			pass "datasource '$name' keeps url '$expected_url'"
		else
			fail "$DATASOURCES_REL datasource '$name' has url '$actual_url', expected '$expected_url'"
		fi
	done <<< "$EXPECTED_DATASOURCES"

	while IFS='|' read -r name _type _url _is_default; do
		[ -n "$name" ] || continue
		if ! printf '%s\n' "$EXPECTED_DATASOURCES" | awk -F'|' -v n="$name" '$1 == n { found = 1 } END { exit !found }'; then
			fail "$DATASOURCES_REL declares datasource '$name', which is not in this suite's frozen table: add it (and its url) to this suite in the same commit"
		fi
	done <<< "$PARSED"

	# 2 + 3. Every URL resolves to a compose service on that service's container port, in-network.
	while IFS='|' read -r name _type url _is_default; do
		[ -n "$name" ] || continue
		if [ -z "$url" ] || [ "$url" = "-" ]; then
			fail "$DATASOURCES_REL datasource '$name' declares no url, so it can answer no query"
			continue
		fi

		scheme="${url%%://*}"
		if [ "$scheme" = "$url" ] || { [ "$scheme" != "http" ] && [ "$scheme" != "https" ]; }; then
			fail "$DATASOURCES_REL datasource '$name' has url '$url', which is not an http(s) URL"
			continue
		fi

		rest="${url#*://}"
		hostport="${rest%%/*}"
		host="${hostport%%:*}"
		port="${hostport##*:}"

		case "$host" in
			localhost | 127.0.0.1 | 0.0.0.0)
				fail "$DATASOURCES_REL datasource '$name' points at '$url', which is outside the compose network: from inside the Grafana container it addresses Grafana's own loopback, not a service"
				continue
				;;
		esac

		if printf '%s\n' "$BASE_SERVICES" | grep -qx "$host"; then
			pass "datasource '$name' points at '$host', which docker-compose.yml declares as a service"
		else
			fail "$DATASOURCES_REL datasource '$name' points at '$url': '$host' is not a service in docker/docker-compose.yml, so the name does not resolve on the compose network"
			continue
		fi

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
					pass "$(basename "$compose"): datasource '$name' uses container port $port, which '$host' exposes"
				fi
			done <<< "$ports"
		done
		if [ "$checked" -eq 0 ]; then
			fail "$DATASOURCES_REL datasource '$name' points at '$host', but no compose file publishes a port for that service, so its container port cannot be verified"
		elif [ "$matched" -eq 0 ]; then
			fail "$DATASOURCES_REL datasource '$name' uses port $port, which is not a container port '$host' exposes: a published host port is not reachable from inside the network"
		fi
	done <<< "$PARSED"

	# 4. Exactly one default, and it is Prometheus: the dashboards carry no `datasource` block, so
	#    every panel follows the default. A Loki or Tempo default turns every metric panel empty
	#    without changing a single dashboard file.
	default_count=0
	default_name=""
	while IFS='|' read -r name _type _url is_default; do
		[ -n "$name" ] || continue
		[ "$is_default" = "true" ] || continue
		default_count=$((default_count + 1))
		default_name="$name"
	done <<< "$PARSED"
	if [ "$default_count" -eq 1 ]; then
		pass "$DATASOURCES_REL declares exactly one datasource with isDefault: true"
	else
		fail "$DATASOURCES_REL declares $default_count datasources with isDefault: true, expected exactly 1: the dashboards carry no datasource block and every panel follows the default"
	fi
	if [ "$default_count" -eq 1 ]; then
		default_type="$(printf '%s\n' "$PARSED" | awk -F'|' -v n="$default_name" '$1 == n { print $2 }')"
		if [ "$default_type" = "prometheus" ]; then
			pass "the default datasource is '$default_name' (type prometheus), which is what the metric panels query"
		else
			fail "the default datasource is '$default_name' (type '$default_type'): the dashboards query Prometheus without naming a datasource, so the default must be the prometheus one"
		fi
	fi
fi

echo "------------------------------------------"
printf 'grafana-datasources-parity: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
