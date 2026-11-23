#!/bin/bash
# ============================================
# LifeControl - credential file ownership (issue #897, S6/S9; T4 of odd/tasks/docker-stack-hardening.md)
# ============================================
# S9's defect was `chmod 0444` on every materialized credential: any local account on the host could read
# the database owner's password. The fix has one shape per file, and the shape is declared once:
#
#   * `0400`, owned by the single non-root uid that reads it, where one account reads the file
#     (`uid:0` -- the group is asserted too, so a credential has exactly one legal shape);
#   * `0440`, `root:<gid>`, where TWO non-root accounts read the same file. Those three gids are numeric
#     and the two consuming services carry them through `group_add` in docker-compose.yml, which keeps
#     one copy of each value (a per-reader duplicate would drift on rotation) and puts the reader
#     declaration next to `cap_drop`/`read_only`/`user:` where the next person looks.
#
# Every host-side reader runs as root (keycloak-setup.sh is documented as requiring sudo), because a
# credential an operator account can read is a credential every process that account runs can read.
#
# What this suite asserts, and why each half exists:
#
#   1. THE TABLE CANNOT DRIFT. `setup-env.sh` applies it, `validate-env.sh` gates on it, and AGENTS.md
#      documents it. The suite freezes its own copy and compares all three in BOTH directions, so a row
#      that moves in one place -- or a credential added without a row -- fails here instead of silently
#      widening a mode in production.
#   2. THE GATE'S VERDICTS. `validate-env.sh` must fail a file whose mode, owner or group drifts, must
#      fail a file nobody declared, and must pass a file that matches. The drift half runs against REAL
#      file metadata; the pass half runs against a doubled `stat`, because no unprivileged run can own a
#      file as uid 1000/1001/0 -- see the note above `install_metadata_double`.
#   3. THE APPLIER'S BEHAVIOUR. The ownership helpers are extracted VERBATIM from `setup-env.sh` and
#      driven against a throwaway tree: already-correct is a success that never shells out to chown, a
#      mismatch it cannot apply is collected (not fatal on the first file), and the collected block is
#      one copy-pasteable unit in the order that works (stop, changes, up).
#   4. THE COMPOSE WIRING. `docker compose config` for the three file combinations this repository runs
#      must show each gid on exactly the services that read that credential, and on no other service.
#   5. THE CRITERION ITSELF, FALSIFIABLY. A container as an arbitrary uid cannot read a file it is not
#      entitled to, a declared reader can, and for the group-based rows the GROUP is what grants it (the
#      same uid without the group is refused). This is the case that measures "not readable by a second
#      local account" without creating one. It needs a Docker daemon and is SKIPPED loudly, never
#      silently passed, when there is none.
#
# Nothing here prints a credential value: the fixtures hold "x", and the probes read into /dev/null and
# print only READ or DENIED.
#
# Run: ./docker/scripts/tests/secrets-ownership.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$DOCKER_DIR/.." && pwd)"
SETUP_ENV_SH="$DOCKER_DIR/scripts/setup-env.sh"
VALIDATE_ENV_SH="$DOCKER_DIR/scripts/validate-env.sh"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

skip() {
	printf 'SKIP: %s\n' "$1"
	SKIP_COUNT=$((SKIP_COUNT + 1))
}

assert_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

assert_contains() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		pass "$1"
	else
		fail "$1 (the output never mentioned '$3')"
	fi
}

assert_absent() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		fail "$1 ('$3' appeared in the output)"
	else
		pass "$1"
	fi
}

WORK="$(mktemp -d)"
ORIGINAL_PATH="$PATH"
# shellcheck disable=SC2329  # invoked by the EXIT trap below
cleanup() {
	chmod -R u+rwX "$WORK" 2>/dev/null
	rm -rf "$WORK"
}
trap cleanup EXIT

# The frozen table: name|mode|owner:group. This is the assertion, not a copy of the code -- if a script
# or AGENTS.md disagrees with it in either direction, exactly one of the two is wrong and this suite says
# which row.
EXPECTED_TABLE='fiscal_csd_certificado|0400|1001:0
fiscal_csd_llave|0400|1001:0
fiscal_csd_password|0400|1001:0
fiscal_sf_password|0400|1001:0
grafana_admin_password|0400|1000:0
keycloak_admin_client_secret|0400|1001:0
keycloak_admin_password|0400|1000:0
keycloak_postgres_password|0440|0:9313
lifecontrol_app_password|0440|0:9311
lifecontrol_postgres_password|0440|0:9312
redis_password|0400|1001:0
smtp_password|0400|1000:0'

