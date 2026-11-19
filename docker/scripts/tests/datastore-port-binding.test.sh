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
#     a wrapper (`command:` is raw YAML, not a shell, so it could never read a file) and reaches the API
#     as an exported variable read from the same mounted file. It appears in no compose file and in no
#     env template, because a password in an environment value is a password in `docker inspect` and in
#     `docker compose config`;
#   * the exporter's half of it, which is a format and not a file. `--redis.password-file` takes a JSON
#     MAP of address to password -- handed the bare secret the pinned image restarts with `password file
#     format error: invalid character ... looking for beginning of value`, measured on 2026-11-21 -- and
#     `oliver006/redis_exporter` is distroless, so it can have no wrapper of its own to convert one into
#     the other. The redis wrapper writes that map into a shared directory the exporter mounts
#     read-only.
#
# Both defects this suite grew assertions for shipped because the checks named the flag and not what the
# flag consumes: an exporter pointed at a file nothing converts, and an API whose wrapper exported a
# credential the container was never given.
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
#      exporter reads `--redis.password-file` at the JSON map path and mounts the directory the redis
#      service writes it into (read-write there, read-only here), the Redis wrapper turns the mounted
#      file into `--requirepass` **and** writes the map -- escaping it and renaming it into place -- the
#      API service mounts the cache secret at the path its wrapper reads, the API wrapper exports
#      `REDIS_PASSWORD` from that file, and `application.properties` consumes it.
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
# 4. The exporter's password is a JSON map, not the secret file, and both services mount it.
#
# The pinned `--redis.password-file` is a JSON object of address to password, so the value the exporter
# parses is `{"redis://redis:6379":"..."}` and never the bare secret. The redis service writes that
# document into a host directory both services mount at the same container path -- read-write there
# because the wrapper writes, read-only here because the exporter only reads. Both halves are asserted:
# a single `:ro` on the wrong service (or on both) is a wrapper that cannot write or an exporter that
# finds a document nobody created.
# ------------------------------------------
SHARED_HOST_DIR='${VOLUMES_ROOT:?VOLUMES_ROOT must be set}/redis/exporter'
SHARED_CONTAINER_DIR='/run/lc-exporter'
SHARED_MOUNT="$SHARED_HOST_DIR:$SHARED_CONTAINER_DIR"
EXPORTER_MAP_PATH="$SHARED_CONTAINER_DIR/redis_passwords.json"

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
	elif [ "$password_file" = "$EXPORTER_MAP_PATH" ]; then
		pass "$file: 'redis-exporter' reads its password from $password_file, the JSON map the redis service writes"
	else
		fail "$file: 'redis-exporter' reads its password from '$password_file' instead of $EXPORTER_MAP_PATH: this image parses that file as a JSON map of address to password, and a bare secret restarts it with 'password file format error'"
	fi

	shared_ro="$(printf '%s\n' "$block" | grep -F "$SHARED_MOUNT" | head -1)"
	if [ -z "$shared_ro" ]; then
		fail "$file: 'redis-exporter' does not mount $SHARED_MOUNT, so the map the redis service writes is not visible to it and it restarts with no password"
	elif printf '%s' "$shared_ro" | grep -qE ':ro[[:space:]]*$'; then
		pass "$file: 'redis-exporter' mounts $SHARED_CONTAINER_DIR read-only"
	else
		fail "$file: 'redis-exporter' mounts $SHARED_CONTAINER_DIR without :ro ('$shared_ro'), and an exporter has no business writing the document redis owns"
	fi

	# The bare secret is not a shape this image can parse, and a mounted secret nothing reads is a
	# credential channel that looks wired and is not -- the exact half-state issue #890 shipped.
	if printf '%s\n' "$block" | grep -qE 'secrets/redis_password:/run/secrets/redis_password'; then
		fail "$file: 'redis-exporter' still mounts the bare secret at /run/secrets/redis_password: it parses that path as a JSON map and restarts"
	else
		pass "$file: 'redis-exporter' no longer mounts the bare secret it cannot parse"
	fi
