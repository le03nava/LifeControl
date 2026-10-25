#!/bin/bash
# ============================================
# LifeControl - Grafana alerting provisioning tests
# ============================================
# T10 of odd/tasks/observability-hardening.md. Decision D5 said the alert rule waits until a
# notification channel exists, because a rule that fires into nothing is exactly the decoration
# this unit exists to remove. The owner named the channel: email, with SMTP pointed at the dev
# Mailpit container and configured through environment variables elsewhere. This suite guards the
# wiring that decision produced, which fails in ways nothing else in the stack notices:
#
#   (a) the repository directories disappear from a compose file (the alerting one, or the SMTP
#       credential mount) -- Grafana would start with no rule and no way to authenticate, and the
#       only symptom is mail that never arrives;
#   (b) the SMTP password becomes an `environment:` value. It travels as a mounted secret for the
#       same reason every other credential in this stack does: an env value lands in
#       `docker inspect` and in compose's rendered output;
#   (c) a provisioning file stops parsing, or says something Grafana refuses -- the whole alerting
#       provisioning pass fails at once (`Failed to provision alerting`), so a typo in one file
#       also removes the rule from the other two;
#   (d) the contact point a policy routes to and the contact point that exists drift apart. The
#       policy names its receiver by string; a rename on one side leaves a rule that fires, a
#       notification policy that routes nowhere, and zero errors anywhere;
#   (e) the rule names a `datasourceUid` that `datasources.yml` does not declare. This is the one
#       failure mode this slice had to design around: a dashboard can omit its datasource and fall
#       back to the default, but an alert rule cannot, so the three datasources gained explicit,
#       stable uids. A stale host copy of `datasources.yml` (setup-env.sh installs it
#       copy-if-missing) leaves the rule pointing at a uid that does not exist -- see the compose
#       comment and the record for what that looks like in the log;
#   (f) the email destination stops being configurable without editing YAML. It is read through
#       Grafana's own `$__env{VAR}` interpolation, measured on the pinned image (an unset variable
#       interpolates to the empty string and fails the provisioning pass loudly, which is why the
#       compose file carries a default);
#   (g) the D4 numbers move. They are starting numbers to be adjusted, and adjusting them is a
#       reviewed edit to this file, not a quiet one;
#   (h) a dashboard starts carrying an alert rule again. The rule has one home, `alerting/`.
#
# YAML is read with `awk`, the way every other suite in this repository reads it: the docker
# toolchain has no YAML library, and `python3` on this host has no `yaml` module (measured:
# `python3 -c 'import yaml'` -> ModuleNotFoundError). What `awk` can prove is structure --
# indentation, one entry per level, the keys each file is allowed to declare -- and structure is
# where a provisioning typo lives. The authoritative parse is Grafana's own loader: the three files
# in this directory were provisioned into a throwaway container of the pinned image and the rule,
# the contact point and the policy were read back through `/api/v1/provisioning/{alert-rules,
# contact-points,policies}` with a clean log (recorded in odd/tasks/observability-hardening.md).
# JSON (the dashboards) is parsed with `python3 -`, as in grafana-provisioning.test.sh.
#
# No Docker daemon, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/grafana-alerting.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
BASE_COMPOSE="$DOCKER_DIR/docker-compose.yml"
PROD_COMPOSE="$DOCKER_DIR/docker-compose.prod.yml"
ALERTING_DIR="$DOCKER_DIR/config/grafana/alerting"
CONTACT_POINTS="$ALERTING_DIR/contact-points.yml"
POLICIES="$ALERTING_DIR/policies.yml"
RULES="$ALERTING_DIR/rules.yml"
DATASOURCES_FILE="$DOCKER_DIR/config/grafana/datasources.yml"
DASHBOARDS_DIR="$DOCKER_DIR/config/grafana/dashboards"
ENTRYPOINT="$DOCKER_DIR/entrypoints/grafana-entrypoint.sh"