# The gids that only exist to let a second uid read one file. They are asserted three ways: in the table
# (0440 rows), in the compose files (`group_add`), and here as the closed set of "shared" gids, so a
# fourth one cannot appear without this line changing.
EXPECTED_SHARED_GIDS='9311
9312
9313'

# ------------------------------------------
# 1. The table in both directions: scripts and docs against the frozen copy.
# ------------------------------------------
# extract_table <file> -- the SECRET_OWNERSHIP_TABLE literal of one script, as name|mode|owner:group,
# sorted, one row per line.
extract_table() {
	awk "/^SECRET_OWNERSHIP_TABLE='\$/ {inside=1; next} inside && /^'\$/ {exit} inside && NF {print}" "$1" |
		cut -d'|' -f1-3 | sort
}

# extract_docs_table -- the same three columns out of the `### Secretos` table in AGENTS.md.
extract_docs_table() {
	awk '
		/^### Secretos/ {section=1}
		section && /^### / && !/^### Secretos/ {section=0}
		section && /^\| `[a-z_]+` \| `[0-9]+` \| `[0-9]+:[0-9]+` \|/ {print}
	' "$REPO_ROOT/AGENTS.md" |
		sed -E 's/^\| `([a-z_]+)` \| `([0-9]+)` \| `([0-9]+:[0-9]+)` \|.*$/\1|\2|\3/' | sort
}

compare_rows() {
	local label="$1" actual="$2" missing extra
	missing="$(comm -23 <(printf '%s\n' "$EXPECTED_TABLE") <(printf '%s\n' "$actual"))"
	extra="$(comm -13 <(printf '%s\n' "$EXPECTED_TABLE") <(printf '%s\n' "$actual"))"
	if [ -z "$missing" ] && [ -z "$extra" ]; then
		pass "$label declares exactly the frozen table ($(printf '%s\n' "$EXPECTED_TABLE" | grep -c .) rows)"
		return 0
	fi
	[ -n "$missing" ] && fail "$label is missing row(s): $(printf '%s' "$missing" | tr '\n' ' ')"
	[ -n "$extra" ] && fail "$label declares row(s) the frozen table does not have: $(printf '%s' "$extra" | tr '\n' ' ')"
	return 1
}

compare_rows "setup-env.sh (the applier)" "$(extract_table "$SETUP_ENV_SH")"
compare_rows "validate-env.sh (the gate)" "$(extract_table "$VALIDATE_ENV_SH")"
compare_rows "AGENTS.md ### Secretos (the documentation)" "$(extract_docs_table)"

# The two script copies are the same bytes, so a divergence is a one-line diff to point at.
if diff <(extract_table "$SETUP_ENV_SH") <(extract_table "$VALIDATE_ENV_SH") >/dev/null; then
	pass "the applier and the gate carry the same table, row for row"
else
	fail "the applier and the gate disagree: $(diff <(extract_table "$SETUP_ENV_SH") <(extract_table "$VALIDATE_ENV_SH") | tr '\n' ' ')"
fi

# Every 0440 row's gid is a declared shared gid, and the shared set has no orphan.
row_gids="$(printf '%s\n' "$EXPECTED_TABLE" | awk -F'|' '$2 == "0440" {split($3, own, ":"); print own[2]}' | sort -u)"
if [ "$row_gids" = "$EXPECTED_SHARED_GIDS" ]; then
	pass "the shared gids are exactly the ones the 0440 rows use ($(printf '%s' "$EXPECTED_SHARED_GIDS" | tr '\n' ' '))"
else
	fail "the 0440 rows use [$row_gids] and the declared shared set is [$EXPECTED_SHARED_GIDS]"
fi

# Every 0400 row's owner is one of the three container uids this stack runs as, and never 0: a 0400 file
# owned by root would be readable by root alone, which is the opposite of the fix.
root_owned="$(printf '%s\n' "$EXPECTED_TABLE" | awk -F'|' '$2 == "0400" {split($3, own, ":"); if (own[1] == "0") print $1}')"
assert_equal "no 0400 credential is owned by root (that would make it unreadable to its own reader)" "" "$root_owned"

