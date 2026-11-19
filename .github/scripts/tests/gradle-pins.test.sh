#!/bin/bash
# ============================================
# LifeControl - Gradle pin tests
# ============================================
# Answers the Gradle half of issue #896's S5, which no suite asked before: does the toolchain this
# repository builds with drift without a commit here?
#
# The failure it exists for is silent, not loud. A `distributionUrl` decides which Gradle
# downloads for a module, and a catalog `[plugins]` entry decides which Spring Boot, spotless,
# spotbugs and asciidoctor run. Both are versions written in the tree, read by `./gradlew` and by
# nothing else: a patch bump in either is a one-line diff with no failing test, no reviewer prompt
# and no record. That is exactly how this tree ended up with two modules on different Spring Boot
# patches (4.1.1 vs 4.1.2) and different wrappers (gradle-9.8.1 vs gradle-9.9.0) in the first
# place — issue #894 aligned both, and this suite is what keeps them aligned.
#
# What is frozen, and how
#   * the `distributionUrl` of BOTH wrappers, exactly as it is today, and the two files are also
#     asserted to state the SAME line, so one module cannot quietly move off the other;
#   * every plugin version of the shared catalog `gradle/libs.versions.toml` — the single file both
#     modules resolve from since #894. There is no root build: it is gone, and nothing here reads
#     it.
# The check runs in BOTH directions: a version in the tree that the table does not carry fails, and
# a table entry the tree no longer declares fails. One direction alone would let a plugin be added
# with an unpinned version and still read green.
#
# A plugin version inside a `plugins { }` block is a finding, whatever it says. Since #894 a module
# declares `alias(libs.plugins.<alias>)` and the version lives in the catalog, so a literal in a
# build file is the second place a version was written — the shape this repository is trying to
# stop having. A plugin declared with NO version is a finding unless it is a Gradle core plugin:
# the four below ship inside the distribution, so their version IS the wrapper's version. Every
# other versionless plugin resolves to whatever the wrapper's default offers, which the table
# cannot see.
#
# The readers below run against the tree AND against fixtures in a scratch directory: a different
# `distributionUrl`, a catalog plugin at a version the table does not carry, a catalog plugin the
# table requires and the file no longer declares, a catalog entry whose `version.ref` resolves to
# nothing, a plugin version written back into a build file, and a non-core plugin with no version
# at all. Without those cases a reader that matched nothing would print the same green line as one
# that read the files.
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

# label|repository-relative path — the five files this suite reads. The root `build.gradle` is not
# here any more: #894 deleted it, so a suite that read it would be claiming a build that does not
# exist.
FROZEN_FILES='wrapper-api|life-control-api/gradle/wrapper/gradle-wrapper.properties
wrapper-gateway|api-gateway/gradle/wrapper/gradle-wrapper.properties
catalog|gradle/libs.versions.toml
build-gateway|api-gateway/build.gradle
build-api|life-control-api/build.gradle'

# label|distributionUrl — the wrapper each module downloads. Both modules state the same line since
# #894; the case at the end of this file is what makes that a rule instead of an observation.
FROZEN_WRAPPERS='wrapper-api|https://services.gradle.org/distributions/gradle-9.9.1-bin.zip
wrapper-gateway|https://services.gradle.org/distributions/gradle-9.9.1-bin.zip'

# plugin id|version — every `[plugins]` entry of the shared catalog, resolved through its
# `version.ref`. One table, not one per module: both builds read the same file, which is the point
# of the catalog.
FROZEN_PLUGINS='org.springframework.boot|4.1.2
io.spring.dependency-management|1.1.7
org.asciidoctor.jvm.convert|4.0.5
com.diffplug.spotless|8.11.0
com.github.spotbugs|6.5.13'

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

