#!/bin/bash
# ============================================
# LifeControl - infrastructure image pin tests
# ============================================
# The images of the infrastructure stack are pinned in exactly two versioned places:
#
#   - docker/.env.template (the only tracked env file; .env, .env.dev, .env.staging and
#     .env.prod are gitignored materializations, so they are NOT a source of truth)
#   - the ${VAR:-default} fallbacks in docker/docker-compose*.yml
#
# When they drift, one environment silently runs a different image from the other, which is
# how a security bump disappears without anyone noticing. The expected set below is a
# deliberate freeze: bumping an image means editing this table too, in the same commit, so
# the version change is always a reviewed decision instead of an unnoticed drift.
#
# It also pins the two constraints that this stack learned the hard way:
#
#   1. Loki >= 3.6.0, Tempo >= 3.0 and redis_exporter are distroless images (gcr.io/distroless/
#      static): no shell, no wget. A `CMD wget --spider ...` healthcheck inside those containers can
#      never pass again, so the readiness signal has to be measured from outside. Measured on the
#      pinned tags: `/bin/sh` is absent from oliver006/redis_exporter and from Loki/Tempo.
#   2. Loki writes under /loki (common.path_prefix of its bundled config), so the bind mount
#      must target /loki and setup-env.sh must make that directory writable by uid 10001,
#      the non-root user of the distroless image. Mounting anything else leaves every log in
#      the container's ephemeral layer.
#
# No Docker daemon, no network, no Gradle: this is a static consistency check.
#
# Run: ./docker/scripts/tests/image-pins.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
ENV_TEMPLATE="$DOCKER_DIR/.env.template"
DEPLOY_SH="$DOCKER_DIR/scripts/deploy.sh"
SETUP_ENV_SH="$DOCKER_DIR/scripts/setup-env.sh"

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

# check_equal <description> <expected> <actual>
check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

# read_env_var <file> <name>
read_env_var() {
	sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -1
}

# Every default of ${VAR:-value} in the compose files, one per line.
# The pattern keeps the `$` and the `{` separate (`\$` then `{`): a `\{` in a sed BRE opens an
# interval expression, so the substitution silently matches nothing.
compose_defaults_for() {
	sed -n 's/.*\${'"$1"':-\([^}]*\)}.*/\1/p' "$DOCKER_DIR"/docker-compose*.yml 2>/dev/null
}

# service_block <compose file> <service name>
service_block() {
	awk -v svc="$2" '
		$0 == "  " svc ":" { inside = 1; print; next }
		inside && /^  [a-zA-Z0-9_-]+:$/ { inside = 0 }
		inside { print }
	' "$1"
}

# ------------------------------------------
# 1. The tracked template declares every pin at the frozen version.
# ------------------------------------------
EXPECTED_PINS="POSTGRES_IMAGE=postgres:18.6
KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.8.1
LOKI_IMAGE=grafana/loki:3.7.8
PROMETHEUS_IMAGE=prom/prometheus:v3.15.0
TEMPO_IMAGE=grafana/tempo:3.1.0
GRAFANA_IMAGE=grafana/grafana:13.2.4
REDIS_IMAGE=redis:8.10-alpine
MAILPIT_IMAGE=axllent/mailpit:v1.31.6
POSTGRES_EXPORTER_IMAGE=prometheuscommunity/postgres-exporter:v0.19.0
REDIS_EXPORTER_IMAGE=oliver006/redis_exporter:v1.69.0"

if [ ! -f "$ENV_TEMPLATE" ]; then
	fail "missing docker/.env.template"
else
	while IFS='=' read -r name expected; do
		[ -n "$name" ] || continue
		check_equal ".env.template $name" "$expected" "$(read_env_var "$ENV_TEMPLATE" "$name")"
	done <<< "$EXPECTED_PINS"
fi

# ------------------------------------------
# 2. Every compose default agrees with the template: a fallback that lags is an environment
#    that runs the old image whenever the variable is not materialized in its env file.
# ------------------------------------------
while IFS='=' read -r name expected; do
	[ -n "$name" ] || continue
	defaults="$(compose_defaults_for "$name")"
	count="$(printf '%s\n' "$defaults" | sed '/^$/d' | wc -l | tr -d ' ')"
	if [ "$count" -eq 0 ]; then
		fail "no compose default found for $name (expected at least one)"
		continue
	fi
	index=0
	while IFS= read -r value; do
		[ -n "$value" ] || continue
		index=$((index + 1))
		check_equal "compose default #$index for $name" "$expected" "$value"
	done <<< "$defaults"
