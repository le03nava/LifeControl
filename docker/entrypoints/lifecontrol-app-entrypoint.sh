#!/bin/sh
# ============================================
# LifeControl - LifeControl API Entrypoint Wrapper
# ============================================
# Turns the mounted secrets into the two database logins ADR-0005 decision 3 requires, then execs the
# image entrypoint (java -jar) so signals and pid-1 semantics survive.
#
#   * the pool  -> DATABASE_USERNAME / DATABASE_PASSWORD -> spring.datasource
#     (life-control-api/src/main/resources/application.properties). It logs in as `lifecontrol_app`,
#     which owns nothing and carries no BYPASSRLS, so every ADR-0005 policy applies to the connection
#     the application actually uses. DATABASE_PASSWORD is docker/secrets/lifecontrol_app_password.
#   * Flyway    -> FLYWAY_USERNAME / FLYWAY_PASSWORD       -> spring.flyway (same file). It logs in as
#     `lifecontrol_owner`, the owner of every object and the only login allowed to run DDL.
#     FLYWAY_PASSWORD is docker/secrets/lifecontrol_postgres_password.
#
# The two logins are distinct on purpose. Flyway needs the owner, and the runtime must not have it: a
# pool that connects as the owner would carry the credential that owns the tables, which is the thing
# ADR-0005 removes. The role *names* come from compose (a role name is not a secret, so the template
# declares them); the passwords are read here, from the secret files, and never from a `.env`.
#
# The Keycloak admin client secret is exported the same way and for the same reason.
#
# The cache's password is exported the same way too, and is required like the three above: a deployment
# where the API starts without it answers NOAUTH on every cached read, and a database restore plus a
# cache flush would then look like an application bug. What changed on the Redis side is not just the
# credential -- the datastore answered without one at all -- so the value arrives through a mounted file
# here as well and never as an environment entry (issue #890).
#
# The fiscal provider's four credentials -- the issuer's certificate, its private key, the password that
# protects that key, and the PAC's own password -- are read the same way, and only while
# FISCAL_PROVIDER_ENABLED is true: a deployment that does not invoice must start exactly as it did before
# this file learned about them (odd/tasks/fiscal-deploy-wiring.md). None of the four is ever an
# environment value, for the same reason none of the three above is: a key in an env entry is a key in
# `docker inspect` and in `compose config`.
#
# The three secret paths are overridable through the `*_FILE` variables below, with the real mount path
# as the default: that is what lets docker/scripts/tests/app-credential-split.test.sh drive this file
# against a throwaway directory instead of the container's /run/secrets. The Redis password path is
# overridable for the same reason.
#
# POSIX sh on purpose: the runtime image is eclipse-temurin alpine (no bash).
# The Java launch command mirrors life-control-api/Dockerfile ENTRYPOINT.
# This wrapper contains NO secret content.
# ============================================

set -eu

owner_secret_file="${LIFECONTROL_OWNER_PASSWORD_FILE:-/run/secrets/lifecontrol_postgres_password}"
app_secret_file="${LIFECONTROL_APP_PASSWORD_FILE:-/run/secrets/lifecontrol_app_password}"
admin_secret_file="${KEYCLOAK_ADMIN_CLIENT_SECRET_FILE:-/run/secrets/keycloak_admin_client_secret}"
redis_secret_file="${REDIS_PASSWORD_FILE:-/run/secrets/redis_password}"

if [ ! -s "$owner_secret_file" ]; then
	echo "FATAL: $owner_secret_file is missing or empty" >&2
	exit 1
fi

if [ ! -s "$app_secret_file" ]; then
	echo "FATAL: $app_secret_file is missing or empty" >&2
	exit 1
fi

if [ ! -s "$admin_secret_file" ]; then
	echo "FATAL: $admin_secret_file is missing or empty" >&2
	exit 1
fi

# A directory is the shape Docker leaves behind for a bind-mount source that does not exist, and `test -s`
# is true for one, so the Redis file is checked for both shapes before its content is read.
if [ -d "$redis_secret_file" ] || [ ! -s "$redis_secret_file" ]; then
	echo "FATAL: $redis_secret_file is missing or empty; the cache requires a password (issue #890)" >&2
	exit 1