# catalog_versions <catalog>: `<key>TAB<version>` for every entry of the `[versions]` table.
catalog_versions() {
	awk '
		BEGIN { in_versions = 0 }
		/^[[:space:]]*\[/ { in_versions = ($0 ~ /^[[:space:]]*\[versions\][[:space:]]*$/); next }
		!in_versions { next }
		/^[[:space:]]*#/ { next }
		/^[[:space:]]*$/ { next }
		{
			line = $0
			sub(/^[[:space:]]*/, "", line)
			key = line
			sub(/[[:space:]]*=.*$/, "", key)
			value = line
			sub(/^[^=]*=[[:space:]]*"/, "", value)
			sub(/".*$/, "", value)
			printf "%s\t%s\n", key, value
		}
	' "$1" 2>/dev/null
}

# catalog_version <catalog> <key>: the version a `[versions]` key resolves to, empty when absent.
catalog_version() {
	catalog_versions "$1" | awk -F'\t' -v k="$2" '$1 == k { print $2; exit }'
}

# catalog_plugin_entries <catalog>: `<line number>TAB<declaration>` for every entry of the
# `[plugins]` table. The count is the denominator the pair reader is checked against.
catalog_plugin_entries() {
	awk '
		BEGIN { in_plugins = 0 }
		/^[[:space:]]*\[/ { in_plugins = ($0 ~ /^[[:space:]]*\[plugins\][[:space:]]*$/); next }
		!in_plugins { next }
		/^[[:space:]]*#/ { next }
		/^[[:space:]]*$/ { next }
		{ print NR "\t" $0 }
	' "$1" 2>/dev/null
}

# catalog_plugin_findings <file> <expected-pairs>: drift in both directions, plus every entry whose
# version cannot be resolved or is missing.
catalog_plugin_findings() {
	local file="$1" expected="$2" actual="" entry id ref version lineno

	if [ ! -r "$file" ]; then
		printf 'is unreadable (%s)\n' "$file"
		return
	fi

	while IFS=$'\t' read -r lineno entry; do
		[ -n "${entry:-}" ] || continue
		id="$(sed -nE 's/.*id[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' <<<"$entry")"
		[ -n "$id" ] || continue
		version="$(sed -nE 's/.*version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' <<<"$entry")"
		if [ -z "$version" ]; then
			ref="$(sed -nE 's/.*version\.ref[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' <<<"$entry")"
			if [ -z "$ref" ]; then
				printf 'line %s declares %s with no version and no version.ref\n' "$lineno" "$id"
				continue
			fi
			version="$(catalog_version "$file" "$ref")"
			if [ -z "$version" ]; then
				printf 'line %s declares %s with version.ref "%s", which the [versions] table does not define\n' \
					"$lineno" "$id" "$ref"
				continue
			fi
		fi
		actual="${actual}${id}|${version}"$'\n'
	done <<<"$(catalog_plugin_entries "$file")"

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
}

# plugin_lines <file>: `<line number>TAB<declaration>` for every `id`/`type` entry inside the
# file's `plugins { }` block(s), comments removed. Only that block is read: a `plugins` block is
# where a version can be declared, and an `id` inside a `buildscript { }` block is a different
# mechanism this suite does not claim to check. An `alias(libs.plugins.…)` line does not start with
# `id`, so it is invisible here by design — its version is the catalog's, and the catalog is what
# the table above freezes.
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
# that is not a core one. Called with an empty expected set on the real build files, where every
# version belongs to the catalog.
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
			printf 'declares %s at version %s inline, and a plugin version belongs to the catalog\n' "${pair%%|*}" "${pair#*|}"
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

# declared_plugin_count <file>: how many `id`/`type` declarations the `plugins { }` block carries.
# A reader that parsed an empty block would report every build file clean.
declared_plugin_count() {
	plugin_lines "$1" | sed '/^$/d' | wc -l | tr -d ' '
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

cat >"$TMP/fixtures/clean.catalog.toml" <<'TOML'
[versions]
spring-boot = "4.1.2"
spotless = "8.11.0"

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
TOML

# One version bumped: the table does not carry it.
cat >"$TMP/fixtures/unknown-version.catalog.toml" <<'TOML'
[versions]
spring-boot = "4.1.3"
spotless = "8.11.0"

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
TOML

# One plugin dropped: the table still requires it.
cat >"$TMP/fixtures/missing-version.catalog.toml" <<'TOML'
[versions]
spotless = "8.11.0"

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
TOML

# A `version.ref` that no `[versions]` key defines: the entry looks versioned and resolves to
# nothing, which is neither "drift" nor "clean" unless the reader says so out loud.
cat >"$TMP/fixtures/unresolved-ref.catalog.toml" <<'TOML'
[versions]
spotless = "8.11.0"

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "a-key-nobody-declares" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
TOML

cat >"$TMP/fixtures/clean.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	alias(libs.plugins.spring.boot)
}
GRADLE

cat >"$TMP/fixtures/inline-version.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.2'
}
GRADLE

cat >"$TMP/fixtures/versionless.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	alias(libs.plugins.spring.boot)
	id 'com.diffplug.spotless'
}
GRADLE

cat >"$TMP/fixtures/empty.build.gradle" <<'GRADLE'
plugins {
	id 'java'
}
GRADLE

# The expectations the fixture files are read against. Two entries, so a fixture that drops one is
# rejected for the missing half and not for a table that was empty.
FIXTURE_EXPECTED='org.springframework.boot|4.1.2
com.diffplug.spotless|8.11.0'

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

check_catalog_clean() {
	local fixture="$1" expected="$2" result
	result="$(catalog_plugin_findings "$fixture" "$expected")"
	if [ -z "$result" ]; then
		pass "fixture $(basename "$fixture") is accepted"
	else
		fail "fixture $(basename "$fixture") should be clean but was rejected: $result"
	fi
}

check_catalog_rejected() {
	local fixture="$1" expected="$2" needle="${3:-}" result
	result="$(catalog_plugin_findings "$fixture" "$expected")"
	if [ -z "$result" ]; then
		fail "fixture $(basename "$fixture") was accepted, so the catalog reader is not reading the file"
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

check_catalog_clean "$TMP/fixtures/clean.catalog.toml" "$FIXTURE_EXPECTED"
check_catalog_rejected "$TMP/fixtures/unknown-version.catalog.toml" "$FIXTURE_EXPECTED" "which the frozen table does not carry"
check_catalog_rejected "$TMP/fixtures/missing-version.catalog.toml" "$FIXTURE_EXPECTED" "org.springframework.boot"
check_catalog_rejected "$TMP/fixtures/unresolved-ref.catalog.toml" "$FIXTURE_EXPECTED" "which the [versions] table does not define"

check_build_clean "$TMP/fixtures/clean.build.gradle" ""
check_build_rejected "$TMP/fixtures/inline-version.build.gradle" "" "belongs to the catalog"
check_build_rejected "$TMP/fixtures/versionless.build.gradle" "" "with no version"
check_build_clean "$TMP/fixtures/empty.build.gradle" ""

# The count of declarations is what a clean verdict cannot answer: a reader that parsed no plugin
# at all reports the same empty result as one that read a correct block.
if [ "$(declared_plugin_count "$TMP/fixtures/clean.build.gradle")" -eq 1 ]; then
	pass "the plugin counter reads the core \`id\` declaration of the clean fixture"
else
	fail "the plugin counter reads $(declared_plugin_count "$TMP/fixtures/clean.build.gradle") declaration(s) of the clean fixture instead of 1"
fi

# ------------------------------------------
# 2. Both wrappers on the tree download the frozen distribution — and the same one.
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

# S3 of #894, stated as its own case: `grep -h distributionUrl */gradle/wrapper/…` prints one line,
# twice. Two wrappers frozen to the same value above would still pass if the table were the only
# thing read, so the two measured lines are compared to each other here.
if [ -n "$MEASURED_WRAPPER_API" ] && [ "$MEASURED_WRAPPER_API" = "$MEASURED_WRAPPER_GATEWAY" ]; then
	pass "both modules print the same distributionUrl ($MEASURED_WRAPPER_API)"
else
	fail "the two modules print different distributionUrls (api: ${MEASURED_WRAPPER_API:-none}, gateway: ${MEASURED_WRAPPER_GATEWAY:-none})"
fi

# ------------------------------------------
# 3. Every plugin version of the shared catalog is in the frozen table, and the table is not
#    carrying entries the catalog dropped.
# ------------------------------------------
echo "== every plugin version of the shared catalog is frozen =="
CATALOG_LABEL=catalog
CATALOG_FILE="$(resolve_file "$CATALOG_LABEL" "$(frozen_path "$CATALOG_LABEL")")"
CATALOG_FINDINGS="$(catalog_plugin_findings "$CATALOG_FILE" "$FROZEN_PLUGINS")"
if [ -z "$CATALOG_FINDINGS" ]; then
	pass "$CATALOG_LABEL declares exactly the plugin versions the frozen table carries"
else
	while IFS= read -r line; do
		[ -n "$line" ] || continue
		fail "$CATALOG_LABEL $line"
	done <<<"$CATALOG_FINDINGS"
fi

# A catalog reader that resolved nothing, or a table edited to carry a different count, would
# report green over a set that was never compared: the counts are the cheap proof that both sides
# were read.
CATALOG_ENTRIES="$(catalog_plugin_entries "$CATALOG_FILE" | sed '/^$/d' | wc -l | tr -d ' ')"
FROZEN_TOTAL="$(printf '%s\n' "$FROZEN_PLUGINS" | sed '/^$/d' | wc -l | tr -d ' ')"
if [ "${CATALOG_ENTRIES:-0}" -eq 0 ]; then
	fail "no [plugins] entry was read from the shared catalog, so the plugin reader is not reading it"
elif [ "$CATALOG_ENTRIES" -eq "$FROZEN_TOTAL" ]; then
	pass "the shared catalog declares $CATALOG_ENTRIES plugin(s), the same number the frozen table carries"
else
	fail "the shared catalog declares $CATALOG_ENTRIES plugin(s) but the frozen table carries $FROZEN_TOTAL"
fi

# ------------------------------------------
# 4. Neither module build file writes a plugin version of its own.
# ------------------------------------------
echo "== no module build file declares a plugin version inline =="
for label in build-gateway build-api; do
	file="$(resolve_file "$label" "$(frozen_path "$label")")"
	result="$(build_findings "$file" "")"
	if [ -z "$result" ]; then
		pass "$label declares no plugin version of its own and no unpinned third-party plugin"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi

	# An empty plugins block would pass the check above for the wrong reason.
	declared="$(declared_plugin_count "$file")"
	if [ "${declared:-0}" -ge 1 ]; then
		pass "$label declares $declared plugin(s), so the plugins block was read"
	else
		fail "$label declares no plugin the reader can see, so the plugins block was never read"
	fi
done

printf 'NOTE: the wrapper is frozen at %s for both modules and every plugin version is frozen in %s; a bump is a one-line diff in one of those two places, and this suite is what makes it a reviewed one\n' \
	"$MEASURED_WRAPPER_API" "$(frozen_path "$CATALOG_LABEL")"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'gradle-pins: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
