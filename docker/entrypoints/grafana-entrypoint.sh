#!/bin/bash
# ============================================
# LifeControl - Grafana Entrypoint Wrapper
# ============================================
# Reads credentials from secret files mounted at /run/secrets/ and exports the real GF_* env vars the
# Grafana image entrypoint expects, then execs /run.sh so signals and pid-1 semantics survive.
#
# This wrapper contains NO secret content.
#
# Two credentials, deliberately handled with different severity:
#
#   grafana_admin_password  required. Without it the container has no admin account, so a missing
#                           file is fatal exactly as it always was.
#   smtp_password           optional. It is only read when the relay authenticates (Grafana
#                           authenticates when smtp.user is non-empty, which is what GF_SMTP_USER
#                           feeds). A missing file warns and the container starts anyway: without it
#                           the *notifications* fail and Grafana logs the send failure, while a
#                           Grafana that refuses to start over mail takes the dashboards and every
#                           panel down with it. keycloak-setup.sh refuses in the same situation for a
#                           different reason -- it WRITES the relay into the realm, so a wrong value
#                           there is persisted and every invitation silently lands in the dev
#                           mailbox while the script prints success. Nothing here is persisted: a
#                           wrong relay costs one mail, once, and says so in the log.
# ============================================

set -euo pipefail

admin_secret="/run/secrets/grafana_admin_password"
smtp_secret="/run/secrets/smtp_password"

if [ ! -s "$admin_secret" ]; then
	echo "FATAL: $admin_secret is missing or empty" >&2
	exit 1
fi

GF_SECURITY_ADMIN_PASSWORD="$(cat "$admin_secret")"
export GF_SECURITY_ADMIN_PASSWORD

# ---- SMTP credential (optional) ----
# `-f` and not only `-s`: Docker creates a *directory* at the bind-mount source when the host file
# does not exist (measured), and a directory is truthy for `-s`. Treating that as "no credential"
# is what keeps a host that never declared SMTP auth from exporting garbage as a password.
if [ -n "${GF_SMTP_USER:-}" ]; then
	if [ -f "$smtp_secret" ] && [ -s "$smtp_secret" ]; then
		smtp_credential="$(cat "$smtp_secret")"
		export GF_SMTP_PASSWORD="$smtp_credential"
	else
		echo "WARNING: GF_SMTP_USER is declared but $smtp_secret is missing, empty or not a regular file." >&2
		echo "WARNING: Grafana starts without an SMTP credential, so alert notifications will fail to send." >&2
		echo "WARNING: dashboards are unaffected; create docker/secrets/smtp_password (and remove any empty directory Docker left in its place) to deliver them." >&2
	fi
fi

# ---- the dev mail catcher, outside dev ----
# GF_SMTP_HOST is built from SMTP_HOST:SMTP_PORT by compose, and its default is the dev Mailpit
# container (`mailpit:1025`), the same relay Keycloak's invitation mail uses. That name is only
# correct where docker-compose.override.yml defines the service -- dev. A staging or prod .env file
# that never declared SMTP_HOST inherits the default, and the only symptom would be alert mails that
# never arrive.
#
# The check is the name's resolution on this compose network, not an environment variable: the
# dev-only service is defined by the override file, so in dev `mailpit` always resolves and outside
# dev it never does. keycloak-setup.sh knows the environment because it takes it as an argument; a
# container does not, and a check that trusted an ENVIRONMENT variable would be silent on exactly
# the env files that forget to set it.
#
# Warning, never refusing to start: see the header -- the failure costs a notification and is logged
# at delivery time, while refusing would cost every dashboard for a mail misconfiguration.
smtp_host="${GF_SMTP_HOST:-}"
smtp_hostname="${smtp_host%%:*}"
if [ "$smtp_hostname" = "mailpit" ]; then
	if command -v getent >/dev/null 2>&1 && getent hosts mailpit >/dev/null 2>&1; then
		: # the dev mail catcher is on this network; this is where dev notifications belong
	else
		echo "WARNING: GF_SMTP_HOST is '$smtp_host', whose host is 'mailpit' -- the service name of the DEV-ONLY mail catcher (docker/docker-compose.override.yml, never the base compose or prod)." >&2
		echo "WARNING: 'mailpit' does not resolve on this network, so alert notifications cannot be delivered. Set SMTP_HOST and SMTP_PORT in docker/.env.<env> to a real relay (the same keys Keycloak's invitation mail uses)." >&2
		echo "WARNING: Grafana keeps serving dashboards; only the notification channel is affected." >&2
	fi
fi

exec /run.sh "$@"
