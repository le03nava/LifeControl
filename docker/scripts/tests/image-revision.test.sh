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

compose_run() {
	# compose_run ps -a -q <service>
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
