#!/bin/bash
# ============================================
# LifeControl - datastore, mailpit and API port binding, and the Redis credential
# ============================================
# A published mapping written as a bare `HOST:CONTAINER` is bound to every interface of the host, and
# this stack published its datastores that way: `"${POSTGRES_PORT:-5433}:5432"`,
# `"${LIFECONTROL_POSTGRES_PORT:-5435}:5432"` and `"${REDIS_PORT:-6379}:6379"` in the base file, the
# two Postgres instances again in the prod override, mailpit in the dev override, and the application
# API's own port. Postgres answers with a password from a mounted secret, so what leaked was a login
# surface; Redis answered **without** a password at all, which made the LAN a read/write cache one
# `CONFIG SET dir` away from an arbitrary file write (issue #890).
#
# Two halves, and neither is optional:
#
#   * the loopback prefix -- the same fix the observability ports already carry, frozen by
#     observability-port-binding.test.sh, which this suite copies in structure and in the frozen-table
#     idea;
#   * the Redis credential -- the password lives in a mounted secret file, reaches redis-server through
#     a wrapper (`command:` is raw YAML, not a shell, so it could never read a file), reaches the API as
#     an exported variable and the exporter through `--redis.password-file`. It appears in no compose
#     file and in no env template, because a password in an environment value is a password in
#     `docker inspect` and in `docker compose config`.
#
# Pinned here:
#
#   1. every mapping S1 names starts with `127.0.0.1:` -- the offending value and its `file:line` are
#      printed when one does not;
#   2. no service lost its mapping (count > 0) and the count still matches the frozen table, so dropping
#      or narrowing a port is a reviewed edit to this file and not a silent change;
#   3. the container side and the variable that names the host side are unchanged, because those are
#      what the rest of the repository addresses (`deploy.sh` reads `${POSTGRES_PORT}`,
#      `.env.template` declares it, and the API container listens on its `LIFECONTROL_API_PORT`);
#   4. the credential half: no Redis password literal in a compose file or in `.env.template`, the
#      exporter reads it with `--redis.password-file` from a mounted secret, the Redis wrapper is what
#      turns that file into `--requirepass`, the API wrapper exports `REDIS_PASSWORD` from a file, and
#      `application.properties` consumes it.
#
# The Redis wrapper is checked statically and not executed: it ends in `exec /usr/local/bin/
# docker-entrypoint.sh`, an absolute path that only exists inside the image, so running it would need
# the redis image and therefore a Docker daemon. No Docker daemon, no network, no file written: this is
# a static consistency check.
#
# Run: ./docker/scripts/tests/datastore-port-binding.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
APP_PROPERTIES="$REPO_ROOT/life-control-api/src/main/resources/application.properties"
ENV_TEMPLATE="$DOCKER_DIR/.env.template"
REDIS_WRAPPER="$DOCKER_DIR/entrypoints/redis-entrypoint.sh"
APP_WRAPPER="$DOCKER_DIR/entrypoints/lifecontrol-app-entrypoint.sh"

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
# The freeze. One row per compose file and service this unit moves:
#
#   file | service | published mappings | container ports | host-side variables
#
# Only the set the spec names, plus the application's own surface in `lifecontrol-api` and
# `api-gateway`: those two mappings moved in the same edit (the API's port and the gateway's server and
# management ports are how a browser or an operator reaches the application), and a mapping this suite
# does not pin is one nothing stops from going back to every interface. Keycloak and the web app are
# deliberately absent, and so are the observability ports: the first two are browser-facing entry points
# this issue does not name, and the last three are already frozen by observability-port-binding.test.sh.
#
# A service that publishes nothing is not a pass: the count is part of the row, so removing a port is a
# reviewed edit here too.
# ------------------------------------------
EXPECTED_TABLE="docker-compose.yml|keycloak-postgres|1|5432|POSTGRES_PORT
docker-compose.yml|lifecontrol-postgres|1|5432|LIFECONTROL_POSTGRES_PORT
docker-compose.yml|redis|1|6379|REDIS_PORT
docker-compose.yml|lifecontrol-api|1|\${LIFECONTROL_API_PORT:-8082}|LIFECONTROL_API_PORT
docker-compose.yml|api-gateway|2|\${API_GATEWAY_SERVER_PORT:-9000} \${API_GATEWAY_MANAGEMENT_PORT:-9001}|API_GATEWAY_PORT API_GATEWAY_MANAGEMENT_PORT
docker-compose.prod.yml|keycloak-postgres|1|5432|POSTGRES_PORT
docker-compose.prod.yml|lifecontrol-postgres|1|5432|LIFECONTROL_POSTGRES_PORT
docker-compose.prod.yml|api-gateway|2|\${API_GATEWAY_SERVER_PORT:-9200} \${API_GATEWAY_MANAGEMENT_PORT:-9201}|API_GATEWAY_PORT API_GATEWAY_MANAGEMENT_PORT
docker-compose.override.yml|mailpit|1|8025|MAILPIT_PORT"

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

