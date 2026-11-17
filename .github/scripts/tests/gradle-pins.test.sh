#!/bin/bash
# ============================================
# LifeControl - Gradle pin tests
# ============================================
# Answers the Gradle half of issue #896's S5, which no suite asked before: does the toolchain this
# repository builds with drift without a commit here?
#
# The failure it exists for is silent, not loud. A `distributionUrl` decides which Gradle
# downloads for a module, and a `plugins { }` block decides which Spring Boot, spotless, spotbugs
# and asciidoctor run. Both are versions written in the tree, read by `./gradlew` and by nothing
# else: a patch bump in either is a one-line diff with no failing test, no reviewer prompt and no
# record. That is exactly how this tree ended up with two modules on different Spring Boot patches
# (4.1.1 vs 4.1.2) and different wrappers (gradle-9.8.1 vs gradle-9.9.0) — measured, frozen below,
# and **deliberately not aligned here**: issue #894 owns that, and a suite that quietly fixed the
# drift while freezing it would be the second place two versions are decided.
#
# What is frozen, and how
#   * the `distributionUrl` of BOTH wrappers, exactly as it is today;
#   * every `id '<plugin>' version '<v>'` literal in the root `build.gradle`,
#     `api-gateway/build.gradle` and `life-control-api/build.gradle`.
# The check runs in BOTH directions: a version on the tree that the table does not carry fails,
# and a table entry the tree no longer declares fails. One direction alone would let a plugin be
# added with an unpinned version and still read green.
#
# A plugin declared with no `version` is a finding unless it is a Gradle core plugin. The list of
# core plugins is stated below (java, application, idea, eclipse): they ship inside the
# distribution, so their version IS the wrapper's version and repeating it is not possible. Every
# other plugin resolves to whatever the wrapper's default offers — the "unpinned" state Gradle
# itself warns about — and the table above cannot see it, because a versionless plugin has no
# version literal to compare.
#
# The readers below run against the tree AND against fixtures in a scratch directory: a different
# `distributionUrl`, a plugin version the table does not carry, a plugin the table requires and
# the file no longer declares, and a non-core plugin with no version at all. Without those cases a
# reader that matched nothing would print the same green line as one that read the files.
#
# Each file can be pointed somewhere else through `GRADLE_PINS_<LABEL>_FILE` (see `resolve_file`),
# which is how the fixture cases are proven and how a broken tree can be demonstrated without
# editing one. The default is always the file in this repository.
#
# No network, no Gradle, no Java, no Docker: this is a static consistency check on files.
#
# Run: ./.github/scripts/tests/gradle-pins.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
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

# ------------------------------------------
# The frozen table. Editing any line below is the reviewed decision: it is the only place in this
# tree that states which Gradle, Spring Boot, dependency-management, asciidoctor, spotless and
# spotbugs this repository builds with.
# ------------------------------------------

# label|repository-relative path — the five files this suite reads.
FROZEN_FILES='wrapper-api|life-control-api/gradle/wrapper/gradle-wrapper.properties
wrapper-gateway|api-gateway/gradle/wrapper/gradle-wrapper.properties
build-root|build.gradle
build-gateway|api-gateway/build.gradle
build-api|life-control-api/build.gradle'

# label|distributionUrl — the wrapper each module downloads. The two differ today, and both are
# frozen as they are: `gradle-9.9.0` for the API, `gradle-9.8.1` for the gateway.
FROZEN_WRAPPERS='wrapper-api|https://services.gradle.org/distributions/gradle-9.9.0-bin.zip
wrapper-gateway|https://services.gradle.org/distributions/gradle-9.8.1-bin.zip'

# label|plugin id|version — every versioned `id` literal, per file. The root and the gateway
# declare the same two; the API declares five, including `org.springframework.boot` at a different
# patch than the other two. That difference is a fact frozen here, not something to fix here.
FROZEN_PLUGINS='build-root|org.springframework.boot|4.1.1
build-root|io.spring.dependency-management|1.1.7
build-gateway|org.springframework.boot|4.1.1
build-gateway|io.spring.dependency-management|1.1.7
build-api|org.springframework.boot|4.1.2
build-api|io.spring.dependency-management|1.1.7
build-api|org.asciidoctor.jvm.convert|4.0.5
build-api|com.diffplug.spotless|8.11.0
build-api|com.github.spotbugs|6.5.13'

# The Gradle core plugins. They are shipped inside the distribution the wrapper downloads, so they
# cannot declare a version; a versionless `id` of any OTHER plugin is a finding.
CORE_PLUGINS="java application idea eclipse"

# ------------------------------------------
# Table lookups.
# ------------------------------------------

# frozen_path <label>: the repository-relative path frozen for a label, empty when unknown.
frozen_path() {
	printf '%s\n' "$FROZEN_FILES" | awk -F'|' -v l="$1" '$1 == l { print $2; exit }'
}

