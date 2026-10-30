#!/bin/bash
# ============================================
# LifeControl - datastore exporter tests
# ============================================
# Prometheus scraped exactly three targets -- `prometheus`, `api-gateway` and `lifecontrol-api` --
# so neither PostgreSQL instance (Keycloak's and LifeControl's) nor Redis exported anything: the
# datastores behind every endpoint were invisible. A connection-pool exhaustion or a Redis outage
# surfaced only as failed requests, and Redis is deliberately excluded from `/actuator/health`
# (`management.health.redis.enabled=false`), so a Redis outage appeared in no signal at all. S11
# requires `pg_up` for both PostgreSQL instances and `redis_up`.
#
# Measured on 2026-10-24 against the running dev stack, not inferred: throwaway
# `prometheuscommunity/postgres-exporter:v0.19.0` and `oliver006/redis_exporter:v1.69.0`
# containers on the compose network, each reading its password through `DATA_SOURCE_PASS_FILE`
# from the same mounted secret the database uses, answered `pg_up 1` (2036 `pg_*` series) and
# `redis_up 1` (377 `redis_*` series).
#
# Pinned here:
#
#   1. the three scrape jobs exist in `config/prometheus/prometheus.yml`, each names a compose
#      service as its target host, and its port is the container port the compose file exposes
#      for that service -- a job pointing at the published host port would scrape nothing from
#      inside the network, where Prometheus actually runs;
#   2. every exporter publishes on `127.0.0.1:` only, the rule T3 applied to the rest of the
#      observability plane: none of these endpoints authenticates its callers;
#   3. no exporter takes its datastore password from an environment value. It reads the same
#      mounted secret the datastore reads, through the exporter's `*_FILE` mechanism, so the
#      password never reaches `docker inspect`, a `.env` file or this repository;
#   4. both image pins agree with the frozen table of `image-pins.test.sh` in the template *and*
#      in every compose default, and neither tag floats. That suite already compares template
#      against compose defaults; this one closes the other direction -- a pin edited in both
#      places while the frozen table keeps the old version;
#   5. no exporter declares a healthcheck. `oliver006/redis_exporter` is distroless (measured on
#      the pinned tag: `/bin/sh` does not exist), so an in-container probe could never pass, and
#      the scrape itself is the readiness signal Prometheus consumes.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/exporters.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"
ENV_TEMPLATE="$DOCKER_DIR/.env.template"
PROM_CONFIG="$DOCKER_DIR/config/prometheus/prometheus.yml"
IMAGE_PINS_SUITE="$SCRIPT_DIR/image-pins.test.sh"

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
# The freeze. One row per exporter:
#
#   service | image pin variable | container port | host port variable
#
# Two PostgreSQL exporters are required, not one: `postgres_exporter` measures one DSN per
# process, and S11 asks for `pg_up` on *both* instances -- Keycloak's identity database and
# LifeControl's. Multi-target mode would need a config file of its own, which is a third thing
# to keep in review for the same signal.
# ------------------------------------------
EXPECTED_EXPORTERS="postgres-exporter-lifecontrol|POSTGRES_EXPORTER_IMAGE|9187|POSTGRES_EXPORTER_PORT
postgres-exporter-keycloak|POSTGRES_EXPORTER_IMAGE|9187|KEYCLOAK_POSTGRES_EXPORTER_PORT
redis-exporter|REDIS_EXPORTER_IMAGE|9121|REDIS_EXPORTER_PORT"

# ------------------------------------------
# The scrape jobs, frozen the same way: job name and the in-network target it must carry.
# ------------------------------------------
EXPECTED_JOBS="postgres-lifecontrol|postgres-exporter-lifecontrol:9187
postgres-keycloak|postgres-exporter-keycloak:9187
redis|redis-exporter:9121"

# service_block <compose file> <service name>
service_block() {
	awk -v svc="$2" '
		$0 == "  " svc ":" { inside = 1; print; next }
		inside && /^  [a-zA-Z0-9_-]+:$/ { inside = 0 }
		inside { print }
	' "$1"
}

# strip_comments <text>: the same text without its `#` lines. These blocks explain the very
# strings the negative checks look for (`DATA_SOURCE_PASS`, `--redis.password-file`), so a check
# that read the comments would report the explanation as the defect.
strip_comments() {
	printf '%s\n' "$1" | awk '!/^[[:space:]]*#/'
}

# compose_services <compose file>: the service names declared under the top-level services: key.
# Only indentation distinguishes them from every nested mapping (4+ spaces).
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