fi

export DATABASE_PASSWORD="$(cat "$app_secret_file")"
export FLYWAY_PASSWORD="$(cat "$owner_secret_file")"
export KEYCLOAK_ADMIN_CLIENT_SECRET="$(cat "$admin_secret_file")"
export REDIS_PASSWORD="$(cat "$redis_secret_file")"

# --------------------------------------------
# The fiscal provider (issue #930)
# --------------------------------------------
# The switch is what makes the block below conditional, and that condition is the whole point: with
# FISCAL_PROVIDER_ENABLED false or absent, this file requires nothing, exports nothing and reaches the
# java launch line with exactly the environment it had before. The switch itself arrives as a plain
# compose value; the credentials never do.
#
# Three of the four files are required while the switch is on. A stamp with no certificate cannot be
# sealed, and an absent PAC password is a login the vendor refuses. The fourth -- the password that
# protects the private key -- is optional on purpose: CsdProperties documents that a blank password and
# an absent one both mean "this key is not protected", so demanding it would force a deployment to
# protect a key it deliberately does not.
if [ "${FISCAL_PROVIDER_ENABLED:-false}" = "true" ]; then
	fiscal_certificado_file="${FISCAL_CSD_CERTIFICADO_FILE:-/run/secrets/fiscal_csd_certificado}"
	fiscal_llave_file="${FISCAL_CSD_LLAVE_FILE:-/run/secrets/fiscal_csd_llave}"
	fiscal_llave_password_file="${FISCAL_CSD_PASSWORD_FILE:-/run/secrets/fiscal_csd_password}"
	fiscal_pac_password_file="${FISCAL_SF_PASSWORD_FILE:-/run/secrets/fiscal_sf_password}"

	for required in "$fiscal_certificado_file" "$fiscal_llave_file" "$fiscal_pac_password_file"; do
		# A directory is the shape Docker leaves behind for a bind-mount source that does not exist, and
		# `test -s` is true for one, so checking only for emptiness reported a credential that was never
		# mounted. validate-env.sh reports the same two shapes before the container starts.
		if [ -d "$required" ] || [ ! -s "$required" ]; then
			echo "FATAL: $required is missing or empty, and FISCAL_PROVIDER_ENABLED=true requires it" >&2
			exit 1
		fi
	done

	# The certificate and the key travel as one continuous base 64 value, because that is the value
	# CsdProperties binds and an X.509 parser reads. `openssl base64` wraps at 64 columns, so the line
	# breaks are removed here instead of being left for a decoder that may not accept them.
	export FISCAL_CSD_CERTIFICADO="$(tr -d '\r\n' <"$fiscal_certificado_file")"
	export FISCAL_CSD_LLAVE="$(tr -d '\r\n' <"$fiscal_llave_file")"

	# The two passwords below are the only credentials in this file that an operator writes by hand: no
	# setup-env.sh template materializes fiscal_sf_password or fiscal_csd_password, so the trailing byte
	# is whatever their tool wrote -- `echo` leaves a newline, an editor on Windows leaves a CRLF -- and
	# the vendor answers either with an authentication error that names neither the file nor the byte. So
	# they get the same trailing CR/LF tolerance the certificate and the key get above, and for the same
	# kind of reason. They keep the `$(cat …)` shape the certificate and the key no longer need because
	# app-credential-split.test.sh reads that token on every `*PASSWORD` export as its evidence that the
	# value came from a mounted file and not from a literal; the filter is piped behind it instead.
	#
	# The database and Keycloak credentials further up are deliberately NOT tolerated this way: they are
	# materialized by setup-env.sh from tracked templates, so their bytes are decided by a file in this
	# repository, and relaxing them here would quietly make a different decision than the one that file
	# belongs to.
	export FISCAL_SF_PASSWORD="$(cat "$fiscal_pac_password_file" | tr -d '\r\n')"

	if [ -f "$fiscal_llave_password_file" ] && [ -s "$fiscal_llave_password_file" ]; then
		export FISCAL_CSD_PASSWORD="$(cat "$fiscal_llave_password_file" | tr -d '\r\n')"
	fi
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