ALERTING_MOUNT="./config/grafana/alerting:/etc/grafana/provisioning/alerting:ro"
SMTP_SECRET_MOUNT="./secrets/smtp_password:/run/secrets/smtp_password:ro"
SMTP_SECRET_PATH="/run/secrets/smtp_password"

# Frozen: the contact point the rule notifies through. `name|type|uid`.
EXPECTED_CONTACT_POINT="lifecontrol-email|email|lc-email-alerts"

# Frozen: the rule. `uid|title|for|threshold`. The threshold is D4's 1% and the duration is D4's
# 5 minutes; both are starting numbers, and changing either is an edit to this line.
EXPECTED_RULE="lc-server-error-ratio|Server error ratio above 1% over 5 minutes|5m|0.01"

# Frozen: the datasources' uids. They became explicit in this slice because the rule cannot omit
# its datasource the way a dashboard panel can. `name|uid`.
EXPECTED_UIDS="Prometheus|lc-prometheus
Loki|lc-loki
Tempo|lc-tempo"

# The metric pair the dashboards already draw, and the grouping the rule promises.
ERROR_RATIO_METRIC="http_server_requests_seconds_count"
SERVER_ERROR_LABEL='outcome="SERVER_ERROR"'

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
# Compose readers: one service block, the `- mount` entries of its volumes list, and its
# environment entries in either shape (map `KEY: value` or list `- KEY=value`). Quotes and trailing
# comments are stripped so what is compared is the value itself.
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

