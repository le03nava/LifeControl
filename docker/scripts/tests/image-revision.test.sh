#!/bin/bash
# ============================================
# LifeControl - Image revision evidence tests
# ============================================
# The three application Dockerfiles bake `org.opencontainers.image.revision` into every image and,
# before this section existed, nothing in the repository read it back. "Which code is running?" had
# one answer: a hand-written `docker image inspect`, typed from memory, on the machine that happened
# to be looking. That is not a procedure, and it is how a stack keeps a green `health` over images
# built ten commits ago -- the same silent-green failure `WORKER_ENABLED` and the image pins each
# produced in their turn.
#
# What the reader has to get right, and what this suite pins:
#
#   1. A missing or `unknown` revision is NOT a match. An image built without `deploy.sh` (a direct
#      `docker compose build`) carries the literal `unknown`, and treating that as evidence of HEAD
#      is the defect, not an edge case.
#   2. `dirty` dominates `drift`. An image built from a tree with uncommitted changes carries code
#      no commit has, whatever revision sits under the suffix.
#   3. The check reads the image a container runs **by ID**, never by tag: a tag is a pointer a
#      later build can move, so a tag-based check answers "which image would run".
#
# Verdicts are pure functions of (label, expected, present), so every branch is asserted here
# without a Docker daemon; the container/image readers are asserted against a stubbed `docker`; and
# one end-to-end case runs the real `deploy.sh <env> version` inside a throwaway tree to pin its
# exit contract, because a report nobody can act on in a script is the same as no report.
#
# No Docker daemon, no network, no Gradle: every case runs against stubs and a `mktemp -d` tree.
#
# Run: ./docker/scripts/tests/image-revision.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
COMMON="$DOCKER_DIR/scripts/_common.sh"
DEPLOY_SH="$DOCKER_DIR/scripts/deploy.sh"

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
		fail "$1 (expected '$2', got '$3')"
	fi
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# shellcheck source=../_common.sh
source "$COMMON"

# ------------------------------------------
# 1. The verdict, as a pure function
# ------------------------------------------
# Every case below is a state this stack has actually been in or can reach, and each one that is
# not `match` has to read as "not proven to be HEAD". A verdict function that returned `match` for
# an unlabelled image would erase the whole point of the check.

HEAD_SHA="149d5e11999a80b7dd8fc7f6a69e5573fcf8c431"
OTHER_SHA="0badc0ffee00a80b7dd8fc7f6a69e5573fcf8c431"

check_equal "the image built from HEAD is a match" \
	"match" "$(image_revision_verdict "$HEAD_SHA" "$HEAD_SHA" 1)"
check_equal "an image built from another commit drifts" \
	"drift" "$(image_revision_verdict "$OTHER_SHA" "$HEAD_SHA" 1)"
check_equal "an image built from a dirty tree says so" \
	"dirty" "$(image_revision_verdict "${HEAD_SHA}-dirty" "$HEAD_SHA" 1)"
check_equal "dirty dominates drift: uncommitted code is in no commit" \
	"dirty" "$(image_revision_verdict "${OTHER_SHA}-dirty" "$HEAD_SHA" 1)"
check_equal "an image with no label is unknown, never a match" \
	"unknown" "$(image_revision_verdict "" "$HEAD_SHA" 1)"
check_equal "the literal 'unknown' stamp is unknown, never a match" \
	"unknown" "$(image_revision_verdict "unknown" "$HEAD_SHA" 1)"
check_equal "a service with no container is absent, never a match" \
	"absent" "$(image_revision_verdict "" "$HEAD_SHA" 0)"
check_equal "a labelled image with no readable HEAD is not proven" \
	"drift" "$(image_revision_verdict "$HEAD_SHA" "" 1)"

# The published vocabulary and the verdicts above must be the same set: a verdict that is printed
# but not declared (or declared but never printed) is what a caller's `case` statement reads wrong.
declared=""
for verdict in $IMAGE_REVISION_VERDICTS; do
	declared="$declared $verdict"
