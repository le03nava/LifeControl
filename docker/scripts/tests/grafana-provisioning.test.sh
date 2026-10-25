#!/bin/bash
# ============================================
# LifeControl - Grafana dashboard provisioning tests
# ============================================
# Measured on the running dev stack, 2026-10-24:
#
#     sqlite3 -readonly grafana.db "select count(*) from dashboard; select count(*) from alert_rule;"
#     -> 0 and 0
#
# The observability plane collected metrics, logs and traces and presented none of them.
# `docker/config/grafana/` held only `datasources.yml`, and the datasource file is the one Grafana
# config installed into $VOLUMES_ROOT by setup-env.sh -- a copy-if-missing install, so every
# dashboard a human drew by hand lived in a volume that is never versioned, and a fresh
# staging/prod volume started Grafana with an empty home.
#
# The fix is that the dashboards are files in this repository, mounted read-only, so they travel as
# a reviewed commit on every environment. This suite pins the five ways that can unravel:
#
#   (a) the read-only mount disappears from either compose file, or the datasources mount next to
#       it stops being the untouched volume install;
#   (b) the provider file is missing, stops being apiVersion 1, stops pointing inside the mounted
#       path, or re-enables UI edits -- a UI edit would live only in the volume while the
#       repository kept the old version and the next reload silently overwrote it;
#   (c) a JSON file under dashboards/ stops parsing, or loses the structure Grafana needs (uid,
#       title, integer schemaVersion, panels with queries);
#   (d) a dashboard names a datasource the provisioning file does not declare -- a query against a
#       datasource that does not exist renders an empty panel, which is worse than no panel;
#   (e) the frozen list of dashboards stops matching the directory, in either direction, so adding
#       or removing one is a reviewed edit to this file.
#
# Two more checks are pinned because this slice made the decision and a later edit could undo it
# silently:
#
#   (f) every metric name the dashboards query was observed in the running Prometheus before it was
#       written down. The freeze was measured on 2026-10-24 with
#       `curl -s 'http://localhost:9090/api/v1/label/__name__/values'` and every expression here was
#       run through `/api/v1/query` on that stack. A dashboard whose query matches nothing is worse
#       than no dashboard, and this suite cannot reach Prometheus on its own, so the names are the
#       frozen part: a new metric means editing the list below, deliberately.
#   (g) no *dashboard panel* carries an alert rule. The first alert rule exists since T10, but it is
#       provisioned from `docker/config/grafana/alerting/`, not from a panel: a panel-embedded alert
#       lives in the volume, and `grafana-alerting.test.sh` is the suite that guards the provisioned
#       one.
#
# JSON is parsed with `python3 -`. Why: `docker/scripts/` assumes no `jq` (verify-provisioning-flow.sh
# says so explicitly, and its JSON helpers are grep/sed), and a grep-based structural check cannot
# prove a document parses -- it would pass on a dashboard that Grafana rejects at load time. python3
# is the one JSON-capable interpreter present on the dev host and on the ubuntu-latest runner.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/grafana-provisioning.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
BASE_COMPOSE="$DOCKER_DIR/docker-compose.yml"
PROD_COMPOSE="$DOCKER_DIR/docker-compose.prod.yml"
DASHBOARDS_DIR="$DOCKER_DIR/config/grafana/dashboards"
PROVIDER_FILE="$DASHBOARDS_DIR/dashboards.yml"
DATASOURCES_FILE="$DOCKER_DIR/config/grafana/datasources.yml"
SETUP_ENV="$DOCKER_DIR/scripts/setup-env.sh"

# Where the repository directory is mounted inside the Grafana container. The provider file's
# `options.path` has to resolve inside this path or Grafana reads the mount and finds nothing.
MOUNT_TARGET="/etc/grafana/provisioning/dashboards"
EXPECTED_MOUNT="./config/grafana/dashboards:/etc/grafana/provisioning/dashboards:ro"
EXPECTED_DATASOURCES_MOUNT='${VOLUMES_ROOT:?VOLUMES_ROOT must be set}/grafana/config:/etc/grafana/provisioning/datasources:ro'

