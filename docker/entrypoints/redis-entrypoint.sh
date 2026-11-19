#!/bin/sh
# ============================================
# LifeControl - Redis Entrypoint Wrapper
# ============================================
# Reads the cache's password from a secret file mounted at /run/secrets/redis_password (overridable
# through LC_REDIS_PASSWORD_FILE, which is what lets a suite drive this file against a throwaway
# directory instead of the container's /run/secrets), passes it to redis-server as --requirepass, and
# execs the image's own entrypoint so signals and pid-1 semantics survive.
#
# Why a wrapper at all, when redis-server accepts --requirepass directly: the password must not exist as
# an environment value. `--requirepass "$(cat /run/secrets/redis_password)"` inside a compose `command:`
# would be raw YAML, not a shell, so the file would never be read; and a password in an env entry is a
# password in `docker inspect` and in `compose config`, which is exactly what issue #890 measured for the
# published port and what S5 forbids for the credential.
#
# Required, not optional: Redis with no password is the defect this unit closes, so a missing or empty
# file is a FATAL and the container does not start. Failing at start is the loud half of the fix; the
# quiet half would be an API that starts, cannot authenticate, and answers 500 on every cached read.
#
# It also writes the exporter's password file, which is a different shape: `--redis.password-file` is a
# JSON MAP of address to password (`{"redis://redis:6379":"..."}`), measured on the pinned
# oliver006/redis_exporter tag, which restarts with `password file format error: invalid character ...
# looking for beginning of value` when handed the bare secret. That image is distroless -- no /bin/sh --
# so it cannot have a wrapper of its own to convert one into the other, and this script is the only shell
# on the path. The map is written into a directory both services mount, read-write here and read-only
# there; redis itself keeps reading the secret file directly, so nothing here changes what redis-server
# authenticates.
#
# This wrapper contains NO secret content.
# ============================================

# POSIX sh on purpose: the redis image is alpine and ships no bash, so a bash shebang is a container
# that never starts -- and `exec` reports the missing interpreter as a missing file, which is how this
# shipped once (issue #890). The assertions that pin this live in datastore-port-binding.test.sh.
set -eu

password_file="${LC_REDIS_PASSWORD_FILE:-/run/secrets/redis_password}"

if [ -d "$password_file" ] || [ ! -s "$password_file" ]; then
	echo "FATAL: $password_file is missing or empty; Redis requires a password (issue #890)" >&2
	echo "FATAL: run ./docker/scripts/setup-env.sh <env> to materialize docker/secrets/redis_password" >&2
	exit 1
fi

# The trailing byte is dropped for the same reason the app wrapper drops it on the fiscal passwords: an
# operator's editor or `echo` leaves a newline that nobody sees, and Redis would then require a password
# with a newline in it while the file appears correct.
redis_password="$(tr -d '\r\n' <"$password_file")"

if [ -z "$redis_password" ]; then
	echo "FATAL: $password_file holds no password after stripping line breaks" >&2
	exit 1
fi

# The exporter's input. Its directory is a shared mount, so a missing one is not a reason to carry on:
# the exporter would start with no password and restart forever, which is the failure mode this unit
# exists to remove.
exporter_dir="${LC_REDIS_EXPORTER_DIR:-/run/lc-exporter}"
exporter_addr="${LC_REDIS_ADDR:-redis://redis:6379}"
exporter_file="$exporter_dir/redis_passwords.json"

if [ ! -d "$exporter_dir" ]; then
	echo "FATAL: $exporter_dir is not a directory; the Redis exporter reads its password map from there (issue #890)" >&2
	echo "FATAL: the redis service mounts it read-write and the exporter read-only, from the same host directory" >&2
	exit 1
fi

# A backslash has to be escaped before the quote it may precede, or the second pass would double the
# backslash this one inserted. Either character unescaped produces a document the exporter cannot parse,
# for a password the file itself shows as correct.
json_escape() {
	printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# The exporter starts as soon as redis is healthy, so it can read this file while it is being written.
# A temporary name plus a rename inside the same directory means a reader sees the previous document or
# the new one, never half of either -- and the mode is set before the rename so the reader never opens a
# file only root can read (the exporter runs as uid 59000).
exporter_tmp="${exporter_file}.tmp.$$"
trap 'rm -f "$exporter_tmp"' EXIT

umask 022
printf '{"%s":"%s"}\n' "$(json_escape "$exporter_addr")" "$(json_escape "$redis_password")" >"$exporter_tmp"
chmod 0644 "$exporter_tmp"
mv "$exporter_tmp" "$exporter_file"

# The image's entrypoint is what chowns /data and drops privileges before exec'ing redis-server, so it is
# kept in the chain rather than replaced.
exec /usr/local/bin/docker-entrypoint.sh redis-server --requirepass "$redis_password" "$@"