# frozen_wrapper_url <label>: the distributionUrl frozen for a wrapper label.
frozen_wrapper_url() {
	printf '%s\n' "$FROZEN_WRAPPERS" | awk -F'|' -v l="$1" '$1 == l { print $2; exit }'
}

# frozen_plugins <label>: the frozen `plugin|version` entries of one build file.
frozen_plugins() {
	printf '%s\n' "$FROZEN_PLUGINS" | awk -F'|' -v l="$1" '$1 == l { print $2 "|" $3 }'
}

# frozen_plugin_version <label> <plugin>: one frozen version, empty when the table has none.
frozen_plugin_version() {
	printf '%s\n' "$FROZEN_PLUGINS" | awk -F'|' -v l="$1" -v p="$2" '$1 == l && $2 == p { print $3; exit }'
}

# resolve_file <label> <repository-relative path>
# The file this suite reads, unless the environment points the label somewhere else. That override
# is what lets the fixture cases below prove the readers, and what a demonstration of a broken tree
# uses so that no file of this repository has to be edited to trigger it.
resolve_file() {
	local label="$1" relpath="$2" var override
	var="GRADLE_PINS_$(printf '%s' "$label" | tr '[:lower:]-' '[:upper:]_')_FILE"
	override="${!var:-}"
	printf '%s\n' "${override:-$REPO_ROOT/$relpath}"
}

# ------------------------------------------
# Readers. Each prints one line per finding and nothing when the file is clean, so the same code
# serves the tree (empty output is the pass) and the fixtures (empty output is the failure).
# ------------------------------------------

# wrapper_url <file>: the distributionUrl the wrapper would download. A properties file escapes
# the colon (`https\://…`), so the escaping is undone before comparing; a trailing CR is dropped so
# a checkout with CRLF endings is not read as drift.
wrapper_url() {
	sed -n 's/^distributionUrl=//p' "$1" 2>/dev/null | head -1 | tr -d '\r' | tr -d '\\'
}

# wrapper_findings <file> <expected-url>
wrapper_findings() {
	local file="$1" expected="$2" actual

	if [ ! -r "$file" ]; then
		printf 'is unreadable (%s)\n' "$file"
		return
	fi

	actual="$(wrapper_url "$file")"
	if [ -z "$actual" ]; then
		printf 'declares no distributionUrl, so ./gradlew runs whatever Gradle the machine has\n'
		return
	fi
	if [ "$actual" != "$expected" ]; then
		printf 'declares distributionUrl %s, but the frozen wrapper is %s\n' "$actual" "$expected"
	fi
}

# plugin_lines <file>: `<line number>TAB<declaration>` for every `id`/`type` entry inside the
# file's `plugins { }` block(s), comments removed. Only that block is read: a `plugins` block is
# where a version can be declared, and an `id` inside a `buildscript { }` block is a different
# mechanism this suite does not claim to check.
plugin_lines() {
	awk '
		BEGIN { in_plugins = 0 }
		/^[[:space:]]*plugins[[:space:]]*[{]/ { in_plugins = 1; next }
		in_plugins && /^[[:space:]]*[}]/ { in_plugins = 0; next }
		in_plugins {
			line = $0
			sub(/\/\/.*$/, "", line)
			sub(/^[[:space:]]*/, "", line)
			sub(/[[:space:]]*$/, "", line)
			if (line ~ /^(id|type)[[:space:](]/) print NR "\t" line
		}
	' "$1" 2>/dev/null
}

# plugin_id <declaration>: the plugin id, empty when the declaration is a `type` or unparseable.
plugin_id() {
	sed -nE "s/^id[[:space:]]*\(?[[:space:]]*['\"]([^'\"]+)['\"].*/\1/p" <<<"$1"
}

# plugin_version <declaration>: the declared version, empty when the plugin declares none.
plugin_version() {
	sed -nE "s/.*version[[:space:]]*\(?[[:space:]]*['\"]([^'\"]+)['\"].*/\1/p" <<<"$1"
}

# versioned_pairs <file>: `plugin|version` for every plugin that declares a version.
versioned_pairs() {
	local lineno entry id version
	while IFS=$'\t' read -r lineno entry; do
		[ -n "${entry:-}" ] || continue
		id="$(plugin_id "$entry")"
		[ -n "$id" ] || continue
		version="$(plugin_version "$entry")"
		[ -n "$version" ] || continue
		printf '%s|%s\n' "$id" "$version"
	done <<<"$(plugin_lines "$1")"
}

# versionless_entries <file>: `<line number>TAB<plugin>` for every plugin that declares no version.
versionless_entries() {
	local lineno entry id
	while IFS=$'\t' read -r lineno entry; do
		[ -n "${entry:-}" ] || continue
		id="$(plugin_id "$entry")"
		[ -n "$id" ] || continue
		[ -z "$(plugin_version "$entry")" ] || continue
		printf '%s\t%s\n' "$lineno" "$id"
	done <<<"$(plugin_lines "$1")"
}

# is_core_plugin <id>: true when the plugin ships inside the distribution the wrapper downloads.
is_core_plugin() {
	case " $CORE_PLUGINS " in
	*" $1 "*) return 0 ;;
	esac
	return 1
}

