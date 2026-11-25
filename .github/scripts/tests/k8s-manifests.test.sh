#!/bin/bash
# ============================================
# LifeControl - k8s manifest tests
# ============================================
# The `k8s/` tree is a local `kind` playground: nothing in this repository builds, pushes or deploys
# it. That is a recorded decision, not an oversight -- section 10 of `docker/scripts/tests/image-pins.test.sh`
# is what freezes its image table and says so -- and this suite is the other half of it: it holds the
# shape of the tree that decision describes.
#
# What it exists for, all measured on the tree before #891:
#
#   1. **A tree nobody could apply.** `README.md` documented `kubectl apply -f
#      k8s/manifests/infrastructure.yaml`, a *file* that does not exist: the tree had directories with
#      that name and no `kustomization.yaml` anywhere, so there was no entry point at all.
#
#   2. **An anonymous Grafana admin.** Two ConfigMaps set `GF_AUTH_ANONYMOUS_ENABLED: "true"` beside
#      `GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"`, which hands datasource editing to anything that reaches
#      the Service -- and a proxy-type datasource is an outbound HTTP client. It is the same mode
#      `AGENTS.md` records as removed from the compose template, for the same reason.
#
#   3. **Five usable credentials in git.** A committed `kind: Secret` object is the file that gets
#      applied by habit, and base64 is not encryption: `mysql`, `password`, `root` and `admin`
#      decoded straight out of the manifests, in two identical copies.
#
#   4. **Two copies of every workload**, near-identical and kept in sync by nothing. A fix applied to
#      one copy silently left the other stale.
#
# A reader that matched nothing would print the same green line as a reader that matched everything,
# so every checker below also runs against a fixture that must be rejected. The float and directory
# checks are the two the tree could regress into without anybody typing a credential, which is why
# they get fixtures too instead of only being asserted on the current tree.
#
# No network, no `kubectl`, no `kind`, no YAML library. `kubectl kustomize` itself is an operator step
# -- the machine this was written on has no kubectl at all -- so what this suite holds offline is that
# the kustomization exists, names every manifest the tree has, and names nothing that is missing. That
# is the half of the render criterion a static check can answer; it is not the render.
#
# Run: ./.github/scripts/tests/k8s-manifests.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"
MANIFESTS="${MANIFESTS_DIR:-$REPO_ROOT/k8s/manifests}"
SECRETS_SCRIPT="$REPO_ROOT/k8s/kind/create-secrets.sh"
README="$REPO_ROOT/README.md"
IMAGE_PINS="$REPO_ROOT/docker/scripts/tests/image-pins.test.sh"

TMP="$(mktemp -d)" || {
	printf 'FAIL: cannot create a scratch directory (TMPDIR=%s)\n' "${TMPDIR:-unset}" >&2
	exit 1
}
[ -d "$TMP" ] || {
	printf 'FAIL: the scratch directory is not usable: %s\n' "$TMP" >&2
	exit 1
}
trap 'rm -rf "$TMP"' EXIT

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

# check_rejected <label> <output>
# A checker is written to print one line per finding and nothing when the input is clean, so the same
# code serves the tree (empty output is the pass) and the fixture (empty output is the failure).
check_rejected() {
	local label="$1" output="$2"
	if [ -n "$output" ]; then
		pass "$label: the rejected fixture was caught"
	else
		fail "$label: the rejected fixture produced no finding, so this checker matches nothing"
	fi
}

check_clean() {
	local label="$1" output="$2"
	if [ -z "$output" ]; then
		pass "$label"
	else
		fail "$label"
		printf '%s\n' "$output" | sed 's/^/        /'
	fi
}

# ------------------------------------------
# Checkers. Each takes a manifests directory and prints findings.
# ------------------------------------------