done
check_equal "the declared verdict vocabulary is the one the suite asserts" \
	" match drift dirty unknown absent" "$declared"

# ------------------------------------------
# 2. The stamp the build writes
# ------------------------------------------
# The stamp is the other half of the contract: an image can only be compared to HEAD if the build
# admits when it did not come from HEAD. These cases stub the two git readers instead of building.

current_git_revision() { printf '%s' "${FIXTURE_HEAD:-}"; }
git_tree_is_dirty() { [ "${FIXTURE_TREE_DIRTY:-0}" = "1" ]; }

FIXTURE_HEAD="$HEAD_SHA"
FIXTURE_TREE_DIRTY=0
check_equal "a clean tree stamps the bare commit" \
	"$HEAD_SHA" "$(image_revision_stamp)"

FIXTURE_TREE_DIRTY=1
check_equal "a dirty tree stamps the commit plus the dirty suffix" \
	"${HEAD_SHA}-dirty" "$(image_revision_stamp)"

# A dirty tree with no readable HEAD has nothing honest to say about its code.
FIXTURE_HEAD=""
check_equal "no readable HEAD stamps 'unknown', never a revision" \
	"unknown" "$(image_revision_stamp)"

# The suffix has to be the one the verdict reads back, or a dirty build reports as a clean match.
case "$IMAGE_REVISION_DIRTY_SUFFIX" in
-*) pass "the dirty suffix is a git-style -suffix ($IMAGE_REVISION_DIRTY_SUFFIX)" ;;
*) fail "the dirty suffix '$IMAGE_REVISION_DIRTY_SUFFIX' is not git-style" ;;
esac

# ------------------------------------------
# 3. Reading the image a container actually runs
# ------------------------------------------
# By ID, never by tag. The stub records every `docker inspect` call so the assertion is about HOW
# the image was read, not only about what was returned.

DOCKER_CALLS="$WORK_DIR/docker-calls.log"
: >"$DOCKER_CALLS"

docker() {
	printf '%s\n' "$*" >>"$DOCKER_CALLS"
	case "$1" in
	inspect)
		# docker inspect --format '{{.Image}}' <container>
		printf '%s\n' "${STUB_CONTAINER_IMAGE:-}"
		;;
	image)
		# docker image inspect <id> --format '{{index .Config.Labels "..."}}'
		printf '%s\n' "${STUB_LABEL:-}"
		;;
	*) return 1 ;;
	esac
}

COMPOSE_CALLS="$WORK_DIR/compose-calls.log"
: >"$COMPOSE_CALLS"

compose_run() {
	# Records the call so the assertions below can check WHICH containers are asked for, not only
	# what comes back: a stopped container answering for the running stack is the failure this
	# reader has to make impossible.
	printf '%s\n' "$*" >>"$COMPOSE_CALLS"
	printf '%s\n' "${STUB_CONTAINER_ID:-}"
}

STUB_CONTAINER_ID="9f1c0a2b7d55"
STUB_CONTAINER_IMAGE="sha256:d740bd21c95d078b09066d0d42615460dbf248dee809422619ddf535fa7329a8"
STUB_LABEL="$HEAD_SHA"

check_equal "the running image is read by container id" \
	"$STUB_CONTAINER_IMAGE" "$(service_running_image_id api-gateway)"
if grep -q -- "--format {{.Image}} 9f1c0a2b7d55" "$DOCKER_CALLS"; then
	pass "docker inspect is asked for the container's image id"
else
	fail "docker inspect was not asked for the image id of the container"
fi

if ! grep -q -- '-a' "$COMPOSE_CALLS" && grep -q 'ps -q api-gateway' "$COMPOSE_CALLS"; then
	pass "compose is asked for running containers only (no -a): a stopped stack cannot report a match"
else
	fail "compose is asked for stopped containers too: a stopped stack could report a match"
fi