# ------------------------------------------
# 2. The gate's verdicts, real metadata first: everything an ordinary account CAN produce.
# ------------------------------------------
mkdir -p "$WORK/scripts" "$WORK/secrets" "$WORK/bin"
cp "$VALIDATE_ENV_SH" "$DOCKER_DIR/scripts/_common.sh" "$WORK/scripts/"

printf '#!/bin/sh\nexit 0\n' >"$WORK/bin/docker"
chmod +x "$WORK/bin/docker"

REQUIRED='COMPOSE_PROJECT_NAME=lc-test
ENVIRONMENT=dev
API_GATEWAY_MANAGEMENT_PORT=9001
LIFECONTROL_API_PORT=8082'
printf '%s\n' "$REQUIRED" >"$WORK/.env.dev"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.dev"
printf '%s\n' "$REQUIRED" >"$WORK/.env.template"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.template"

OUT="$WORK/out.log"
LAST_RC=0
LAST_TEXT=""
run_gate() {
	PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" dev >"$OUT" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(sed 's/\x1b\[[0-9;]*m//g' "$OUT")"
}

reset_secrets() {
	chmod -R u+rwX "$WORK/secrets" 2>/dev/null
	rm -rf "$WORK/secrets"
	mkdir -p "$WORK/secrets"
}

# A real file, owned by whoever runs this suite. Every field of the contract is then wrong in a way the
# gate can see without any help, which is the half of the rule a privileged fixture is not needed for.
reset_secrets
printf 'x\n' >"$WORK/secrets/lifecontrol_app_password"
chmod 0444 "$WORK/secrets/lifecontrol_app_password"
run_gate
assert_equal "a file whose real mode, owner and group all drift fails the gate" "1" "$LAST_RC"
assert_contains "the failure names the file" "$LAST_TEXT" "$WORK/secrets/lifecontrol_app_password"
assert_contains "the mode verdict names the mode it found" "$LAST_TEXT" "mode is 444"
assert_contains "the mode verdict names the mode it needs" "$LAST_TEXT" "must be 0440"
assert_contains "the owner verdict names the owner it found" "$LAST_TEXT" "owner is uid $(id -u)"
assert_contains "the owner verdict names the owner it needs" "$LAST_TEXT" "must be 0 "
assert_contains "the group verdict names the group it found" "$LAST_TEXT" "group is gid $(id -g)"
assert_contains "the group verdict names the group it needs" "$LAST_TEXT" "must be 9311"

# The transient 0444-only message is gone: a reader reading the old failure would go looking for a fourth
# digit instead of an owner.
assert_absent "the old 'must be 0444' verdict is gone" "$LAST_TEXT" "must be 0444"

# A credential nobody declared is a failure, not a pass: a file with no row is a file nobody decided the
# reader of, which is the state this contract exists to end.
reset_secrets
printf 'x\n' >"$WORK/secrets/db_password"
run_gate
assert_equal "a materialized secret with no row fails the gate" "1" "$LAST_RC"
assert_contains "the failure names the undeclared file" "$LAST_TEXT" "$WORK/secrets/db_password"
assert_contains "the failure says nobody decided who may read it" "$LAST_TEXT" "no declared ownership row"

# The two verdicts the task said to leave untouched still behave exactly as they did.
reset_secrets
printf 'CHANGEME\n' >"$WORK/secrets/redis_password"
run_gate
assert_equal "a CHANGEME credential still fails the gate" "1" "$LAST_RC"
assert_contains "the CHANGEME verdict is unchanged" "$LAST_TEXT" "still contains CHANGEME"

reset_secrets
# A directory where a credential belongs leaves FOUND at 0 without tripping the undeclared-row error,
# which is how the legacy suite already drives this verdict: a directory is not a credential.
mkdir "$WORK/secrets/smtp_password"
run_gate
assert_equal "a folder with no materialized credential still fails the gate" "1" "$LAST_RC"
assert_contains "the not-materialized verdict is unchanged" "$LAST_TEXT" "No secrets materialized"