done

# The writing half. Without this mount the wrapper has nowhere to put the map, and the exporter -- which
# waits for this service to be healthy -- would start against a directory that never holds a document.
redis_service_block="$(strip_comments "$(service_block "$DOCKER_DIR/docker-compose.yml" redis)")"
if [ -z "$redis_service_block" ]; then
	fail "docker-compose.yml declares no redis service"
else
	shared_rw="$(printf '%s\n' "$redis_service_block" | grep -F "$SHARED_MOUNT" | head -1)"
	if [ -z "$shared_rw" ]; then
		fail "docker-compose.yml: 'redis' does not mount $SHARED_MOUNT, so the wrapper has nowhere to write the map the exporter reads and the cache credential never reaches the exporter"
	elif printf '%s' "$shared_rw" | grep -qE ':ro[[:space:]]*$'; then
		fail "docker-compose.yml: 'redis' mounts $SHARED_CONTAINER_DIR read-only ('$shared_rw'): the wrapper cannot write into it, and a read-only mount fails the start instead of the exporter"
	else
		pass "docker-compose.yml: 'redis' mounts $SHARED_CONTAINER_DIR read-write, the half that writes the map"
	fi
fi

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

	# --- the map the exporter parses -------------------------------------------------
	# The exporter's --redis.password-file is a JSON object, not a password, so the wrapper is the only
	# converter on this path (the exporter image is distroless). The four properties below are what a
	# working document needs; a wrapper that wrote the bare password instead would pass a check that only
	# named the file.
	if printf '%s\n' "$wrapper_body" | grep -qF 'LC_REDIS_EXPORTER_DIR:-/run/lc-exporter'; then
		pass "the wrapper writes into the exported directory by default (LC_REDIS_EXPORTER_DIR:-/run/lc-exporter)"
	else
		fail "the wrapper never defaults LC_REDIS_EXPORTER_DIR to /run/lc-exporter, the mount the compose files declare"
	fi

	if printf '%s\n' "$wrapper_body" | grep -qF 'redis_passwords.json'; then
		pass "the wrapper writes the file name the exporter's --redis.password-file names"
	else
		fail "the wrapper never writes redis_passwords.json, the file --redis.password-file points at"
	fi

	# The map's key is the address the exporter dials. A map keyed on anything else carries no password
	# for the target being scraped, and the exporter authenticates as nobody.
	if printf '%s\n' "$wrapper_body" | grep -qF 'LC_REDIS_ADDR:-redis://redis:6379'; then
		pass "the wrapper keys the map on the exporter's address (LC_REDIS_ADDR:-redis://redis:6379)"
	else
		fail "the wrapper does not key the map on the address the exporter dials (LC_REDIS_ADDR:-redis://redis:6379)"
	fi

	# Shape, not just the file name: an object with an address key and a password value. A bare password
	# is what the exporter could not parse, which is the defect this unit removes.
	json_format="$(printf '%s\n' "$wrapper_body" | sed -n "s/.*printf[[:space:]]*'\([^']*{[^']*\)'.*/\1/p" | head -1)"
	json_shape="$(printf '%s' "$json_format" | sed -e 's/\\n$//')"
	json_values="$(printf '%s' "$json_shape" | grep -o '%s' | wc -l | tr -d ' ')"
	if [ -n "$json_shape" ] && printf '%s' "$json_shape" | grep -qE '^\{.*:.*\}$' && [ "$json_values" -ge 2 ]; then
		pass "the wrapper builds a JSON object with a key and a string value ('$json_shape'), not a bare password"
	else
		fail "the wrapper's document is '${json_shape:-<none>}', which is not an address-to-password JSON object: the exporter parses this file as a map and restarts on anything else"
	fi

	# A password holding either character would otherwise produce invalid JSON, and the operator would see
	# a parse error for a password the file shows as correct.
	if printf '%s\n' "$wrapper_body" | grep -qF 's/\\/\\\\/g' && printf '%s\n' "$wrapper_body" | grep -qF 's/"/\\"/g'; then
		pass "the wrapper escapes a backslash and then a double quote, so neither can corrupt the document"
	else
		fail "the wrapper does not escape a backslash and a double quote: a password containing either produces invalid JSON and the exporter restarts"
	fi

	# The exporter starts on the same event redis becomes healthy, so it can read while the wrapper
	# writes. A rename is what makes the reader see the old document or the new one, never half of either.
	tmp_line="$(printf '%s\n' "$wrapper_body" | grep -E '^[[:space:]]*[A-Za-z_]*_tmp=' | head -1)"
	mv_line="$(printf '%s\n' "$wrapper_body" | grep -E '^[[:space:]]*mv[[:space:]]+.*_tmp' | head -1)"
	if [ -n "$tmp_line" ] && printf '%s' "$tmp_line" | grep -qF '.tmp' && [ -n "$mv_line" ] && printf '%s' "$mv_line" | grep -qE 'redis_passwords\.json|[A-Za-z_]*file'; then
		pass "the wrapper writes a temporary name and renames it into place, so a concurrent reader never sees a half-written document"
	else
		fail "the wrapper writes the map in place instead of writing a temporary file and renaming it: the exporter starts beside it and can parse a truncated document"
	fi

	# uid 59000 is the exporter's own user (measured on the pinned tag), and a 0600 root-owned map is a
	# document the exporter cannot open -- a permission error in place of a credential.
	if printf '%s\n' "$wrapper_body" | grep -qE '^[[:space:]]*chmod[[:space:]]+0?6[04][04][[:space:]]+.*_tmp'; then
		pass "the wrapper sets a mode the exporter's own uid can read before the rename"
	else
		fail "the wrapper does not chmod the map before renaming it: the exporter runs as uid 59000 and cannot read a file only root can open"
	fi

	# A missing directory must be its own FATAL. A silent skip leaves the exporter restarting forever
	# with no password, which is the failure mode this unit exists to remove.
	if printf '%s\n' "$wrapper_body" | grep -qE '\[[[:space:]]*![[:space:]]*-d[[:space:]]+"[^\"]*exporter_dir"' && printf '%s\n' "$wrapper_body" | grep -qF 'FATAL: $exporter_dir'; then
		pass "the wrapper fails with its own FATAL when the exporter's directory is missing, instead of skipping the write"
	else
		fail "the wrapper skips the map when its directory is missing: the exporter would restart forever with no password and nothing would say why"
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

	# The other half of the same sentence, and the reason `lifecontrol-api` restarted with `FATAL: ... is
	# missing or empty; the cache requires a password (issue #890)`: the wrapper exported a credential the
	# service never received. The mount is checked against the path the wrapper actually defaults to, so a
	# renamed default cannot leave a mount behind that nothing reads.
	app_redis_mount="$(sed -n 's/.*REDIS_PASSWORD_FILE:-\([^}]*\)}.*/\1/p' "$APP_WRAPPER" | head -1)"
	api_block="$(strip_comments "$(service_block "$DOCKER_DIR/docker-compose.yml" lifecontrol-api)")"
	if [ -z "$api_block" ]; then
		fail "docker-compose.yml declares no lifecontrol-api service"
	elif [ -z "$app_redis_mount" ]; then
		fail "the API wrapper's Redis secret path could not be read, so the mount it needs cannot be checked"
	elif printf '%s\n' "$api_block" | grep -qE "^[[:space:]]*-[[:space:]]*\./secrets/redis_password:$app_redis_mount:ro[[:space:]]*$"; then
		pass "the lifecontrol-api service mounts the cache secret read-only at $app_redis_mount, the path its wrapper reads"
	else
		fail "the lifecontrol-api service does not mount ./secrets/redis_password at $app_redis_mount: the wrapper exports a credential the container never receives, and the API restarts with 'the cache requires a password (issue #890)'"
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
