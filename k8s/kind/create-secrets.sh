#!/usr/bin/env bash
# ============================================
# LifeControl - create the k8s Secrets from outside the repository
# ============================================
# The manifests under `k8s/manifests/` read three Secrets through `secretKeyRef` and no longer contain
# them. This script is the replacement, and it is the only thing that writes those values.
#
# Why they are not committed: a `kind: Secret` object in git is the file that gets applied by habit,
# and base64 is not encryption. Until #891 the tree shipped `mysql`, `password`, `root` and `admin` as
# decoded-straight-out credentials, in two identical copies.
#
# Every password is generated here, at run time. Nothing reads a credential from the repository and
# nothing writes one to it, so a fresh clone has no credential to leak.
#
# Usage:
#   ./k8s/kind/create-secrets.sh [namespace]      # defaults to the namespace the tree declares
#
# Re-running is safe and changes nothing observable: an existing Secret is left exactly as it is.
# Rotation is deliberately not offered as a flag. Regenerating over a live cluster would disconnect
# the pods that already hold the old value while they keep running, so the destructive step is one a
# human types on purpose -- delete the Secret, then run this again -- instead of one this script takes
# on their behalf.
#
# Run this before `kubectl apply -k k8s/manifests`, not after: the objects the containers read have to
# exist before the pods that read them start.
#
# That ordering is also why this script creates the namespace before it writes anything into it. The
# manifests declare their namespace and this runs first, so the object the apply would create does not
# exist yet; a Secret created in some other namespace is a Secret no pod of this tree can read.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KUSTOMIZATION="$SCRIPT_DIR/../manifests/kustomization.yaml"

# declared_namespace: the namespace the tree declares, read from the kustomization's `namespace:`
# transform instead of written here. A literal in this file would be the third copy of the name and the
# one that keeps pointing at the old namespace after a rename -- silently: the Secrets are created, the
# apply succeeds, and no pod can read them.
declared_namespace() {
	[ -f "$KUSTOMIZATION" ] || return 0
	awk '
		/^namespace:[[:space:]]*/ {
			sub(/^namespace:[[:space:]]*/, "")
			sub(/[[:space:]]*$/, "")
			print
			exit
		}
	' "$KUSTOMIZATION"
}

usage() {
	awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
-h | --help | help)
	usage
	exit 0
	;;
esac

# The namespace is the one the tree declares. Read, never written here: the kustomization's
# `namespace:` transform and the Namespace object are the two declarations, and a third copy in this
# file is the one that would keep writing Secrets into the old namespace after a rename.
NAMESPACE="${1:-$(declared_namespace)}"
if [ -z "$NAMESPACE" ]; then
	echo "the namespace could not be read from $KUSTOMIZATION" >&2
	echo "pass it explicitly: ./k8s/kind/create-secrets.sh <namespace>" >&2
	exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
	echo "kubectl is required and was not found on PATH" >&2
	echo "this script creates objects in a cluster; without kubectl there is nothing to create them in" >&2
	exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
	echo "openssl is required to generate the password material and was not found on PATH" >&2
	exit 1
fi

# 16 random bytes, hex-encoded: 32 characters drawn from [0-9a-f]. Hex on purpose -- base64 can emit
# `+`, `/` and `=`, which need quoting in a shell argument and are not safe unquoted inside a YAML
# block, and a password that breaks the manifest it is pasted into is a worse problem than a shorter
# alphabet.
generate_password() {
	openssl rand -hex 16
}

# create_namespace: the namespace the tree declares, created before the Secrets that live in it.
# Skipped when it already exists, so a second run against a cluster that has already been applied does
# not fail on the object the apply created.
create_namespace() {
	if kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
		echo "namespace/$NAMESPACE already exists: left untouched"
		return 0
	fi

	kubectl create namespace "$NAMESPACE"
	echo "namespace/$NAMESPACE created"
}

# create_secret <name> [--from-literal=key=value]...
# Skips the whole object when it already exists, so a second run is a no-op.
create_secret() {
	local name="$1"
	shift

	if kubectl get secret "$name" --namespace "$NAMESPACE" >/dev/null 2>&1; then
		echo "secret/$name already exists in namespace $NAMESPACE: left untouched"
		return 0
	fi

	kubectl create secret generic "$name" \
		--namespace "$NAMESPACE" \
		"$@" \
		--dry-run=client \
		--output yaml |
		kubectl apply --namespace "$NAMESPACE" --filename -
	echo "secret/$name created in namespace $NAMESPACE"
}

create_namespace

echo "creating the Secrets the k8s manifests read, in namespace $NAMESPACE"

# `mysql-secrets` is read by `manifests/infra/mysql.yml`.
create_secret mysql-secrets \
	--from-literal=mysql_root_password="$(generate_password)"

# `keycloak-mysql-secrets` is read by `manifests/infra/keycloak-mysql.yml`.
create_secret keycloak-mysql-secrets \
	--from-literal=MYSQL_PASSWORD="$(generate_password)" \
	--from-literal=MYSQL_ROOT_PASSWORD="$(generate_password)"

# `keycloak-secrets` is read by `manifests/infra/keycloak.yml`. Only the password is generated: the
# admin username is `admin` in the `keycloak-realm-config` ConfigMap, and a username is not a secret.
create_secret keycloak-secrets \
	--from-literal=DB_PASSWORD="$(generate_password)" \
	--from-literal=KEYCLOAK_ADMIN_PASSWORD="$(generate_password)"

echo "done: no value was written to disk, and none exists in the repository"