# ------------------------------------------
# 3. The gate's pass verdict, through a doubled `stat`.
# ------------------------------------------
# `stat` is doubled the way `docker info` already is: a shim ahead of the real command on PATH. The
# accounts this contract names are 1000, 1001 and root, and an unprivileged run cannot create a file
# owned by them, so without a double the PASS half of the rule would be untestable here and the drift
# half above would be the only thing covered. The real permission semantics -- that a 0400 file is
# unreadable to a second account and a 0440 file readable to its group -- are measured with real
# containers in case 6, which is the falsifiable half of the criterion.
install_metadata_double() {
	local bindir="$1" tsv="$2" real
	real="$(command -v stat)"
	cat >"$bindir/stat" <<SHIM
#!/bin/bash
# Test double: answers \`stat -c '%a %u %g' <file>\` from $tsv for the names it lists, and delegates
# every other question to the real stat.
if [ "\${1:-}" = "-c" ] && [ "\${2:-}" = "%a %u %g" ]; then
	name="\$(basename "\${@: -1}")"
	while IFS='|' read -r n m u g; do
		[ -n "\$n" ] || continue
		if [ "\$n" = "\$name" ]; then
			printf '%s %s %s\\n' "\$m" "\$u" "\$g"
			exit 0
		fi
	done <"$tsv"
fi
exec "$real" "\$@"
SHIM
	chmod +x "$bindir/stat"
}

METADATA_TSV="$WORK/metadata.tsv"
install_metadata_double "$WORK/bin" "$METADATA_TSV"

# seed_owned <name> <mode> <uid> <gid> -- one file, and the metadata the shim answers for it.
seed_owned() {
	reset_secrets
	printf 'x\n' >"$WORK/secrets/$1"
	printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4" >"$METADATA_TSV"
}

seed_owned lifecontrol_app_password 440 0 9311
run_gate
assert_equal "a file that matches its row passes the gate" "0" "$LAST_RC"
assert_contains "the success line names the mode and the owner" "$LAST_TEXT" "mode 0440, owner 0:9311"

seed_owned lifecontrol_app_password 444 0 9311
run_gate
assert_equal "a wrong mode alone fails the gate" "1" "$LAST_RC"
assert_contains "the mode verdict names what it found" "$LAST_TEXT" "mode is 444"
assert_absent "a wrong mode does not invent an owner complaint" "$LAST_TEXT" "owner is uid"
assert_absent "a wrong mode does not invent a group complaint" "$LAST_TEXT" "group is gid"

seed_owned lifecontrol_app_password 440 1 9311
run_gate
assert_equal "a wrong owner alone fails the gate" "1" "$LAST_RC"
assert_contains "the owner verdict names what it found" "$LAST_TEXT" "owner is uid 1"
assert_absent "a wrong owner does not invent a mode complaint" "$LAST_TEXT" "mode is"

seed_owned lifecontrol_app_password 440 0 9312
run_gate
assert_equal "a wrong group alone fails the gate" "1" "$LAST_RC"
assert_contains "the group verdict names what it found" "$LAST_TEXT" "group is gid 9312"
assert_contains "the group verdict names the gid the row declares" "$LAST_TEXT" "must be 9311"
assert_absent "a wrong group does not invent a mode complaint" "$LAST_TEXT" "mode is"

# The three single-reader rows, driven the same way: the shape that passes is 0400 with the reading uid.
for name in grafana_admin_password keycloak_admin_client_secret redis_password smtp_password fiscal_sf_password; do
	row_owner="$(printf '%s\n' "$EXPECTED_TABLE" | awk -F'|' -v n="$name" '$1 == n {print $3}')"
	seed_owned "$name" 400 "${row_owner%%:*}" "${row_owner##*:}"
	run_gate
	assert_equal "$name passes at 0400 with the uid that reads it as owner" "0" "$LAST_RC"
done

# ------------------------------------------
# 4. The applier, extracted verbatim from setup-env.sh and driven against a throwaway tree.
# ------------------------------------------
# The whole secrets section cannot be executed: setup-env.sh resolves the environment, applies the volume
# ownership (which needs root) and materializes templates first. The ownership helpers ARE its own bytes,
# extracted from `SECRET_OWNERSHIP_TABLE='` through the closing brace of `print_pending_secret_fixes`,
# which is what makes the driven behaviour the shipped behaviour.
OWNERSHIP_SH="$WORK/ownership.sh"
if awk '
	/^SECRET_OWNERSHIP_TABLE=.$/ {on=1}
	on {print}
	on && /^print_pending_secret_fixes\(\) \{/ {fn=1}
	fn && /^\}$/ {exit}
