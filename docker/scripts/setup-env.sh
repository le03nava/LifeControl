#!/bin/bash
# ============================================
# LifeControl - Environment Setup Script
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Resolve env (defaults to dev)
resolve_compose_env "${1:-dev}"

print_status "Setting up ${ENV} environment"

# Check if env file exists; create from template if missing
if [ ! -f "$ENV_FILE" ]; then
	print_status "Environment file $ENV_FILE not found. Creating from template..."
	cp "$DOCKER_DIR/.env.template" "$ENV_FILE"
	# Template defaults VOLUMES_ROOT to the dev volume root; point it at this
	# environment's volume root so non-dev setups never share the dev volumes.
	# dev/staging keep a repo-relative dir; prod uses an absolute root outside
	# the repo (see docker-compose.prod.yml and .env.prod).
	if [ "$ENV" = "prod" ]; then
		sed -i "s|^VOLUMES_ROOT=.*|VOLUMES_ROOT=/var/lib/lifecontrol/volumes|" "$ENV_FILE"
		# Prod uses a postgres host port distinct from dev/staging (5435) so a
		# fresh prod setup never collides with other envs on the same host.
		sed -i "s|^LIFECONTROL_POSTGRES_PORT=.*|LIFECONTROL_POSTGRES_PORT=5445|" "$ENV_FILE"
	else
		sed -i "s|^VOLUMES_ROOT=.*|VOLUMES_ROOT=./volumes-${ENV}|" "$ENV_FILE"
	fi
	# The access-provisioning worker ships OFF everywhere (it mutates Keycloak with nobody
	# watching), but dev is the one environment where leaving it off has no upside and hides
	# the truth: dev's data is disposable and its mail lands in Mailpit, so a provisioning
	# task that nobody claims is just a pending task nobody will ever see advance. The fresh
	# dev file therefore DECLARES it on, instead of relying on the compose override's
	# default, because a declared value beats a default and the operator has to be able to
	# read which value is in force. staging and prod keep the template's false: switching the
	# worker on there stays an explicit operator decision.
	# One key, one reason: this is the only per-environment rewrite of this switch, and no
	# other key joins it (see docker/scripts/tests/env-defaults.test.sh).
	if [ "$ENV" = "dev" ]; then
		sed -i "s|^WORKER_ENABLED=.*|WORKER_ENABLED=true|" "$ENV_FILE"
	fi
	# Passwords come exclusively from docker/secrets/<name> files mounted at
	# /run/secrets/<name> (see entrypoint wrappers) in ALL environments. Strip
	# every password and client-secret related line so compose can never
	# interpolate a plaintext value — the wrapper is the sole password source.
	sed -i -E "/^[A-Z0-9_]*PASSWORD(_FILE)?=/d; /^[A-Z0-9_]*CLIENT_SECRET=/d" "$ENV_FILE"
	print_success "Created $ENV_FILE"
	print_warning "Please review $ENV_FILE and fill in any secrets before starting services."
fi

print_status "Using environment file: $ENV_FILE"

# Check Docker first
if ! docker info >/dev/null 2>&1; then
	print_error "Docker is not running!"
	exit 1
fi
print_success "Docker is running"