check_equal "the label is read from the image id" \
	"$HEAD_SHA" "$(image_revision_label "$STUB_CONTAINER_IMAGE")"
if grep -q -- "image inspect sha256:d740bd21c95d078b" "$DOCKER_CALLS"; then
	pass "docker image inspect receives the image id, not a tag"
else
	fail "docker image inspect did not receive the image id"
fi

# A service that was never created has no container: no guessing, and no docker call either.
: >"$DOCKER_CALLS"
STUB_CONTAINER_ID=""
check_equal "a service with no container yields no image id" \
	"" "$(service_running_image_id web-app)"
if [ ! -s "$DOCKER_CALLS" ]; then
	pass "no container means no docker inspect call"
else
	fail "docker was called for a service with no container"
fi

check_equal "no image id yields no label" "" "$(image_revision_label "")"

# ------------------------------------------
# 4. The report, and the count a strict caller reads
# ------------------------------------------
# The counter is set by the report rather than returned, so it has to survive the call. It only
# does when the report is invoked DIRECTLY -- this case calls it directly on purpose, because the
# documented trap is `out=$(report_image_revisions)`, where the report prints and the counter never
# reaches the caller.

declare -A FIXTURE_IMAGE_IDS=()
declare -A FIXTURE_LABELS=()

service_running_image_id() { printf '%s' "${FIXTURE_IMAGE_IDS[$1]:-}"; }
image_revision_label() { printf '%s' "${FIXTURE_LABELS[$1]:-}"; }
current_git_revision() { printf '%s' "$HEAD_SHA"; }
git_tree_is_dirty() { [ "${FIXTURE_TREE_DIRTY:-0}" = "1" ]; }
ENV="dev"

reset_fixtures() {
	FIXTURE_IMAGE_IDS=()
	FIXTURE_LABELS=()
	FIXTURE_TREE_DIRTY=0
}

report_to_file() {
	REPORT_OUT="$WORK_DIR/report.out"
	report_image_revisions >"$REPORT_OUT" 2>&1
	REPORT_COUNT="$IMAGE_REVISION_MISMATCH_COUNT"
}

# 4a. Every image built from HEAD: no mismatch, and the services are named.
reset_fixtures
for service in $(image_revision_services); do
	FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
	FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
done
report_to_file
check_equal "every image from HEAD reports no mismatch" "0" "$REPORT_COUNT"
for service in $(image_revision_services); do
	if grep -qF "$service" "$REPORT_OUT"; then
		pass "the report names $service"
	else
		fail "the report does not name $service"
	fi
done
check_equal "the matching line is a success, not a warning" \
	"0" "$(grep -c '^.*WARNING.*NOT proven' "$REPORT_OUT")"

# 4b. Each non-match verdict is counted, and the warning names the service.
for case_name in drift dirty unknown absent; do
	reset_fixtures
	for service in $(image_revision_services); do
		FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
		FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
	done

	case "$case_name" in
	drift) FIXTURE_LABELS["sha256:api-gateway0000000000000000"]="$OTHER_SHA" ;;
	dirty) FIXTURE_LABELS["sha256:api-gateway0000000000000000"]="${HEAD_SHA}-dirty" ;;
	unknown) FIXTURE_LABELS["sha256:api-gateway0000000000000000"]="unknown" ;;
	absent) FIXTURE_IMAGE_IDS[api-gateway]="" ;;
	esac

	report_to_file
	check_equal "a $case_name image counts as one mismatch" "1" "$REPORT_COUNT"
	if grep -q "api-gateway.*$case_name" "$REPORT_OUT"; then
		pass "the $case_name verdict is printed for api-gateway"
	else
		fail "the $case_name verdict is not printed for api-gateway"
	fi
done

# 4c. The counter is per run: a clean report after a dirty one reads 0, never the previous count.
reset_fixtures
for service in $(image_revision_services); do
	FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
	FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