' "$SETUP_ENV_SH" >"$OWNERSHIP_SH" && grep -q 'print_pending_secret_fixes() {' "$OWNERSHIP_SH"; then
	pass "the ownership helpers were extracted verbatim from setup-env.sh"
else
	fail "could not extract the ownership helpers from setup-env.sh"
fi

# chown/chmod shims: they log the call and fail while SHIM_BLOCK=1, which is a non-root run trying to set
# an owner it does not have. With SHIM_BLOCK=0 they are the real commands, so a call that needs no
# privilege still works.
REAL_CHOWN="$(command -v chown)"
REAL_CHMOD="$(command -v chmod)"
SHIM_LOG="$WORK/shim.log"
cat >"$WORK/bin/chown" <<SHIM
#!/bin/bash
if [ "\${SHIM_BLOCK:-0}" = "1" ]; then printf 'chown %s\n' "\$*" >>"$SHIM_LOG"; exit 1; fi
exec "$REAL_CHOWN" "\$@"
SHIM
cat >"$WORK/bin/chmod" <<SHIM
#!/bin/bash
if [ "\${SHIM_BLOCK:-0}" = "1" ]; then printf 'chmod %s\n' "\$*" >>"$SHIM_LOG"; exit 1; fi
exec "$REAL_CHMOD" "\$@"
SHIM
chmod +x "$WORK/bin/chown" "$WORK/bin/chmod"

ENV="dev"
# The chown/chmod shims have to be reachable by the extracted helper itself, and only while it runs:
# they shadow commands the later sections need for real (`docker`, `stat`).
export PATH="$WORK/bin:$PATH"
source "$DOCKER_DIR/scripts/_common.sh"
# shellcheck source=/dev/null  # generated above, extracted from setup-env.sh
source "$OWNERSHIP_SH"

TREE="$WORK/tree"
mkdir -p "$TREE"

# Already correct: a success that never shells out to chown or chmod. The file is owned by this run's
# uid, so the row driven here is the one whose owner is this uid -- the property under test is "no write
# when the shape is already right", which is independent of which uid it is.
printf 'x\n' >"$TREE/already"
chown "$(id -u):$(id -g)" "$TREE/already"
chmod 400 "$TREE/already"
: >"$SHIM_LOG"
export SHIM_BLOCK=1
apply_secret_ownership "$TREE/already" 0400 "$(id -u):$(id -g)" "this run's uid" >"$OUT" 2>&1
LAST_RC=$?
export SHIM_BLOCK=0
assert_equal "an already-correct file is a success" "0" "$LAST_RC"
assert_contains "and it says so" "$(cat "$OUT")" "already $(id -u):$(id -g) 0400"
assert_equal "and it never runs chown or chmod" "" "$(cat "$SHIM_LOG")"

# A mismatch this run CAN fix, which is the branch a root run takes and the only version of it an
# unprivileged run is allowed to reach: the owner is already this uid, so the chown is a no-op that
# succeeds, the mode is applied, and nothing is left pending.
printf 'x\n' >"$TREE/fixable"
chmod 0644 "$TREE/fixable"
export SHIM_BLOCK=0
apply_secret_ownership "$TREE/fixable" 0400 "$(id -u):$(id -g)" "this run's uid" >"$OUT" 2>&1
LAST_RC=$?
assert_equal "a mismatch the run can apply is a success" "0" "$LAST_RC"
assert_equal "and the file ends at the mode the table declares" "400" "$(stat -c '%a' "$TREE/fixable")"
assert_equal "and nothing is left pending for it" "0" "${#PENDING_SECRET_FIXES[@]}"

# A mismatch that cannot be applied: recorded, not fatal, and named with the exact command.
printf 'x\n' >"$TREE/needs_root_app"
printf 'x\n' >"$TREE/needs_root_owner"
: >"$SHIM_LOG"
export SHIM_BLOCK=1
apply_secret_ownership "$TREE/needs_root_app" 0440 0:9311 "the api wrapper (uid 1001) and the postgres role script (uid 999)" >"$OUT" 2>&1
rc_app=$?
apply_secret_ownership "$TREE/needs_root_owner" 0440 0:9312 "the api wrapper (uid 1001) and postgres-exporter-lifecontrol (uid 65534)" >>"$OUT" 2>&1
rc_owner=$?
export SHIM_BLOCK=0
assert_equal "a mismatch it cannot apply returns non-zero" "1" "$rc_app"
assert_equal "and the next file is still checked, instead of the first one aborting the run" "1" "$rc_owner"
assert_contains "the first mismatch names the file it found" "$(cat "$OUT")" "needs_root_app is $(id -u):$(id -g) $(stat -c '%a' "$TREE/needs_root_app")"
assert_contains "the applier really attempted the chown on the first file" "$(cat "$SHIM_LOG")" "needs_root_app"
assert_contains "the applier really attempted the chown on the second file" "$(cat "$SHIM_LOG")" "needs_root_owner"
assert_contains "both mismatches are collected" "$(printf '%s\n' "${PENDING_SECRET_FIXES[@]}")" "needs_root_owner"
assert_equal "the collection holds one entry per file, not one for the run" "2" "${#PENDING_SECRET_FIXES[@]}"

# The collected block: one copy-pasteable unit, the stop first, the up last, and the exact
# chown+chmod line per file.
print_pending_secret_fixes >"$OUT" 2>&1
BLOCK="$(sed 's/\x1b\[[0-9;]*m//g' "$OUT")"
assert_contains "the block names the stack stop command" "$BLOCK" "./docker/scripts/deploy.sh dev stop"
assert_contains "the block carries the exact command for the first file" "$BLOCK" "sudo chown 0:9311 '$TREE/needs_root_app' && sudo chmod 0440 '$TREE/needs_root_app'"
assert_contains "the block carries the exact command for the second file" "$BLOCK" "sudo chown 0:9312 '$TREE/needs_root_owner' && sudo chmod 0440 '$TREE/needs_root_owner'"
assert_contains "the block names the stack start command" "$BLOCK" "./docker/scripts/deploy.sh dev up"
assert_equal "the stop comes before the first privileged command" "1" \
	"$(printf '%s\n' "$BLOCK" | grep -n 'deploy.sh dev stop' | head -1 | cut -d: -f1 | awk -v first="$(printf '%s\n' "$BLOCK" | grep -n 'sudo chown' | head -1 | cut -d: -f1)" '{print ($1 < first) ? 1 : 0}')"
assert_equal "the start comes after the last privileged command" "1" \
	"$(printf '%s\n' "$BLOCK" | grep -n 'deploy.sh dev up' | head -1 | cut -d: -f1 | awk -v last="$(printf '%s\n' "$BLOCK" | grep -n 'sudo chown' | tail -1 | cut -d: -f1)" '{print ($1 > last) ? 1 : 0}')"
assert_contains "the block says why the stack has to stop first" "$BLOCK" "Stop the stack FIRST"

# The block alone does not decide the run: setup-env.sh has to exit non-zero right after printing it.
if awk '
	/^\tprint_pending_secret_fixes$/ {seen=1; next}
	seen && /^[[:space:]]*$/ {next}
	seen {print; exit}
' "$SETUP_ENV_SH" | grep -q 'exit 1'; then
	pass "setup-env.sh exits non-zero right after printing the block"
else
	fail "setup-env.sh does not exit non-zero after printing the pending block"
fi

export PATH="$ORIGINAL_PATH"

# ------------------------------------------
# 5. The compose wiring: each gid on exactly the services that read that credential.
# ------------------------------------------
EXPECTED_GROUP_ADD='keycloak|9313
lifecontrol-api|9311,9312
lifecontrol-postgres|9311,9312
postgres-exporter-keycloak|9313
postgres-exporter-lifecontrol|9312'

# normalize_group_add -- one TAB row per service: name<TAB>sorted gids, empty when the service declares
# none, so a gid that disappears and a gid that appears are both visible.
normalize_group_add() {
	python3 -c '
import json
import sys

data = json.load(sys.stdin)
for name in sorted(data.get("services", {})):
    gids = data["services"][name].get("group_add") or []
    print("%s\t%s" % (name, ",".join(sorted(str(g) for g in gids))))
'
}

env_file_for() {
	local env_name="$1" candidate
	[ "$env_name" = "base" ] && env_name="dev"
	for candidate in "$DOCKER_DIR/.env.$env_name" "$DOCKER_DIR/.env.template"; do
		if [ -f "$candidate" ]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done
	return 1
}

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
	for combo in base dev prod; do
		case "$combo" in
		base) args=(-f docker-compose.yml) ;;
		dev) args=(-f docker-compose.yml -f docker-compose.override.yml) ;;
		prod) args=(-f docker-compose.yml -f docker-compose.prod.yml) ;;
		esac
		env_file="$(env_file_for "$combo")"
		rendered="$(cd "$DOCKER_DIR" && docker compose --env-file "$env_file" "${args[@]}" config --format json 2>/dev/null | normalize_group_add)"
		if [ -z "$rendered" ]; then
			fail "$combo: \`docker compose config\` produced nothing, so the group_add wiring could not be read"
			continue
		fi
		declared="$(printf '%s\n' "$rendered" | awk -F'\t' '$2 != "" {print $1 "|" $2}' | sort)"
		missing="$(comm -23 <(printf '%s\n' "$EXPECTED_GROUP_ADD" | sort) <(printf '%s\n' "$declared"))"
		extra="$(comm -13 <(printf '%s\n' "$EXPECTED_GROUP_ADD" | sort) <(printf '%s\n' "$declared"))"
		if [ -z "$missing" ] && [ -z "$extra" ]; then
			pass "$combo: the five consumers carry exactly their credential's gid, and no other service declares one"
		else
			[ -n "$missing" ] && fail "$combo: missing group_add on: $(printf '%s' "$missing" | tr '\n' ' ')"
			[ -n "$extra" ] && fail "$combo: unexpected group_add on: $(printf '%s' "$extra" | tr '\n' ' ')"
		fi
	done