# published_ports <compose file> <service>: the service's `ports:` entries, one per line, with
# quotes and trailing comments stripped so the value compared is the mapping itself.
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
				print value
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

# image_pins_table: the frozen EXPECTED_PINS block of image-pins.test.sh, one NAME=value per
# line. Parsed from the assignment instead of from a copy, so there is exactly one freeze.
image_pins_table() {
	awk '
		/^EXPECTED_PINS="/ { inside = 1; sub(/^EXPECTED_PINS="/, "") }
		!inside { next }
		{
			line = $0
			if (line ~ /"$/) {
				sub(/"$/, "", line)
				print line
				exit
			}
			print line
		}
	' "$1"
}

# ------------------------------------------
# 1. The three scrape jobs exist and point at a compose service, on the port compose exposes.
# ------------------------------------------
if [ ! -f "$PROM_CONFIG" ]; then
	fail "missing docker/config/prometheus/prometheus.yml"
elif [ ! -f "$COMPOSE_BASE" ]; then
	fail "missing docker/docker-compose.yml"
else
	JOBS="$(scrape_jobs "$PROM_CONFIG")"
	BASE_SERVICES="$(compose_services "$COMPOSE_BASE")"

	while IFS='|' read -r job expected_target; do
		[ -n "$job" ] || continue
		actual_target="$(printf '%s\n' "$JOBS" | awk -F'\t' -v j="$job" '$1 == j { print $2 }')"
		if [ -z "$actual_target" ]; then
			fail "prometheus.yml declares no scrape job '$job': nothing exports the datastore it measures"
			continue
		fi
		if [ "$actual_target" = "$expected_target" ]; then
			pass "prometheus.yml job '$job' scrapes $expected_target"
		else
			fail "prometheus.yml job '$job' scrapes '$actual_target', expected '$expected_target'"
		fi

		host="${actual_target%%:*}"
		target_port="${actual_target##*:}"
		if printf '%s\n' "$BASE_SERVICES" | grep -qx "$host"; then
			pass "job '$job' targets '$host', which docker-compose.yml declares as a service"
		else
			fail "job '$job' targets '$host', which is not a service in docker-compose.yml: Prometheus resolves the name on the compose network"
		fi

		# (e) The port has to be the container port compose exposes, not the published host
		# port: Prometheus scrapes inside the network, where the host mapping does not exist.
		for compose in "$DOCKER_DIR"/docker-compose*.yml; do
			[ -f "$compose" ] || continue
			mappings="$(published_ports "$compose" "$host")"
			[ -n "$mappings" ] || continue
			while IFS= read -r mapping; do
				[ -n "$mapping" ] || continue
				container_port="${mapping##*:}"
				if [ "$container_port" = "$target_port" ]; then
					pass "$(basename "$compose"): job '$job' scrapes container port $target_port, which '$host' exposes"
				else
					fail "$(basename "$compose"): job '$job' scrapes port $target_port but '$host' exposes container port $container_port"
				fi
			done <<< "$mappings"
		done
	done <<< "$EXPECTED_JOBS"

	# An exporter job that exists in Prometheus but not in the table above would be a target
	# nobody reviewed; the three jobs are the whole measured set.
	while IFS=$'\t' read -r job target; do
		[ -n "$job" ] || continue
		case "$job" in
			127.0.0.1:*)
				fail "prometheus.yml job '$job' scrapes $target over loopback, which Prometheus cannot reach from its own container"
				;;
			postgres-* | redis | redis-*)
				if ! printf '%s\n' "$EXPECTED_JOBS" | awk -F'|' -v j="$job" '$1 == j { found = 1 } END { exit !found }'; then
					fail "prometheus.yml declares exporter job '$job' ($target), which is not in this suite's frozen table"
				fi
				;;
		esac
	done <<< "$JOBS"
fi