# container_side <mapping>: everything after the first colon that sits outside a `${...}` placeholder.
#
# The last colon is NOT the container port here, which is why this is not `${value##*:}` as in the
# observability suite: `lifecontrol-api` publishes `${LIFECONTROL_API_PORT:-8082}` on both sides, and
# `${VAR:-default}` carries a colon of its own. The host side is always a value or a placeholder, so
# walking to the first depth-zero colon is what separates the two halves.
container_side() {
	local value="$1"
	local depth=0
	local index=0
	local char
	case "$value" in
	127.0.0.1:*) value="${value#127.0.0.1:}" ;;
	*) printf '%s' '<not loopback>' && return 0 ;;
	esac
	while [ "$index" -lt "${#value}" ]; do
		char="${value:$index:1}"
		case "$char" in
		'{') depth=$((depth + 1)) ;;
		'}') depth=$((depth - 1)) ;;
		':')
			if [ "$depth" -eq 0 ]; then
				printf '%s' "${value:$((index + 1))}"
				return 0
			fi
			;;
		esac
		index=$((index + 1))
	done
	printf '%s' '<no container side>'
}

# service_block <compose file> <service name>: the service's own lines, for the credential checks.
service_block() {
	awk -v svc="$2" '
		$0 == "  " svc ":" { inside = 1; print; next }
		inside && /^  [a-zA-Z0-9_-]+:$/ { inside = 0 }
		inside { print }
	' "$1"
}

# strip_comments <text>: the same text without its `#` lines. These blocks explain the very strings the
# negative checks look for (`REDIS_PASSWORD`, `--requirepass`), so a check that read the comments would
# report the explanation as the defect.
strip_comments() {
	printf '%s\n' "$1" | awk '!/^[[:space:]]*#/'
}

# ------------------------------------------
# 1. Loopback binding, mapping by mapping, in all three compose files.
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

		actual_container="$(container_side "$value")"
		expected_container="$(printf '%s\n' "$expected_port_list" | sed -n "${index}p")"
		if [ "$actual_container" = "$expected_container" ]; then
			pass "$file:$line '$service' still targets container port $actual_container"
		else
			fail "$file:$line '$service' targets container side '${actual_container}', expected '${expected_container:-<not in the frozen table>}'"
		fi
	done <<< "$ports"

	# The host-side variable names are what .env files, `deploy.sh health` and the Service URLs table
	# address; a literal port number would silently stop honouring them.
	for variable in $expected_vars; do
		if printf '%s\n' "$ports" | grep -qF "\${$variable"; then
			pass "$file: '$service' still names its host side \${$variable...}"
		else
			fail "$file: service '$service' no longer uses \${$variable...} on its host side, so the environment variable is ignored"
		fi
	done
done <<< "$EXPECTED_TABLE"