else
	skip "the docker compose CLI is unavailable, so the group_add wiring was not checked"
fi

# ------------------------------------------
# 6. The criterion, measured with real containers.
# ------------------------------------------
# "Not readable by a second local account" is measured without creating one: a file owned by uid X, read
# by a throwaway container as uid X (READ), as uid 1234 (DENIED), and -- for the group-based rows -- as
# uid X *without* the gid (DENIED, so the group is what grants it, not a widened mode). The image is the
# one this stack already runs for postgres, read out of the rendered config so no second version is
# written down here.
DOCKER_AVAILABLE=0
if docker info >/dev/null 2>&1; then
	DOCKER_AVAILABLE=1
fi

PROBE_IMAGE="$(cd "$DOCKER_DIR" && docker compose --env-file "$(env_file_for base)" -f docker-compose.yml config --format json 2>/dev/null |
	python3 -c 'import json,sys
try: print(json.load(sys.stdin)["services"]["lifecontrol-postgres"]["image"])
except Exception: print("")' 2>/dev/null)"
[ -n "$PROBE_IMAGE" ] || PROBE_IMAGE="postgres:18.6"

if [ "$DOCKER_AVAILABLE" -eq 0 ]; then
	skip "no Docker daemon: the criterion itself (a foreign uid cannot read the file, the declared reader can) was NOT measured"
