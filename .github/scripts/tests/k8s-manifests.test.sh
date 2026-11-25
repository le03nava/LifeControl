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
app=keycloak -> app=mysql TCP/3306
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
# The fixtures. Each one is the tree with exactly one rule broken, so a checker that stopped working
# fails here instead of printing the same green line as a clean read.
# ------------------------------------------
echo
echo "== 7. every checker rejects the input it exists for"

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
			'              app: mysql' \
			'      ports:' \
			'        - protocol: TCP' \
			'          port: 3306' \
			>>"$TMP/fx-cross/$POLICY_REL"
		check_rejected "a flow that crosses the namespace boundary" \
			"$(cross_namespace_findings "$TMP/fx-cross/$POLICY_REL")"
	else
		fail "cannot build the network boundary fixtures: no NetworkPolicy manifest was found"
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