# The dashboards, frozen: relative path | uid | title, pre-sorted. Both directions are compared
# below, so a file added or removed without editing this table fails the suite.
EXPECTED_DASHBOARDS="json/saturation.json|lc-saturation|LifeControl - Saturation
json/service-health.json|lc-service-health|LifeControl - Service health"

# Metric names measured present in Prometheus on 2026-10-24, before a single query was written:
#     curl -s 'http://localhost:9090/api/v1/label/__name__/values' | grep -E 'http_server_requests|hikaricp'
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

# ------------------------------------------
# Compose readers. This repository's Docker toolchain has no YAML parser, so the two shapes needed
# are read directly: one service block, and the `- mount` entries of its `volumes:` list. Quotes and
# trailing comments are stripped so what is compared is the mount spec itself.
# ------------------------------------------
service_block() {
	awk -v svc="$2" '
		$0 ~ "^  " svc ":[[:space:]]*$" { in_service = 1; next }
		in_service && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
		in_service { print }
	' "$1"
}

volume_mounts() {
	service_block "$1" "$2" | awk '
		/^    volumes:[[:space:]]*$/ { in_volumes = 1; next }
		in_volumes && /^    [A-Za-z0-9_-]+:/ { in_volumes = 0 }
		!in_volumes { next }
		/^      - / {
			mount = $0
			sub(/^      - /, "", mount)
			sub(/[[:space:]]+#.*$/, "", mount)
			gsub(/"/, "", mount)
			sub(/[[:space:]]+$/, "", mount)
			print mount
		}
	'
}

# yaml_value <file> <key>: the last value declared for <key> at any indentation. Comments and
# quotes are stripped.
yaml_value() {
	awk -v key="$2" '
		{
			line = $0
			sub(/^[[:space:]]*-?[[:space:]]*/, "", line)
			if (index(line, key ":") == 1) {
				value = line
				sub(/^[^:]*:[[:space:]]*/, "", value)
				sub(/[[:space:]]+#.*$/, "", value)
				gsub(/"/, "", value)
				sub(/[[:space:]]+$/, "", value)
				last = value
			}
		}
		END { if (last != "") print last }
	' "$1"
}

# datasource_block <name>: the lines of one datasource entry in datasources.yml.
datasource_block() {
	awk -v name="$1" '
		$0 ~ "^  - name: " name "[[:space:]]*$" { in_block = 1 }
		in_block && /^  - name: / && $0 !~ "^  - name: " name "[[:space:]]*$" { exit }
		in_block { print }
	' "$DATASOURCES_FILE"
}

# ------------------------------------------
# 1. Both compose files mount the dashboards read-only into the grafana service, and the datasources
#    mount next to it is still the volume install this slice deliberately did not touch.
# ------------------------------------------
check_compose_file() {
	local compose_file="$1"
	local label="$2"
	local mounts

	if [ ! -f "$compose_file" ]; then
		fail "$label: file is missing"
		return
	fi

	mounts="$(volume_mounts "$compose_file" grafana)"

	if printf '%s\n' "$mounts" | grep -qxF "$EXPECTED_MOUNT"; then
		pass "$label: grafana mounts the dashboards read-only at $MOUNT_TARGET"
	else
		fail "$label: the grafana service does not mount ./config/grafana/dashboards:ro at $MOUNT_TARGET -- Grafana would start with whatever the volume happens to hold"
	fi

	if printf '%s\n' "$mounts" | grep -qxF "$EXPECTED_DATASOURCES_MOUNT"; then
		pass "$label: the datasources mount is unchanged (still the setup-env.sh volume install)"
	else
		fail "$label: the datasources mount changed; this slice must not alter it (expected $EXPECTED_DATASOURCES_MOUNT)"
	fi

	if printf '%s\n' "$mounts" | grep -qE 'VOLUMES_ROOT[^[:space:]]*grafana/(config|dashboards)[^[:space:]]*:[^[:space:]]*dashboards'; then
		fail "$label: a dashboards directory is mounted from \$VOLUMES_ROOT as well -- two sources of truth, and the volume one is the copy nobody reviews"
	else
		pass "$label: the dashboards are not installed from \$VOLUMES_ROOT (the repository is the only source)"
	fi
}

check_compose_file "$BASE_COMPOSE" "docker/docker-compose.yml"
check_compose_file "$PROD_COMPOSE" "docker/docker-compose.prod.yml"

# ------------------------------------------
# 2. The provider file: present, inside the mounted directory, apiVersion 1, one file provider
#    pointing inside the mount, and UI edits/updates off so the repository is the only way in.
# ------------------------------------------
if [ ! -f "$PROVIDER_FILE" ]; then
	fail "missing docker/config/grafana/dashboards/dashboards.yml: without a provider file Grafana reads no dashboard from the mount"
else
	provider_count="$(grep -cE '^  - name:[[:space:]]*[^[:space:]]' "$PROVIDER_FILE")"

	if [ "$(yaml_value "$PROVIDER_FILE" apiVersion)" = "1" ]; then
		pass "provider: apiVersion is 1"
	else
		fail "provider: apiVersion is '$(yaml_value "$PROVIDER_FILE" apiVersion)', expected 1"
	fi

	if [ "$provider_count" = "1" ]; then
		pass "provider: exactly one provider is declared"
	else
		fail "provider: $provider_count providers are declared, expected 1"
	fi

	if [ "$(yaml_value "$PROVIDER_FILE" type)" = "file" ]; then
		pass "provider: type is file"
	else
		fail "provider: type is '$(yaml_value "$PROVIDER_FILE" type)', expected file"
	fi

	provider_path="$(yaml_value "$PROVIDER_FILE" path)"
	case "$provider_path" in
		"$MOUNT_TARGET"/?*)
			provider_rel="${provider_path#"$MOUNT_TARGET"/}"
			if [ -d "$DASHBOARDS_DIR/$provider_rel" ]; then
				pass "provider: options.path $provider_path resolves to docker/config/grafana/dashboards/$provider_rel, inside the mount"
			else
				fail "provider: options.path $provider_path resolves to docker/config/grafana/dashboards/$provider_rel, which is not a directory in the repository"
			fi
			;;
		"")
			fail "provider: options.path is not declared, so Grafana scans nothing"
			;;
		*)
			fail "provider: options.path is '$provider_path', outside the mounted path $MOUNT_TARGET"
			;;
	esac

	if [ "$(yaml_value "$PROVIDER_FILE" allowUiUpdates)" = "false" ]; then
		pass "provider: allowUiUpdates is false (a UI edit cannot diverge from the repository)"
	else
		fail "provider: allowUiUpdates is '$(yaml_value "$PROVIDER_FILE" allowUiUpdates)', expected false"
	fi

	if [ "$(yaml_value "$PROVIDER_FILE" foldersFromFilesStructure)" = "false" ]; then
		pass "provider: foldersFromFilesStructure is false (one folder, independent of the directory layout)"
	else
		fail "provider: foldersFromFilesStructure is '$(yaml_value "$PROVIDER_FILE" foldersFromFilesStructure)', expected false"
	fi

	if grep -qE '^[^#]*\bcp\b[^#]*dashboard' "$SETUP_ENV" 2>/dev/null; then
		fail "setup-env.sh copies the dashboards into \$VOLUMES_ROOT: a copy-if-missing install is how a dashboard freezes at whatever was installed first"
	else
		pass "setup-env.sh does not copy the dashboards into \$VOLUMES_ROOT"
	fi
fi

# ------------------------------------------
# 3-7. Everything that needs the documents themselves, read in one pass: structure, the frozen list,
#      datasource references, metric names and the deliberate absence of alert rules.
# ------------------------------------------
PY_OUT=""
PY_STATUS=1

if command -v python3 >/dev/null 2>&1; then
	PY_OUT="$(python3 - "$DASHBOARDS_DIR" 2>&1 <<'PY'
import json
import os
import re
import sys

root = sys.argv[1]
problems = 0

AGGREGATORS = {
    "sum", "avg", "min", "max", "count", "group", "and", "or", "unless",
    "bool", "offset", "by", "without", "on", "ignoring", "group_left", "group_right",
}


def metric_names(expr):
    """Metric names referenced by one PromQL expression.

    Quoted strings are blanked first (label values are not metrics), range selectors are removed
    (`[5m]` names no metric), grouping clauses are removed (`by (job)` names labels, not metrics),
    and anything followed by `(` is a function call or an aggregation operator. What is left is a
    metric name or a label matcher, and a matcher is recognised by the `=` that follows it.
    """
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', expr)
    text = re.sub(r'\[[^\]]*\]', ' ', text)
    text = re.sub(r'\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)', ' ', text)
    names = []
    for match in re.finditer(r"[A-Za-z_][A-Za-z0-9_]*", text):
        name = match.group(0)
        rest = text[match.end():].lstrip()
        if rest.startswith("("):
            continue
        if re.match(r"(=~|!~|!=|=)", rest):
            continue
        if name in AGGREGATORS:
            continue
        if name not in names:
            names.append(name)
    return names


def datasource_refs(node):
    """Every `datasource` value in the document, at any depth."""
    refs = []
    if isinstance(node, dict):
        if "datasource" in node:
            refs.append(node["datasource"])
        for value in node.values():
            refs.extend(datasource_refs(value))
    elif isinstance(node, list):
        for value in node:
            refs.extend(datasource_refs(value))
    return refs


def classify(ref):
    if ref is None:
        return ("default", "-")
    if isinstance(ref, str):
        return ("name", ref)
    if isinstance(ref, dict):
        uid = ref.get("uid")
        ref_type = ref.get("type")
        if isinstance(uid, str) and uid.startswith("-- "):
            return ("builtin", uid)
        if isinstance(uid, str) and uid:
            return ("uid", uid)
        if isinstance(ref_type, str) and ref_type:
            return ("type", ref_type)
    return ("unknown", json.dumps(ref, sort_keys=True))


for dirpath, dirnames, filenames in os.walk(root):
    dirnames.sort()
    for filename in sorted(filenames):
        if not filename.endswith(".json"):
            continue
        path = os.path.join(dirpath, filename)
        rel = os.path.relpath(path, root)
        print("FILE\t%s" % rel)
        try:
            with open(path, encoding="utf-8") as handle:
                doc = json.load(handle)
        except (ValueError, OSError) as exc:
            print("INVALID\t%s\tdoes not parse as JSON: %s" % (rel, exc))
            problems += 1
            continue
        if not isinstance(doc, dict):
            print("INVALID\t%s\tis not a JSON object" % rel)
            problems += 1
            continue

        uid = doc.get("uid")
        title = doc.get("title")
        panels = doc.get("panels")

        if not isinstance(uid, str) or not uid:
            print("INVALID\t%s\tmissing a string \"uid\"" % rel)
            problems += 1
            uid = ""
        if not isinstance(title, str) or not title:
            print("INVALID\t%s\tmissing a string \"title\"" % rel)
            problems += 1
            title = ""
        if not isinstance(doc.get("schemaVersion"), int):
            print("INVALID\t%s\tmissing an integer \"schemaVersion\"" % rel)
            problems += 1

        if not isinstance(panels, list) or not panels:
            print("INVALID\t%s\t\"panels\" is not a non-empty list" % rel)
            problems += 1
            print("ENTRY\t%s\t%s\t%s\t0" % (rel, uid, title))
            continue

        print("ENTRY\t%s\t%s\t%s\t%d" % (rel, uid, title, len(panels)))

        metrics = []
        for index, panel in enumerate(panels):
            if not isinstance(panel, dict):
                print("INVALID\t%s\tpanel %d is not an object" % (rel, index))
                problems += 1
                continue
            panel_title = panel.get("title")
            if not isinstance(panel_title, str) or not panel_title:
                print("INVALID\t%s\tpanel %d has no title" % (rel, index))
                problems += 1
                panel_title = "panel %d" % index
            if not isinstance(panel.get("type"), str):
                print("INVALID\t%s\tpanel '%s' has no string \"type\"" % (rel, panel_title))
                problems += 1
            targets = panel.get("targets")
            if not isinstance(targets, list) or not targets:
                print("INVALID\t%s\tpanel '%s' has no queries" % (rel, panel_title))
                problems += 1
                continue
            for target in targets:
                expr = target.get("expr") if isinstance(target, dict) else None
                if not isinstance(expr, str) or not expr.strip():
                    print("INVALID\t%s\tpanel '%s' has a query with no expression" % (rel, panel_title))
                    problems += 1
                    continue
                if "${" in expr:
                    print("INVALID\t%s\tpanel '%s' has an unexpanded template in its query" % (rel, panel_title))
                    problems += 1
                for name in metric_names(expr):
                    if name not in metrics:
                        metrics.append(name)
            if panel.get("alert"):
                print("ALERT\t%s\t%s" % (rel, panel_title))

        for name in metrics:
            print("METRIC\t%s\t%s" % (rel, name))

        for ref in datasource_refs(doc):
            kind, value = classify(ref)
            print("DATASOURCE\t%s\t%s\t%s" % (rel, kind, value))

sys.exit(1 if problems else 0)
PY
)"
	PY_STATUS=$?
