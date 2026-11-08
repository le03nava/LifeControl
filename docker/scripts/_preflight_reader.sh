#!/bin/bash
# ============================================
# LifeControl - the reporting reader both money pre-flights read through
# ============================================
# A report that reads `sales_orders` has one question to answer before it can answer its own: **which
# role reads it**. That question is not a detail of one script, so it lives here once and both pre-flights
# source it — `preflight-money-parts.sh` (ADR-0002 step 2, the total against the line sum) and
# `preflight-payment-methods.sh` (ADR-0002 step 4, the historical payment backfill of D-a). Two copies of
# this resolution would drift, and the way they drift is the way that matters: one of them would keep
# reading rows while the other silently reported a clean dataset.
#
# **It reads as a superuser, and that is not a convenience.** `sales_orders` is armed with `ENABLE` +
# `FORCE` ROW LEVEL SECURITY (`V65`), and `apply_tenant_isolation` makes the owner subject to the policy
# too. Run as `lifecontrol_owner` — the role Flyway uses — a report over that table reads **zero rows and
# prints a clean dataset**, which is the exact failure `V54` and `V64` already record. So the reader is
# resolved by asking `pg_roles` for `rolsuper`, in the same order the roles wrapper uses, and the owner is
# deliberately **not** a candidate: a candidate that connects but reads nothing is worse than no answer.
#
# Sourced, never executed. The caller must have sourced `_common.sh` and called `resolve_compose_env`
# before calling `init_preflight_reader`, because the role list and the database name are read from the
# env file that resolution names. This library decides nothing and writes nothing: it resolves a reader,
# and `psql_report` runs the one statement the caller hands it.
#
# Run: sourced by the two pre-flights above; no entry point of its own.
# Suites: docker/scripts/tests/preflight-money-parts.test.sh,
#         docker/scripts/tests/preflight-payment-methods.test.sh

# The service both reports read through: the one Postgres instance the compose stack runs, named once so
# a second report cannot address a different one.
POSTGRES_SERVICE="lifecontrol-postgres"
# The question that decides the reader. `rolsuper` and not "did it connect": a role that connects and is
# filtered by the policy answers every report with an empty set and exit 0.
SUPERUSER_PROBE="SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper"

# init_preflight_reader sets REPORT_ROLE_CANDIDATES and db_name from the env file `resolve_compose_env`
# resolved. It is a function rather than top-level code so the sourcing script keeps its own order:
# argument parsing (`--help` must not need a database), then the environment, then the reader.
init_preflight_reader() {
	local declared_role

	# The maintenance superuser is the first candidate for a reason `V64` records: PostgreSQL refuses to
	# take SUPERUSER away from the role initdb created, so on any volume this repository initializes,
	# `lifecontrol_bootstrap` is a superuser with no password that reaches the database over the
	# container's local socket. `postgres` covers an image that still has it, and the declared initdb role
	# covers a volume whose key names something else. The owner is not a candidate — see the header.
	REPORT_ROLE_CANDIDATES="lifecontrol_bootstrap postgres"

	db_name="$(get_env_var LIFECONTROL_POSTGRES_DB)"
	if [ -z "$db_name" ]; then
		db_name="lifecontrol"
	fi

	# A declared role that is already a candidate is not tried twice: the report would ask the same role
	# the same question and print the same answer, which reads as two candidates to an operator debugging
	# it.
	declared_role="$(get_env_var LIFECONTROL_POSTGRES_USER)"
	case " $REPORT_ROLE_CANDIDATES " in
	*" $declared_role "*) ;;
	*) [ -z "$declared_role" ] || REPORT_ROLE_CANDIDATES="$REPORT_ROLE_CANDIDATES $declared_role" ;;
	esac
}

# psql_report <role> <psql args...> — one statement, tuples only, no alignment, so the caller reads the
# answer and not psql's presentation of it.
psql_report() {
	local username="$1"
	shift
	compose_run exec -T "$POSTGRES_SERVICE" psql \
		--username "$username" --dbname "$db_name" \
		--no-psqlrc --no-password --tuples-only --no-align "$@"
}

# resolve_report_role echoes the first candidate that connects AND answers `rolsuper` with one row. A
# candidate that connects but is not a superuser prints no row, which is the shape of the defect this
# whole resolution exists for: the report would run and find nothing.
resolve_report_role() {
	local candidate
	for candidate in $REPORT_ROLE_CANDIDATES; do
		if [ "$(psql_report "$candidate" --command "$SUPERUSER_PROBE" 2>/dev/null)" = "1" ]; then
			printf '%s' "$candidate"
			return 0
		fi
	done
	return 1
}