done
FIXTURE_LABELS["sha256:api-gateway0000000000000000"]="$OTHER_SHA"
report_to_file
check_equal "the drifting run counts one mismatch" "1" "$REPORT_COUNT"
reset_fixtures
for service in $(image_revision_services); do
	FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
	FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
done
report_to_file
check_equal "the next clean run does not inherit the previous count" "0" "$REPORT_COUNT"

# 4d. A dirty working tree is reported even when every image matches HEAD: the image cannot carry
# changes nobody committed, and saying so is the whole point of asking.
reset_fixtures
for service in $(image_revision_services); do
	FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
	FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
done
FIXTURE_TREE_DIRTY=1
report_to_file
if grep -qE 'uncommitted changes' "$REPORT_OUT"; then
	pass "a dirty working tree is reported next to matching images"
else
	fail "a dirty working tree is not reported"
fi

# 4e. With no readable HEAD nothing is proven, and "nothing proven" is not "everything matches":
# the report fails closed, so `version` exits non-zero and the strict health guard fails instead of
# accepting a stack no image was compared to. Asserting 0 here -- which an earlier version of this
# suite did -- pins the silent pass the review found.
reset_fixtures
for service in $(image_revision_services); do
	FIXTURE_IMAGE_IDS[$service]="sha256:${service}0000000000000000"
	FIXTURE_LABELS["sha256:${service}0000000000000000"]="$HEAD_SHA"
done
current_git_revision() { printf ''; }
report_to_file
expected_services="$(image_revision_services | grep -c .)"
check_equal "no readable HEAD fails closed: every application image counts as not proven" \
	"$expected_services" "$REPORT_COUNT"
if grep -qE 'Cannot read the repository revision' "$REPORT_OUT"; then
	pass "no readable HEAD is reported as missing evidence"
else
	fail "no readable HEAD is not reported"
fi
if grep -qE 'count as NOT proven to be HEAD' "$REPORT_OUT"; then
	pass "the fail-closed consequence is stated, not implied"
else
	fail "the report does not say the images count as not proven"
fi
current_git_revision() { printf '%s' "$HEAD_SHA"; }

# 4f. The report never fails a caller on its own: validate-env.sh is the production start gate, so
# a report that could stop a start is the defect the pin report already refuses.
reset_fixtures
FIXTURE_IMAGE_IDS[api-gateway]="sha256:api-gateway0000000000000000"
FIXTURE_LABELS["sha256:api-gateway0000000000000000"]="$OTHER_SHA"
report_image_revisions >/dev/null 2>&1
check_equal "the report returns 0 even with mismatches" "0" "$?"

# ------------------------------------------
# 5. The wiring, asserted statically
# ------------------------------------------
# Behaviour is asserted above; these cases assert the parts that only exist as wiring. Without them
# the reader can be perfect and simply never called -- which is exactly the state the three
# Dockerfiles were in before this unit.

if [ -f "$DEPLOY_SH" ]; then
	pass "docker/scripts/deploy.sh exists"
else
	fail "missing docker/scripts/deploy.sh"
fi

if grep -qE '^version\)' "$DEPLOY_SH"; then
	pass "deploy.sh dispatches a version command"
else
	fail "deploy.sh has no version) dispatch"
fi

if grep -q 'show_version' "$DEPLOY_SH"; then
	pass "deploy.sh defines the version command body"
else
	fail "deploy.sh has no show_version"
fi

if grep -q 'version           - Show the revision' "$DEPLOY_SH"; then
	pass "the help text documents the version command"
else
	fail "the help text does not document the version command"
fi

if grep -q 'image_revision_stamp' "$DEPLOY_SH"; then
	pass "build_images stamps through image_revision_stamp"
else
	fail "build_images does not use image_revision_stamp: a dirty build would claim a clean commit"
fi