# ------------------------------------------
# 2. No compose file carries a Redis password, in any of the shapes that would put it in
#    `docker inspect` or in `docker compose config`.
#
# The three literal shapes are an environment entry, a bare `--requirepass <value>` and a
# `--redis.password=` argument (the `-file` spelling is the one the exporter must use, and it is checked
# in section 4). A `$(cat …)` substitution is not a literal and is what the wrapper is for, so a
# placeholder shape is not reported.
# ------------------------------------------
for file in docker-compose.yml docker-compose.prod.yml docker-compose.override.yml; do
	compose="$DOCKER_DIR/$file"
	if [ ! -f "$compose" ]; then
		fail "missing $file"
		continue
	fi
	body="$(strip_comments "$(cat "$compose")")"

	env_hits="$(printf '%s\n' "$body" | grep -nE '^[[:space:]]*-?[[:space:]]*REDIS_PASSWORD[[:space:]]*[:=]' || true)"
	if [ -z "$env_hits" ]; then
		pass "$file: no Redis password as an environment value"
	else
		while IFS= read -r hit; do
			fail "$file:${hit%%:*} sends a Redis password as an environment value: the container reads the mounted secret instead, so the value never reaches docker inspect"
		done <<< "$env_hits"
	fi

	flag_hits="$(printf '%s\n' "$body" | grep -nE -- '--requirepass[[:space:]=]+[^(]' || true)"
	if [ -z "$flag_hits" ]; then
		pass "$file: no --requirepass literal on any command line"
	else
		while IFS= read -r hit; do
			fail "$file:${hit%%:*} passes --requirepass a literal: the password belongs in the mounted secret file, not in a compose command"
		done <<< "$flag_hits"
	fi

	arg_hits="$(printf '%s\n' "$body" | grep -nE -- '--redis\.password=' || true)"
	if [ -z "$arg_hits" ]; then
		pass "$file: the Redis exporter never takes the password as an argument value"
	else
		while IFS= read -r hit; do
			fail "$file:${hit%%:*} passes --redis.password= a value: --redis.password-file keeps it out of docker inspect"
		done <<< "$arg_hits"
	fi
done

# ------------------------------------------
# 3. No env template declares the Redis password.
#
# A `.env.<env>` is materialized once by setup-env.sh and never reconciled, so a password declared
# there is a password that survives in a file nobody re-reads. Only the key and the position are
# printed: the value, if a regression ever puts one there, is a credential and this report is not the
# place to reproduce it.
# ------------------------------------------
if [ ! -f "$ENV_TEMPLATE" ]; then
	fail "missing docker/.env.template"
else
	template_hits="$(grep -nE '^[[:space:]]*(export[[:space:]]+)?REDIS_PASSWORD[[:space:]]*=[[:space:]]*[^[:space:]]' "$ENV_TEMPLATE" || true)"
	if [ -z "$template_hits" ]; then
		pass ".env.template declares no Redis password (the value ships as a mounted secret)"
	else
		while IFS= read -r hit; do
			fail "docker/.env.template:${hit%%:*} declares a Redis password: it belongs in docker/secrets/redis_password, never in an env file"
		done <<< "$template_hits"
	fi
fi