done <<< "$EXPECTED_PINS"

# LIFECONTROL_POSTGRES_IMAGE is a prod-only variable with no template entry: it must still
# name the same Postgres as POSTGRES_IMAGE.
canonical_postgres="$(read_env_var "$ENV_TEMPLATE" POSTGRES_IMAGE)"
lc_defaults="$(compose_defaults_for LIFECONTROL_POSTGRES_IMAGE)"
if [ -z "$lc_defaults" ]; then
	fail "no compose default found for LIFECONTROL_POSTGRES_IMAGE (expected at least one)"
else
	index=0
	while IFS= read -r value; do
		[ -n "$value" ] || continue
		index=$((index + 1))
		check_equal "compose default #$index for LIFECONTROL_POSTGRES_IMAGE" "$canonical_postgres" "$value"
	done <<< "$lc_defaults"
fi

# ------------------------------------------
# 3. No floating tag on the pinned services: `latest` or a bare major would defeat the freeze
#    and make two environments disagree while both look "correct". A minor line with a variant
#    suffix (`8.10-alpine`) is a pin; a bare `18` or `3` is not.
# ------------------------------------------
while IFS='=' read -r name value; do
	[ -n "$name" ] || continue
	case "$value" in
	*:*)
		tag="${value##*:}"
		case "$tag" in
		latest)
			fail "$name is pinned to 'latest' ($value)"
			;;
		*.*)
			pass "$name is pinned to a version ($tag)"
			;;
		*)
			fail "$name uses a bare-major tag, which floats ($value)"
			;;
		esac
		;;
	*)
		fail "$name has no tag at all, so it resolves to 'latest' ($value)"
		;;
	esac
done <<< "$EXPECTED_PINS"

# ------------------------------------------
# 4. Loki, Tempo and redis_exporter must not declare a healthcheck at all: their images are
#    distroless, so the only binary inside is the service itself and any in-container probe reports
#    a healthy service as unhealthy forever. Readiness is measured from outside (section 5).
#    postgres-exporter is deliberately absent from this list: its image does ship a shell, so a
#    probe there is possible -- it is simply not needed, because nothing gates on that container and
#    the scrape reports it as `up == 0` when it stops answering.
# ------------------------------------------
for compose in "$DOCKER_DIR"/docker-compose*.yml; do
	[ -f "$compose" ] || continue
	for svc in loki tempo redis-exporter; do
		block="$(service_block "$compose" "$svc")"
		if [ -z "$block" ]; then
			continue
		fi
		if printf '%s\n' "$block" | grep -qE '^[[:space:]]*healthcheck:'; then
			fail "$(basename "$compose") $svc declares a healthcheck, but its image is distroless (no shell, no wget): it can never pass"
		else
			pass "$(basename "$compose") $svc declares no in-image healthcheck"
		fi
	done
done

# ------------------------------------------
# 5. ... which is only acceptable because deploy.sh measures that readiness from the host, on
#    the published port. Without these two probes the redesign would just delete the signal.
# ------------------------------------------
if [ ! -f "$DEPLOY_SH" ]; then
	fail "missing docker/scripts/deploy.sh"
else
	for svc in LOKI TEMPO; do
		if grep -q "get_env_var ${svc}_PORT)/ready" "$DEPLOY_SH"; then
			pass "deploy.sh probes $svc readiness at /ready on the published port"
		else
			fail "deploy.sh does not probe $svc readiness (expected a /ready probe built from ${svc}_PORT)"
		fi
	done
fi

# ------------------------------------------
# 6. Loki's bind mount targets /loki, and setup-env.sh prepares that directory for the image's
#    uid. A mount at any other path silently discards every log on container recreation.
# ------------------------------------------
for compose in "$DOCKER_DIR"/docker-compose*.yml; do
	[ -f "$compose" ] || continue
	block="$(service_block "$compose" loki)"
	[ -n "$block" ] || continue
	if printf '%s\n' "$block" | grep -qE ':[[:space:]]*/loki/data$'; then
		fail "$(basename "$compose") mounts Loki data at /loki/data, but its config writes to /loki"
	elif printf '%s\n' "$block" | grep -qE ':[[:space:]]*/loki$'; then
		pass "$(basename "$compose") mounts Loki data at /loki"
	else
		fail "$(basename "$compose") has no Loki data mount targeting /loki"
	fi
done