# Every bind-mounted data directory below is writable by the fixed non-root uid its image runs as,
# and that uid is a property of the image, not of the host: Docker creates a missing bind-mount
# source as root, and a directory owned by anyone else is a `permission denied` inside the container
# and a restart loop outside it -- measured for Loki as `mkdir /loki/chunks: permission denied`, and
# for Prometheus and Tempo as the same class of failure once they stopped running as root (issue
# #897).
#
# A failure here is FATAL, never a warning: the stack cannot start, the reason is buried in one
# container's log, and continuing just hides which directory is wrong. The message names both the
# directory and the command that fixes it, and a directory that is ALREADY owned by the target is a
# success -- otherwise a non-root operator could never re-run this script after following the hint.
require_dir_owner() {
	local dir="$1"
	local owner="$2"
	local current

	mkdir -p "$dir"
	current="$(stat -c '%u:%g' "$dir" 2>/dev/null || echo unknown)"
	uid="${owner%%:*}"

	# The directory's own owner is not the answer, its contents are. A service that ran as another uid
	# wrote files a shallow check never sees: measured on this stack, Tempo's data directory was already
	# owned by 10001 while 57 trace blocks inside were still root's, and the only symptom was a restart
	# loop whose logs said "permission denied" about a block nobody had heard of. `-print -quit` stops
	# at the first offender, so only the healthy case walks the tree (1,718 files on the largest of these
	# directories, under 0.01 s).
	foreign="$(find "$dir" ! -user "$uid" -print -quit 2>/dev/null || true)"

	if [ "$current" = "$owner" ] && [ -z "$foreign" ]; then
		print_success "$dir already owned by $owner"
		return 0
	fi

	if chown -R "$owner" "$dir" 2>/dev/null; then
		# Re-read after the write instead of trusting its exit status: the directory can be set while
		# an entry inside is left behind.
		foreign="$(find "$dir" ! -user "$uid" -print -quit 2>/dev/null || true)"
		if [ -z "$foreign" ]; then
			print_success "$dir owner set to $owner"
			return 0
		fi
		current="$(stat -c '%u:%g' "$dir" 2>/dev/null || echo unknown)"
	fi

	print_error "Cannot set the owner of '$dir' to $owner (it is $current)."
	if [ -n "$foreign" ]; then
		print_error "At least one entry inside is still owned by someone else: $foreign"
	fi
	print_error "The container runs as $owner and cannot write there without it, so it would restart-loop."
	print_error "Stop the stack FIRST. A container that is still running keeps writing here as its old"
	print_error "uid and recreates files this chown cannot reach, which is how a chown that looked"
	print_error "successful produced the restart loop anyway. Then, in this order:"
	print_error "  ./docker/scripts/deploy.sh <env> stop"
	print_error "  sudo chown -R $owner '$dir'"
	print_error "  ./docker/scripts/deploy.sh <env> up"
	exit 1
}

# Install reference monitoring configs (prometheus/tempo/grafana) into the
# environment's volume root if absent. Prometheus/Tempo need a config file to
# start and the grafana config dir is the provisioning/datasources mount.
# Never overwrites existing user edits.
install_monitoring_configs() {
	local vol_path="$1"
	local src="$DOCKER_DIR/config"
	local copied=0

	if [ -f "$src/prometheus/prometheus.yml" ] && [ ! -f "$vol_path/prometheus/config/prometheus.yml" ]; then
		cp "$src/prometheus/prometheus.yml" "$vol_path/prometheus/config/prometheus.yml"
		copied=$((copied + 1))
	fi
	if [ -f "$src/tempo/tempo.yml" ] && [ ! -f "$vol_path/tempo/config/tempo.yml" ]; then
		cp "$src/tempo/tempo.yml" "$vol_path/tempo/config/tempo.yml"
		copied=$((copied + 1))
	fi
	if [ -f "$src/grafana/datasources.yml" ] && [ ! -f "$vol_path/grafana/config/datasources.yml" ]; then
		cp "$src/grafana/datasources.yml" "$vol_path/grafana/config/datasources.yml"
		copied=$((copied + 1))
	fi

	if [ "$copied" -gt 0 ]; then
		print_success "Monitoring configs installed ($copied file(s))"
	else
		print_status "Monitoring configs already present — not overwritten"
	fi

	# The grafana container runs as uid 1000:1000 (docker-compose.yml pins it) and keeps its
	# SQLite database under /var/lib/grafana.
	require_dir_owner "$vol_path/grafana/data" 1000:1000

	# Loki writes under /loki (common.path_prefix) and its distroless image runs as uid 10001, so the
	# bind-mounted directory must be writable by it. `rules/` and `rules-temp/` are its rules
	# directories and have to be inside the same mount.
	mkdir -p "$vol_path/loki/data/rules" "$vol_path/loki/data/rules-temp"
	require_dir_owner "$vol_path/loki/data" 10001:10001

	# Prometheus is `nobody` (65534:65534) in the pinned image and writes its TSDB to /prometheus.
	require_dir_owner "$vol_path/prometheus/data" 65534:65534

	# Tempo's image default is 10001:10001 and it writes the local trace store to /var/tempo.
	require_dir_owner "$vol_path/tempo/data" 10001:10001
}