# build_findings <file> <expected-pairs>: drift in both directions, plus every versionless plugin
# that is not a core one.
build_findings() {
	local file="$1" expected="$2" actual pair lineno id

	if [ ! -r "$file" ]; then
		printf 'is unreadable (%s)\n' "$file"
		return
	fi

	actual="$(versioned_pairs "$file")"

	# A version on the tree that the table does not carry.
	while IFS= read -r pair; do
		[ -n "${pair:-}" ] || continue
		if ! printf '%s\n' "$expected" | grep -Fxq -- "$pair"; then
			printf 'declares %s at version %s, which the frozen table does not carry\n' "${pair%%|*}" "${pair#*|}"
		fi
	done <<<"$actual"

	# A table entry the tree no longer declares.
	while IFS= read -r pair; do
		[ -n "${pair:-}" ] || continue
		if ! printf '%s\n' "$actual" | grep -Fxq -- "$pair"; then
			printf 'no longer declares %s at version %s, which the frozen table still requires\n' "${pair%%|*}" "${pair#*|}"
		fi
	done <<<"$expected"

	# A third-party plugin with no version resolves to whatever the wrapper's default offers.
	while IFS=$'\t' read -r lineno id; do
		[ -n "${id:-}" ] || continue
		is_core_plugin "$id" && continue
		printf 'line %s declares %s with no version, so it resolves to whatever the wrapper default offers\n' "$lineno" "$id"
	done <<<"$(versionless_entries "$file")"
}

# ------------------------------------------
# 1. The readers reject what they exist to reject.
# ------------------------------------------
mkdir -p "$TMP/fixtures"

cat >"$TMP/fixtures/clean-wrapper.properties" <<'PROPERTIES'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPERTIES

cat >"$TMP/fixtures/drifted-wrapper.properties" <<'PROPERTIES'
distributionBase=GRADLE_USER_HOME
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.1-bin.zip
PROPERTIES

cat >"$TMP/fixtures/no-url-wrapper.properties" <<'PROPERTIES'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
networkTimeout=10000
PROPERTIES

cat >"$TMP/fixtures/clean.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.1'
}
GRADLE

cat >"$TMP/fixtures/unknown-version.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.3'
}
GRADLE

cat >"$TMP/fixtures/versionless.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.1'
	id 'com.diffplug.spotless'
}
GRADLE

cat >"$TMP/fixtures/missing-version.build.gradle" <<'GRADLE'
plugins {
	id 'java'
}
GRADLE

# The expectations the fixture build files are read against. One plugin is enough for the clean
# case: a reader that matches nothing is caught by the versionless and missing cases below.
FIXTURE_EXPECTED='org.springframework.boot|4.1.1'

check_wrapper_clean() {
	local fixture="$1" expected="$2" result
	result="$(wrapper_findings "$fixture" "$expected")"
	if [ -z "$result" ]; then
		pass "fixture $(basename "$fixture") is accepted"
	else
		fail "fixture $(basename "$fixture") should be clean but was rejected: $result"
	fi
}

check_wrapper_rejected() {
	local fixture="$1" expected="$2" result
	result="$(wrapper_findings "$fixture" "$expected")"
	if [ -n "$result" ]; then
		pass "fixture $(basename "$fixture") is rejected ($(printf '%s' "$result" | head -1))"
	else
		fail "fixture $(basename "$fixture") was accepted, so the wrapper reader is not reading the file"
	fi
}

check_build_clean() {
	local fixture="$1" expected="$2" result
	result="$(build_findings "$fixture" "$expected")"
	if [ -z "$result" ]; then
		pass "fixture $(basename "$fixture") is accepted"
	else
		fail "fixture $(basename "$fixture") should be clean but was rejected: $result"
	fi
}

# check_build_rejected <fixture> <expected> [reason the verdict must name]
# The optional reason is asserted because "rejected for some other entry" and "rejected for the
# reason this case exists" read the same otherwise: the versionless case would pass on the
# missing-version finding alone while the versionless check stayed dead.
check_build_rejected() {
	local fixture="$1" expected="$2" needle="${3:-}" result
	result="$(build_findings "$fixture" "$expected")"
	if [ -z "$result" ]; then
		fail "fixture $(basename "$fixture") was accepted, so the build reader is not reading the file"
		return
	fi
	if [ -n "$needle" ] && ! printf '%s' "$result" | grep -Fq -- "$needle"; then
		fail "fixture $(basename "$fixture") was rejected, but not for $needle: $result"
		return
	fi
	pass "fixture $(basename "$fixture") is rejected ($(printf '%s' "$result" | head -1))"
}