if [ ! -f "$SETUP_ENV_SH" ]; then
	fail "missing docker/scripts/setup-env.sh"
else
	if grep -q '10001:10001' "$SETUP_ENV_SH"; then
		pass "setup-env.sh prepares the Loki data dir for uid 10001"
	else
		fail "setup-env.sh does not chown the Loki data dir to 10001:10001 (the distroless image runs as uid 10001)"
	fi
	if grep -q 'rules-temp' "$SETUP_ENV_SH"; then
		pass "setup-env.sh creates the Loki rules directories"
	else
		fail "setup-env.sh does not create the Loki rules/rules-temp directories (Loki needs them under the mounted /loki)"
	fi
fi

# ------------------------------------------
# 7. Every materialized environment file agrees with the template on every image pin.
#    Checking the template against the compose defaults is not enough: `.env.dev`,
#    `.env.staging` and `.env.prod` are created once by setup-env.sh and never reconciled,
#    so a pin that moves in the template leaves the old image behind in a file that still
#    reads as correct. That is how `grafana/grafana:10.2.0` survived in staging and prod
#    while the template and every compose default said `13.2.4`, one image serving two
#    versions of the same stack with no signal anywhere.
#
#    Only `*_IMAGE` keys are compared. Ports and memory limits legitimately differ per
#    environment, and a parity check that knows no exceptions is a parity check that gets
#    switched off the first time it is right about a difference someone wanted.
# ------------------------------------------
template_image_keys() {
	grep -E '^[A-Za-z_][A-Za-z0-9_]*_IMAGE=' "$ENV_TEMPLATE" 2>/dev/null | cut -d= -f1 | sort -u
}

for env_file in "$DOCKER_DIR/.env.dev" "$DOCKER_DIR/.env.staging" "$DOCKER_DIR/.env.prod"; do
	env_name="$(basename "$env_file")"
	if [ ! -f "$env_file" ]; then
		# A gitignored materialization, absent on a fresh clone and in CI. Reported as a
		# note and NOT counted: a pass would claim a comparison that never happened.
		printf 'NOTE: docker/%s is not materialized, its image pins are not compared\n' "$env_name"
		continue
	fi
	keys_compared=0
	while IFS= read -r key; do
		[ -n "$key" ] || continue
		# An env file that does not mention the key at all runs the compose default, and
		# section 2 already pins every compose default against the template.
		grep -qE "^$key=" "$env_file" || continue
		keys_compared=$((keys_compared + 1))
		check_equal "docker/$env_name $key agrees with .env.template" \
			"$(read_env_var "$ENV_TEMPLATE" "$key")" "$(read_env_var "$env_file" "$key")"
	done <<< "$(template_image_keys)"
	if [ "$keys_compared" -eq 0 ]; then
		# Declaring none of the pins is not agreement either: it means the reader stopped
		# matching, which would turn this whole section into a silent no-op.
		fail "docker/$env_name declares none of the image pins .env.template declares, so nothing was compared"
	else
		pass "docker/$env_name pins $keys_compared image(s), each compared against .env.template"
	fi
done

# ------------------------------------------
# 8. The template declares no Grafana auth key the grafana service never forwards.
#    Compose hands a container only the variables its own `environment:` block names, so a
#    `GF_AUTH_*` key that no block forwards changes nothing in the running Grafana: it only
#    makes the file read as configured. Measured on 2026-10-24, the template declared all
#    three while `/api/dashboards/home` answered 401 and `/login` answered 200 — login form
#    enabled, anonymous access disabled, the opposite of what the file said. An absent key
#    is at least honest about that, which is why an unreachable key is worse than an absent
#    one. The stack runs a real login from docker/secrets/grafana_admin_password, so these
#    are removed rather than forwarded: an anonymous admin Grafana on a published port must
#    not be one `.env` edit away from being switched on by accident.
# ------------------------------------------
for key in GF_AUTH_ANONYMOUS_ENABLED GF_AUTH_ANONYMOUS_ORG_ROLE GF_AUTH_DISABLE_LOGIN_FORM; do
	if grep -qE "^$key=" "$ENV_TEMPLATE" 2>/dev/null; then
		fail ".env.template declares $key, which the grafana service never forwards: an unreachable key is worse than an absent one (it reads as configured while the container runs the opposite)"
	else
		pass ".env.template declares no $key (Grafana runs a real login, not an env-file setting)"
	fi
done