# Create volume directories from VOLUMES_ROOT and install reference configs
print_status "Creating volume directories..."
VOLUMES_ROOT_VAL=$(get_env_var VOLUMES_ROOT)
if [ -n "$VOLUMES_ROOT_VAL" ]; then
	# Support both relative (dev/staging) and absolute (prod) VOLUMES_ROOT values
	case "$VOLUMES_ROOT_VAL" in
		/*) vol_path="$VOLUMES_ROOT_VAL" ;;
		*)  vol_path="$DOCKER_DIR/$VOLUMES_ROOT_VAL" ;;
	esac
	mkdir -p "$vol_path"/{keycloak-postgres/data,postgres/{data,lifecontrol},redis/data,keycloak/realms,prometheus/{data,config},grafana/{data,config},tempo/{data,config},loki/{data,config}}
	print_success "Volume directories created"

	install_monitoring_configs "$vol_path"
else
	print_warning "VOLUMES_ROOT not set in $ENV_FILE — volume directories not created. Compose will fail loudly if VOLUMES_ROOT is missing."
fi

# Copy selected env file to .env (convenience for manual docker-compose usage)
cp "$ENV_FILE" "$DOCKER_DIR/.env"
print_success "Copied $ENV_FILE to $DOCKER_DIR/.env"

# ---------------------------------------------------------------------------------------------------
# The credential files' ownership: the table below is the source of truth for two things at once --
# who may read a file, and the exact owner:group mode that makes that true. `validate-env.sh` carries
# the same table and `docker/scripts/tests/secrets-ownership.test.sh` asserts the two copies and the
# `### Secretos` table in AGENTS.md against one another and against its own frozen copy, so the three
# cannot drift: a row that moves in one place fails the suite instead of silently widening a mode.
#
# Why the shape is not "one owner per file at 0400" for everything (measured, odd/tasks/
# docker-stack-hardening.md L20): three credentials have TWO non-root readers, and a 0400 file has
# exactly one. Those three are 0440 with a numeric gid and a `group_add` on the two consuming services
# in docker-compose.yml -- one copy of the value (no rotation drift), and the reader declaration sits
# next to cap_drop/read_only/user: where the next person looks. A single-reader file needs neither a
# group nor a supplementary gid: 0400 with the reading uid as owner is the narrowest mode that still
# lets that one container read it.
#
# The group is asserted even on the 0400 rows (gid 0, root) so every credential has exactly one legal
# shape -- `owner:group mode` -- and "already correct" is never a judgement call.
SECRET_OWNERSHIP_TABLE='
fiscal_csd_certificado|0400|1001:0|the api wrapper (uid 1001)
fiscal_csd_llave|0400|1001:0|the api wrapper (uid 1001)
fiscal_csd_password|0400|1001:0|the api wrapper (uid 1001)
fiscal_sf_password|0400|1001:0|the api wrapper (uid 1001)
grafana_admin_password|0400|1000:0|the grafana wrapper (uid 1000)
keycloak_admin_client_secret|0400|1001:0|the api wrapper (uid 1001)
keycloak_admin_password|0400|1000:0|the keycloak wrapper (uid 1000)
keycloak_postgres_password|0440|0:9313|the keycloak wrapper (uid 1000) and postgres-exporter-keycloak (uid 65534)
lifecontrol_app_password|0440|0:9311|the api wrapper (uid 1001) and the postgres role script (uid 999)
lifecontrol_postgres_password|0440|0:9312|the api wrapper (uid 1001) and postgres-exporter-lifecontrol (uid 65534)
redis_password|0400|1001:0|the api wrapper (uid 1001); the redis wrapper reads it as root before its drop
smtp_password|0400|1000:0|the grafana wrapper (uid 1000)
'

# secret_ownership_row <name> -- prints "mode|owner:group|reader" for a declared credential, or nothing
# and a non-zero status when the name has no row. validate-env.sh carries its own copy of this table and
# of this lookup, and the suite asserts the two copies are identical; neither script sources the other.
secret_ownership_row() {
	local name="$1" row_name row_mode row_owner row_reader
	while IFS='|' read -r row_name row_mode row_owner row_reader; do
		[ -n "$row_name" ] || continue
		if [ "$row_name" = "$name" ]; then
			printf '%s|%s|%s\n' "$row_mode" "$row_owner" "$row_reader"
			return 0
		fi
	done <<<"$SECRET_OWNERSHIP_TABLE"
	return 1
}

# file_metadata <file> -- "mode uid gid" on one line. The two spellings are the GNU and the BSD stat,
# as the mode check in validate-env.sh already did; `?` is the answer when neither can read the inode.
file_metadata() {
	local file="$1"
	stat -c '%a %u %g' "$file" 2>/dev/null || stat -f '%Lp %u %g' "$file" 2>/dev/null || printf '? ? ?\n'
}

# normalize_mode <mode> -- drops leading zeros, because `stat -c %a` prints 400 for a 0400 file and the
# table spells it 0400. Comparing the raw strings would call every correct file wrong.
normalize_mode() {
	local mode="$1"
	printf '%s\n' "${mode#"${mode%%[!0]*}"}"
}

# Every file this script could not make right is collected instead of aborting on the first one: on a
# non-root checkout the owner has to be set by root (uid 1000/1001, or root itself for the 0440 rows), so
# failing fast would cost the operator one privileged round trip PER FILE. `setup-env.sh` prints one
# copy-pasteable block for all of them at the end, in the order that works.
PENDING_SECRET_FIXES=()

# apply_secret_ownership <file> <mode> <owner:group> -- idempotent and fail-closed, the same two
# properties `require_dir_owner` gives the data directories. Already-correct is a success that never
# touches the file. Anything else is attempted once (a root run or a matching uid succeeds) and then
# RE-READ, because chown and chmod exit codes do not prove the inode ended up right; what cannot be
# applied is recorded for the single block below, never a warning that lets a broken deploy through.
apply_secret_ownership() {
	local file="$1" mode="$2" owner="$3" reader="$4"
	local current_mode current_uid current_gid wanted_uid wanted_gid
	wanted_uid="${owner%%:*}"
	wanted_gid="${owner##*:}"

	read -r current_mode current_uid current_gid <<<"$(file_metadata "$file")"
	if [ "$(normalize_mode "$current_mode")" = "$(normalize_mode "$mode")" ] &&
		[ "$current_uid:$current_gid" = "$wanted_uid:$wanted_gid" ]; then
		print_success "$(basename "$file") already $owner $mode"
		return 0
	fi

	chown "$owner" "$file" 2>/dev/null || true
	chmod "$mode" "$file" 2>/dev/null || true

	read -r current_mode current_uid current_gid <<<"$(file_metadata "$file")"
	if [ "$(normalize_mode "$current_mode")" = "$(normalize_mode "$mode")" ] &&
		[ "$current_uid:$current_gid" = "$wanted_uid:$wanted_gid" ]; then
		print_success "$(basename "$file") owner/mode set to $owner $mode"
		return 0
	fi

	print_warning "$(basename "$file") is $current_uid:$current_gid $current_mode, and has to be $owner $mode ($reader)"
	PENDING_SECRET_FIXES+=("sudo chown $owner '$file' && sudo chmod $mode '$file'")
	return 1
}

# print_pending_secret_fixes -- the ONE block that fixes everything this run could not apply, in the
# order that works, and its own copy-pasteable form. Stop first: a container that is still running keeps
# writing as its old uid, which is how the Tempo chown that looked successful produced a restart loop
# anyway (odd/tasks/docker-stack-hardening.md L19). One block, one privileged round trip.
print_pending_secret_fixes() {
	local secret_fix

	print_error "${#PENDING_SECRET_FIXES[@]} credential file(s) are not readable by the account that reads them."
	print_error "Stop the stack FIRST: a container that is still running keeps writing with its old uid, so a"
	print_error "change applied against a live stack is undone by the next write. Then run this block as it is:"
	print_error "  ./docker/scripts/deploy.sh ${ENV} stop"
	for secret_fix in "${PENDING_SECRET_FIXES[@]}"; do
		print_error "  $secret_fix"
	done
	print_error "  ./docker/scripts/deploy.sh ${ENV} up"
}

# Materialize docker/secrets/* files from templates for ALL environments.
# Passwords in every env come exclusively from these files (entrypoint wrappers
# read them at /run/secrets/<name>), so secrets must exist in dev/staging too.
print_status "Materializing docker/secrets files..."

# Ensure the directory exists (gitignored; only *.template is tracked)
mkdir -p "$DOCKER_DIR/secrets"

SECRETS_DIR="$DOCKER_DIR/secrets"

for template in "$SECRETS_DIR"/*.template; do
	[ -e "$template" ] || continue
	secret_name="$(basename "$template" .template)"
	secret_file="$SECRETS_DIR/$secret_name"

	if [ -f "$secret_file" ]; then
		print_success "Secret $secret_name already exists — keeping it"
		continue
	fi

	# Strip comment/blank lines so the materialized file holds only the value. The mode is NOT set here:
	# the ownership pass below is the only place that decides it, from the table, for every file.
	grep -v '^[[:space:]]*#' "$template" | grep -v '^[[:space:]]*$' > "$secret_file"
	if [ ! -s "$secret_file" ]; then
		print_error "Secret template $template produced an empty file — fix the template"
		exit 1
	fi
	print_success "Created $secret_name from its template"
done

print_status "Checking credential ownership..."
while IFS='|' read -r secret_name secret_mode secret_owner secret_reader; do
	[ -n "$secret_name" ] || continue
	secret_file="$SECRETS_DIR/$secret_name"

	if [ ! -f "$secret_file" ]; then
		# A credential without a .template is created by hand (docker/secrets has no template for
		# redis_password or the four fiscal ones), and absence is legal until the feature that reads it
		# is switched on. Nothing to own yet; validate-env.sh gates the ones that must exist.
		print_status "$secret_name is not present — no .template declares it, so it is created by hand"
		continue
	fi

	apply_secret_ownership "$secret_file" "$secret_mode" "$secret_owner" "$secret_reader" || true
done <<<"$SECRET_OWNERSHIP_TABLE"

if [ "${#PENDING_SECRET_FIXES[@]}" -gt 0 ]; then
	print_pending_secret_fixes
	exit 1
fi

print_success "Environment setup completed!"
print_status "Every credential file is now readable by the account that reads it, and by no other local one."
print_status "Run './docker/scripts/deploy.sh ${ENV} start' to start services"

# Show service URLs (derived from env vars)
echo ""
print_status "Service URLs:"
echo "=========================================="
print_url "API Gateway"    "$(get_env_var API_GATEWAY_PORT)"
print_url "Keycloak"       "$(get_env_var KEYCLOAK_PORT)"
print_url "Grafana"        "$(get_env_var GRAFANA_PORT)"
print_url "Prometheus"     "$(get_env_var PROMETHEUS_PORT)"
print_url "Loki"           "$(get_env_var LOKI_PORT)"
print_url "Tempo"          "$(get_env_var TEMPO_PORT)"
echo "=========================================="