# manifest_dirs <root>: the directory names directly under the manifests root.
manifest_dirs() {
	local d
	for d in "$1"/*/; do
		[ -d "$d" ] || continue
		basename "$d"
	done | sort
}

# extra_manifest_dirs <root>: any directory that is not the two the tree declares.
extra_manifest_dirs() {
	manifest_dirs "$1" | grep -vxE 'applications|infra' || true
}

# inline_secret_findings <root>: files holding a `kind: Secret` document that carries its own data.
# The document split is on `---`, because a Secret beside a ConfigMap in the same file is exactly the
# shape the tree had, and a `data:` belonging to the ConfigMap is not a finding.
inline_secret_findings() {
	local f
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort | while IFS= read -r f; do
		awk -v file="$f" '
			function flush() {
				if (is_secret && has_data) print file
				is_secret = 0
				has_data = 0
			}
			/^---[[:space:]]*$/ { flush(); next }
			/^kind:[[:space:]]*Secret[[:space:]]*$/ { is_secret = 1 }
			/^(data|stringData):[[:space:]]*$/ { has_data = 1 }
			END { flush() }
		' "$f"
	done
}

# anonymous_admin_findings <root>: a ConfigMap that sets the anonymous switch to anything but "false".
# The container's `key: GF_AUTH_ANONYMOUS_ENABLED` reference carries no trailing colon after the name,
# so it does not match, which is what lets this stay a one-line reader.
anonymous_admin_findings() {
	grep -rn 'GF_AUTH_ANONYMOUS_ENABLED:' "$1" 2>/dev/null | grep -v '"false"' || true
}

# anonymous_admin_declared <root>: whether the switch is declared at all, so that a tree with no
# Grafana config cannot pass the check above by absence.
anonymous_admin_declared() {
	grep -rn 'GF_AUTH_ANONYMOUS_ENABLED:' "$1" 2>/dev/null || true
}

# banned_keys_findings <root>: the two keys that only existed to serve the anonymous admin.
banned_keys_findings() {
	grep -rnE 'GF_AUTH_ANONYMOUS_ORG_ROLE|GF_AUTH_DISABLE_LOGIN_FORM' "$1" 2>/dev/null || true
}

# secret_producers <script>: the `<secret>.<key>` pairs the external script creates, sorted.
# Read from the script itself rather than from a second list, so the two cannot disagree.
secret_producers() {
	awk '
		/^create_secret / { name = $2; inside = 1; next }
		inside && /--from-literal=/ {
			line = $0
			sub(/.*--from-literal=/, "", line)
			sub(/=.*/, "", line)
			print name "." line
			next
		}
		inside { inside = 0 }
	' "$1" 2>/dev/null | sort -u
}

# secret_consumers <root>: the `<secret>.<key>` pairs the manifests read through `secretKeyRef`.
secret_consumers() {
	local f
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort | while IFS= read -r f; do
		awk '
			/secretKeyRef:/ { inside = 1; next }
			inside && /^[[:space:]]*key:/ {
				key = $2
				gsub(/"/, "", key)
				next
			}
			inside && /^[[:space:]]*name:/ {
				name = $2
				gsub(/"/, "", name)
				print name "." key
				inside = 0
			}
		' "$f"
	done | sort -u
}

# unproduced_secrets <root>: a consumer with no producer. An absent Secret does not fail at apply
# time -- the object is accepted and the pod schedules and then cannot start -- so this is asserted
# instead of being left to whoever runs the apply. It is the second half of S7: removing the inline
# Secret is only safe while something still creates what the containers read.
unproduced_secrets() {
	comm -13 <(secret_producers "$SECRETS_SCRIPT") <(secret_consumers "$1")
}

# readme_path_findings <readme>: a `./k8s/...` path the documented entry point names that does not
# exist, or a script it runs that is not executable. The second half is the one that failed by
# construction: the `kind` scripts were mode 644 while README invoked them as
# `./k8s/kind/create-kind-cluster.sh`, so the documented first command could not run at all.
readme_path_findings() {
	local doc="$1" path abs
	if [ ! -f "$doc" ]; then
		printf '%s\n' "README.md is missing, so the documented entry point cannot be read"
		return
	fi
	grep -oE '\./k8s/[A-Za-z0-9./_-]+' "$doc" | sort -u | while IFS= read -r path; do
		abs="$REPO_ROOT/${path#./}"
		if [ ! -e "$abs" ]; then
			printf '%s is named by README.md and does not exist\n' "$path"
			continue
		fi
		case "$path" in
		*.sh)
			[ -x "$abs" ] || printf '%s is named by README.md and is not executable\n' "$path"
			;;
		esac
	done
}

# image_refs <root>: every `image:` value, one per line, deduplicated.
image_refs() {
	grep -rhoE 'image:[[:space:]]+[^[:space:]]+' "$1" 2>/dev/null | sed -E 's/.*image:[[:space:]]+//' | sort -u
}

# floating_tag_findings <root>: a movable tag that is not the exception the pin suite already names.
# The exception is read from `image-pins.test.sh` rather than copied here, so there is one place to
# edit when it stops being true. Only the three movable names are judged here: the bare-major rule and
# the frozen table are `image-pins.test.sh` section 10, and a second, weaker copy of them would be the
# drift this suite is against.
floating_tag_findings() {
	local exception ref
	exception="$(sed -n 's/^K8S_DOCUMENTED_EXCEPTION="\(.*\)"$/\1/p' "$IMAGE_PINS" 2>/dev/null)"
	while IFS= read -r ref; do
		[ -n "$ref" ] || continue
		[ "$ref" = "$exception" ] && continue
		case "${ref##*:}" in
		latest | main | stable) printf '%s\n' "$ref" ;;
		esac
	done <<< "$(image_refs "$1")"
}