# ------------------------------------------
# 9. The Dockerfile base images are pins too. They were the ones no suite read: the template and the
#    compose files agreed with each other while `eclipse-temurin:21-jre-alpine`, `node:24.15-alpine`
#    and `nginx:alpine` lived only inside a `FROM` line, so changing the image a build starts from was
#    a one-word edit nobody reviewed and no check compared against anything.
#
#    Three things have to agree, in this order: the template declares the key, the compose files
#    forward it as a build arg with the same default, and the Dockerfile consumes the ARG in its
#    `FROM` instead of naming an image. The last one is asserted as "no literal in a FROM" and not as
#    "the literal equals the template": a Dockerfile that writes the same value by hand still passes
#    a comparison, and still drifts the day one of the two is bumped.
# ------------------------------------------
EXPECTED_BASE_IMAGES="JAVA_BASE_IMAGE=eclipse-temurin:21-jre-alpine
NODE_BASE_IMAGE=node:24.15-alpine
NGINX_BASE_IMAGE=nginx:alpine"

JAVA_DOCKERFILES="$REPO_ROOT/api-gateway/Dockerfile $REPO_ROOT/life-control-api/Dockerfile"
ALL_DOCKERFILES="$JAVA_DOCKERFILES $REPO_ROOT/life-control-app-angular/Dockerfile"
PIN_SOURCE_LABEL="$(basename "$ENV_TEMPLATE")"

# dockerfile_from_refs <file>: the image token of every FROM line, with any "AS <stage>" removed.
dockerfile_from_refs() {
	sed -nE 's/^FROM[[:space:]]+([^[:space:]]+).*/\1/p' "$1" 2>/dev/null
}

# dockerfile_arg_default <file> <arg>: the default of "ARG <arg>=<value>", or nothing.
dockerfile_arg_default() {
	sed -nE "s/^ARG[[:space:]]+$2=(.*)$/\1/p" "$1" 2>/dev/null | tail -1
}

while IFS='=' read -r name expected; do
	[ -n "$name" ] || continue
	check_equal "$PIN_SOURCE_LABEL $name" "$expected" "$(read_env_var "$ENV_TEMPLATE" "$name")"

	defaults="$(compose_defaults_for "$name")"
	count="$(printf '%s\n' "$defaults" | sed '/^$/d' | wc -l | tr -d ' ')"
	if [ "$count" -eq 0 ]; then
		fail "no compose build arg found for $name (expected at least one, so the image the stack builds is the pinned one)"
	else
		index=0
		while IFS= read -r value; do
			[ -n "$value" ] || continue
			index=$((index + 1))
			check_equal "compose build arg #$index for $name" "$expected" "$value"
		done <<< "$defaults"
	fi

	refs=0
	for dockerfile in $ALL_DOCKERFILES; do
		[ -f "$dockerfile" ] || continue
		label="$(basename "$(dirname "$dockerfile")")/Dockerfile"
		arg_default="$(dockerfile_arg_default "$dockerfile" "$name")"
		if [ -n "$arg_default" ]; then
			check_equal "$label ARG $name default" "$expected" "$arg_default"
		fi
		while IFS= read -r ref; do
			[ -n "$ref" ] || continue
			if [ "$ref" = "\${$name}" ]; then
				refs=$((refs + 1))
			fi
		done <<< "$(dockerfile_from_refs "$dockerfile")"
	done
	if [ "$refs" -eq 0 ]; then
		fail "no Dockerfile has a FROM \${$name}, so the pinned base image is declared somewhere nothing reads"
	else
		pass "$refs Dockerfile FROM line(s) consume \${$name}"
	fi
done <<< "$EXPECTED_BASE_IMAGES"

# A FROM that names an image instead of an ARG is the pin this section exists to remove, so it fails
# even when the literal happens to equal the template today.
for dockerfile in $ALL_DOCKERFILES; do
	if [ ! -f "$dockerfile" ]; then
		fail "missing $(basename "$(dirname "$dockerfile")")/Dockerfile"
		continue
	fi
	label="$(basename "$(dirname "$dockerfile")")/Dockerfile"
	while IFS= read -r ref; do
		[ -n "$ref" ] || continue
		case "$ref" in
		'${'*'_BASE_IMAGE}')
			pass "$label FROM names a template-managed base image ($ref)"
			;;
		*)
			fail "$label FROM writes its own base image ($ref): declare it in the docker env template and reference the ARG"
			;;
		esac
	done <<< "$(dockerfile_from_refs "$dockerfile")"
done