# The label the reader reads and the label the Dockerfiles write have to be the same string. This is
# the coupling that a rename in one file and not the other breaks silently.
dockerfile_missing=""
for dockerfile in "$REPO_ROOT/api-gateway/Dockerfile" \
	"$REPO_ROOT/life-control-api/Dockerfile" \
	"$REPO_ROOT/life-control-app-angular/Dockerfile"; do
	if ! grep -qF "$IMAGE_REVISION_LABEL" "$dockerfile"; then
		dockerfile_missing="$dockerfile_missing $(basename "$(dirname "$dockerfile")")"
	fi
done
if [ -z "$dockerfile_missing" ]; then
	pass "all three application Dockerfiles bake $IMAGE_REVISION_LABEL"
else
	fail "these Dockerfiles do not bake $IMAGE_REVISION_LABEL:$dockerfile_missing"
fi

# ------------------------------------------
# 6. The exit contract, end to end
# ------------------------------------------
# A report nobody can act on in a script is the same as no report, so the real command runs in a
# throwaway tree: its own git repository (so HEAD is real), a stub `docker` first on PATH, and a
# materialized .env.dev, because that is the only state deploy.sh needs to start.

E2E="$WORK_DIR/e2e"
E2E_REPO="$E2E/repo"
mkdir -p "$E2E_REPO/docker/scripts" "$E2E/bin"
cp "$COMMON" "$E2E_REPO/docker/scripts/_common.sh"
cp "$DEPLOY_SH" "$E2E_REPO/docker/scripts/deploy.sh"
printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\n' >"$E2E_REPO/docker/.env.dev"

E2E_HEAD=""
if git init -q -b main "$E2E_REPO" 2>/dev/null &&
	git -C "$E2E_REPO" -c user.email=test@example.invalid -c user.name=test \
		commit -q --allow-empty -m "fixture" 2>/dev/null; then
	E2E_HEAD="$(git -C "$E2E_REPO" rev-parse HEAD)"
	pass "the throwaway tree is a repository with one commit"
else
	fail "could not build the throwaway repository"
fi

cat >"$E2E/bin/docker" <<'STUB'
#!/bin/bash
# Answers only what deploy.sh <env> version asks for, from the environment.
case "$1" in
info) exit 0 ;;
compose)
	# compose_run ps -a -q <service>
	printf 'cid-%s\n' "${@: -1}"
	;;
inspect)
	# docker inspect --format '{{.Image}}' <container>
	printf 'sha256:fixtureimage000000000000000000000000000000000000000000000000000000\n'
	;;
image)
	# docker image inspect <id> --format '{{index .Config.Labels "..."}}'
	printf '%s\n' "${STUB_REVISION:-unknown}"
	;;
*) exit 1 ;;
esac
STUB
chmod +x "$E2E/bin/docker"