# ------------------------------------------
# 4. The exporter: `--redis.password-file` and the secret it names, mounted read-only, in both files.
# ------------------------------------------
for file in docker-compose.yml docker-compose.prod.yml; do
	compose="$DOCKER_DIR/$file"
	if [ ! -f "$compose" ]; then
		fail "missing $file"
		continue
	fi
	block="$(strip_comments "$(service_block "$compose" redis-exporter)")"
	if [ -z "$block" ]; then
		fail "$file: declares no redis-exporter service, so nothing measures the cache"
		continue
	fi

	password_file="$(printf '%s\n' "$block" | sed -n 's/.*--redis\.password-file=\([^"[:space:]]*\).*/\1/p' | head -1)"
	if [ -z "$password_file" ]; then
		fail "$file: 'redis-exporter' passes no --redis.password-file, so it cannot authenticate against a Redis that requires a password (redis_up would be 0)"
	elif [ "$password_file" = "/run/secrets/redis_password" ]; then
		pass "$file: 'redis-exporter' reads its password from $password_file"
	else
		fail "$file: 'redis-exporter' reads its password from '$password_file' instead of /run/secrets/redis_password"
	fi

	if [ -n "$password_file" ]; then
		secret_name="${password_file##*/}"
		if printf '%s\n' "$block" | grep -qE "^[[:space:]]*-[[:space:]]*\./secrets/$secret_name:$password_file:ro[[:space:]]*$"; then
			pass "$file: 'redis-exporter' mounts ./secrets/$secret_name read-only at $password_file"
		else
			fail "$file: 'redis-exporter' reads $password_file but does not mount ./secrets/$secret_name there read-only"
		fi
	fi
done

# ------------------------------------------
# 5. The Redis wrapper: the only thing that can turn a mounted file into `--requirepass`.
# ------------------------------------------
if [ ! -f "$REDIS_WRAPPER" ]; then
	fail "missing docker/entrypoints/redis-entrypoint.sh: without it nothing can read the secret into --requirepass"
else
	if [ -x "$REDIS_WRAPPER" ]; then
		pass "docker/entrypoints/redis-entrypoint.sh is executable"
	else
		fail "docker/entrypoints/redis-entrypoint.sh is not executable: compose runs what it mounts, and a non-executable wrapper fails the start"
	fi

	wrapper_body="$(strip_comments "$(cat "$REDIS_WRAPPER")")"
	if printf '%s\n' "$wrapper_body" | grep -qE 'LC_REDIS_PASSWORD_FILE:-/run/secrets/redis_password'; then
		pass "the wrapper reads the mounted secret by default (LC_REDIS_PASSWORD_FILE:-/run/secrets/redis_password)"
	else
		fail "the wrapper never defaults to /run/secrets/redis_password, the path the compose files mount"
	fi
	if printf '%s\n' "$wrapper_body" | grep -q -- '--requirepass'; then
		pass "the wrapper is what passes --requirepass to redis-server"
	else
		fail "the wrapper never passes --requirepass: Redis would answer without a password, which is the defect this unit closes"
	fi
	# Required, not optional: a Redis that starts without a password because someone forgot to mount
	# the file is exactly the state this unit removes, and only a FATAL at start makes it loud.
	if printf '%s\n' "$wrapper_body" | grep -q 'FATAL'; then
		pass "the wrapper refuses to start without the password instead of falling back to no authentication"
	else
		fail "the wrapper has no failure path for a missing password: an unmounted secret would start an unauthenticated Redis"
	fi
	# The credential boundary app-credential-split.test.sh protects: a wrapper that carried the value
	# would be a password in the repository.
	if printf '%s\n' "$wrapper_body" | grep -qE '^\s*redis_password="?[A-Za-z0-9]'; then
		fail "the wrapper assigns a literal password instead of reading the file"
	else
		pass "the wrapper carries no credential value"
	fi

	# The interpreter has to exist in the image, and no assertion above can tell. On 2026-11-19 this
	# suite was green while the container restarted forever with `exec
	# /usr/local/bin/lc-redis-entrypoint.sh: no such file or directory`, because the shebang named bash
	# and `redis:8.10-alpine` ships only sh. `exec` reports a missing interpreter exactly like a missing
	# file, so the symptom named the wrong cause and the fix looked like a mount problem.
	wrapper_shebang="$(head -1 "$REDIS_WRAPPER")"
	if [ "$wrapper_shebang" = '#!/bin/sh' ]; then
		pass "the wrapper starts with #!/bin/sh, the interpreter the redis image actually ships"
	else
		fail "the wrapper starts with '$wrapper_shebang' instead of #!/bin/sh: redis:8.10-alpine has no bash, and exec reports the missing interpreter as a missing file, so the container restarts instead of starting"
	fi
	if printf '%s\n' "$wrapper_body" | grep -qE '(^|[^[:alnum:]_])bash([^[:alnum:]_]|$)'; then
		fail "the wrapper names bash somewhere: the redis image has none, and a wrapper written for bash dies before its first line of logic"
	else
		pass "the wrapper asks for nothing the redis image does not ship"
	fi

	redis_block="$(strip_comments "$(service_block "$DOCKER_DIR/docker-compose.yml" redis)")"
	if [ -z "$redis_block" ]; then
		fail "docker-compose.yml declares no redis service"
	else
		if printf '%s\n' "$redis_block" | grep -qF 'entrypoint: ["/usr/local/bin/lc-redis-entrypoint.sh"]'; then
			pass "the redis service runs the wrapper as its entrypoint"
		else
			fail "the redis service does not run the wrapper, so the mounted secret never becomes --requirepass"
		fi
		if printf '%s\n' "$redis_block" | grep -qE '^[[:space:]]*-[[:space:]]*\./entrypoints/redis-entrypoint\.sh:/usr/local/bin/lc-redis-entrypoint\.sh:ro[[:space:]]*$'; then
			pass "the redis service mounts the wrapper read-only"
		else
			fail "the redis service does not mount docker/entrypoints/redis-entrypoint.sh"
		fi
		if printf '%s\n' "$redis_block" | grep -qE '^[[:space:]]*-[[:space:]]*\./secrets/redis_password:/run/secrets/redis_password:ro[[:space:]]*$'; then
			pass "the redis service mounts ./secrets/redis_password read-only"
		else
			fail "the redis service does not mount ./secrets/redis_password at /run/secrets/redis_password"
		fi
		# An unauthenticated probe answers NOAUTH and redis-cli still exits 0, so a bare `ping` would
		# report healthy a container that authenticates nobody.
		if printf '%s\n' "$redis_block" | grep -qE 'redis-cli .*-a '; then
			pass "the redis healthcheck authenticates, so NOAUTH cannot read as healthy"
		else
			fail "the redis healthcheck runs redis-cli without -a: NOAUTH exits 0 and an unauthenticated probe reports healthy"
		fi
	fi