# The Node base image and .nvmrc name the same line of the same runtime: two sources that can
# disagree are two sources, and this makes them one.
NVMRC_FILE="$REPO_ROOT/life-control-app-angular/.nvmrc"
if [ -f "$NVMRC_FILE" ]; then
	nvmrc="$(tr -d '[:space:]' < "$NVMRC_FILE")"
	node_pin="$(read_env_var "$ENV_TEMPLATE" NODE_BASE_IMAGE)"
	nvmrc_line="$(printf '%s' "$nvmrc" | cut -d. -f1,2)"
	case "$node_pin" in
	"node:$nvmrc_line"-*)
		pass "NODE_BASE_IMAGE agrees with .nvmrc ($nvmrc)"
		;;
	*)
		fail "NODE_BASE_IMAGE is '$node_pin' but .nvmrc pins $nvmrc: two sources for the Node line the app is typed for"
		;;
	esac
else
	fail "missing life-control-app-angular/.nvmrc, so NODE_BASE_IMAGE has nothing to agree with"
fi

# ------------------------------------------
# 10. Every image reference under k8s/ is a pin or a declared exception.
#     k8s/ is a local playground: nothing in this repository builds, pushes or deploys it, and the
#     kind scripts only pull the same references into a local cluster. That is why it cannot read the
#     docker pin source -- it runs MySQL, Kafka and an external gateway image the stack never runs --
#     and why the frozen table below is its pin source instead. The exception is named, with its
#     reason, rather than filtered out by a pattern: an image with a single published tag cannot be
#     materialized, and hiding it in a regex is how the next floating tag goes unnoticed too.
# ------------------------------------------
EXPECTED_K8S_IMAGES="grafana/grafana:10.1.0
grafana/loki:3.7.8
grafana/tempo:2.2.2
mysql:8.3.0
prom/prometheus:v2.46.0
quay.io/keycloak/keycloak:24.0.1
saiupadhyayula007/new-api-gateway:latest"

# The one documented exception: a third-party image this repository does not build, published under a
# single tag (Docker Hub, last updated 2024-08-03), inside a tutorial manifest tree that no deploy here
# reads.
K8S_DOCUMENTED_EXCEPTION="saiupadhyayula007/new-api-gateway:latest"

k8s_image_refs() {
	{
		grep -rhoE 'image:[[:space:]]+[^[:space:]]+' "$REPO_ROOT/k8s" 2>/dev/null | sed -E 's/.*image:[[:space:]]+//'
		grep -rhoE 'docker pull[[:space:]]+[^[:space:]]+|kind load docker-image -n [A-Za-z0-9_-]+[[:space:]]+[^[:space:]]+' "$REPO_ROOT/k8s" 2>/dev/null | awk '{print $NF}'
	} | sed '/^$/d' | sort -u
}

actual_k8s="$(k8s_image_refs)"
if [ -z "$actual_k8s" ]; then
	fail "no image reference matched under k8s/, so the reader is not reading the manifests"
else
	while IFS= read -r ref; do
		[ -n "$ref" ] || continue
		if printf '%s\n' "$EXPECTED_K8S_IMAGES" | grep -qxF "$ref"; then
			pass "k8s/ reference $ref is the frozen pin"
		else
			fail "k8s/ reference $ref is not in the frozen k8s table: add it here deliberately, with the version"
		fi
		case "$ref" in
		"$K8S_DOCUMENTED_EXCEPTION")
			pass "k8s/ reference $ref is the documented dev-only exception"
			continue
			;;
		esac
		case "$ref" in
		*:latest | *:stable | *:main)
			fail "k8s/ reference $ref floats on a movable tag"
			;;
		*)
			tag="${ref##*:}"
			case "$tag" in
			*.*) : ;;
			*) fail "k8s/ reference $ref uses a bare-major tag, which floats" ;;
			esac
			;;
		esac
	done <<< "$actual_k8s"

	missing_k8s=0
	while IFS= read -r ref; do
		[ -n "$ref" ] || continue
		if ! printf '%s\n' "$actual_k8s" | grep -qxF "$ref"; then
			missing_k8s=$((missing_k8s + 1))
			fail "the frozen k8s table names $ref but no manifest uses it any more: remove it from the table"
		fi
	done <<< "$EXPECTED_K8S_IMAGES"
	if [ "$missing_k8s" -eq 0 ]; then
		pass "every reference in the frozen k8s table is still used by the tree"
	fi
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'image-pins: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