else
	fail "python3 is required to parse the dashboards; it is assumed by this repository's docker scripts"
fi

JSON_FILE_COUNT=0
while IFS=$'\t' read -r kind field_a field_b _field_c; do
	case "$kind" in
		FILE)
			JSON_FILE_COUNT=$((JSON_FILE_COUNT + 1))
			;;
		INVALID)
			fail "dashboard '$field_a': $field_b"
			;;
	esac
done <<< "$PY_OUT"

if [ "$PY_STATUS" -ne 0 ]; then
	fail "the dashboards did not all parse with the structure Grafana provisions (see the failures above)"
elif [ "$JSON_FILE_COUNT" -eq 0 ]; then
	fail "no JSON dashboard found under docker/config/grafana/dashboards: Grafana would provision an empty folder"
else
	pass "$JSON_FILE_COUNT dashboard file(s) parse as JSON with a uid, a title, an integer schemaVersion and panels that carry queries"
fi

# ------------------------------------------
# 4. The frozen list, both directions: a renamed file, a changed uid/title or a new dashboard is a
#    reviewed edit to this suite.
# ------------------------------------------
EXPECTED_TABLE="$(printf '%s\n' "$EXPECTED_DASHBOARDS" | sort)"
ACTUAL_TABLE="$(printf '%s\n' "$PY_OUT" | awk -F'\t' '$1 == "ENTRY" { printf "%s|%s|%s\n", $2, $3, $4 }' | sort)"