# kustomization_listed <root>: the resources the kustomization names, sorted.
kustomization_listed() {
	awk '
		/^resources:/ { inside = 1; next }
		/^[^[:space:]-]/ { inside = 0 }
		inside && /^[[:space:]]*-[[:space:]]*/ {
			sub(/^[[:space:]]*-[[:space:]]*/, "")
			sub(/[[:space:]]*$/, "")
			print
		}
	' "$1/kustomization.yaml" 2>/dev/null | sort
}

# kustomization_present <root>: every manifest file the tree actually has, relative to the root.
kustomization_present() {
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) ! -name 'kustomization.yaml' |
		sed "s|^$1/||" | sort
}

# kustomization_findings <root>: missing file, a named resource that is not there, or a manifest the
# kustomization forgot -- which is the failure that would silently leave a workload unreachable.
kustomization_findings() {
	local root="$1" listed present
	if [ ! -f "$root/kustomization.yaml" ]; then
		printf '%s\n' "kustomization.yaml is missing: the tree has no entry point"
		return
	fi
	listed="$(kustomization_listed "$root")"
	present="$(kustomization_present "$root")"
	if [ -z "$listed" ]; then
		printf '%s\n' "kustomization.yaml names no resources"
		return
	fi
	printf '%s\n' "$listed" | while IFS= read -r rel; do
		[ -n "$rel" ] || continue
		[ -f "$root/$rel" ] || printf 'kustomization.yaml names %s, which does not exist\n' "$rel"
	done
	printf '%s\n' "$present" | while IFS= read -r rel; do
		[ -n "$rel" ] || continue
		printf '%s\n' "$listed" | grep -qxF "$rel" || printf 'kustomization.yaml forgot %s\n' "$rel"
	done
}

# deployment_count <root>: Deployments across the manifests the kustomization names.
deployment_count() {
	local rel total=0 n
	while IFS= read -r rel; do
		[ -n "$rel" ] || continue
		[ -f "$1/$rel" ] || continue
		n="$(grep -cE '^kind:[[:space:]]*Deployment[[:space:]]*$' "$1/$rel" 2>/dev/null || true)"
		total=$((total + ${n:-0}))
	done <<< "$(kustomization_listed "$1")"
	printf '%s\n' "$total"
}

# ------------------------------------------
# The tree
# ------------------------------------------
echo "== 1. one manifests tree, and a kustomization that names all of it"

if [ ! -d "$MANIFESTS" ]; then
	fail "the manifests directory is missing: $MANIFESTS"
else
	check_clean "only applications/ and infra/ exist under k8s/manifests" "$(extra_manifest_dirs "$MANIFESTS")"

	dirs="$(manifest_dirs "$MANIFESTS" | tr '\n' ' ' | sed 's/ $//')"
	if [ "$dirs" = "applications infra" ]; then
		pass "the tree declares exactly two manifest directories: $dirs"
	else
		fail "the tree's manifest directories are '$dirs', and the frozen set is 'applications infra'"
	fi

	check_clean "kustomization.yaml names every manifest and only manifests that exist" \
		"$(kustomization_findings "$MANIFESTS")"

	deployments="$(deployment_count "$MANIFESTS")"
	if [ "$deployments" = "8" ]; then
		pass "the kustomization renders 8 Deployments (7 infra + api-gateway)"
	else
		fail "the kustomization renders $deployments Deployments, and the frozen count is 8"
	fi
fi

echo
echo "== 2. no credential is committed"

if [ ! -d "$MANIFESTS" ]; then
	fail "cannot check for inline Secrets: no manifests directory"
else
	check_clean "no kind: Secret carries its own data or stringData" "$(inline_secret_findings "$MANIFESTS")"

	declared="$(anonymous_admin_declared "$MANIFESTS")"
	if [ -n "$declared" ]; then
		pass "GF_AUTH_ANONYMOUS_ENABLED is declared, so the switch below is not passing by absence"
	else
		fail "GF_AUTH_ANONYMOUS_ENABLED is declared nowhere: a tree with no Grafana config cannot pass this check by omission"
	fi
	check_clean "GF_AUTH_ANONYMOUS_ENABLED is \"false\" wherever it is set" \
		"$(anonymous_admin_findings "$MANIFESTS")"
	check_clean "GF_AUTH_ANONYMOUS_ORG_ROLE and GF_AUTH_DISABLE_LOGIN_FORM are gone" \
		"$(banned_keys_findings "$MANIFESTS")"

	produced="$(secret_producers "$SECRETS_SCRIPT")"
	produced_count="$(printf '%s\n' "$produced" | grep -c . || true)"
	if [ "${produced_count:-0}" = "5" ]; then
		pass "create-secrets.sh produces the five Secret/key pairs the manifests read"
	else
		fail "create-secrets.sh produces ${produced_count:-0} pairs, and the manifests read five"
	fi
	check_clean "every secretKeyRef names a Secret and a key that create-secrets.sh creates" \
		"$(unproduced_secrets "$MANIFESTS")"