check_wrapper_clean "$TMP/fixtures/clean-wrapper.properties" "https://services.gradle.org/distributions/gradle-9.9.0-bin.zip"
check_wrapper_rejected "$TMP/fixtures/drifted-wrapper.properties" "https://services.gradle.org/distributions/gradle-9.9.0-bin.zip"
check_wrapper_rejected "$TMP/fixtures/no-url-wrapper.properties" "https://services.gradle.org/distributions/gradle-9.9.0-bin.zip"

check_build_clean "$TMP/fixtures/clean.build.gradle" "$FIXTURE_EXPECTED"
check_build_rejected "$TMP/fixtures/unknown-version.build.gradle" "$FIXTURE_EXPECTED" "which the frozen table does not carry"
check_build_rejected "$TMP/fixtures/versionless.build.gradle" "$FIXTURE_EXPECTED" "with no version"
check_build_rejected "$TMP/fixtures/missing-version.build.gradle" "$FIXTURE_EXPECTED" "which the frozen table still requires"

# ------------------------------------------
# 2. Both wrappers on the tree download the frozen distribution.
# ------------------------------------------
echo "== the wrapper each module downloads is frozen =="
MEASURED_WRAPPER_API="$(wrapper_url "$(resolve_file wrapper-api "$(frozen_path wrapper-api)")")"
MEASURED_WRAPPER_GATEWAY="$(wrapper_url "$(resolve_file wrapper-gateway "$(frozen_path wrapper-gateway)")")"

for label in wrapper-api wrapper-gateway; do
	expected="$(frozen_wrapper_url "$label")"
	file="$(resolve_file "$label" "$(frozen_path "$label")")"
	result="$(wrapper_findings "$file" "$expected")"
	if [ -z "$result" ]; then
		pass "$label downloads $expected"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi
done

# ------------------------------------------
# 3. Every plugin version on the tree is in the frozen table, and the table is not carrying
#    entries the tree dropped.
# ------------------------------------------
echo "== every plugin version in the three build files is frozen =="
VERSIONED_ON_TREE=0
MEASURED_BOOT_GATEWAY="$(frozen_plugin_version build-gateway org.springframework.boot)"
MEASURED_BOOT_API="$(frozen_plugin_version build-api org.springframework.boot)"

for label in build-root build-gateway build-api; do
	file="$(resolve_file "$label" "$(frozen_path "$label")")"
	expected="$(frozen_plugins "$label")"
	result="$(build_findings "$file" "$expected")"
	if [ -z "$result" ]; then
		pass "$label declares exactly the plugin versions the frozen table carries"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi

	VERSIONED_ON_TREE=$((VERSIONED_ON_TREE + $(versioned_pairs "$file" | sed '/^$/d' | wc -l | tr -d ' ')))

	case "$label" in
	build-gateway) MEASURED_BOOT_GATEWAY="$(versioned_pairs "$file" | sed -n 's/^org\.springframework\.boot|//p')" ;;
	build-api) MEASURED_BOOT_API="$(versioned_pairs "$file" | sed -n 's/^org\.springframework\.boot|//p')" ;;
	esac
done

# A reader that parsed no version at all, or a table edited to carry a different count, would
# report green over a set that was never compared: the count is the cheap proof that both sides
# were read.
FROZEN_TOTAL="$(printf '%s\n' "$FROZEN_PLUGINS" | sed '/^$/d' | wc -l | tr -d ' ')"
if [ "$VERSIONED_ON_TREE" -eq 0 ]; then
	fail "no versioned plugin was read from the three build files, so the plugin reader is not reading them"
elif [ "$VERSIONED_ON_TREE" -eq "$FROZEN_TOTAL" ]; then
	pass "the three build files declare $VERSIONED_ON_TREE versioned plugin(s), the same number the frozen table carries"
else
	fail "the three build files declare $VERSIONED_ON_TREE versioned plugin(s) but the frozen table carries $FROZEN_TOTAL"
fi

# Stated, not asserted: the two modules disagree on both axes, this suite freezes each value as it
# is, and closing the gap is a different unit's decision. Printing it keeps the disagreement
# visible to whoever reads a green run, which is the whole reason it went unnoticed.
printf 'NOTE: the gateway declares Spring Boot %s and wrapper %s, the API declares Spring Boot %s and wrapper %s: this suite freezes both as they are, and aligning them is issue #894'"'"'s job\n' \
	"$MEASURED_BOOT_GATEWAY" "${MEASURED_WRAPPER_GATEWAY##*/}" "$MEASURED_BOOT_API" "${MEASURED_WRAPPER_API##*/}"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'gradle-pins: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