if [ "$ACTUAL_TABLE" = "$EXPECTED_TABLE" ]; then
	pass "the dashboard directory matches the frozen list (2 dashboards, uid and title each)"
else
	fail "the dashboard directory no longer matches the frozen list in this suite"
	printf '      expected:\n'
	printf '%s\n' "$EXPECTED_TABLE" | sed 's/^/        /'
	printf '      found:\n'
	printf '%s\n' "$ACTUAL_TABLE" | sed 's/^/        /'
fi

# ------------------------------------------
# 5. Datasource references resolve to what the provisioning file declares. A panel with no
#    `datasource` uses Grafana's default, which is why the default is pinned right below: a dashboard
#    that queries nothing is the failure this section exists to prevent.
# ------------------------------------------
DECLARED_NAMES=""
DECLARED_TYPES=""
if [ -f "$DATASOURCES_FILE" ]; then
	DECLARED_NAMES="$(grep -E '^[[:space:]]+- name:' "$DATASOURCES_FILE" | sed -e 's/^[[:space:]]*- name:[[:space:]]*//' -e 's/[[:space:]]*$//')"
	DECLARED_TYPES="$(grep -E '^[[:space:]]+type:' "$DATASOURCES_FILE" | sed -e 's/^[[:space:]]*type:[[:space:]]*//' -e 's/[[:space:]]*$//')"