if [ -n "$E2E_HEAD" ]; then
	# 6a. Every image stamped with HEAD: the command answers 0.
	STUB_REVISION="$E2E_HEAD" PATH="$E2E/bin:$PATH" \
		"$E2E_REPO/docker/scripts/deploy.sh" dev version >"$WORK_DIR/e2e-match.out" 2>&1
	check_equal "version exits 0 when every image was built from HEAD" "0" "$?"
	if grep -q 'Every running application image was built from HEAD' "$WORK_DIR/e2e-match.out"; then
		pass "version says the images match HEAD"
	else
		fail "version does not report the match"
	fi

	# 6b. One image built from another commit: the command answers non-zero, so a script can gate.
	STUB_REVISION="$OTHER_SHA" PATH="$E2E/bin:$PATH" \
		"$E2E_REPO/docker/scripts/deploy.sh" dev version >"$WORK_DIR/e2e-drift.out" 2>&1
	check_equal "version exits non-zero when an image is not HEAD" "1" "$?"
	if grep -q 'not running the code this repository has checked out' "$WORK_DIR/e2e-drift.out"; then
		pass "version says the stack is not running this checkout"
	else
		fail "version does not explain the non-zero exit"
	fi

	# 6c. The `unknown` stamp is not a pass: it is the state of every image built outside deploy.sh.
	STUB_REVISION="unknown" PATH="$E2E/bin:$PATH" \
		"$E2E_REPO/docker/scripts/deploy.sh" dev version >"$WORK_DIR/e2e-unknown.out" 2>&1
	check_equal "version exits non-zero when the images carry no revision" "1" "$?"

	# 6e. STRICT_REVISION turns the same evidence into a failure, which is the switch an operator
	# asks for -- and it has to fail BEFORE the health loop, so a strict check is not a 150s wait.
	STUB_REVISION="$OTHER_SHA" STRICT_REVISION=1 PATH="$E2E/bin:$PATH" \
		"$E2E_REPO/docker/scripts/deploy.sh" dev health >"$WORK_DIR/e2e-strict.out" 2>&1
	check_equal "STRICT_REVISION fails health when an image is not HEAD" "1" "$?"
	if grep -q 'STRICT_REVISION is set' "$WORK_DIR/e2e-strict.out"; then
		pass "the strict failure names the switch that caused it"
	else
		fail "the strict failure does not name the switch"
	fi

	# 6d. The dirty working tree is named even when the images match.
	printf 'scratch\n' >"$E2E_REPO/docker/scratch.txt"
	STUB_REVISION="$E2E_HEAD" PATH="$E2E/bin:$PATH" \
		"$E2E_REPO/docker/scripts/deploy.sh" dev version >"$WORK_DIR/e2e-dirty.out" 2>&1
	if grep -q 'uncommitted changes' "$WORK_DIR/e2e-dirty.out"; then
		pass "version reports the uncommitted changes the images cannot carry"
	else
		fail "version does not report the working tree's uncommitted changes"
	fi
	rm -f "$E2E_REPO/docker/scratch.txt"
fi

# ------------------------------------------
# 5b. The switch, and the wiring that uses it
# ------------------------------------------
# `health` is a report by default, so a started stack is never failed by missing evidence; the switch
# is the opt-in that turns the same evidence into a failure. Its truth table is asserted through the
# shared helper, because a second copy of "what does on mean" is how SKIP_BUILD and STRICT_REVISION
# would end up disagreeing.

for on in 1 true yes TRUE Yes; do
	if env_flag_is_truthy "$on"; then
		pass "'$on' is truthy"
	else
		fail "'$on' is not read as truthy"
	fi
done
for off in "" 0 false no off TRUEISH; do
	if env_flag_is_truthy "$off"; then
		fail "'$off' is read as truthy"
	else
		pass "'$off' is not truthy"
	fi
done

if grep -q 'is_strict_revision' "$DEPLOY_SH"; then
	pass "deploy.sh declares the strict-revision switch"
else
	fail "deploy.sh does not declare the strict-revision switch"
fi

if grep -q 'STRICT_REVISION  - Set to' "$DEPLOY_SH"; then
	pass "the help text documents STRICT_REVISION"
else
	fail "the help text does not document STRICT_REVISION"
fi

# The guard has to live in health_check: it is the one function start, up, restart and health all
# reach, so wiring it there is what covers `up` and `SKIP_BUILD=true` -- the two paths that never
# build and therefore never saw a freshness check.
HEALTH_BODY="$(awk '/^health_check\(\)/{f=1} f{print} f&&/^}$/{exit}' "$DEPLOY_SH")"
if printf '%s' "$HEALTH_BODY" | grep -q 'report_image_revisions'; then
	pass "health_check reports the running image revisions"
else
	fail "health_check does not report the running image revisions"
fi

# `is_skip_build` was the only flag reader before this unit; it now delegates, so its behaviour has
# to be the behaviour it always had.
if grep -q 'env_flag_is_truthy "${SKIP_BUILD:-}"' "$DEPLOY_SH"; then
	pass "is_skip_build delegates to the shared flag helper"
else
	fail "is_skip_build does not delegate to the shared flag helper"
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo ""
echo "=========================================="
echo "Results: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "=========================================="

if [ "$FAIL_COUNT" -gt 0 ]; then
	exit 1
fi
exit 0