# service_env <compose file> <service>: one `KEY=value` per environment entry, both shapes.
service_env() {
	service_block "$1" "$2" | awk '
		/^    environment:[[:space:]]*$/ { in_env = 1; next }
		in_env && /^    [A-Za-z0-9_-]+:/ { in_env = 0 }
		!in_env { next }
		/^      - / {
			entry = $0
			sub(/^      - /, "", entry)
			sub(/[[:space:]]+#.*$/, "", entry)
			gsub(/"/, "", entry)
			sub(/[[:space:]]+$/, "", entry)
			print entry
			next
		}
		/^      [A-Za-z_][A-Za-z0-9_]*:/ {
			entry = $0
			sub(/^      /, "", entry)
			key = entry
			sub(/:.*/, "", key)
			value = entry
			sub(/^[^:]*:[[:space:]]*/, "", value)
			sub(/[[:space:]]+#.*$/, "", value)
			gsub(/"/, "", value)
			sub(/[[:space:]]+$/, "", value)
			printf "%s=%s\n", key, value
			next
		}
	'
}

env_value() {
	service_env "$1" "$2" | awk -F= -v key="$3" '$1 == key { sub(/^[^=]*=/, "", $0); print; exit }'
}

# ------------------------------------------
# YAML readers. `yaml_scalar <file> <key>` is the last value declared for <key> at any indentation.
# `yaml_sequence <file> <key>` prints the indented entries of the first list <key>: declares, one
# per line, with the `- ` marker removed. `yaml_top_keys <file>` prints every top-level key.
# ------------------------------------------
yaml_scalar() {
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

yaml_top_keys() {
	awk '
		/^#/ { next }
		/^[[:space:]]*$/ { next }
		/^[^[:space:]]/ {
			key = $0
			sub(/:.*/, "", key)
			print key
		}
	' "$1"
}

# yaml_count <file> <regex>: how many lines match, for "exactly one entry" assertions.
yaml_count() {
	grep -cE -- "$2" "$1" 2>/dev/null || true
}

# check_no_tabs <file> <label>: a tab in a YAML file is a parse error in places nobody looks.
check_no_tabs() {
	if grep -qP '\t' "$1" 2>/dev/null; then
		fail "$2: contains a tab character, which YAML rejects as indentation"
	else
		pass "$2: indentation uses spaces only"
	fi
}

# ------------------------------------------
# 1. Both compose files mount the repository's alerting directory and the SMTP credential, and
#    declare the SMTP variables. The dev defaults are what make `deploy.sh dev up` deliver mail
#    into Mailpit without touching a single file.
# ------------------------------------------
check_compose_file() {
	local compose_file="$1"
	local label="$2"
	local mounts
	local value

	if [ ! -f "$compose_file" ]; then
		fail "$label: file is missing"
		return
	fi

	mounts="$(volume_mounts "$compose_file" grafana)"

	if printf '%s\n' "$mounts" | grep -qxF "$ALERTING_MOUNT"; then
		pass "$label: grafana mounts ./config/grafana/alerting read-only at /etc/grafana/provisioning/alerting"
	else
		fail "$label: the grafana service does not mount $ALERTING_MOUNT -- Grafana would start with no alert rule, no contact point and no policy"
	fi

	if printf '%s\n' "$mounts" | grep -qxF "$SMTP_SECRET_MOUNT"; then
		pass "$label: grafana mounts the SMTP credential read-only at $SMTP_SECRET_PATH"
	else
		fail "$label: the grafana service does not mount $SMTP_SECRET_MOUNT -- a relay that requires auth could never be authenticated"
	fi

	if printf '%s\n' "$mounts" | grep -qE 'VOLUMES_ROOT[^[:space:]]*grafana/(alerting|config)[^[:space:]]*alerting'; then
		fail "$label: an alerting directory is also mounted from \$VOLUMES_ROOT -- two sources of truth for the rule"
	else
		pass "$label: alerting is not installed from \$VOLUMES_ROOT (the repository is the only source)"
	fi

	# The variables Grafana reads. Their absence is silent: Grafana simply keeps `smtp.enabled =
	# false` and every notification is dropped after the fact.
	for key in GF_SMTP_ENABLED GF_SMTP_FROM_ADDRESS GF_SMTP_FROM_NAME GF_SMTP_USER GRAFANA_ALERT_EMAIL; do
		if [ -n "$(env_value "$compose_file" grafana "$key")" ]; then
			pass "$label: grafana declares $key"
		else
			fail "$label: grafana does not declare $key"
		fi
	done

	value="$(env_value "$compose_file" grafana GF_SMTP_HOST)"
	case "$value" in
		'${SMTP_HOST:-mailpit}:${SMTP_PORT:-1025}')
			pass "$label: GF_SMTP_HOST is built from SMTP_HOST:SMTP_PORT with the dev relay as its default (Grafana has no separate smtp.port setting)"
			;;
		"")
			fail "$label: grafana does not declare GF_SMTP_HOST -- Grafana would fall back to localhost:25 and nothing would be delivered"
			;;
		*)
			fail "$label: GF_SMTP_HOST is '$value', expected '\${SMTP_HOST:-mailpit}:\${SMTP_PORT:-1025}' so one relay declaration serves the whole stack"
			;;
	esac

	if service_env "$compose_file" grafana | grep -qiE '^[^=]*PASSWORD'; then
		fail "$label: the grafana environment declares a PASSWORD value; the SMTP credential must come from $SMTP_SECRET_PATH, never from compose (an env value is visible in docker inspect)"
	else
		pass "$label: no password travels through the grafana environment block"
	fi

	if service_env "$compose_file" grafana | grep -qE '^GF_SMTP_PASSWORD='; then
		fail "$label: GF_SMTP_PASSWORD is set inline in compose instead of being exported by the entrypoint wrapper"
	else
		pass "$label: GF_SMTP_PASSWORD is not an environment value in compose"
	fi
}

check_compose_file "$BASE_COMPOSE" "docker/docker-compose.yml"
check_compose_file "$PROD_COMPOSE" "docker/docker-compose.prod.yml"

if grep -rqE '^[[:space:]]*(-[[:space:]]*)?GF_SMTP_PASSWORD[=:]' "$DOCKER_DIR"/docker-compose*.yml; then
	fail "a compose file mentions GF_SMTP_PASSWORD; the credential is read by docker/entrypoints/grafana-entrypoint.sh from the mounted secret"
else
	pass "no compose file declares GF_SMTP_PASSWORD"
fi

# ------------------------------------------
# 2. The entrypoint wrapper: the credential comes from the mounted secret, only when the relay
#    needs it, and a missing file never takes the dashboards down. The admin password stays the one
#    fatal check, so `exit 1` appearing anywhere else is a regression to a Grafana that refuses to
#    start over mail.
# ------------------------------------------
if [ ! -f "$ENTRYPOINT" ]; then
	fail "missing docker/entrypoints/grafana-entrypoint.sh"
else
	if bash -n "$ENTRYPOINT" 2>/dev/null; then
		pass "the grafana entrypoint parses (bash -n)"
	else
		fail "the grafana entrypoint does not parse (bash -n)"
	fi

	if grep -qF "$SMTP_SECRET_PATH" "$ENTRYPOINT"; then
		pass "the entrypoint reads the SMTP credential from $SMTP_SECRET_PATH"
	else
		fail "the entrypoint does not read $SMTP_SECRET_PATH, so the mounted secret is never used"
	fi

	if grep -qE 'export GF_SMTP_PASSWORD=' "$ENTRYPOINT"; then
		pass "the entrypoint exports GF_SMTP_PASSWORD (the variable the Grafana image expects)"
	else
		fail "the entrypoint never exports GF_SMTP_PASSWORD"
	fi

	if grep -qF 'GF_SMTP_USER' "$ENTRYPOINT"; then
		pass "the entrypoint reads the credential only when the relay authenticates (GF_SMTP_USER is declared)"
	else
		fail "the entrypoint reads the credential unconditionally; it must only do so when GF_SMTP_USER is declared"
	fi

	fatal_exits="$(grep -cE '^[[:space:]]*exit 1' "$ENTRYPOINT" || true)"
	if [ "$fatal_exits" = "1" ]; then
		pass "the entrypoint has exactly one fatal exit (the admin password): a missing SMTP credential warns and keeps serving"
	else
		fail "the entrypoint has $fatal_exits 'exit 1' statements, expected 1 (only the admin password is fatal -- a mail misconfiguration must not take the dashboards down)"
	fi

	if grep -qE 'mailpit' "$ENTRYPOINT"; then
		pass "the entrypoint knows the dev mail catcher's service name, which is how the misconfiguration outside dev is detected"
	else
		fail "the entrypoint does not recognise 'mailpit': a staging/prod env file that still declares SMTP_HOST=mailpit would lose every notification in silence"
	fi
fi

# ------------------------------------------
# 3. The three provisioning files exist, carry the keys Grafana reads, and declare no other
#    top-level key (a misspelled key is a setting Grafana ignores without a word).
# ------------------------------------------
check_top_keys() {
	local file="$1"
	local label="$2"
	shift 2
	local allowed="$*"
	local unexpected=""

	if [ ! -f "$file" ]; then
		fail "missing $label"
		return
	fi

	check_no_tabs "$file" "$label"

	if [ "$(yaml_scalar "$file" apiVersion)" = "1" ]; then
		pass "$label: apiVersion is 1"
	else
		fail "$label: apiVersion is '$(yaml_scalar "$file" apiVersion)', expected 1"
	fi

	for key in $(yaml_top_keys "$file"); do
		case " $allowed " in
			*" $key "*) ;;
			*) unexpected="$unexpected $key" ;;
		esac
	done

	if [ -z "$unexpected" ]; then
		pass "$label: declares only the top-level keys Grafana provisions (allowed:$allowed)"
	else
		fail "$label: declares unexpected top-level key(s):$unexpected"
	fi
}