else
	fail "missing docker/config/grafana/datasources.yml: nothing the dashboards query would resolve"
fi

DATASOURCE_REFS=0
while IFS=$'\t' read -r kind field_a ref_kind ref_value; do
	[ "$kind" = "DATASOURCE" ] || continue
	DATASOURCE_REFS=$((DATASOURCE_REFS + 1))
	case "$ref_kind" in
		name|uid)
			if printf '%s\n' "$DECLARED_NAMES" | grep -qxF "$ref_value"; then
				pass "dashboard '$field_a' names datasource '$ref_value', which datasources.yml declares"
			else
				fail "dashboard '$field_a' names datasource '$ref_value', which datasources.yml does not declare (declared: $(printf '%s' "$DECLARED_NAMES" | tr '\n' ' '))"
			fi
			;;
		type)
			if printf '%s\n' "$DECLARED_TYPES" | grep -qxF "$ref_value"; then
				pass "dashboard '$field_a' selects datasource type '$ref_value', which datasources.yml declares"
			else
				fail "dashboard '$field_a' selects datasource type '$ref_value', which datasources.yml does not declare (declared: $(printf '%s' "$DECLARED_TYPES" | tr '\n' ' '))"
			fi
			;;
		builtin)
			pass "dashboard '$field_a' references the built-in datasource '$ref_value'"
			;;
		default)
			pass "dashboard '$field_a' leaves a datasource unset, which means the configured default"
			;;
		*)
			fail "dashboard '$field_a' has a datasource reference this suite cannot resolve: $ref_value"
			;;
	esac
