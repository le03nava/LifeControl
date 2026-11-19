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

# The image's entrypoint is what chowns /data and drops privileges before exec'ing redis-server, so it is
# kept in the chain rather than replaced.
exec /usr/local/bin/docker-entrypoint.sh redis-server --requirepass "$redis_password" "$@"