# ------------------------------------------
# 2. Loopback publishing, and the password never arrives as an environment value.
# ------------------------------------------
while IFS='|' read -r svc image_var container_port host_var; do
	[ -n "$svc" ] || continue
	found_in_base=0
	for compose in "$DOCKER_DIR"/docker-compose*.yml; do
		[ -f "$compose" ] || continue
		block="$(strip_comments "$(service_block "$compose" "$svc")")"
		[ -n "$block" ] || continue
		file="$(basename "$compose")"
		[ "$file" = "docker-compose.yml" ] && found_in_base=1

		mappings="$(published_ports "$compose" "$svc")"
		if [ -z "$mappings" ]; then
			fail "$file: '$svc' publishes no host port, so nothing reaches it from the host and the mapping this suite freezes is gone"
		else
			while IFS= read -r mapping; do
				[ -n "$mapping" ] || continue
				case "$mapping" in
					127.0.0.1:*)
						pass "$file: '$svc' publishes '$mapping' on 127.0.0.1 only"
						;;
					*)
						fail "$file: '$svc' publishes '$mapping' on every host interface: the host side has no 127.0.0.1 prefix"
						;;
				esac
				if [ "${mapping##*:}" = "$container_port" ]; then
					pass "$file: '$svc' still targets container port $container_port"
				else
					fail "$file: '$svc' targets container port '${mapping##*:}', expected $container_port"
				fi
			done <<< "$mappings"
			if printf '%s\n' "$mappings" | grep -qF "\${$host_var"; then
				pass "$file: '$svc' still names its host side \${$host_var...}"
			else
				fail "$file: '$svc' no longer uses \${$host_var...} on its host side, so the env variable is ignored"
			fi
		fi

		# A password in the block, in any of the shapes these exporters accept, is the defect:
		# it lands in `docker inspect` and in whatever `docker compose config` prints.
		if printf '%s\n' "$block" | grep -qE '^[[:space:]]*-?[[:space:]]*(PGPASSWORD|DATA_SOURCE_PASS|DATA_SOURCE_NAME|REDIS_PASSWORD)[[:space:]]*[:=]'; then
			fail "$file: '$svc' passes its password as an environment value: the mounted secret is read with *_FILE so the value never reaches docker inspect"
		fi
		if printf '%s\n' "$block" | grep -qE 'redis\.password='; then
			fail "$file: '$svc' passes the Redis password on its command line: --redis.password-file=/run/secrets/... keeps it out of docker inspect"
		fi

		case "$svc" in
			postgres-exporter-*)
				pass_file="$(printf '%s\n' "$block" | sed -n 's/^[[:space:]]*DATA_SOURCE_PASS_FILE:[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' | head -1)"
				if [ -z "$pass_file" ]; then
					fail "$file: '$svc' sets no DATA_SOURCE_PASS_FILE, so its password would have to come from an environment value"
				else
					pass "$file: '$svc' reads its password from $pass_file"
					case "$pass_file" in
						/run/secrets/*)
							secret_name="${pass_file##*/}"
							if printf '%s\n' "$block" | grep -qE "^[[:space:]]*-[[:space:]]*\./secrets/$secret_name:$pass_file:ro[[:space:]]*$"; then
								pass "$file: '$svc' mounts ./secrets/$secret_name read-only at $pass_file"
							else
								fail "$file: '$svc' reads $pass_file but does not mount ./secrets/$secret_name there read-only"
							fi
							;;
						*)
							fail "$file: '$svc' reads its password from '$pass_file', which is not a mounted secret under /run/secrets"
							;;
					esac
				fi
				if ! printf '%s\n' "$block" | grep -qE '^[[:space:]]*DATA_SOURCE_URI:'; then
					fail "$file: '$svc' sets no DATA_SOURCE_URI, so the exporter has no database to measure"
				fi
				# This exporter is the second consumer of the same role and the same secret. It has to
				# follow ${LIFECONTROL_POSTGRES_USER:-...} like the database and the API do, or a rename
				# of that role leaves a green stack with `pg_up 0` on the LifeControl target.
				data_source_user="$(printf '%s\n' "$block" | sed -n 's/^[[:space:]]*DATA_SOURCE_USER:[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' | head -1)"
				if [ -z "$data_source_user" ]; then
					fail "$file: '$svc' sets no DATA_SOURCE_USER, so it cannot follow a role rename"
				elif printf '%s' "$data_source_user" | grep -qE '^\$\{[A-Za-z_][A-Za-z0-9_]*:-'; then
					pass "$file: '$svc' connects as $data_source_user, so a role rename reaches it"
				else
					fail "$file: '$svc' hardcodes its user as '$data_source_user' instead of following the template's \${KEY:-...} declaration"
				fi
				;;
			redis-exporter)
				# This stack's Redis runs without --requirepass, so the exporter authenticates
				# exactly like the API: with no credential at all. If one is ever added it goes
				# through --redis.password-file, which is checked here when it appears.
				pass_file="$(printf '%s\n' "$block" | sed -n 's/.*--redis\.password-file=\([^"[:space:]]*\).*/\1/p' | head -1)"
				if [ -z "$pass_file" ]; then
					pass "$file: '$svc' passes no Redis password (the datastore has no requirepass)"
				else
					case "$pass_file" in
						/run/secrets/*)
							secret_name="${pass_file##*/}"
							if printf '%s\n' "$block" | grep -qE "^[[:space:]]*-[[:space:]]*\./secrets/$secret_name:$pass_file:ro[[:space:]]*$"; then
								pass "$file: '$svc' reads its password from $pass_file and mounts that secret read-only"
							else
								fail "$file: '$svc' reads $pass_file but does not mount ./secrets/$secret_name there read-only"
							fi
							;;
						*)
							fail "$file: '$svc' reads its Redis password from '$pass_file', which is not a mounted secret under /run/secrets"
							;;
					esac
				fi
				;;
		esac

		# (5) No in-container probe: the Redis exporter image is distroless.
		if printf '%s\n' "$block" | grep -qE '^[[:space:]]*healthcheck:'; then
			fail "$file: '$svc' declares a healthcheck, but the exporter images ship no way to run one (measured: oliver006/redis_exporter has no /bin/sh) and the scrape is the readiness signal"
		else
			pass "$file: '$svc' declares no in-image healthcheck"
		fi

		# The image pin is the same variable in every file: two variables for one exporter is how
		# production ends up on a different version than development.
		if printf '%s\n' "$block" | grep -qE "image:[[:space:]]*\\\${$image_var:-"; then
			pass "$file: '$svc' pins its image through \${$image_var:-...}"
		else
			fail "$file: '$svc' does not pin its image through \${$image_var:-...}"
		fi
	done
	if [ "$found_in_base" -eq 0 ]; then
		fail "docker/docker-compose.yml declares no service '$svc'"
	fi