done <<< "$PY_OUT"

if [ "$DATASOURCE_REFS" -eq 0 ]; then
	pass "no dashboard pins a datasource: every panel uses Grafana's default, pinned below"
fi

if printf '%s\n' "$(datasource_block Prometheus)" | grep -qE '^[[:space:]]+isDefault:[[:space:]]*true'; then
	pass "datasources.yml still declares Prometheus as the default (what the unpinned panels query)"
else
	fail "datasources.yml no longer marks Prometheus as the default: the unpinned panels would have nothing to query"
fi

# ------------------------------------------
# 6. Every metric name a query uses was observed in the running stack before it was written down.
# ------------------------------------------
OBSERVED_METRICS="hikaricp_connections_acquire_seconds_count
hikaricp_connections_acquire_seconds_sum
hikaricp_connections_active
hikaricp_connections_max
hikaricp_connections_pending
hikaricp_connections_usage_seconds_count
hikaricp_connections_usage_seconds_sum
http_server_requests_seconds_bucket
http_server_requests_seconds_count
jvm_memory_max_bytes
jvm_memory_used_bytes
process_cpu_usage"

METRIC_REFS=0
while IFS=$'\t' read -r kind field_a metric_name; do
	[ "$kind" = "METRIC" ] || continue
	METRIC_REFS=$((METRIC_REFS + 1))
	if printf '%s\n' "$OBSERVED_METRICS" | grep -qxF "$metric_name"; then
		pass "dashboard '$field_a' queries $metric_name, measured present in Prometheus on 2026-10-24"
	else
		fail "dashboard '$field_a' queries $metric_name, which was never measured in Prometheus -- a query that matches nothing renders an empty panel"
	fi
done <<< "$PY_OUT"

if [ "$METRIC_REFS" -eq 0 ]; then
	fail "no dashboard queries a metric at all"
fi

# ------------------------------------------
# 7. No dashboard panel carries an alert rule. The rule that does exist is provisioned from
#    docker/config/grafana/alerting/ (T10), which grafana-alerting.test.sh guards; this suite keeps
#    the dashboards free of them, because a panel-embedded alert lives in the volume instead of in a
#    reviewed file.
# ------------------------------------------
DASHBOARD_ALERTS=0
while IFS=$'\t' read -r kind field_a field_b; do
	[ "$kind" = "ALERT" ] || continue
	DASHBOARD_ALERTS=$((DASHBOARD_ALERTS + 1))
	fail "dashboard '$field_a', panel '$field_b': carries an alert rule; alerting is provisioned from docker/config/grafana/alerting/ and a panel alert would live only in the volume"
done <<< "$PY_OUT"

if [ "$DASHBOARD_ALERTS" -eq 0 ]; then
	pass "no dashboard carries an alert rule (the rule is provisioned from alerting/, never from a panel)"
fi

if [ -d "$DOCKER_DIR/config/grafana/alerting" ]; then
	pass "docker/config/grafana/alerting exists: alerting is provisioned from the repository, through its own directory and not through dashboard panels"
else
	fail "docker/config/grafana/alerting no longer exists: the contact point, the notification policy and the alert rule are provisioned from there, so removing the directory removes the only notification this stack has"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