elif ! docker image inspect "$PROBE_IMAGE" >/dev/null 2>&1; then
	skip "image $PROBE_IMAGE is not present locally: the criterion was not measured (the suite never pulls)"
else
	CRITERION="$WORK/criterion"
	mkdir -p "$CRITERION"
	printf 'x\n' >"$CRITERION/own-0400"
	chmod 0400 "$CRITERION/own-0400"
	printf '{"redis://probe":"x"}\n' >"$CRITERION/group-0440"
	# A root:9312 file cannot be created by an unprivileged run; the container reads it as root and
	# chowns it, which is the same inode state an operator's sudo block produces.
	docker run --rm --user 0:0 -v "$CRITERION:/w" "$PROBE_IMAGE" \
		sh -c 'chown 0:9312 /w/group-0440 && chmod 0440 /w/group-0440' >/dev/null 2>&1

	probe() {
		# probe <file> <user:group> -- READ when the container can open the file, DENIED otherwise.
		local file="$1" identity="$2"
		docker run --rm --user "$identity" -v "$file:/mnt/probe:ro" "$PROBE_IMAGE" \
			sh -c 'cat /mnt/probe >/dev/null 2>&1 && printf READ || printf DENIED' 2>/dev/null
	}

	assert_equal "0400 owned by uid $(id -u): that uid reads it" "READ" "$(probe "$CRITERION/own-0400" "$(id -u):$(id -g)")"
	assert_equal "0400 owned by uid $(id -u): an arbitrary uid cannot read it" "DENIED" "$(probe "$CRITERION/own-0400" 1234:1234)"
	assert_equal "0440 root:9312: a reader in gid 9312 reads it" "READ" "$(probe "$CRITERION/group-0440" 1001:9312)"
	assert_equal "0440 root:9312: the same uid WITHOUT gid 9312 cannot read it, so the group is the grant" "DENIED" "$(probe "$CRITERION/group-0440" 1001:1001)"
	assert_equal "0440 root:9312: an arbitrary uid cannot read it" "DENIED" "$(probe "$CRITERION/group-0440" 1234:1234)"

	# The rows the exporters depend on, measured with the real image: postgres-exporter reads its password
	# with DATA_SOURCE_PASS_FILE as uid 65534, so a 0440 root:<gid> file is readable only when 65534 is in
	# that group. As a foreign uid it refuses to start; with the gid it starts and listens.
	EXPORTER_IMAGE="$(cd "$DOCKER_DIR" && docker compose --env-file "$(env_file_for base)" -f docker-compose.yml config --format json 2>/dev/null |
		python3 -c 'import json,sys