done <<< "$EXPECTED_EXPORTERS"

# ------------------------------------------
# 3. Both pins agree with image-pins.test.sh's frozen table, in the template and in every
#    compose default, and neither tag floats.
# ------------------------------------------
if [ ! -f "$IMAGE_PINS_SUITE" ]; then
	fail "missing image-pins.test.sh: the pin this suite compares against is gone"
elif [ ! -f "$ENV_TEMPLATE" ]; then
	fail "missing docker/.env.template"
else
	FROZEN_PINS="$(image_pins_table "$IMAGE_PINS_SUITE")"
	while IFS='|' read -r svc image_var container_port host_var; do
		[ -n "$svc" ] || continue
		frozen="$(printf '%s\n' "$FROZEN_PINS" | sed -n "s/^$image_var=//p" | head -1)"
		if [ -z "$frozen" ]; then
			fail "image-pins.test.sh's EXPECTED_PINS declares no $image_var, so '$svc' has no frozen version"
			continue
		fi
		pass "image-pins.test.sh freezes $image_var=$frozen"

		template_value="$(sed -n "s/^$image_var=//p" "$ENV_TEMPLATE" | tail -1)"
		if [ "$template_value" = "$frozen" ]; then
			pass ".env.template declares $image_var=$template_value"
		else
			fail ".env.template declares $image_var='${template_value:-<missing>}', expected '$frozen' (the frozen table is the source of truth)"
		fi

		defaults="$(sed -n "s/.*\\\${$image_var:-\([^}]*\)}.*/\1/p" "$DOCKER_DIR"/docker-compose*.yml 2>/dev/null)"
		if [ -z "$defaults" ]; then
			fail "no compose default found for $image_var (expected at least one)"
		else
			index=0
			while IFS= read -r value; do
				[ -n "$value" ] || continue
				index=$((index + 1))
				if [ "$value" = "$frozen" ]; then
					pass "compose default #$index for $image_var is $value"
				else
					fail "compose default #$index for $image_var is '$value', expected '$frozen'"
				fi
			done <<< "$defaults"
		fi

		# A floating tag defeats the freeze even when all three places agree.
		case "$frozen" in
			*:*) ;;
			*)
				fail "$image_var has no tag at all, so it resolves to 'latest' ($frozen)"
				continue
				;;
		esac
		tag="${frozen##*:}"
		case "$tag" in
			latest)
				fail "$image_var is pinned to 'latest' ($frozen)"
				;;
			*.*)
				pass "$image_var is pinned to a version ($tag)"
				;;
			*)
				fail "$image_var uses a bare-major tag, which floats ($frozen)"
				;;
		esac
	done <<< "$EXPECTED_EXPORTERS"
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'exporters: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