fi

echo
echo "== 3. the documented entry point names things that exist"

check_clean "every ./k8s path README.md names exists, and every script it runs is executable" \
	"$(readme_path_findings "$README")"

echo
echo "== 4. no image floats outside the declared exception"

if [ ! -f "$IMAGE_PINS" ]; then
	fail "cannot read the documented exception: $IMAGE_PINS is missing"
else
	exception="$(sed -n 's/^K8S_DOCUMENTED_EXCEPTION="\(.*\)"$/\1/p' "$IMAGE_PINS")"
	if [ -n "$exception" ]; then
		pass "the documented exception was read from image-pins.test.sh: $exception"
	else
		fail "image-pins.test.sh declares no K8S_DOCUMENTED_EXCEPTION, so this checker has nothing to allow"
	fi
	check_clean "every image reference under k8s/manifests is pinned or the named exception" \
		"$(floating_tag_findings "$MANIFESTS")"
fi

# ------------------------------------------
# The fixtures. Each one is the tree with exactly one rule broken, so a checker that stopped working
# fails here instead of printing the same green line as a clean read.
# ------------------------------------------
echo
echo "== 5. every checker rejects the input it exists for"

if [ -d "$MANIFESTS" ]; then
	cp -R "$MANIFESTS" "$TMP/fx-inline"
	printf '\n---\napiVersion: v1\nkind: Secret\nmetadata:\n  name: leaked\ndata:\n  password: djE=\n' \
		>>"$TMP/fx-inline/infra/loki.yml"
	check_rejected "inline Secret" "$(inline_secret_findings "$TMP/fx-inline")"

	cp -R "$MANIFESTS" "$TMP/fx-admin"
	sed -i 's|GF_AUTH_ANONYMOUS_ENABLED: "false"|GF_AUTH_ANONYMOUS_ENABLED: "true"|' \
		"$TMP/fx-admin/infra/grafana.yml"
	check_rejected "anonymous Grafana admin" "$(anonymous_admin_findings "$TMP/fx-admin")"

	cp -R "$MANIFESTS" "$TMP/fx-orgrole"
	printf '\n    GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"\n' >>"$TMP/fx-orgrole/infra/grafana.yml"
	check_rejected "anonymous org role" "$(banned_keys_findings "$TMP/fx-orgrole")"

	cp -R "$MANIFESTS" "$TMP/fx-dirs"
	mkdir -p "$TMP/fx-dirs/infrastructure"
	check_rejected "a second manifests directory" "$(extra_manifest_dirs "$TMP/fx-dirs")"

	cp -R "$MANIFESTS" "$TMP/fx-float"
	sed -i 's|grafana/grafana:10.1.0|grafana/grafana:latest|' "$TMP/fx-float/infra/grafana.yml"
	check_rejected "a floating image tag" "$(floating_tag_findings "$TMP/fx-float")"

	cp -R "$MANIFESTS" "$TMP/fx-kustomize"
	sed -i '/infra\/tempo.yml/d' "$TMP/fx-kustomize/kustomization.yaml"
	check_rejected "a manifest the kustomization forgot" "$(kustomization_findings "$TMP/fx-kustomize")"

	cp -R "$MANIFESTS" "$TMP/fx-producer"
	printf '            - name: X\n              valueFrom:\n                secretKeyRef:\n                  key: MISSING\n                  name: not-created\n' \
		>>"$TMP/fx-producer/infra/loki.yml"
	check_rejected "a secretKeyRef with no producer" "$(unproduced_secrets "$TMP/fx-producer")"

	if [ -f "$README" ]; then
		cp "$README" "$TMP/fx-readme.md"
		printf '\n```bash\n./k8s/kind/does-not-exist.sh\n```\n' >>"$TMP/fx-readme.md"
		check_rejected "a README path that does not exist" "$(readme_path_findings "$TMP/fx-readme.md")"
	else
		fail "cannot build the README fixture: $README is missing"
	fi
else
	fail "cannot build the fixtures: no manifests directory"
fi

# ------------------------------------------
echo
echo "--------------------------------------------"
printf 'k8s manifest tests: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	echo "RESULT: FAILED"
	exit 1
fi
echo "RESULT: OK"
exit 0