check_top_keys "$CONTACT_POINTS" "docker/config/grafana/alerting/contact-points.yml" apiVersion contactPoints
check_top_keys "$POLICIES" "docker/config/grafana/alerting/policies.yml" apiVersion policies
check_top_keys "$RULES" "docker/config/grafana/alerting/rules.yml" apiVersion groups

# ------------------------------------------
# 4. The contact point: one, of type email, named by the frozen table, delivering to the address
#    the environment declares.
# ------------------------------------------
if [ -f "$CONTACT_POINTS" ]; then
	cp_count="$(yaml_count "$CONTACT_POINTS" '^[[:space:]]*-[[:space:]]*orgId:')"
	if [ "$cp_count" = "1" ]; then
		pass "contact points: exactly one org block"
	else
		fail "contact points: $cp_count org blocks, expected 1"
	fi

	IFS='|' read -r expected_name expected_type expected_uid <<< "$EXPECTED_CONTACT_POINT"

	if [ "$(yaml_scalar "$CONTACT_POINTS" name)" = "$expected_name" ]; then
		pass "contact points: the contact point is named '$expected_name'"
	else
		fail "contact points: the contact point is named '$(yaml_scalar "$CONTACT_POINTS" name)', expected '$expected_name'"
	fi

	if [ "$(yaml_scalar "$CONTACT_POINTS" type)" = "$expected_type" ]; then
		pass "contact points: the integration is of type '$expected_type'"
	else
		fail "contact points: the integration type is '$(yaml_scalar "$CONTACT_POINTS" type)', expected '$expected_type'"
	fi

	if [ "$(yaml_scalar "$CONTACT_POINTS" uid)" = "$expected_uid" ]; then
		pass "contact points: the receiver uid is '$expected_uid'"
	else
		fail "contact points: the receiver uid is '$(yaml_scalar "$CONTACT_POINTS" uid)', expected '$expected_uid'"
	fi

	addresses="$(yaml_scalar "$CONTACT_POINTS" addresses)"
	case "$addresses" in
		'$__env{GRAFANA_ALERT_EMAIL}')
			pass "contact points: addresses is \$__env{GRAFANA_ALERT_EMAIL}, so the destination is an environment value and not an edit to this YAML"
			;;
		"")
			fail "contact points: no addresses setting; Grafana refuses the whole provisioning pass with 'could not find addresses in settings'"
			;;
		*)
			fail "contact points: addresses is '$addresses'; a hardcoded address means the destination cannot be changed without editing a provisioned file"
			;;
	esac