fi

# ------------------------------------------
# 6. The API: the wrapper exports the password from a file and the application consumes it.
# ------------------------------------------
if [ ! -f "$APP_WRAPPER" ]; then
	fail "missing docker/entrypoints/lifecontrol-app-entrypoint.sh"
else
	export_line="$(grep -E '^[[:space:]]*export REDIS_PASSWORD=' "$APP_WRAPPER" || true)"
	if [ -z "$export_line" ]; then
		fail "the API wrapper never exports REDIS_PASSWORD, so spring.data.redis.password is empty and every cached read answers NOAUTH"
	elif printf '%s' "$export_line" | grep -qF '$(cat '; then
		pass "the API wrapper exports REDIS_PASSWORD read from a file, never a literal"
	else
		fail "the API wrapper exports REDIS_PASSWORD from something other than a file read: $(printf '%s' "$export_line")"
	fi
	if grep -qE 'REDIS_PASSWORD_FILE:-/run/secrets/redis_password' "$APP_WRAPPER"; then
		pass "the API wrapper's default Redis secret path is the mount the compose files declare"
	else
		fail "the API wrapper does not default REDIS_PASSWORD_FILE to /run/secrets/redis_password"
	fi
fi

if [ ! -f "$APP_PROPERTIES" ]; then
	fail "missing life-control-api/src/main/resources/application.properties"
elif grep -qF 'spring.data.redis.password=${REDIS_PASSWORD:}' "$APP_PROPERTIES"; then
	pass "application.properties consumes \${REDIS_PASSWORD:}"
else
	fail "application.properties does not set spring.data.redis.password=\${REDIS_PASSWORD:}: the exported secret would never reach Lettuce"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