try: print(json.load(sys.stdin)["services"]["postgres-exporter-lifecontrol"]["image"])
except Exception: print("")' 2>/dev/null)"
	if [ -n "$EXPORTER_IMAGE" ] && docker image inspect "$EXPORTER_IMAGE" >/dev/null 2>&1; then
		exporter_log="$WORK/exporter.log"
		# Attached (background) plus `docker stop` by name, `--rm` on: a client-side `timeout` around a
		# foreground `docker run` kills the CLIENT and the container keeps running -- that is how a probe
		# leaked a stray container while this suite was being written -- while `-d --rm` loses the log of a
		# container that exits on its own. So the client writes the log, the container is stopped by name
		# when it has said what this case asks, and `--rm` removes it on the way out.
		exporter_probe() {
			local identity="$1" log="$2" name="lc-suite-exporter-$$-$RANDOM" pid waited=0
			docker run --rm --name "$name" --user "$identity" -v "$CRITERION/group-0440:/mnt/c.dat:ro" \
				-e DATA_SOURCE_URI="postgres:5432/x?sslmode=disable" -e DATA_SOURCE_USER=x \
				-e DATA_SOURCE_PASS_FILE=/mnt/c.dat "$EXPORTER_IMAGE" >"$log" 2>&1 &
			pid=$!
			while [ "$waited" -lt 40 ]; do
				grep -q "failed loading data source pass file\|Listening on" "$log" 2>/dev/null && break
				sleep 0.5
				waited=$((waited + 1))
			done
			docker stop "$name" >/dev/null 2>&1
			wait "$pid" 2>/dev/null || true
			return 0
		}
		exporter_probe 65534:65534 "$exporter_log"
		assert_contains "the exporter as uid 65534 without gid 9312 cannot read its password file" \
			"$(cat "$exporter_log")" "failed loading data source pass file"
		exporter_probe 65534:9312 "$exporter_log"
		assert_absent "the exporter inside gid 9312 reads it, so the group is enough" \
			"$(cat "$exporter_log")" "failed loading data source pass file"
	else
		skip "the exporter image is not present locally: its DATA_SOURCE_PASS_FILE read was not measured"
	fi
fi

# ------------------------------------------
# The rotation path never re-opens a credential
# ------------------------------------------
# `cleanup.sh secrets <env>` rematerializes every credential. Deciding `0444` there would undo the whole
# table in one command, and the failure would outlive the deploy that looked fine: the files would be
# world-readable until the next `setup-env.sh`. The mode it sets must never be wider than its creator, and
# the closing warning must name the one script that applies the declared shape.
cleanup_sh="$DOCKER_DIR/scripts/cleanup.sh"
if [ ! -f "$cleanup_sh" ]; then
	fail "missing docker/scripts/cleanup.sh"
else
	cleanup_body="$(cat "$cleanup_sh")"
	assert_absent "cleanup.sh never widens a rotated credential to the old world-readable mode" \
		"$cleanup_body" "chmod 0444"
	assert_contains "cleanup.sh narrows a rotated credential to its creator" \
		"$cleanup_body" "chmod 0600"
	assert_contains "cleanup.sh names the script that applies the declared shape" \
		"$cleanup_body" 'setup-env.sh $ENV'
	assert_contains "cleanup.sh still tells the operator to restart after applying it" \
		"$cleanup_body" 'deploy.sh $ENV restart'
fi

echo ""
echo "=========================================="
printf 'secrets-ownership: %d passed, %d failed, %d skipped\n' "$PASS_COUNT" "$FAIL_COUNT" "$SKIP_COUNT"
echo "=========================================="

[ "$FAIL_COUNT" -eq 0 ]