fi

# ------------------------------------------
# 5. The notification policy routes to that contact point, and groups by job so one noisy job does
#    not batch another job's errors into the same mail.
# ------------------------------------------
if [ -f "$POLICIES" ]; then
	policy_count="$(yaml_count "$POLICIES" '^[[:space:]]*-[[:space:]]*orgId:')"
	if [ "$policy_count" = "1" ]; then
		pass "policies: exactly one policy tree"
	else
		fail "policies: $policy_count policy trees, expected 1"
	fi

	receiver="$(yaml_scalar "$POLICIES" receiver)"
	cp_name="$(yaml_scalar "$CONTACT_POINTS" name)"
	if [ -n "$receiver" ] && [ "$receiver" = "$cp_name" ]; then
		pass "policies: the policy routes to receiver '$receiver', which contact-points.yml declares"
	else
		fail "policies: the policy routes to receiver '$receiver', but the declared contact point is '$cp_name' -- the rule would fire and notify nobody"
	fi

	if grep -qE '^[[:space:]]+-[[:space:]]*job[[:space:]]*$' "$POLICIES"; then
		pass "policies: the group_by list includes job, the dimension the rule breaks the ratio down by"
	else
		fail "policies: the group_by list does not include job"
	fi
fi

# ------------------------------------------
# 6. The rule: one, on the error ratio the dashboards already draw, with D4's numbers, pointing at
#    a datasource uid that datasources.yml declares.
# ------------------------------------------
if [ -f "$RULES" ]; then
	group_count="$(yaml_count "$RULES" '^[[:space:]]*-[[:space:]]*orgId:')"
	rule_count="$(yaml_count "$RULES" '^[[:space:]]*-[[:space:]]*uid:')"
	if [ "$group_count" = "1" ]; then
		pass "rules: exactly one rule group"
	else
		fail "rules: $group_count rule groups, expected 1"
	fi
	if [ "$rule_count" = "1" ]; then
		pass "rules: exactly one alert rule (this slice ships one, not a set)"
	else
		fail "rules: $rule_count alert rules, expected 1"
	fi

	if [ "$(yaml_scalar "$RULES" folder)" = "LifeControl" ]; then
		pass "rules: the rule lives in the LifeControl folder, next to the dashboards' folder"
	else
		fail "rules: folder is '$(yaml_scalar "$RULES" folder)', expected LifeControl"
	fi

	IFS='|' read -r expected_uid expected_title expected_for expected_threshold <<< "$EXPECTED_RULE"

	# The rule's own uid, not any later `uid:` inside a `datasource:` block: the frozen table above
	# is what identifies the rule, so it is read from the sequence entry that declares it.
	rules_uid="$(grep -oE '^[[:space:]]*-[[:space:]]*uid:[[:space:]]*[^[:space:]]+' "$RULES" | head -1 | sed -e 's/^[[:space:]]*-[[:space:]]*uid:[[:space:]]*//')"
	if [ "$rules_uid" = "$expected_uid" ]; then
		pass "rules: the rule uid is '$expected_uid'"
	else
		fail "rules: the rule uid is '$rules_uid', expected '$expected_uid'"
	fi

	if [ "$(yaml_scalar "$RULES" title)" = "$expected_title" ]; then
		pass "rules: the rule title is '$expected_title'"
	else
		fail "rules: the rule title is '$(yaml_scalar "$RULES" title)', expected '$expected_title'"
	fi

	if [ "$(yaml_scalar "$RULES" for)" = "$expected_for" ]; then
		pass "rules: 'for' is $expected_for (D4: above 1% for 5 minutes)"
	else
		fail "rules: 'for' is '$(yaml_scalar "$RULES" for)', expected $expected_for (D4)"
	fi

	if [ "$(yaml_scalar "$RULES" condition)" = "C" ]; then
		pass "rules: the rule's condition is the expression node C"
	else
		fail "rules: condition is '$(yaml_scalar "$RULES" condition)', expected C"
	fi

	# The expression node's registered type. This pinned the bug this slice hit: the singular
	# `classic_cond` (what most examples and every hand-written file says) is NOT an expression type
	# in the pinned image -- it answers `invalid command type in expression 'C': 'classic_cond' is not
	# a recognized expression type`, which is only visible in the scheduler log because the rule is
	# provisioned anyway. The recognized name is `classic_conditions` (measured).
	expression_type="$(grep -oE '^[[:space:]]*type:[[:space:]]*classic_[a-z_]+' "$RULES" | head -1 | sed -e 's/.*type:[[:space:]]*//')"
	if [ "$expression_type" = "classic_conditions" ]; then
		pass "rules: the condition node is a classic condition, spelled 'classic_conditions' as the pinned image requires"
	else
		fail "rules: the condition node type is '$expression_type', expected classic_conditions -- the singular 'classic_cond' is rejected at evaluation time ('invalid command type in expression')"
	fi

	if [ "$(yaml_scalar "$RULES" noDataState)" = "OK" ]; then
		pass "rules: noDataState is OK -- a job with no traffic must not fire"
	else
		fail "rules: noDataState is '$(yaml_scalar "$RULES" noDataState)', expected OK"
	fi

	if [ "$(yaml_scalar "$RULES" execErrState)" = "Error" ]; then
		pass "rules: execErrState is Error -- a broken query shows as an error, it does not send a false 'error ratio' email"
	else
		fail "rules: execErrState is '$(yaml_scalar "$RULES" execErrState)', expected Error"
	fi

	# The threshold and its evaluator, read as a pair: a 0.01 sitting next to a `lt` would alert
	# exactly when the service is healthy. `evaluator` is a sequence entry (`- evaluator:`), which
	# is why the pattern allows the marker.
	if grep -qE '^[[:space:]]*(-[[:space:]]*)?evaluator:' "$RULES" && grep -qE '^[[:space:]]*params:' "$RULES"; then
		evaluator_type="$(awk '/^[[:space:]]*(-[[:space:]]*)?evaluator:/{inside=1} inside && /^[[:space:]]*type:/{gsub(/^[[:space:]]*type:[[:space:]]*/,""); print; exit}' "$RULES")"
		threshold="$(awk '/^[[:space:]]*(-[[:space:]]*)?evaluator:/{inside=1} inside && /^[[:space:]]*params:/{getline; gsub(/^[[:space:]]*-[[:space:]]*/,""); gsub(/[[:space:]]*$/,""); print; exit}' "$RULES")"
		if [ "$evaluator_type" = "gt" ]; then
			pass "rules: the evaluator is 'gt' (above the threshold, which is how a ratio alert is written)"
		else
			fail "rules: the evaluator is '$evaluator_type', expected gt"
		fi
		if [ "$threshold" = "$expected_threshold" ]; then
			pass "rules: the threshold is $expected_threshold (D4: 1%)"
		else
			fail "rules: the threshold is '$threshold', expected $expected_threshold (D4: 1%)"
		fi
	else
		fail "rules: no evaluator/params pair found, so the threshold cannot be read"
	fi

	# The query itself: the same metric pair the dashboards draw, per job.
	if grep -qF "$ERROR_RATIO_METRIC" "$RULES"; then
		pass "rules: the query uses $ERROR_RATIO_METRIC, the metric the service-health dashboard draws"
	else
		fail "rules: the query does not use $ERROR_RATIO_METRIC"
	fi

	if grep -qF "$SERVER_ERROR_LABEL" "$RULES"; then
		pass "rules: the numerator filters $SERVER_ERROR_LABEL"
	else
		fail "rules: the numerator does not filter $SERVER_ERROR_LABEL -- it would alert on every request"
	fi

	if grep -qE 'by \(job\)' "$RULES"; then
		pass "rules: the ratio is broken down by job"
	else
		fail "rules: the ratio is not broken down by job"
	fi

	# D4's numbers are starting numbers, and the rule has to say so where it lives.
	if grep -qiE 'starting number' "$RULES" && grep -qiE 'SLO' "$RULES"; then
		pass "rules: the annotation says the thresholds are starting numbers, not an SLO"
	else
		fail "rules: the annotation does not say the thresholds are starting numbers and not an SLO"
	fi
