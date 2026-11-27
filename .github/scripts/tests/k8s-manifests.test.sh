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
#   5. **No namespace and no boundary.** Every object landed in the cluster's `default`, seven DNS
#      strings spelled `default` out by hand, and any pod could reach any other pod -- MySQL
#      included. The tree now declares its namespace once and denies egress and ingress by default;
#      what this suite holds is that the declaration is single-sourced and that every allow the
#      policy file writes exists in both directions, because a NetworkPolicy that allows one side
#      alone allows nothing at all and fails as a broken pod rather than as a bad policy.
#
#   6. **A workload wired to a database that does not declare its database.** Keycloak's ConfigMap
#      said `DB_ADDR: "mysql"`, and that Service did exist -- so a check that only looked for the
#      Service called it clean. The MySQL behind it creates `order_service` and `inventory_service`,
#      and the database Keycloak asks for is `keycloak`. Nothing fails at apply time: the pod
#      schedules, Keycloak connects, and the database it needs is not there.
#
# A reader that matched nothing would print the same green line as a reader that matched everything,
# so every checker below also runs against a fixture that must be rejected. The float and directory
# checks are the two the tree could regress into without anybody typing a credential, which is why
# they get fixtures too instead of only being asserted on the current tree.
#
# No network, no `kubectl`, no `kind`, no YAML library. `kubectl kustomize` itself is an operator step
# -- the machine this was written on has no kubectl at all -- so what this suite holds offline is that
# the kustomization exists, names every manifest the tree has, and names nothing that is missing, that
# the namespace is declared by one object and one transform and by nothing else, and that the
# network policy file is symmetric and default-deny. That is the half of the render criterion a static
# check can answer; it is not the render, and it is not a cluster.
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

# strip_comments <path>: the script with every comment removed, lines and lines-in-place preserved.
# A checker that greps for a command has to read the commands: a sentence about `docker run ...
# --install arm64` on its own line satisfies the same regex as the command, and the fixture that only
# deletes a line cannot see that difference (it was measured: a script whose emulator call was
# commented out still printed PASS).
strip_comments() {
	sed -E 's/(^|[[:space:]])#.*$//' "$1" 2>/dev/null
}

