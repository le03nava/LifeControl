#!/bin/bash
# ============================================
# LifeControl - Observability probe verdicts
# ============================================
# `deploy.sh health` used to answer one question: is every container running? That question was
# green on 2026-10-24 while the stack could not read a single trace it had flushed. Tempo 3.1.0
# answered HTTP 500 with `vParquet2 is not a valid block version` for every block on disk, the
# Prometheus jobs were all `up`, and `docker ps` said healthy. A signal that is collected and
# unreadable is worse than a signal that is missing, because nothing reports it.
#
# `host_observability_probes` (docker/scripts/_common.sh) is the reader that was missing, and this
# suite is what keeps its verdicts honest. Every case feeds it a fixture response instead of a live
# stack: the probe's job is to turn an answer into a verdict, and a verdict that is only testable by
# breaking the stack never gets tested. The stub below stands in for `curl`, honouring the two flags
# the probe uses (`-o <file>` and `-w '%{http_code}'`).
#
# The failure to avoid here is the one that motivated the unit: a probe that treats an unreadable
# or unreachable answer as "nothing is failing". Both paths are asserted to fail closed.
#
# No Docker daemon, no network: the stub replaces every request.
#
# Run: ./docker/scripts/tests/observability-probes.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMMON="$REPO_ROOT/docker/scripts/_common.sh"
DEPLOY="$REPO_ROOT/docker/scripts/deploy.sh"

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

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# shellcheck source=../_common.sh
source "$COMMON"