fi

# ------------------------------------------
# 7. The rule's datasource uid is one datasources.yml declares. This is the assertion that makes the
#    datasource uids load-bearing: without it, a rename on either side is a rule that evaluates
#    against nothing.
# ------------------------------------------
declare_uid() {
	awk -v name="$1" '
		/^[[:space:]]*-[[:space:]]*name:/ {
			value = $0
			sub(/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/, "", value)
			gsub(/"/, "", value)
			sub(/[[:space:]]+$/, "", value)
			current = value
			next
		}
		/^[[:space:]]+uid:/ {
			if (current == name) {
				value = $0
				sub(/^[[:space:]]*uid:[[:space:]]*/, "", value)
				gsub(/"/, "", value)
				sub(/[[:space:]]+$/, "", value)
				print value
				exit
			}
		}
	' "$2"
}

if [ ! -f "$DATASOURCES_FILE" ]; then
	fail "missing docker/config/grafana/datasources.yml"
else
	while IFS='|' read -r ds_name expected_ds_uid; do
		[ -n "$ds_name" ] || continue
		actual="$(declare_uid "$ds_name" "$DATASOURCES_FILE")"
		if [ "$actual" = "$expected_ds_uid" ]; then
			pass "datasources: '$ds_name' declares uid '$expected_ds_uid'"
		else
			fail "datasources: '$ds_name' declares uid '$actual', expected '$expected_ds_uid' -- an alert rule cannot omit its datasource the way a dashboard panel can"
		fi
	done <<< "$EXPECTED_UIDS"

	# Every uid is distinct: two datasources cannot share one.
	uid_count="$(grep -E '^[[:space:]]+uid:' "$DATASOURCES_FILE" | sed -e 's/^[[:space:]]*uid:[[:space:]]*//' -e 's/[[:space:]]*$//' | sort -u | wc -l | tr -d ' ')"
	declared_count="$(grep -cE '^[[:space:]]*-[[:space:]]*name:' "$DATASOURCES_FILE" || true)"
	if [ "$uid_count" = "$declared_count" ] && [ "$declared_count" != "0" ]; then
		pass "datasources: all $declared_count datasources declare a distinct uid"
	else
		fail "datasources: $declared_count datasources declare $uid_count distinct uids"
	fi

	rule_uid="$(awk '/datasourceUid:/{gsub(/^[[:space:]]*datasourceUid:[[:space:]]*/,""); gsub(/[[:space:]]*$/,""); if ($0 != "__expr__") { print; exit }}' "$RULES" 2>/dev/null)"
	if [ -z "$rule_uid" ]; then
		fail "rules: no datasourceUid found on the query node"
	elif [ "$(declare_uid Prometheus "$DATASOURCES_FILE")" = "$rule_uid" ]; then
		pass "rules: the query's datasourceUid '$rule_uid' is the uid datasources.yml declares for Prometheus"
	else
		fail "rules: the query's datasourceUid is '$rule_uid', which datasources.yml does not declare for Prometheus -- every evaluation would log 'data source not found'"
	fi
fi

# ------------------------------------------
# 8. The rule has one home. The dashboards carry none: a panel-embedded alert lives in the volume,
#    not in a provisioned file, and is the exact thing this slice replaced.
# ------------------------------------------
PY_OUT=""
PY_STATUS=1

if command -v python3 >/dev/null 2>&1; then
	PY_OUT="$(python3 - "$DASHBOARDS_DIR" 2>&1 <<'PY'
import json
import os
import sys

root = sys.argv[1]
problems = 0
files = 0
alerts = 0


def walk(node, path):
    hits = []
    if isinstance(node, dict):
        if "alert" in node:
            hits.append("%s (panel '%s')" % (path, node.get("title", "?")))
        for key, value in node.items():
            hits.extend(walk(value, path))
    elif isinstance(node, list):
        for value in node:
            hits.extend(walk(value, path))
    return hits


for dirpath, dirnames, filenames in os.walk(root):
    dirnames.sort()
    for filename in sorted(filenames):
        if not filename.endswith(".json"):
            continue
        files += 1
        path = os.path.join(dirpath, filename)
        rel = os.path.relpath(path, root)
        try:
            with open(path, encoding="utf-8") as handle:
                doc = json.load(handle)
        except (ValueError, OSError) as exc:
            print("INVALID\t%s\tdoes not parse as JSON: %s" % (rel, exc))
            problems += 1
            continue
        for hit in walk(doc, rel):
            print("ALERT\t%s" % hit)
            alerts += 1

print("SUMMARY\t%d\t%d\t%d" % (files, alerts, problems))
sys.exit(1 if problems else 0)
PY
)"
	PY_STATUS=$?

	while IFS=$'\t' read -r kind a b; do
		case "$kind" in
			INVALID) fail "dashboard '$a': $b" ;;
			ALERT) fail "dashboard $a carries an alert rule: the rule belongs in docker/config/grafana/alerting/" ;;
		esac
	done <<< "$PY_OUT"

	if [ "$PY_STATUS" -ne 0 ]; then
		fail "a dashboard did not parse (see above)"
	else
		summary="$(printf '%s\n' "$PY_OUT" | awk -F'\t' '$1 == "SUMMARY" { print $2 " " $3 " " $4 }')"
		read -r dash_count alert_count invalid_count <<< "$summary"
		if [ "${dash_count:-0}" -eq 0 ]; then
			fail "no dashboard JSON found: this check would pass over an empty directory"
		elif [ "${alert_count:-0}" -eq 0 ] && [ "${invalid_count:-0}" -eq 0 ]; then
			pass "$dash_count dashboard(s) parse and none carries an alert rule (the rule is provisioned from alerting/, not from a panel)"
		fi
	fi
else
	fail "python3 is required to parse the dashboards; it is assumed by this repository's docker scripts"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