# arm64_emulation_findings <cluster_script> <gateway_manifest>: the cluster entry point registers the
# arm64 emulator the gateway image needs, reads the registration back, and does both before the
# cluster exists -- and the manifest says at the image why a third-party image published for one
# architecture is still the pinned one. Docker Hub publishes `saiupadhyayula007/new-api-gateway` for
# `linux/arm64` only, a kind node shares the host kernel, and until `binfmt_misc` names the emulator the
# pod dies with `exec /cnb/process/web: exec format error` on an amd64 host: neither a securityContext
# nor a tag can fix a missing architecture (issue #1056). The registration is host-level, so the entry
# point is the only place in the tree that can hold it, which is why it is asserted instead of trusted.
arm64_emulation_findings() {
	local script="$1" manifest="$2" name emulator_line cluster_line code
	name="${script#"$REPO_ROOT"/}"
	if [ ! -f "$script" ]; then
		printf '%s is missing, so nothing registers the emulator the gateway image needs\n' "$name"
		return 0
	fi
	code="$(strip_comments "$script")"
	printf '%s\n' "$code" | grep -qE 'docker run[^|]*tonistiigi/binfmt:[^ ]+.*--install arm64' ||
		printf '%s never registers the arm64 emulator, so the arm64-only gateway image cannot start\n' "$name"
	printf '%s\n' "$code" | grep -q '/proc/sys/fs/binfmt_misc' ||
		printf '%s never reads the registration back, so an install that did nothing would go unnoticed\n' "$name"
	# The order is the contract: the emulator has to be in the kernel before the node that runs the
	# image exists, or the kubelet is what discovers it is missing.
	emulator_line="$(printf '%s\n' "$code" | grep -nE 'docker run[^|]*tonistiigi/binfmt:' | head -n1 | cut -d: -f1)"
	cluster_line="$(printf '%s\n' "$code" | grep -nE 'kind create cluster' | head -n1 | cut -d: -f1)"
	if [ -n "$emulator_line" ] && [ -n "$cluster_line" ] && [ "$emulator_line" -gt "$cluster_line" ]; then
		printf '%s registers the emulator after creating the cluster, and the node runs the image before that\n' "$name"
	fi
	if [ ! -f "$manifest" ]; then
		printf '%s is missing, so the tree pins an arm64-only image without saying why\n' "${manifest#"$REPO_ROOT"/}"
		return 0
	fi
	grep -q 'arm64' "$manifest" ||
		printf '%s pins an image that exists for arm64 only and never says so, which makes the emulator read as an accident\n' \
			"${manifest#"$REPO_ROOT"/}"
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
# The database a workload is wired to
# ------------------------------------------

# db_addr_facts <root>: one `<file><TAB><record><TAB>...` line per fact the reader below needs:
#
#   configmap   <configmap> <key> <value>          -- one per key of a ConfigMap's `data:` block
#   service     <service> <label> <value>          -- one per `spec.selector.matchLabels` entry
#   deployment  <deployment> <label> <value>       -- one per label of its pod template
#   configref   <deployment> <EnvVar> <configmap> <key>
#                                                  -- one per `configMapKeyRef` of its containers
#
# A Service document also emits one record with no label, so that "this Service does not exist" and "this
# Service selects nothing" stay two different findings.
#
# The reader understands exactly the shape this tree writes, the same bargain `policy_flows` and
# `deployment_facts` make: a stack of the keys above each line, so nothing depends on a fixed indent.
# Block scalars are skipped whole, and that is not cosmetic: `keycloak-realm-config` carries a realm
# document whose lines look like keys and are not ones, and a reader that invented records would call a
# wired tree clean for the wrong reason.
db_addr_facts() {
	local f
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort | while IFS= read -r f; do
		awk -v file="${f#"$1"/}" '
			function unquote(s) { gsub(/"/, "", s); return s }
			function emit(kind, a, b, c, d) { printf "%s\t%s\t%s\t%s\t%s\t%s\n", file, kind, a, b, c, d }
			function flush_doc() {
				if (kind == "Service" && name != "") emit("service", name, "", "", "")
			}
			function reset_doc() {
				flush_doc()
				kind = ""
				name = ""
				split("", path)
				in_block = 0
				env_ind = -1
				envvar = ""
				in_ref = 0
				ref_ind = -1
				refkey = ""
				refname = ""
			}
			{
				ind = match($0, /[^ ]/) - 1
				if (ind < 0) ind = 0
				line = substr($0, ind + 1)
				sub(/[ \t]+$/, "", line)
				if (line == "" || line ~ /^#/) next
				if (in_block && ind > block_ind) next
				in_block = 0
				if (ind == 0 && line ~ /^---/) {
					reset_doc()
					next
				}
				if (ind == 0 && line ~ /^kind:/) {
					reset_doc()
					kind = line
					sub(/^kind:[[:space:]]*/, "", kind)
					next
				}
				if (kind == "") next
				kv = line
				if (kv ~ /^- /) sub(/^- /, "", kv)
				if (kv !~ /^[^:]+:/) next
				key = kv
				sub(/:.*/, "", key)
				val = ""
				if (kv ~ /: /) {
					val = kv
					sub(/^[^:]*: /, "", val)
					val = unquote(val)
				}
				for (d = ind + 1; d < 256; d++) path[d] = ""
				path[ind] = key
				if (val == "|" || val == "|-" || val == ">" || val == ">-") {
					in_block = 1
					block_ind = ind
					next
				}
				if (ind == 2 && path[0] == "metadata" && key == "name" && val != "") {
					name = val
					next
				}
				if (kind == "ConfigMap") {
					if (ind == 2 && path[0] == "data" && val != "") emit("configmap", name, key, val, "")
					next
				}
				if (kind == "Service") {
					if (ind == 4 && path[2] == "selector" && val != "") emit("service", name, key, val, "")
					next
				}
				if (kind == "Deployment") {
					if (ind == 8 && path[2] == "template" && path[4] == "metadata" && path[6] == "labels" && val != "") {
						emit("deployment", name, key, val, "")
						next
					}
					if (env_ind >= 0 && ind <= env_ind) {
						env_ind = -1
						envvar = ""
						in_ref = 0
						ref_ind = -1
						refkey = ""
						refname = ""
					}
					if (in_ref && ind <= ref_ind) {
						in_ref = 0
						refkey = ""
						refname = ""
					}
					if (key == "env" && val == "") {
						env_ind = ind
						next
					}
					if (env_ind < 0 || ind <= env_ind) next
					if (key == "configMapKeyRef" && val == "") {
						in_ref = 1
						ref_ind = ind
						refkey = ""
						refname = ""
						next
					}
					if (ind == env_ind + 2 && key == "name" && val != "") {
						envvar = val
						next
					}
					if (!in_ref) next
					if (key == "key") refkey = val
					else if (key == "name") refname = val
					if (refkey != "" && refname != "" && envvar != "") {
						emit("configref", name, envvar, refname, refkey)
						refkey = ""
						refname = ""
					}
					next
				}
			}
			END { reset_doc() }
		' "$f"
	done
}

# db_addr_findings <root>: a ConfigMap that names a database server which (a) does not exist in the tree
# as a Service, or (b) exists but has no ConfigMap behind that Service's Deployment declaring
# `MYSQL_DATABASE` equal to the `DB_DATABASE` the first ConfigMap asks for. Both halves are needed: the
# Service that defect (b) describes did exist, so a check that only looked for the Service read the tree
# as clean while the database a workload asked for was never created.
db_addr_findings() {
	local facts pairs cm addr database svc selector deployments deployment refs probe declared found
	facts="$(db_addr_facts "$1")"
	# Only a ConfigMap carrying both keys is judged: `DB_ADDR` alone names nothing to compare against and
	# `DB_DATABASE` alone names no server.
	pairs="$(printf '%s\n' "$facts" | awk -F'\t' '
		$2 == "configmap" {
			seen[$3] = 1
			if ($4 == "DB_ADDR") addr[$3] = $5
			else if ($4 == "DB_DATABASE") database[$3] = $5
		}
		END {
			for (c in seen)
				if ((c in addr) && (c in database)) printf "%s\t%s\t%s\n", c, addr[c], database[c]
		}
	' | sort)"
	if [ -z "$pairs" ]; then
		printf '%s\n' "no ConfigMap declares both DB_ADDR and DB_DATABASE, so the database a workload is wired to cannot be read from this tree"
		return 0
	fi
	while IFS=$'\t' read -r cm addr database; do
		[ -n "$cm" ] || continue
		svc="$(printf '%s\n' "$facts" | awk -F'\t' -v s="$addr" '$2 == "service" && $3 == s { print s; exit }')"
		if [ -z "$svc" ]; then
			printf 'ConfigMap %s names DB_ADDR "%s" and no Service of that name exists in this tree\n' "$cm" "$addr"
			continue
		fi
		selector="$(printf '%s\n' "$facts" | awk -F'\t' -v s="$addr" '$2 == "service" && $3 == s && $4 != "" { print $4 "=" $5 }')"
		if [ -z "$selector" ]; then
			printf 'ConfigMap %s asks Service %s for DB_DATABASE "%s" and that Service selects no pod, so no ConfigMap behind it can declare the database\n' \
				"$cm" "$addr" "$database"
			continue
		fi
		deployments="$(printf '%s\n' "$facts" | awk -F'\t' -v sel="$selector" '
			$2 == "deployment" { labels[$3] = labels[$3] " " $4 "=" $5 }
			END {
				n = split(sel, want, "\n")
				for (d in labels) {
					ok = 1
					for (i = 1; i <= n; i++) {
						if (want[i] == "") continue
						if (index(labels[d] " ", " " want[i] " ") == 0) ok = 0
					}
					if (ok) print d
				}
			}
		' | sort)"
		refs=""
		for deployment in $deployments; do
			refs="$refs $(printf '%s\n' "$facts" | awk -F'\t' -v d="$deployment" '$2 == "configref" && $3 == d { print $5 }' | sort -u | tr '\n' ' ')"
		done
		found=""
		for probe in $refs; do
			declared="$(printf '%s\n' "$facts" | awk -F'\t' -v c="$probe" '$2 == "configmap" && $3 == c && $4 == "MYSQL_DATABASE" { print $5; exit }')"
			if [ -n "$declared" ] && [ "$declared" = "$database" ]; then
				found="$probe"
			fi
		done
		if [ -z "$found" ]; then
			printf 'ConfigMap %s asks Service %s for DB_DATABASE "%s" and no ConfigMap behind that Service declares MYSQL_DATABASE "%s"\n' \
				"$cm" "$addr" "$database" "$database"
		fi
	done <<< "$pairs"
}

# ------------------------------------------
# The namespace
# ------------------------------------------

# declared_namespace <root>: the value of the kustomization's top-level `namespace:` field.
declared_namespace() {
	awk '
		/^namespace:[[:space:]]*/ {
			sub(/^namespace:[[:space:]]*/, "")
			sub(/[[:space:]]*$/, "")
			print
			exit
		}
	' "$1/kustomization.yaml" 2>/dev/null
}

# files_with_kind <root> <Kind>: the manifests holding a document of that kind.
files_with_kind() {
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort | while IFS= read -r f; do
		grep -qxE "kind:[[:space:]]*$2[[:space:]]*" "$f" && printf '%s\n' "$f"
	done
}

# namespace_object_names <path>: the metadata.name of each `kind: Namespace` document in the file.
namespace_object_names() {
	awk '
		function flush() {
			if (is_ns && name != "") print name
			is_ns = 0
			name = ""
		}
		/^---[[:space:]]*$/ { flush(); next }
		/^kind:[[:space:]]*Namespace[[:space:]]*$/ { is_ns = 1; next }
		is_ns && /^[[:space:]]+name:[[:space:]]*/ {
			name = $0
			sub(/^[[:space:]]+name:[[:space:]]*/, "", name)
			gsub(/"/, "", name)
			sub(/[[:space:]]*$/, "", name)
		}
		END { flush() }
	' "$1" 2>/dev/null
}

# namespace_findings <root>: exactly one Namespace object, and it declares the namespace the
# kustomization transforms into. The two are read separately on purpose: an object without the
# transform leaves the workloads in `default`, a transform without the object leaves nothing to
# create, and one of the two edited without the other is the drift this pair exists to catch.
namespace_findings() {
	local root="$1" declared paths count path name
	declared="$(declared_namespace "$root")"
	if [ -z "$declared" ]; then
		printf '%s\n' "kustomization.yaml declares no namespace: field, so no workload belongs to a declared namespace"
	fi
	paths="$(files_with_kind "$root" Namespace)"
	count="$(printf '%s\n' "$paths" | grep -c . || true)"
	if [ "${count:-0}" = "0" ]; then
		printf 'no kind: Namespace object exists under %s\n' "$root"
		return 0
	fi
	if [ "${count:-0}" != "1" ]; then
		printf 'the tree declares %s Namespace objects, and one is the shape this tree supports\n' "$count"
	fi
	while IFS= read -r path; do
		[ -n "$path" ] || continue
		[ -n "$declared" ] || continue
		while IFS= read -r name; do
			[ -n "$name" ] || continue
			[ "$name" = "$declared" ] ||
				printf '%s declares Namespace %s while kustomization.yaml declares namespace %s\n' \
					"${path#"$root"/}" "$name" "$declared"
			done <<< "$(namespace_object_names "$path")"
	done <<< "$paths"
}

# cluster_local_findings <root>: a service name spelled as a full cluster FQDN. A short service name
# resolves inside the pod's own declared namespace, so this is the line that decides whether renaming
# the namespace is two edits or one per manifest.
cluster_local_findings() {
	grep -rn '\.svc\.cluster\.local' "$1" 2>/dev/null | sed "s|^$1/||" || true
}

# namespace_literal_findings <root>: every manifest that writes the declared namespace's name, except
# the Namespace object and the kustomization transform. Those two are the declaration; a third copy is
# what turns a rename into a hand edit in every file that spells it out.
namespace_literal_findings() {
	local root="$1" declared allowed entry path
	declared="$(declared_namespace "$root")"
	[ -n "$declared" ] || return 0
	allowed="kustomization.yaml"
	while IFS= read -r entry; do
		[ -n "$entry" ] || continue
		allowed="$allowed ${entry#"$root"/}"
	done <<< "$(files_with_kind "$root" Namespace)"
	while IFS= read -r path; do
		[ -n "$path" ] || continue
		case " $allowed " in
		*" $path "*) continue ;;
		esac
		printf '%s writes the namespace name outside the two declarations\n' "$path"
	done <<< "$(grep -rlF "$declared" "$root" 2>/dev/null | sed "s|^$root/||" | sort)"
}

# ------------------------------------------
# The network boundary
# ------------------------------------------

# comma_list_has <list> <value>: whether a comma-joined list names the value.
comma_list_has() {
	case ",$1," in
	*",$2,"*) return 0 ;;
	esac
	return 1
}

# policy_documents <file>: `name<TAB>podSelector<TAB>policyTypes<TAB>rules` per NetworkPolicy
# document, where `rules` counts the ingress and egress rules it declares. A policy with no rules is a
# deny and a policy with rules is an allow, which is what lets the deny check tell them apart.
policy_documents() {
	[ -f "$1" ] || return 0
	awk '
		function flush() {
			if (is_np) printf "%s\t%s\t%s\t%s\n", name, sel, types, rules
			name = ""
			sel = ""
			types = ""
			rules = 0
			is_np = 0
			in_types = 0
		}
		{
			indent = match($0, /[^ ]/) - 1
			if (indent < 0) indent = 0
			line = substr($0, indent + 1)
			sub(/[ \t]+$/, "", line)
		}
		line == "" || line ~ /^#/ { next }
		indent == 0 && line ~ /^---/ { flush(); next }
		indent == 0 && line ~ /^kind:/ {
			flush()
			is_np = (line == "kind: NetworkPolicy")
			next
		}
		indent == 0 { next }
		!is_np { next }
		indent == 2 {
			in_types = (line == "policyTypes:")
			if (line == "podSelector: {}") sel = "*"
			else if (line == "podSelector:") sel = "labeled"
			else if (line ~ /^name: /) {
				name = line
				sub(/^name: /, "", name)
				gsub(/"/, "", name)
			}
			next
		}
		indent == 4 && in_types && line ~ /^- / {
			types = (types == "") ? substr(line, 3) : types "," substr(line, 3)
			next
		}
		indent == 4 && line ~ /^- / {
			rules++
			next
		}
		END { flush() }
	' "$1"
}

# policy_flows <file>: one `direction<TAB>source<TAB>destination<TAB>protocol/port` record per flow the
# file allows, where a selector is `app=<name>`, `*` for a selector that names every pod in the
# namespace, and a peer in another namespace is `ns=<namespace>/<selector>`.
#
# The reader understands exactly the shape this tree writes: one peer per rule, `matchLabels` under
# each selector, `ports` after the peer, labels at a fixed indent. Anything else produces no record,
# which is why the fixtures below mutate the real file instead of testing a friendlier copy: a reader
# that had stopped understanding the shape would otherwise report every pair as complete.
policy_flows() {
	[ -f "$1" ] || return 0
	awk '
		function selector(pod, ns) {
			if (ns == "") return pod
			if (pod == "") return "ns=" ns
			return "ns=" ns "/" pod
		}
		function flush_peer() {
			peer = selector(peer_pod, peer_ns)
			if (!is_np || peer == "") {
				peer_pod = ""
				peer_ns = ""
				ports = ""
				return
			}
			n = split(ports, parts, ",")
			for (i = 1; i <= n; i++) {
				if (parts[i] == "") continue
				if (dir == "egress") printf "egress\t%s\t%s\t%s\n", policy_sel, peer, parts[i]
				else if (dir == "ingress") printf "ingress\t%s\t%s\t%s\n", peer, policy_sel, parts[i]
			}
			peer_pod = ""
			peer_ns = ""
			ports = ""
		}
		{
			indent = match($0, /[^ ]/) - 1
			if (indent < 0) indent = 0
			line = substr($0, indent + 1)
			sub(/[ \t]+$/, "", line)
		}
		line == "" || line ~ /^#/ { next }
		indent == 0 && line ~ /^---/ { flush_peer(); next }
		indent == 0 && line ~ /^kind:/ {
			flush_peer()
			is_np = (line == "kind: NetworkPolicy")
			policy_sel = ""
			dir = ""
			next
		}
		indent == 0 { next }
		!is_np { next }
		indent == 2 && line == "podSelector: {}" { policy_sel = "*"; next }
		indent == 2 && (line == "egress:" || line == "ingress:") {
			flush_peer()
			dir = line
			sub(/:$/, "", dir)
			next
		}
		indent == 4 && (line == "- to:" || line == "- from:") {
			flush_peer()
			dir = (line == "- to:") ? "egress" : "ingress"
			next
		}
		indent == 6 && dir == "" && line ~ /^[^:]+: / {
			policy_sel = line
			sub(/: /, "=", policy_sel)
			gsub(/"/, "", policy_sel)
			next
		}
		indent == 8 && line == "- namespaceSelector: {}" {
			flush_peer()
			peer_ns = "*"
			peer_scope = "ns"
			next
		}
		indent == 8 && line == "- podSelector: {}" {
			flush_peer()
			peer_pod = "*"
			peer_scope = "pod"
			next
		}
		indent == 8 && line == "- namespaceSelector:" { flush_peer(); peer_scope = "ns"; next }
		indent == 8 && line == "- podSelector:" { flush_peer(); peer_scope = "pod"; next }
		indent == 8 && line ~ /^- protocol: / {
			proto = line
			sub(/^- protocol: /, "", proto)
			next
		}
		indent == 10 && line == "podSelector:" { peer_scope = "pod"; next }
		indent == 10 && line == "namespaceSelector:" { peer_scope = "ns"; next }
		indent == 10 && line ~ /^port: / {
			port = line
			sub(/^port: /, "", port)
			if (proto == "") proto = "TCP"
			ports = ports "," proto "/" port
			proto = ""
			next
		}
		indent == 14 && peer_scope == "pod" {
			peer_pod = line
			sub(/: /, "=", peer_pod)
			next
		}
		indent == 14 && peer_scope == "ns" {
			peer_ns = line
			sub(/: /, "=", peer_ns)
			sub(/^kubernetes\.io\/metadata\.name=/, "", peer_ns)
			next
		}
		END { flush_peer() }
	' "$1"
}

# default_deny_findings <file>: a policy that selects every pod, declares no allow of its own, and
# stops both directions. One direction is not a default deny: the traffic it does not name still flows
# the other way. A policy that selects every pod and does carry rules is an allow (cluster DNS is one),
# not a candidate deny, so it is not judged here.
default_deny_findings() {
	local docs name sel types rules found=0
	docs="$(policy_documents "$1")"
	if [ -z "$docs" ]; then
		printf '%s\n' "no NetworkPolicy document exists, so nothing stops the traffic this tree does not need"
		return 0
	fi
	while IFS=$'\t' read -r name sel types rules; do
		[ -n "$name" ] || continue
		[ "$sel" = "*" ] || continue
		if comma_list_has "$types" Ingress && comma_list_has "$types" Egress; then
			found=$((found + 1))
			continue
		fi
		[ "${rules:-0}" = "0" ] || continue
		printf 'policy %s selects every pod and declares no rule, but its policyTypes "%s" does not name both directions\n' \
			"$name" "$types"
	done <<< "$docs"
	if [ "$found" = "0" ]; then
		printf '%s\n' "no policy with an empty podSelector declares both Ingress and Egress, so the namespace is not default-deny"
	fi
}

# blanket_peer_findings <file>: a rule whose peer is every pod, or every namespace. That is the line
# that makes the boundary unprovable -- any pod in the namespace could reach MySQL -- so it is a
# finding even when both directions agree, which is the one case a pair check cannot catch.
blanket_peer_findings() {
	policy_flows "$1" | awk -F'\t' '
		($1 == "egress" && ($3 == "*" || $3 ~ /^ns=\*/)) ||
		($1 == "ingress" && ($2 == "*" || $2 ~ /^ns=\*/)) {
			printf "the %s rule %s -> %s %s allows every peer\n", $1, $2, $3, $4
		}
	'
}

# unpaired_flows <file>: every allowed flow has to exist in both directions. A NetworkPolicy is
# directional, so one side alone allows nothing at all and the workload that rule was written for
# simply cannot reach its dependency -- a failure that shows up as a broken pod, not as a bad policy.
unpaired_flows() {
	local flows egress ingress probe
	flows="$(policy_flows "$1")"
	egress="$(printf '%s\n' "$flows" | awk -F'\t' '$1 == "egress" && $3 !~ /^ns=/ { printf "%s -> %s %s\n", $2, $3, $4 }' | sort -u)"
	ingress="$(printf '%s\n' "$flows" | awk -F'\t' '$1 == "ingress" && $2 !~ /^ns=/ { printf "%s -> %s %s\n", $2, $3, $4 }' | sort -u)"
	while IFS= read -r probe; do
		[ -n "$probe" ] || continue
		printf '%s\n' "$ingress" | grep -qxF "$probe" ||
			printf 'egress %s has no ingress rule that allows it\n' "$probe"
	done <<< "$egress"
	while IFS= read -r probe; do
		[ -n "$probe" ] || continue
		printf '%s\n' "$egress" | grep -qxF "$probe" ||
			printf 'ingress %s has no egress rule that allows it\n' "$probe"
	done <<< "$ingress"
}

# The eight flows the manifests declare, frozen. The pair check above proves the two halves of
# whatever is written agree; this proves the set itself, because deleting both halves of a pair keeps
# the boundary symmetric and silently takes a workload's dependency away.
DECLARED_FLOWS="app=api-gateway -> app=keycloak TCP/8080
app=api-gateway -> app=loki TCP/3100
app=api-gateway -> app=tempo TCP/9411
app=grafana -> app=loki TCP/3100
app=grafana -> app=prometheus TCP/9090
app=grafana -> app=tempo TCP/3200
app=keycloak -> app=keycloak-mysql TCP/3306
app=prometheus -> app=api-gateway TCP/9000"

# declared_flow_findings <file>: the intra-namespace flows the file allows are exactly the eight
# above. An extra allow is a flow no manifest needs, which is what S6 says cannot exist.
declared_flow_findings() {
	local flows probe
	flows="$(policy_flows "$1" | awk -F'\t' '$1 == "egress" && $3 !~ /^ns=/ { printf "%s -> %s %s\n", $2, $3, $4 }' | sort -u)"
	while IFS= read -r probe; do
		[ -n "$probe" ] || continue
		printf '%s\n' "$flows" | grep -qxF "$probe" ||
			printf 'this tree needs %s and no egress rule allows it\n' "$probe"
	done <<< "$DECLARED_FLOWS"
	while IFS= read -r probe; do
		[ -n "$probe" ] || continue
		printf '%s\n' "$DECLARED_FLOWS" | grep -qxF "$probe" ||
			printf 'an egress rule allows %s, and no manifest in this tree needs it\n' "$probe"
	done <<< "$flows"
}

# cross_namespace_findings <file>: the only flow that may leave this namespace is cluster DNS, from
# every pod, on 53 over both transports. Every other dependency in the tree is a Service in its own
# namespace, so a second cross-namespace allow is a flow nobody declared -- and a DNS allow that names
# one pod instead of every pod leaves the rest unable to resolve any of the short names.
DNS_DESTINATION="ns=kube-system/k8s-app=kube-dns"
cross_namespace_findings() {
	local flows sources transport
	flows="$(policy_flows "$1")"
	printf '%s\n' "$flows" | awk -F'\t' -v dns="$DNS_DESTINATION" '
		($1 == "egress" && $3 ~ /^ns=/ && $3 != dns) ||
		($1 == "ingress" && $2 ~ /^ns=/) {
			printf "the %s rule %s -> %s %s crosses the namespace boundary, and nothing in this tree needs it\n", $1, $2, $3, $4
		}
	'
	for transport in UDP/53 TCP/53; do
		printf '%s\n' "$flows" |
			awk -F'\t' -v dns="$DNS_DESTINATION" -v t="$transport" \
				'$1 == "egress" && $3 == dns && $4 == t' |
			grep -q . ||
			printf 'no egress rule allows %s to cluster DNS, so the short names in this tree do not resolve\n' "$transport"
	done
	sources="$(printf '%s\n' "$flows" | awk -F'\t' -v dns="$DNS_DESTINATION" '$1 == "egress" && $3 == dns { print $2 }' | sort -u)"
	if [ -n "$sources" ] && [ "$sources" != "*" ]; then
		printf 'the DNS allow names %s instead of every pod, so a pod outside that list cannot resolve a short name\n' \
			"$(printf '%s\n' "$sources" | tr '\n' ' ')"
	fi
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
	if [ "$deployments" = "7" ]; then
		pass "the kustomization renders 7 Deployments (6 infra + api-gateway)"
	else
		fail "the kustomization renders $deployments Deployments, and the frozen count is 7"
	fi

	check_clean "every ConfigMap's DB_ADDR names a Service whose MySQL declares the database it asks for" \
		"$(db_addr_findings "$MANIFESTS")"
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
	if [ "${produced_count:-0}" = "4" ]; then
		pass "create-secrets.sh produces the four Secret/key pairs the manifests read"
	else
		fail "create-secrets.sh produces ${produced_count:-0} pairs, and the manifests read four"
	fi
	check_clean "every secretKeyRef names a Secret and a key that create-secrets.sh creates" \
		"$(unproduced_secrets "$MANIFESTS")"
fi

echo
echo "== 3. the namespace is declared twice and written nowhere else"

if [ ! -d "$MANIFESTS" ]; then
	fail "cannot check the namespace: no manifests directory"
else
	check_clean "a kind: Namespace object declares the namespace the kustomization transforms into" \
		"$(namespace_findings "$MANIFESTS")"

	check_clean "no manifest spells a service name as a cluster FQDN" \
		"$(cluster_local_findings "$REPO_ROOT/k8s")"

	check_clean "the namespace name is written only by the object and the transform" \
		"$(namespace_literal_findings "$MANIFESTS")"

	declared="$(declared_namespace "$MANIFESTS")"
	if [ -z "$declared" ]; then
		fail "the kustomization declares no namespace, so there is no name to count"
	else
		# One declaration in the object, one in the transform. A third occurrence is the copy that
		# a rename leaves behind, which is the defect this count exists to keep out.
		occurrences="$(grep -roF "$declared" "$MANIFESTS" 2>/dev/null | wc -l | tr -d ' ')"
		if [ "$occurrences" = "2" ]; then
			pass "the namespace name is written exactly twice: once as the object, once as the transform"
		else
			fail "the namespace name is written $occurrences times, and the frozen count is 2"
		fi
	fi
fi

echo
echo "== 4. the network boundary is a default deny and every allow is a pair"

# One manifest owns the boundary. Two of them would be two places to look for the flow that is
# actually allowed, and the pair check below reads one file.
POLICY_FILE="$(files_with_kind "$MANIFESTS" NetworkPolicy 2>/dev/null | head -n1)"
POLICY_COUNT="$(files_with_kind "$MANIFESTS" NetworkPolicy 2>/dev/null | grep -c . || true)"
POLICY_REL=""
if [ -n "$POLICY_FILE" ]; then
	POLICY_REL="${POLICY_FILE#"$MANIFESTS"/}"
fi

if [ "${POLICY_COUNT:-0}" = "1" ]; then
	pass "one manifest declares the NetworkPolicies: $POLICY_REL"
elif [ "${POLICY_COUNT:-0}" = "0" ]; then
	fail "no manifest declares a NetworkPolicy, so every pod in the namespace can reach every other pod"
else
	fail "the tree declares NetworkPolicies in ${POLICY_COUNT:-0} manifests, and exactly one owns the boundary"
fi

if [ -n "$POLICY_FILE" ] && [ -f "$POLICY_FILE" ]; then
	check_clean "a default-deny policy selects every pod and names both directions" \
		"$(default_deny_findings "$POLICY_FILE")"

	check_clean "no rule allows every peer, which is the blanket allow the boundary cannot have" \
		"$(blanket_peer_findings "$POLICY_FILE")"

	check_clean "every allowed flow is allowed in both directions" \
		"$(unpaired_flows "$POLICY_FILE")"

	check_clean "the allowed flows are exactly the ones the manifests declare" \
		"$(declared_flow_findings "$POLICY_FILE")"

	check_clean "the only flow leaving this namespace is cluster DNS on 53, from every pod" \
		"$(cross_namespace_findings "$POLICY_FILE")"
else
	fail "cannot check the network boundary: no NetworkPolicy manifest was found"
fi

echo
echo "== 5. the documented entry point names things that exist"

check_clean "every ./k8s path README.md names exists, and every script it runs is executable" \
	"$(readme_path_findings "$README")"

echo
echo "== 6. no image floats outside the declared exception"

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
# The hardening of every workload
# ------------------------------------------

# Every number below is a measurement, and holding the manifests equal to it is the whole job of this
# half of the suite: an unmeasured uid or a missing limit is not a visible defect -- the tree applies
# and the workload runs wide open -- so a value that disagrees has to fail a check instead of being
# read past.

# The identity each image actually supports. `docker image inspect` for the images that declare a user,
# `docker run --entrypoint id` for the MySQL image whose entrypoint drops to one, and `Config.User`
# for the gateway, whose image is arm64-only: since #1056 the emulator lets it run on an amd64 node, but
# the image carries no `id` either (`exec: "id": executable file not found in $PATH`), so the
# declaration is still the only reading -- and the pod sets the same pair explicitly, which is what the
# assertion below compares.
MEASURED_IDENTITIES="api-gateway 1002 1000
grafana 472 0
keycloak 1000 0
keycloak-mysql 999 999
loki 10001 10001
prometheus 65534 65534
tempo 65534 65534"

# The four images whose read-only root filesystem works, and every path each run named. One of them
# named a second path once the first was writable -- read-only, MySQL stops at `/tmp` and, with `/tmp`
# as a tmpfs, at `/var/run/mysqld` -- which is why keycloak-mysql takes two lines and the rest one. The
# pair is the assertion: `readOnlyRootFilesystem: true` without the tmpfs is a workload that cannot
# start.
READ_ONLY_ROOTFS="grafana /var/lib/grafana
keycloak-mysql /tmp
keycloak-mysql /var/run/mysqld
loki /loki
prometheus /prometheus"

# The three that keep a writable root filesystem, each carrying its measured reason at the container.
# Tempo is here for a structural reason rather than an incidental one: its data path is `/tmp/tempo`,
# so a tmpfs on `/tmp` would shadow the volume it writes to instead of protecting it.
WRITABLE_ROOTFS="api-gateway
keycloak
tempo"

# The readiness probe each workload can actually answer, and the port its own container listens on.
# The gateway's path is the only endpoint its image leaves unauthenticated: `/`, `/actuator`,
# `/actuator/health`, its two `/actuator/health/*` children and `/actuator/info` all answer `401`, which
# the kubelet reads as a failed probe, while `/actuator/prometheus` answers `200` (both measured on an
# amd64 host under the emulation from #1056, issue #1056).
READINESS="api-gateway httpGet /actuator/prometheus 9000
grafana httpGet /api/health 3000
keycloak tcpSocket - 8080
keycloak-mysql tcpSocket - 3306
loki httpGet /ready 3100
prometheus httpGet /-/ready 9090
tempo httpGet /ready 3200"

# The two hostPath-backed workloads and the uid `prepare-data` has to hand the directory to. Those two
# are the whole reason an init container exists in this tree: `hostPath` is the one volume type the
# kubelet does not hand to `fsGroup`, so a non-root workload cannot open its own datadir until somebody
# chowns it.
PREPARE_DATA="keycloak-mysql 999
tempo 65534"

# deployment_facts <root>: one `<workload><TAB><path><TAB><value>` record per hardening fact of every
# Deployment, plus `<workload><TAB>file<TAB><manifest>`.
#
# The reader understands exactly the shape this tree writes, the same bargain `policy_flows` makes:
# `spec.template.spec` at indent 4, `securityContext` / `containers` / `initContainers` / `volumes` at
# 6, a list item's `name` at 8, a container key at 10, its children at 12 and the list under
# `capabilities` at 16. Anything else produces no record, which is why every checker below also runs
# against a fixture built by mutating a real manifest: a reader that had stopped understanding the
# shape would report nothing and print the same green line as a clean tree.
#
# The naming assumption is worth stating: the workload container is the one named after the Deployment,
# so `container.keycloak-mysql.*` is MySQL's container and a rename makes every check below report rather
# than read a different container by accident.
deployment_facts() {
	local f
	find "$1" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort | while IFS= read -r f; do
		awk -v file="${f#"$1"/}" '
			function emit(prefix, key, value) {
				printf "%s\t%s.%s\t%s\n", workload, prefix, key, value
			}
			function flush_doc(   i, k, idx, p, paths, tmps) {
				if (in_dep && workload != "") {
					printf "%s\tfile\t%s\n", workload, file
					for (i = 1; i <= norder; i++) {
						p = order[i]
						paths = ""
						tmps = ""
						for (k = 1; k <= mcount[p]; k++) {
							idx = p "|" k
							if (mpath[idx] == "") continue
							paths = (paths == "") ? mpath[idx] : paths "," mpath[idx]
							if (vol_mem[mname[idx]]) tmps = (tmps == "") ? mpath[idx] : tmps "," mpath[idx]
						}
						emit(p, "volumeMounts", paths)
						emit(p, "tmpfsMounts", tmps)
					}
				}
				in_dep = 0
				workload = ""
				section = ""
				ckind = ""
				block = ""
				pfx = ""
				curvol = ""
				norder = 0
				split("", order)
				split("", mcount)
				split("", mname)
				split("", mpath)
				split("", vol_mem)
			}
			function value(line, key) {
				v = line
				sub("^" key ": ", "", v)
				gsub(/"/, "", v)
				return v
			}
			function item_value(line) {
				v = line
				sub(/^- /, "", v)
				gsub(/"/, "", v)
				return v
			}
			{
				ind = match($0, /[^ ]/) - 1
				if (ind < 0) ind = 0
				line = substr($0, ind + 1)
				sub(/[ \t]+$/, "", line)
			}
			line == "" { next }
			line ~ /^#/ {
				if (in_dep && pfx != "") printf "%s\t%s.comment\t%s\n", workload, pfx, line
				next
			}
			ind == 0 && line ~ /^---/ { flush_doc(); next }
			ind == 0 && line ~ /^kind:/ { flush_doc(); in_dep = (line == "kind: Deployment"); next }
			!in_dep { next }
			ind == 2 && line ~ /^name: / {
				workload = value(line, "name")
				next
			}
			ind == 6 {
				block = ""
				pfx = ""
				ckind = ""
				if (line == "securityContext:") section = "pod"
				else if (line == "containers:") section = "containers"
				else if (line == "initContainers:") section = "inits"
				else if (line == "volumes:") section = "volumes"
				else section = ""
				next
			}
			ind == 8 {
				if (line ~ /^- name: /) {
					item = value(line, "- name")
					block = ""
					if (section == "containers") {
						ckind = "container"
						pfx = "container." item
						order[++norder] = pfx
					} else if (section == "inits") {
						ckind = "init"
						pfx = "init." item
						order[++norder] = pfx
					} else if (section == "volumes") {
						ckind = ""
						pfx = ""
						curvol = item
					}
					next
				}
				if (section == "pod") {
					if (line ~ /^runAsUser: /) emit("pod", "securityContext.runAsUser", value(line, "runAsUser"))
					else if (line ~ /^runAsGroup: /) emit("pod", "securityContext.runAsGroup", value(line, "runAsGroup"))
					else if (line ~ /^runAsNonRoot: /) emit("pod", "securityContext.runAsNonRoot", value(line, "runAsNonRoot"))
					else if (line == "seccompProfile:") block = "seccomp"
				}
				next
			}
			ind == 10 && section == "pod" && block == "seccomp" {
				if (line ~ /^type: /) emit("pod", "securityContext.seccompProfile.type", value(line, "type"))
				next
			}
			ind == 10 && section == "volumes" {
				block = (line == "emptyDir:") ? "emptydir" : ""
				next
			}
			ind == 12 && section == "volumes" && block == "emptydir" {
				if (line == "medium: Memory") vol_mem[curvol] = 1
				next
			}
			ind == 10 && ckind != "" {
				if (line == "securityContext:") block = "sc"
				else if (line == "volumeMounts:") block = "mounts"
				else if (line == "resources:") block = "resources"
				else if (line == "readinessProbe:") block = "readiness"
				else if (line == "livenessProbe:") {
					block = "liveness"
					emit(pfx, "livenessProbe", "declared")
				} else if (line ~ /^imagePullPolicy: /) {
					block = ""
					emit(pfx, "imagePullPolicy", value(line, "imagePullPolicy"))
				} else if (line ~ /^image: /) {
					block = ""
					emit(pfx, "image", value(line, "image"))
				} else if (line ~ /^args: /) {
					block = ""
					emit(pfx, "args", value(line, "args"))
				} else if (line ~ /^command: /) {
					block = ""
					emit(pfx, "command", value(line, "command"))
				} else block = ""
				next
			}
			ind == 12 && ckind != "" {
				# Keyed on the key rather than on the block, because `capabilities` and `mounts` keep
				# their state while the keys that follow them in the same `securityContext` do not.
				if (line ~ /^runAsUser: /) emit(pfx, "securityContext.runAsUser", value(line, "runAsUser"))
				else if (line ~ /^runAsGroup: /) emit(pfx, "securityContext.runAsGroup", value(line, "runAsGroup"))
				else if (line ~ /^runAsNonRoot: /) emit(pfx, "securityContext.runAsNonRoot", value(line, "runAsNonRoot"))
				else if (line ~ /^allowPrivilegeEscalation: /) emit(pfx, "securityContext.allowPrivilegeEscalation", value(line, "allowPrivilegeEscalation"))
				else if (line ~ /^readOnlyRootFilesystem: /) emit(pfx, "securityContext.readOnlyRootFilesystem", value(line, "readOnlyRootFilesystem"))
				else if (line == "capabilities:") block = "caps"
				else if (line == "requests:") block = "requests"
				else if (line == "limits:") block = "limits"
				else if (line == "httpGet:") block = "httpGet"
				else if (line == "tcpSocket:") block = "tcpSocket"
				else if (block == "mounts" && line ~ /^- /) {
					idx = pfx "|" ++mcount[pfx]
					if (line ~ /^- name: /) mname[idx] = value(line, "- name")
					else if (line ~ /^- mountPath: /) mpath[idx] = value(line, "- mountPath")
				}
				next
			}
			ind == 14 && ckind != "" {
				if (block == "caps") {
					if (line == "drop:") capnext = "drop"
					else if (line == "add:") capnext = "add"
				} else if (block == "requests" || block == "limits") {
					if (line ~ /^cpu: /) emit(pfx, "resources." block ".cpu", value(line, "cpu"))
					else if (line ~ /^memory: /) emit(pfx, "resources." block ".memory", value(line, "memory"))
				} else if (block == "httpGet" || block == "tcpSocket") {
					if (line ~ /^path: /) emit(pfx, "readinessProbe.path", value(line, "path"))
					else if (line ~ /^port: /) {
						emit(pfx, "readinessProbe.kind", block)
						emit(pfx, "readinessProbe.port", value(line, "port"))
					}
				} else if (block == "mounts") {
					idx = pfx "|" mcount[pfx]
					if (line ~ /^name: /) mname[idx] = value(line, "name")
					else if (line ~ /^mountPath: /) mpath[idx] = value(line, "mountPath")
				}
				next
			}
			ind == 16 && ckind != "" && block == "caps" && capnext != "" && line ~ /^- / {
				emit(pfx, "securityContext.capabilities." capnext, item_value(line))
				next
			}
			END { flush_doc() }
		' "$f"
	done
}

# one_fact <facts> <workload> <path>: the last value recorded for one fact, or nothing.
one_fact() {
	printf '%s\n' "$1" | awk -F'\t' -v w="$2" -v p="$3" '$1 == w && $2 == p { v = $3 } END { print v }'
}

# all_facts <facts> <workload> <path>: every value recorded for one path, one per line.
all_facts() {
	printf '%s\n' "$1" | awk -F'\t' -v w="$2" -v p="$3" '$1 == w && $2 == p { print $3 }'
}

# identity_findings <facts>: the pod-level identity is the one the image supports, and nothing
# overrides it at the container level -- an override would make the frozen table describe a value the
# container does not actually run as, which is the silent guess this check exists to refuse.
identity_findings() {
	local facts="$1" wl uid gid got
	while read -r wl uid gid; do
		[ -n "$wl" ] || continue
		got="$(one_fact "$facts" "$wl" pod.securityContext.runAsUser)"
		[ "$got" = "$uid" ] ||
			printf '%s declares pod runAsUser "%s" and its image supports %s\n' "$wl" "$got" "$uid"
		got="$(one_fact "$facts" "$wl" pod.securityContext.runAsGroup)"
		[ "$got" = "$gid" ] ||
			printf '%s declares pod runAsGroup "%s" and its image supports %s\n' "$wl" "$got" "$gid"
		got="$(one_fact "$facts" "$wl" pod.securityContext.runAsNonRoot)"
		[ "$got" = "true" ] ||
			printf '%s does not declare runAsNonRoot: true at the pod level\n' "$wl"
		got="$(one_fact "$facts" "$wl" pod.securityContext.seccompProfile.type)"
		[ "$got" = "RuntimeDefault" ] ||
			printf '%s does not declare seccompProfile: RuntimeDefault, which is one rule for the whole tree\n' "$wl"
		got="$(one_fact "$facts" "$wl" "container.$wl.securityContext.runAsUser")"
		[ -z "$got" ] ||
			printf '%s overrides runAsUser to "%s" at the container level, which the measured table does not see\n' \
				"$wl" "$got"
	done <<< "$MEASURED_IDENTITIES"
}

# container_security_findings <facts>: every workload container refuses escalation and drops every
# capability. A capability added back is a drop of ALL that does not mean ALL, so it is a finding.
container_security_findings() {
	local facts="$1" wl got adds
	while read -r wl _; do
		[ -n "$wl" ] || continue
		got="$(one_fact "$facts" "$wl" "container.$wl.securityContext.allowPrivilegeEscalation")"
		[ "$got" = "false" ] ||
			printf '%s does not set allowPrivilegeEscalation: false on its container\n' "$wl"
		got="$(all_facts "$facts" "$wl" "container.$wl.securityContext.capabilities.drop" | sort -u | tr '\n' ' ' | sed 's/ $//')"
		[ "$got" = "ALL" ] ||
			printf '%s drops capabilities "%s" and the tree drops ALL\n' "$wl" "${got:-none}"
		adds="$(all_facts "$facts" "$wl" "container.$wl.securityContext.capabilities.add" | sort -u | tr '\n' ' ' | sed 's/ $//')"
		[ -z "$adds" ] ||
			printf '%s adds capability "%s" back to a container that must drop ALL\n' "$wl" "$adds"
	done <<< "$MEASURED_IDENTITIES"
}

# image_pull_findings <facts>: every container that declares an image also declares
# `imagePullPolicy: IfNotPresent`. Declared and not spelled out is the whole point: the tree's images
# are loaded into the kind node by `k8s/kind/kind-load.sh`, and the default for the gateway's `:latest`
# is `Always`, which would try to pull it and fail. The count is per container rather than per workload
# so an init container cannot pass by absence.
image_pull_findings() {
	printf '%s\n' "$1" | awk -F'\t' '
		$2 ~ /^(container|init)\.[^.]+\.image$/ {
			p = $2
			sub(/\.image$/, "", p)
			has_image[$1 SUBSEP p] = 1
		}
		$2 ~ /^(container|init)\.[^.]+\.imagePullPolicy$/ {
			p = $2
			sub(/\.imagePullPolicy$/, "", p)
			if ($3 != "IfNotPresent") printf "%s declares imagePullPolicy \"%s\" for %s and this tree only loads images\n", $1, $3, p
			has_policy[$1 SUBSEP p] = 1
		}
		END {
			for (k in has_image) {
				if (k in has_policy) continue
				split(k, parts, SUBSEP)
				printf "%s declares no imagePullPolicy for %s\n", parts[1], parts[2]
			}
		}
	'
}

# resources_findings <facts>: all four values present and non-zero. A zero request schedules nothing
# and a zero limit is a limit Kubernetes reads as "no limit", so both are findings rather than a
# smaller number.
resources_findings() {
	local facts="$1" wl kind res got
	while read -r wl _; do
		[ -n "$wl" ] || continue
		for kind in requests limits; do
			for res in cpu memory; do
				got="$(one_fact "$facts" "$wl" "container.$wl.resources.$kind.$res")"
				case "$got" in
				"" | 0 | 0m | 0Mi | 0M | 0MiB)
					printf '%s declares no non-zero %s.%s\n' "$wl" "$kind" "$res"
					;;
				esac
			done
		done
	done <<< "$MEASURED_IDENTITIES"
}

# readiness_findings <facts>: each workload answers the probe the tree holds for it. `livenessProbe`
# has its own check because a workload that cannot answer one must not carry a naively configured one.
readiness_findings() {
	local facts="$1" wl kind path port got_kind got_path got_port
	while read -r wl kind path port; do
		[ -n "$wl" ] || continue
		got_kind="$(one_fact "$facts" "$wl" "container.$wl.readinessProbe.kind")"
		got_path="$(one_fact "$facts" "$wl" "container.$wl.readinessProbe.path")"
		got_port="$(one_fact "$facts" "$wl" "container.$wl.readinessProbe.port")"
		[ "$got_kind" = "$kind" ] ||
			printf '%s declares readiness probe "%s" and this workload answers %s\n' "$wl" "${got_kind:-none}" "$kind"
		[ "$got_port" = "$port" ] ||
			printf '%s probes port "%s" for readiness and its container listens on %s\n' "$wl" "${got_port:-none}" "$port"
		if [ "$kind" = "httpGet" ] && [ "$got_path" != "$path" ]; then
			printf '%s probes path "%s" and the frozen endpoint is %s\n' "$wl" "${got_path:-none}" "$path"
		fi
	done <<< "$READINESS"
}

# liveness_comment_findings <facts>: the workload that omits a livenessProbe says at the probe site
# what one would have to prove. S4 permits the omission only when it is stated, so a silent omission
# is a finding even though the omission itself is correct.
liveness_comment_findings() {
	local facts="$1" wl
	while read -r wl _; do
		[ -n "$wl" ] || continue
		all_facts "$facts" "$wl" "container.$wl.comment" | grep -q 'livenessProbe' ||
			printf '%s omits a livenessProbe and does not say at the probe site what one would have to prove\n' "$wl"
	done <<< "$MEASURED_IDENTITIES"
}

# root_filesystem_findings <facts>: read-only exactly where the image tolerated it and its one
# writable path is a tmpfs, writable exactly where it did not and the measured reason is written at
# the container. The tmpfs half is asserted in both directions: a missing one is a pod that cannot
# start, and one added to a workload whose data path is a volume is a mount that shadows it.
root_filesystem_findings() {
	local facts="$1" wl path got tmps
	while read -r wl path; do
		[ -n "$wl" ] || continue
		got="$(one_fact "$facts" "$wl" "container.$wl.securityContext.readOnlyRootFilesystem")"
		[ "$got" = "true" ] ||
			printf '%s declares readOnlyRootFilesystem "%s" and this image tolerates a read-only root filesystem\n' \
				"$wl" "${got:-none}"
		tmps="$(one_fact "$facts" "$wl" "container.$wl.tmpfsMounts")"
		comma_list_has "$tmps" "$path" ||
			printf '%s writes %s with a read-only root filesystem and no tmpfs is mounted there\n' "$wl" "$path"
	done <<< "$READ_ONLY_ROOTFS"
	while read -r wl; do
		[ -n "$wl" ] || continue
		got="$(one_fact "$facts" "$wl" "container.$wl.securityContext.readOnlyRootFilesystem")"
		[ "$got" = "false" ] ||
			printf '%s keeps a writable root filesystem and does not declare readOnlyRootFilesystem: false\n' "$wl"
		tmps="$(one_fact "$facts" "$wl" "container.$wl.tmpfsMounts")"
		[ -z "$tmps" ] ||
			printf '%s mounts a tmpfs at "%s" while its root filesystem is writable, which is the mount tempo cannot have\n' \
				"$wl" "$tmps"
		all_facts "$facts" "$wl" "container.$wl.comment" | grep -q 'readOnlyRootFilesystem' ||
			printf '%s keeps a writable root filesystem with no measured reason at the container\n' "$wl"
	done <<< "$WRITABLE_ROOTFS"
}

# prepare_data_findings <facts>: exactly the two hostPath-backed workloads carry the init container,
# it runs as uid 0 with every capability dropped but CHOWN, it mounts nothing but the directory it
# fixes, and it hands that directory to the uid the workload runs as.
prepare_data_findings() {
	local facts="$1" wl uid got adds mounts args_value
	while read -r wl uid; do
		[ -n "$wl" ] || continue
		got="$(one_fact "$facts" "$wl" init.prepare-data.securityContext.runAsUser)"
		[ "$got" = "0" ] ||
			printf '%s: prepare-data runs as "%s" and handing a directory over needs uid 0\n' "$wl" "${got:-none}"
		got="$(one_fact "$facts" "$wl" init.prepare-data.securityContext.runAsNonRoot)"
		[ "$got" = "false" ] ||
			printf '%s: prepare-data does not declare runAsNonRoot: false, so the pod-level true would refuse to start it\n' "$wl"
		got="$(one_fact "$facts" "$wl" init.prepare-data.securityContext.allowPrivilegeEscalation)"
		[ "$got" = "false" ] ||
			printf '%s: prepare-data does not set allowPrivilegeEscalation: false\n' "$wl"
		got="$(all_facts "$facts" "$wl" init.prepare-data.securityContext.capabilities.drop | sort -u | tr '\n' ' ' | sed 's/ $//')"
		[ "$got" = "ALL" ] ||
			printf '%s: prepare-data drops capabilities "%s" and the tree drops ALL\n' "$wl" "${got:-none}"
		adds="$(all_facts "$facts" "$wl" init.prepare-data.securityContext.capabilities.add | sort -u | tr '\n' ' ' | sed 's/ $//')"
		[ "$adds" = "CHOWN" ] ||
			printf '%s: prepare-data takes capabilities "%s" back and CHOWN alone is the contract\n' "$wl" "${adds:-none}"
		mounts="$(one_fact "$facts" "$wl" init.prepare-data.volumeMounts)"
		case "$mounts" in
		"" | *,*)
			printf '%s: prepare-data mounts "%s" and it may mount exactly the one directory it fixes\n' \
				"$wl" "${mounts:-none}"
			;;
		esac
		args_value="$(one_fact "$facts" "$wl" init.prepare-data.args)"
		printf '%s\n' "$args_value" | grep -qE "chown -R ${uid}:[0-9]+ " ||
			printf '%s: prepare-data does not chown the directory to uid %s\n' "$wl" "$uid"
	done <<< "$PREPARE_DATA"
	# The other four Deployments declare no init container at all: the two hostPath volumes are the
	# whole reason one exists in this tree, so a third is a mechanism nobody measured.
	printf '%s\n' "$facts" |
		awk -F'\t' -v expected="$(printf '%s\n' "$PREPARE_DATA" | awk '{ print $1 }' | tr '\n' ' ')" '
			$2 ~ /^init\./ {
				if (index(" " expected " ", " " $1 " ") == 0) {
					printf "%s declares an init container (%s) and only the two hostPath volumes need one\n", $1, $2
				}
			}
		' | sort -u
}

# ------------------------------------------
# The tree, hardened
# ------------------------------------------

echo
echo "== 7. every workload is unprivileged, bounded and observable"

if [ ! -d "$MANIFESTS" ]; then
	fail "cannot check the hardening: no manifests directory"
else
	FACTS="$(deployment_facts "$MANIFESTS")"
	declared_workloads="$(printf '%s\n' "$FACTS" | awk -F'\t' '$2 == "file" { print $1 }' | sort | tr '\n' ' ' | sed 's/ $//')"
	measured_workloads="$(printf '%s\n' "$MEASURED_IDENTITIES" | awk '{ print $1 }' | sort | tr '\n' ' ' | sed 's/ $//')"
	if [ -n "$FACTS" ] && [ "$declared_workloads" = "$measured_workloads" ]; then
		pass "the measured table names exactly the 7 Deployments the tree declares"
	else
		fail "the measured table names '$measured_workloads' and the tree declares '$declared_workloads'"
	fi

	check_clean "every workload runs as the measured uid and gid, non-root, with RuntimeDefault seccomp" \
		"$(identity_findings "$FACTS")"

	check_clean "every workload container refuses privilege escalation and drops ALL capabilities" \
		"$(container_security_findings "$FACTS")"

	check_clean "every container declares imagePullPolicy: IfNotPresent" \
		"$(image_pull_findings "$FACTS")"

	check_clean "every container declares non-zero cpu and memory requests and limits" \
		"$(resources_findings "$FACTS")"

	check_clean "every Deployment declares the readiness probe its workload answers" \
		"$(readiness_findings "$FACTS")"

	check_clean "no manifest declares a livenessProbe" \
		"$(grep -rnE '^[[:space:]]*livenessProbe:' "$MANIFESTS" 2>/dev/null | sed "s|^$MANIFESTS/||" || true)"

	check_clean "every workload says at the probe site what a livenessProbe would have to prove" \
		"$(liveness_comment_findings "$FACTS")"

	check_clean "readOnlyRootFilesystem is true on exactly the four measured workloads, each with its tmpfs" \
		"$(root_filesystem_findings "$FACTS")"

	check_clean "prepare-data hands the two hostPath volumes over and takes nothing but CHOWN" \
		"$(prepare_data_findings "$FACTS")"
fi

# ------------------------------------------
# The image the node has to be able to execute
# ------------------------------------------

echo
echo "== 7b. the cluster entry point registers the emulator the gateway image needs"

CLUSTER_SCRIPT="$REPO_ROOT/k8s/kind/create-kind-cluster.sh"
GATEWAY_MANIFEST="$MANIFESTS/applications/api-gateway.yml"
check_clean "the emulator is registered before the cluster and the arm64-only pin says why" \
	"$(arm64_emulation_findings "$CLUSTER_SCRIPT" "$GATEWAY_MANIFEST")"

# ------------------------------------------
# The fixtures. Each one is the tree with exactly one rule broken, so a checker that stopped working
# fails here instead of printing the same green line as a clean read.
# ------------------------------------------
echo
echo "== 8. every checker rejects the input it exists for"

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

	# The two shapes the database wiring check exists for, each as one edit of the real tree: the
	# Service the ConfigMap names is gone, and the Service is there but its MySQL declares a different
	# database. The second one is the shape that shipped -- the Service existed, so a check that only
	# looked for it called the tree clean.
	cp -R "$MANIFESTS" "$TMP/fx-db-absent"
	sed -i 's/^  DB_ADDR: "keycloak-mysql"$/  DB_ADDR: "keycloak-db-absent"/' "$TMP/fx-db-absent/infra/keycloak.yml"
	check_rejected "a ConfigMap that names a database Service the tree does not have" \
		"$(db_addr_findings "$TMP/fx-db-absent")"

	cp -R "$MANIFESTS" "$TMP/fx-db-mismatch"
	sed -i 's/^  DB_DATABASE: "keycloak"$/  DB_DATABASE: "not_the_declared_database"/' "$TMP/fx-db-mismatch/infra/keycloak.yml"
	check_rejected "a Service whose MySQL declares a different database" \
		"$(db_addr_findings "$TMP/fx-db-mismatch")"

	if [ -f "$README" ]; then
		cp "$README" "$TMP/fx-readme.md"
		printf '\n```bash\n./k8s/kind/does-not-exist.sh\n```\n' >>"$TMP/fx-readme.md"
		check_rejected "a README path that does not exist" "$(readme_path_findings "$TMP/fx-readme.md")"
	else
		fail "cannot build the README fixture: $README is missing"
	fi

	# The namespace fixtures. The object and the transform are two edits that have to agree, so the
	# first fixture edits one of them: a checker that read only one would call that clean.
	NS_PATH="$(files_with_kind "$MANIFESTS" Namespace | head -n1)"
	if [ -n "$NS_PATH" ]; then
		NS_REL="${NS_PATH#"$MANIFESTS"/}"
		cp -R "$MANIFESTS" "$TMP/fx-ns-drift"
		sed -i 's/^  name: .*/  name: not-the-declared-namespace/' "$TMP/fx-ns-drift/$NS_REL"
		check_rejected "a Namespace object that disagrees with the transform" \
			"$(namespace_findings "$TMP/fx-ns-drift")"

		cp -R "$MANIFESTS" "$TMP/fx-ns-absent"
		sed -i 's/^kind: Namespace$/kind: NamespaceLost/' "$TMP/fx-ns-absent/$NS_REL"
		check_rejected "a tree whose Namespace object is gone" \
			"$(namespace_findings "$TMP/fx-ns-absent")"
	else
		fail "cannot build the namespace fixtures: no Namespace object under $MANIFESTS"
	fi

	cp -R "$REPO_ROOT/k8s" "$TMP/fx-fqdn"
	printf '  LOKI_URL: "http://loki.default.svc.cluster.local:3100/loki/api/v1/push"\n' \
		>>"$TMP/fx-fqdn/manifests/applications/common-config.yml"
	check_rejected "a service name spelled as a cluster FQDN" "$(cluster_local_findings "$TMP/fx-fqdn")"

	cp -R "$REPO_ROOT/k8s" "$TMP/fx-no-emulator"
	sed -i '/docker run[^|]*tonistiigi\/binfmt:/d' "$TMP/fx-no-emulator/kind/create-kind-cluster.sh"
	check_rejected "a cluster entry point that never registers the arm64 emulator" \
		"$(arm64_emulation_findings "$TMP/fx-no-emulator/kind/create-kind-cluster.sh" "$GATEWAY_MANIFEST")"

	# The command turned into a sentence: the emulator is named, and named in a comment, so a checker
	# that greps the file instead of the commands calls this clean. The block is removed and the mention
	# appended as a comment rather than edited in place, because the call sits inside `if ! ...; then`
	# and a half-commented block would not parse.
	cp -R "$REPO_ROOT/k8s" "$TMP/fx-emulator-comment"
	awk '
		/^[[:space:]]*if ! docker run .*binfmt/ { skip = 1; next }
		skip && /^[[:space:]]*fi[[:space:]]*$/ { skip = 0; next }
		{ print }
	' "$TMP/fx-no-emulator/kind/create-kind-cluster.sh" >"$TMP/fx-emulator-comment/kind/create-kind-cluster.sh"
	printf '# docker run --rm --privileged tonistiigi/binfmt:qemu-v10.2.3 --install arm64\n' \
		>>"$TMP/fx-emulator-comment/kind/create-kind-cluster.sh"
	check_rejected "a cluster entry point that only names the emulator in a comment" \
		"$(arm64_emulation_findings "$TMP/fx-emulator-comment/kind/create-kind-cluster.sh" "$GATEWAY_MANIFEST")"

	declared_for_fixture="$(declared_namespace "$MANIFESTS")"
	if [ -n "$declared_for_fixture" ]; then
		cp -R "$MANIFESTS" "$TMP/fx-ns-repeat"
		printf '  NS: "%s"\n' "$declared_for_fixture" >>"$TMP/fx-ns-repeat/infra/loki.yml"
		check_rejected "a third copy of the namespace name" \
			"$(namespace_literal_findings "$TMP/fx-ns-repeat")"
	else
		fail "cannot build the namespace-repeat fixture: the kustomization declares no namespace"
	fi

	if [ -n "$POLICY_REL" ] && [ -f "${MANIFESTS:-}/$POLICY_REL" ]; then
		cp -R "$MANIFESTS" "$TMP/fx-deny-one-way"
		awk '/^---$/ { doc++ } doc == 1 && $0 == "    - Egress" { next } { print }' \
			"$MANIFESTS/$POLICY_REL" >"$TMP/fx-deny-one-way/$POLICY_REL"
		check_rejected "a default deny that names only one direction" \
			"$(default_deny_findings "$TMP/fx-deny-one-way/$POLICY_REL")"

		cp -R "$MANIFESTS" "$TMP/fx-pair"
		sed -i '0,/^              app: tempo$/s//              app: no-longer-tempo/' "$TMP/fx-pair/$POLICY_REL"
		check_rejected "an egress rule whose destination has no ingress rule" \
			"$(unpaired_flows "$TMP/fx-pair/$POLICY_REL")"
		check_rejected "a flow the manifests need that no egress rule allows" \
			"$(declared_flow_findings "$TMP/fx-pair/$POLICY_REL")"

		cp -R "$MANIFESTS" "$TMP/fx-blanket"
		printf '%s\n' \
			'---' \
			'apiVersion: networking.k8s.io/v1' \
			'kind: NetworkPolicy' \
			'metadata:' \
			'  name: everything' \
			'spec:' \
			'  podSelector: {}' \
			'  policyTypes:' \
			'    - Egress' \
			'  egress:' \
			'    - to:' \
			'        - podSelector: {}' \
			'      ports:' \
			'        - protocol: TCP' \
			'          port: 3306' \
			>>"$TMP/fx-blanket/$POLICY_REL"
		check_rejected "a rule that allows every peer" \
			"$(blanket_peer_findings "$TMP/fx-blanket/$POLICY_REL")"

		cp -R "$MANIFESTS" "$TMP/fx-cross"
		printf '%s\n' \
			'---' \
			'apiVersion: networking.k8s.io/v1' \
			'kind: NetworkPolicy' \
			'metadata:' \
			'  name: another-namespace' \
			'spec:' \
			'  podSelector:' \
			'    matchLabels:' \
			'      app: keycloak' \
			'  policyTypes:' \
			'    - Egress' \
			'  egress:' \
			'    - to:' \
			'        - namespaceSelector:' \
			'            matchLabels:' \
			'              kubernetes.io/metadata.name: default' \
			'          podSelector:' \
			'            matchLabels:' \
			'              app: keycloak-mysql' \
			'      ports:' \
			'        - protocol: TCP' \
			'          port: 3306' \
			>>"$TMP/fx-cross/$POLICY_REL"
		check_rejected "a flow that crosses the namespace boundary" \
			"$(cross_namespace_findings "$TMP/fx-cross/$POLICY_REL")"
	else
		fail "cannot build the network boundary fixtures: no NetworkPolicy manifest was found"
	fi

	# The hardening fixtures. Each one breaks exactly one construct, because the checker that proves the
	# tree cannot also prove itself: a reader that had stopped understanding the manifests would print
	# the same green line for the tree and for the defect.
	cp -R "$MANIFESTS" "$TMP/fx-hard-uid"
	sed -i '0,/^        runAsUser: 999$/s//        runAsUser: 1000/' "$TMP/fx-hard-uid/infra/keycloak-mysql.yml"
	check_rejected "a pod uid that disagrees with the measured one" \
		"$(identity_findings "$(deployment_facts "$TMP/fx-hard-uid")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-override"
	awk '{ if ($0 == "            allowPrivilegeEscalation: false") print "            runAsUser: 0"; print }' \
		"$MANIFESTS/infra/grafana.yml" >"$TMP/fx-hard-override/infra/grafana.yml"
	check_rejected "a container-level uid that overrides the measured one" \
		"$(identity_findings "$(deployment_facts "$TMP/fx-hard-override")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-escape"
	sed -i '/^            allowPrivilegeEscalation: false$/d' "$TMP/fx-hard-escape/infra/grafana.yml"
	check_rejected "a container that does not refuse privilege escalation" \
		"$(container_security_findings "$(deployment_facts "$TMP/fx-hard-escape")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-caps"
	sed -i 's/^                - ALL$/                - NET_BIND_SERVICE/' "$TMP/fx-hard-caps/infra/prometheus.yml"
	check_rejected "a container that drops something other than ALL" \
		"$(container_security_findings "$(deployment_facts "$TMP/fx-hard-caps")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-pull"
	sed -i '/^          imagePullPolicy: IfNotPresent$/d' "$TMP/fx-hard-pull/infra/loki.yml"
	check_rejected "a container with no imagePullPolicy" \
		"$(image_pull_findings "$(deployment_facts "$TMP/fx-hard-pull")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-pull-always"
	sed -i 's/^          imagePullPolicy: IfNotPresent$/          imagePullPolicy: Always/' \
		"$TMP/fx-hard-pull-always/applications/api-gateway.yml"
	check_rejected "a container that would pull the gateway's :latest tag" \
		"$(image_pull_findings "$(deployment_facts "$TMP/fx-hard-pull-always")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-zero"
	sed -i 's/^              memory: 384Mi$/              memory: 0/' "$TMP/fx-hard-zero/infra/prometheus.yml"
	check_rejected "a zero memory limit" \
		"$(resources_findings "$(deployment_facts "$TMP/fx-hard-zero")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-ready"
	sed -i 's|^              path: /ready$|              path: /-/healthy|' "$TMP/fx-hard-ready/infra/loki.yml"
	check_rejected "a readiness probe against a path the workload does not serve" \
		"$(readiness_findings "$(deployment_facts "$TMP/fx-hard-ready")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-live"
	awk '{ if ($0 == "          readinessProbe:") print "          livenessProbe:\n            tcpSocket:\n              port: 3000"; print }' \
		"$MANIFESTS/infra/grafana.yml" >"$TMP/fx-hard-live/infra/grafana.yml"
	check_rejected "a livenessProbe this tree must not declare" \
		"$(grep -rnE '^[[:space:]]*livenessProbe:' "$TMP/fx-hard-live" 2>/dev/null || true)"

	cp -R "$MANIFESTS" "$TMP/fx-hard-noreason"
	sed -i '/# No livenessProbe:/d' "$TMP/fx-hard-noreason/infra/loki.yml"
	check_rejected "a workload that omits a livenessProbe without saying what one would prove" \
		"$(liveness_comment_findings "$(deployment_facts "$TMP/fx-hard-noreason")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-writable"
	sed -i 's/^            readOnlyRootFilesystem: true$/            readOnlyRootFilesystem: false/' \
		"$TMP/fx-hard-writable/infra/grafana.yml"
	check_rejected "a workload that tolerates a read-only root filesystem and declares it writable" \
		"$(root_filesystem_findings "$(deployment_facts "$TMP/fx-hard-writable")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-notmpfs"
	sed -i '/^            medium: Memory$/d' "$TMP/fx-hard-notmpfs/infra/keycloak-mysql.yml"
	check_rejected "a read-only root filesystem with no tmpfs on the path the image named" \
		"$(root_filesystem_findings "$(deployment_facts "$TMP/fx-hard-notmpfs")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-silent"
	sed -i '/# readOnlyRootFilesystem stays writable/d' "$TMP/fx-hard-silent/infra/keycloak.yml"
	check_rejected "a writable root filesystem with no measured reason at the container" \
		"$(root_filesystem_findings "$(deployment_facts "$TMP/fx-hard-silent")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-shadow"
	awk '
		{
			if ($0 == "            - name: tempo-config") print "            - name: tempo-tmp\n              mountPath: /tmp"
			if ($0 == "        - name: tempo-config") print "        - name: tempo-tmp\n          emptyDir:\n            medium: Memory"
			print
		}
	' "$MANIFESTS/infra/tempo.yml" >"$TMP/fx-hard-shadow/infra/tempo.yml"
	check_rejected "a tmpfs that would shadow the data volume of a writable workload" \
		"$(root_filesystem_findings "$(deployment_facts "$TMP/fx-hard-shadow")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-chown"
	sed -i 's|chown -R 999:999 /var/lib/mysql|chown -R 1000:1000 /var/lib/mysql|' "$TMP/fx-hard-chown/infra/keycloak-mysql.yml"
	check_rejected "a prepare-data that hands the directory to the wrong uid" \
		"$(prepare_data_findings "$(deployment_facts "$TMP/fx-hard-chown")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-chown-cap"
	awk '{ if ($0 == "                - CHOWN") print "                - DAC_OVERRIDE"; print }' \
		"$MANIFESTS/infra/keycloak-mysql.yml" >"$TMP/fx-hard-chown-cap/infra/keycloak-mysql.yml"
	check_rejected "a prepare-data that takes more than CHOWN" \
		"$(prepare_data_findings "$(deployment_facts "$TMP/fx-hard-chown-cap")")"

	cp -R "$MANIFESTS" "$TMP/fx-hard-fourth"
	awk '
		{
			if ($0 == "      containers:") {
				print "      initContainers:"
				print "        - name: prepare-data"
				print "          image: grafana/grafana:10.1.0"
				print "          imagePullPolicy: IfNotPresent"
			}
			print
		}
	' "$MANIFESTS/infra/grafana.yml" >"$TMP/fx-hard-fourth/infra/grafana.yml"
	check_rejected "a fourth workload carrying an init container" \
		"$(prepare_data_findings "$(deployment_facts "$TMP/fx-hard-fourth")")"
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