# ------------------------------------------
# The stub: every request the probe makes is answered from a fixture variable.
#
#   PROM_BODY    body of /api/v1/query  (the `up == 0` response)
#   SEARCH_BODY  body of /api/search
#   TRACE_CODE   status of /api/traces/<id>
#   API_FAIL     when non-empty, no request succeeds at all (a down API)
# ------------------------------------------
curl() {
	local out="" url="" prev="" arg
	for arg in "$@"; do
		case "$prev" in
		-o) out="$arg" ;;
		esac
		case "$arg" in
		http*) url="$arg" ;;
		esac
		prev="$arg"
	done

	if [ -n "${API_FAIL:-}" ]; then
		return 22
	fi

	case "$url" in
	*/api/v1/query*)
		printf '%s' "${PROM_BODY:-}" > "${out:-/dev/stdout}"
		;;
	*/api/search*)
		STUB_SEARCH_URL="$url"
		printf '%s' "${SEARCH_BODY:-}" > "${out:-/dev/stdout}"
		;;
	*/api/traces/*)
		# The probe reads the status with `-w '%{http_code}' -o /dev/null`.
		printf '%s' "${TRACE_CODE:-200}"
		;;
	*)
		return 22
		;;
	esac
	return 0
}

# Fixtures: one env file per case, because `get_env_var` reads lines out of it.
write_env() {
	local name="$1" prom="$2" tempo="$3"
	{
		[ -n "$prom" ] && printf 'PROMETHEUS_PORT=%s\n' "$prom"
		[ -n "$tempo" ] && printf 'TEMPO_PORT=%s\n' "$tempo"
	} > "$WORK_DIR/$name.env"
	printf '%s' "$WORK_DIR/$name.env"
}

STUB_SEARCH_URL=""
ALL_UP='{"status":"success","data":{"resultType":"vector","result":[]}}'
ONE_DOWN='{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"up","instance":"lifecontrol-api:8082","job":"lifecontrol-api"},"value":[1,"0"]}]}}'
TWO_DOWN_SAME_JOB='{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"up","instance":"a:9001","job":"api-gateway"},"value":[1,"0"]},{"metric":{"__name__":"up","instance":"b:9001","job":"api-gateway"},"value":[1,"0"]}]}}'
QUERY_ERROR='{"status":"error","errorType":"bad_data","error":"parse error"}'
SEARCH_ONE='{"traces":[{"traceID":"8c471a9e3bbbaa31cbf6037ac64ca35","rootServiceName":"api-gateway"}],"metrics":{}}'
SEARCH_EMPTY='{"traces":[],"metrics":{"inspectedTraces":0}}'

# run_probe <env-file> -> sets PROBE_OUT, PROBE_ERR, PROBE_RC
run_probe() {
	# Exported, not merely assigned: `get_env_var` in _common.sh reads $ENV_FILE from the environment
	# it runs in, and this is the one place the suite tells it which fixture to read.
	export ENV_FILE="$1"
	: > "$WORK_DIR/out"
	: > "$WORK_DIR/err"
	host_observability_probes > "$WORK_DIR/out" 2> "$WORK_DIR/err"
	PROBE_RC=$?
	PROBE_OUT="$(cat "$WORK_DIR/out")"
	PROBE_ERR="$(cat "$WORK_DIR/err")"
}

assert_verdict() {
	local label="$1" want_rc="$2" want_out="$3" want_err_contains="${4:-}"
	if [ "$PROBE_RC" -ne "$want_rc" ]; then
		fail "$label: expected exit $want_rc, got $PROBE_RC (stdout: '${PROBE_OUT}')"
		return
	fi
	if [ "$PROBE_OUT" != "$want_out" ]; then
		fail "$label: expected stdout '${want_out}', got '${PROBE_OUT}'"
		return
	fi
	if [ -n "$want_err_contains" ] && ! printf '%s' "$PROBE_ERR" | grep -qF -- "$want_err_contains"; then
		fail "$label: expected stderr to mention '${want_err_contains}', got '${PROBE_ERR}'"
		return
	fi
	pass "$label"
}

# ------------------------------------------
# 1. Everything readable: the probe is silent and succeeds.
# ------------------------------------------
PROM_BODY="$ALL_UP" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=200
run_probe "$(write_env clean 9090 3110)"
assert_verdict "all targets up and a readable trace pass silently" 0 ""

# ------------------------------------------
# 2. A failing target is named, and the probe fails.
# ------------------------------------------
PROM_BODY="$ONE_DOWN" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=200
run_probe "$(write_env one-down 9090 3110)"
assert_verdict "one failing target names its job" 1 " Prometheus(lifecontrol-api-not-up)"

PROM_BODY="$TWO_DOWN_SAME_JOB" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=200
run_probe "$(write_env two-down 9090 3110)"
assert_verdict "two instances of one job are reported once" 1 " Prometheus(api-gateway-not-up)"

# ------------------------------------------
# 3. Fail closed: an unreadable or unreachable Prometheus is a failure, never a silent pass.
# ------------------------------------------
PROM_BODY="$QUERY_ERROR" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=200
run_probe "$(write_env query-error 9090 3110)"
assert_verdict "a Prometheus error response fails closed" 1 " Prometheus(report-unreadable)"

PROM_BODY="$ALL_UP" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=200 API_FAIL=1
run_probe "$(write_env prom-down 9090 3110)"
assert_verdict "an unreachable Prometheus fails closed" 1 " Prometheus(no-api) Tempo(no-api)"
unset API_FAIL

# ------------------------------------------
# 4. Tempo: readable, unreadable, empty, and unreachable.
# ------------------------------------------
PROM_BODY="$ALL_UP" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=500
run_probe "$(write_env trace-500 9090 3110)"
assert_verdict "a trace that cannot be read fails the probe" 1 " Tempo(trace-not-readable:500)"

PROM_BODY="$ALL_UP" SEARCH_BODY="$SEARCH_EMPTY" TRACE_CODE=200
run_probe "$(write_env trace-empty 9090 3110)"
assert_verdict "a window with no flushed trace passes and says so out loud" 0 "" "no flushed trace in the probe window"

# The empty-store notice must not reach stdout: the caller captures stdout as failure tokens.
if printf '%s' "$PROBE_OUT" | grep -q "no flushed trace"; then
	fail "the empty-window notice leaked to stdout, where the caller reads failure tokens"
else
	pass "the empty-window notice stays on stderr"
fi

# The window is the probe: `limit=1` with no range answered 200 from the ingester on the broken
# stack while every flushed trace answered 500, so a regression to the newest-trace read would make
# this whole suite pass over the defect it was written for.
case "$STUB_SEARCH_URL" in
*start=*end=*)
	pass "the trace read asks for a flushed window, not the newest trace"
	;;
*)
	fail "the trace read does not bound a window (url: '${STUB_SEARCH_URL}'): it can pass on traces that were never flushed"
	;;
esac

# ------------------------------------------
# 5. Only the configured signals are probed.
# ------------------------------------------
PROM_BODY="$ONE_DOWN" SEARCH_BODY="$SEARCH_ONE" TRACE_CODE=500
run_probe "$(write_env tempo-only "" 3110)"
assert_verdict "an unconfigured Prometheus port is skipped" 1 " Tempo(trace-not-readable:500)"

run_probe "$(write_env nothing-configured "" "")"
assert_verdict "no configured port means no probe and no failure" 0 ""

# ------------------------------------------
# 6. Wiring: the probe is only worth its verdicts if the health check runs it.
# ------------------------------------------
health_body="$(sed -n '/^health_check()/,/^}/p' "$DEPLOY" 2>/dev/null)"
if printf '%s' "$health_body" | grep -q 'host_observability_probes'; then
	pass "health_check runs host_observability_probes"
else
	fail "health_check does not run host_observability_probes: the verdicts above reach nobody"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
