#!/bin/bash
# ============================================
# LifeControl - Gradle version catalog tests
# ============================================
# Answers issue #894's S1: is `gradle/libs.versions.toml` the only place a managed version is
# written, and is it reachable from both modules?
#
# The failure it exists for is quiet. A version catalog is shared by DECLARATION — a catalog is
# visible to the projects of one build, and a composite shares no configuration at all — so each
# module's `settings.gradle` names the file explicitly. Two things then drift with no other
# symptom: a module that stops declaring the catalog loses every accessor at once, and a version
# typed back into a `plugins { }` block or a `group:artifact:version` string re-introduces the
# second place a version lives, which is exactly how this tree ended up with Spring Boot 4.1.1 in
# one build and 4.1.2 in another.
#
# What is asserted
#   * the catalog exists at the repository root and parses: the number of `[libraries]` entries and
#     the number of coordinates read from them are compared, so a library written in a shape the
#     reader does not understand is a finding instead of an invisible zero;
#   * neither `build.gradle` carries a `group:artifact:version` string for a coordinate the
#     catalog manages (the version belongs to the catalog);
#   * neither `build.gradle` declares a version inside a `plugins { }` block — a plugin version is
#     a catalog `[plugins]` entry reached through `alias(libs.plugins.<alias>)`;
#   * no `[versions]` value of the catalog is written back into either `build.gradle` as a quoted
#     literal, which is the shape a tooling version (`palantir-java-format`, the spotbugs tool)
#     would take if it escaped the catalog;
#   * both `settings.gradle` files declare the catalog with `from(files("../gradle/libs.versions.toml"))`,
#     which is the only mechanism Gradle offers for two independent builds to share one file.
#
# Every reader runs against fixtures in a scratch directory too: a clean build file, one carrying a
# literal coordinate, one carrying a plugin version, one whose only plugin is a bare `id`, a
# settings file with and without the declaration, and a catalog. Without those cases a reader that
# matched nothing would print the same green line as one that read the files.
#
# Each file can be pointed somewhere else through `VERSION_CATALOG_<LABEL>_FILE` (see
# `resolve_file`), which is how the fixture cases are proven and how a broken tree can be
# demonstrated without editing one. The default is always the file in this repository.
#
# No network, no Gradle, no Java, no Docker: this is a static consistency check on files.
#
# Run: ./.github/scripts/tests/version-catalog.test.sh
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
# The tree this suite reads. Editing a line below is the reviewed decision.
# ------------------------------------------

# label|repository-relative path — the five files this suite reads.
CATALOG_FILES='catalog|gradle/libs.versions.toml
build-gateway|api-gateway/build.gradle
build-api|life-control-api/build.gradle
settings-gateway|api-gateway/settings.gradle
settings-api|life-control-api/settings.gradle'

# The declaration both settings files must carry. Gradle reads no catalog from another build's
# settings, so this string is the whole sharing mechanism; the relative path is one level up
# because each module is its own build root.
CATALOG_DECLARATION='../gradle/libs.versions.toml'

# One coordinate the catalog is known to manage. Asserted to be READ from the catalog, not to
# exist in a build file: without it a reader that returned nothing would pass every case below.
CATALOG_SENTINEL='com.github.loki4j:loki-logback-appender'

# ------------------------------------------
# Table lookups.
# ------------------------------------------

# catalog_path <label>: the repository-relative path of a label, empty when unknown.
catalog_path() {
	printf '%s\n' "$CATALOG_FILES" | awk -F'|' -v l="$1" '$1 == l { print $2; exit }'
}

# resolve_file <label> <repository-relative path>
# The file this suite reads, unless the environment points the label somewhere else.
resolve_file() {
	local label="$1" relpath="$2" var override
	var="VERSION_CATALOG_$(printf '%s' "$label" | tr '[:lower:]-' '[:upper:]_')_FILE"
	override="${!var:-}"
	printf '%s\n' "${override:-$REPO_ROOT/$relpath}"
}

# ------------------------------------------
# Readers. Each prints one line per finding and nothing when the file is clean, so the same code
# serves the tree (empty output is the pass) and the fixtures (empty output is the failure).
# ------------------------------------------

# catalog_library_entries <catalog>: `<line number>TAB<declaration>` for every non-comment,
# non-blank line inside the `[libraries]` table. The count is the denominator the coordinate
# reader below is checked against.
catalog_library_entries() {
	awk '
		BEGIN { in_libraries = 0 }
		/^[[:space:]]*\[/ { in_libraries = ($0 ~ /^[[:space:]]*\[libraries\][[:space:]]*$/); next }
		!in_libraries { next }
		/^[[:space:]]*#/ { next }
		/^[[:space:]]*$/ { next }
		{ print NR "\t" $0 }
	' "$1" 2>/dev/null
}

# catalog_coordinates <catalog>: `group:artifact` for every `[libraries]` entry written with the
# `module = "group:artifact"` form, which is the form this catalog uses. An entry in any other
# shape yields nothing and is caught by comparing the two counts.
catalog_coordinates() {
	awk '
		BEGIN { in_libraries = 0 }
		/^[[:space:]]*\[/ { in_libraries = ($0 ~ /^[[:space:]]*\[libraries\][[:space:]]*$/); next }
		!in_libraries { next }
		/^[[:space:]]*#/ { next }
		{
			if (match($0, /module[[:space:]]*=[[:space:]]*"[^"]+"/)) {
				value = substr($0, RSTART, RLENGTH)
				sub(/^module[[:space:]]*=[[:space:]]*"/, "", value)
				sub(/"$/, "", value)
				print value
			}
		}
	' "$1" 2>/dev/null
}

# catalog_version_values <catalog>: `<key>TAB<version>` for every entry of the `[versions]` table.
catalog_version_values() {
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

# catalog_version_count <catalog>: how many `[versions]` entries the table declares. The denominator
# the literal reader below is checked against.
catalog_version_count() {
	catalog_version_values "$1" | sed '/^$/d' | wc -l | tr -d ' '
}

# version_literal_findings <build.gradle> <catalog>: one line per `[versions]` VALUE the build file
# writes as a quoted literal. A tooling version is not a coordinate and not a plugin, so it reaches
# a build file as a bare string (`palantirJavaFormat('2.98.0')`); without this reader that string
# could reappear beside the catalog accessor and nothing would notice.
version_literal_findings() {
	local build_file="$1" catalog="$2" key value match lineno
	if [ ! -r "$build_file" ]; then
		printf 'is unreadable (%s)\n' "$build_file"
		return
	fi
	while IFS=$'\t' read -r key value; do
		[ -n "${value:-}" ] || continue
		while IFS= read -r match; do
			[ -n "${match:-}" ] || continue
			lineno="${match%%:*}"
			printf 'line %s writes %s as a literal ("%s"), which belongs to the catalog\n' "$lineno" "$value" "$key"
		done <<<"$(grep -nF -e "'$value'" -e "\"$value\"" "$build_file" 2>/dev/null)"
	done <<<"$(catalog_version_values "$catalog")"
}

# catalog_count_verdict <catalog>: empty when every `[libraries]` entry yielded a coordinate,
# otherwise the finding. A catalog with no entries at all is a finding too: a reader compared
# against zero entries proves nothing.
catalog_count_verdict() {
	local file="$1" entries coordinates entry_count coordinate_count
	if [ ! -r "$file" ]; then
		printf 'is unreadable (%s)\n' "$file"
		return
	fi
	entries="$(catalog_library_entries "$file")"
	coordinates="$(catalog_coordinates "$file")"
	entry_count="$(printf '%s\n' "$entries" | sed '/^$/d' | wc -l | tr -d ' ')"
	coordinate_count="$(printf '%s\n' "$coordinates" | sed '/^$/d' | wc -l | tr -d ' ')"
	if [ "$entry_count" -eq 0 ]; then
		printf 'declares no [libraries] entry, so nothing about it can be checked\n'
		return
	fi
	if [ "$entry_count" -ne "$coordinate_count" ]; then
		printf 'declares %s [libraries] entr(ies) but only %s carry a `module = "group:artifact"` value\n' \
			"$entry_count" "$coordinate_count"
	fi
}

# literal_coordinates <build.gradle> <catalog>: one line per `group:artifact:version` string the
# build file writes for a coordinate the catalog manages.
literal_coordinates() {
	local build_file="$1" catalog="$2" coordinate lineno
	if [ ! -r "$build_file" ]; then
		printf 'is unreadable (%s)\n' "$build_file"
		return
	fi
	while IFS= read -r coordinate; do
		[ -n "${coordinate:-}" ] || continue
		while IFS= read -r match; do
			[ -n "${match:-}" ] || continue
			lineno="${match%%:*}"
			printf 'line %s writes %s with a version literal, which belongs to the catalog\n' "$lineno" "$coordinate"
		done <<<"$(grep -nF -- "$coordinate:" "$build_file" 2>/dev/null)"
	done <<<"$(catalog_coordinates "$catalog")"
}

# plugins_block_lines <build.gradle>: `<line number>TAB<declaration>` for every line inside a
# `plugins { }` block, comments stripped. Only that block is read: a version anywhere else is a
# different question this suite does not claim to answer.
plugins_block_lines() {
	awk '
		BEGIN { in_plugins = 0 }
		/^[[:space:]]*plugins[[:space:]]*[{]/ { in_plugins = 1; next }
		in_plugins && /^[[:space:]]*[}]/ { in_plugins = 0; next }
		!in_plugins { next }
		{
			line = $0
			sub(/\/\/.*$/, "", line)
			sub(/^[[:space:]]*/, "", line)
			sub(/[[:space:]]*$/, "", line)
			if (line != "") print NR "\t" line
		}
	' "$1" 2>/dev/null
}

# plugin_version_literals <build.gradle>: one line per version declared inside a `plugins { }`
# block. A plugin version is a catalog `[plugins]` entry (`alias(libs.plugins.<alias>)`), never a
# literal here.
plugin_version_literals() {
	local build_file="$1" lineno line
	if [ ! -r "$build_file" ]; then
		printf 'is unreadable (%s)\n' "$build_file"
		return
	fi
	while IFS=$'\t' read -r lineno line; do
		[ -n "${line:-}" ] || continue
		case "$line" in
		*version*)
			if printf '%s\n' "$line" | grep -Eq '(^|[[:space:](])version[[:space:](]'; then
				printf 'line %s declares a plugin version inline (%s), which belongs to the catalog\n' "$lineno" "$line"
			fi
			;;
		esac
	done <<<"$(plugins_block_lines "$build_file")"
}

# alias_count <build.gradle>: how many plugins the build file reaches through the catalog.
alias_count() {
	local build_file="$1"
	[ -r "$build_file" ] || {
		printf '0\n'
		return
	}
	plugins_block_lines "$build_file" | grep -c 'alias(libs\.plugins\.' | tr -d ' '
}

# libs_reference_count <build.gradle>: how many catalog accessors the file uses at all.
libs_reference_count() {
	local build_file="$1"
	[ -r "$build_file" ] || {
		printf '0\n'
		return
	}
	grep -c 'libs\.' "$build_file" 2>/dev/null | tr -d ' '
}

# catalog_declaration_findings <settings.gradle>: empty when the settings file declares the shared
# catalog, otherwise the finding. Both halves are required: `versionCatalogs` alone can declare a
# catalog from anywhere, and the `from(files(...))` line alone declares nothing.
catalog_declaration_findings() {
	local file="$1" flat
	if [ ! -r "$file" ]; then
		printf 'is unreadable (%s)\n' "$file"
		return
	fi
	flat="$(tr '\n' ' ' <"$file")"
	if ! printf '%s' "$flat" | grep -Eq 'dependencyResolutionManagement[[:space:]]*\{'; then
		printf 'declares no dependencyResolutionManagement block, so no version catalog is shared\n'
		return
	fi
	if ! printf '%s' "$flat" | grep -Eq 'versionCatalogs[[:space:]]*\{'; then
		printf 'declares no versionCatalogs block, so no version catalog is shared\n'
		return
	fi
	if ! printf '%s' "$flat" | grep -Fq -- "from(files(\"$CATALOG_DECLARATION\"))"; then
		printf 'does not declare the shared catalog with from(files("%s"))\n' "$CATALOG_DECLARATION"
	fi
}

# ------------------------------------------
# 1. The readers reject what they exist to reject.
# ------------------------------------------
mkdir -p "$TMP/fixtures"

cat >"$TMP/fixtures/catalog.toml" <<'TOML'
[versions]
springdoc = "3.1.1"

[libraries]
springdoc-openapi-starter-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
loki-logback-appender = { module = "com.github.loki4j:loki-logback-appender", version.ref = "loki" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
TOML

# A catalog whose second entry is written in a shape the coordinate reader does not understand:
# the two counts diverge, which is the whole point of comparing them.
cat >"$TMP/fixtures/unparsed-catalog.toml" <<'TOML'
[versions]
springdoc = "3.1.1"

[libraries]
springdoc-openapi-starter-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
split-coordinate = { group = "com.github.loki4j", name = "loki-logback-appender", version.ref = "loki" }
TOML

cat >"$TMP/fixtures/empty-catalog.toml" <<'TOML'
[versions]
springdoc = "3.1.1"
TOML

cat >"$TMP/fixtures/tooling-catalog.toml" <<'TOML'
[versions]
palantir-java-format = "2.98.0"

[libraries]
loki-logback-appender = { module = "com.github.loki4j:loki-logback-appender", version.ref = "palantir-java-format" }
TOML

cat >"$TMP/fixtures/clean.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
}

spotless {
	java {
		palantirJavaFormat(libs.versions.palantir.java.format.get())
	}
}

dependencies {
	implementation libs.springdoc.openapi.starter.webmvc.ui
	testImplementation libs.springdoc.openapi.starter.webmvc.api
}
GRADLE

# The tooling version written back as a bare string: no coordinate, no plugin, same escape.
cat >"$TMP/fixtures/tooling-literal.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	alias(libs.plugins.spring.boot)
}

spotless {
	java {
		palantirJavaFormat('2.98.0')
	}
}
GRADLE

cat >"$TMP/fixtures/literal.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1'
}
GRADLE

cat >"$TMP/fixtures/versioned-plugin.build.gradle" <<'GRADLE'
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.2'
}
GRADLE

# A version on a plugin the reader is not keyed on: the check must find any inline version, not
# just the one the tree used to drift on.
cat >"$TMP/fixtures/other-versioned-plugin.build.gradle" <<'GRADLE'
plugins {
	id 'com.diffplug.spotless' version '8.11.0'
	alias(libs.plugins.spring.boot)
}
GRADLE

# A build whose plugins block reaches the catalog for nothing: this is not a finding by itself,
# but the alias count is what keeps the plugins reader from reporting green over an empty block.
cat >"$TMP/fixtures/no-alias.build.gradle" <<'GRADLE'
plugins {
	id 'java'
}
GRADLE

cat >"$TMP/fixtures/clean.settings.gradle" <<'GRADLE'
rootProject.name = 'svc'

dependencyResolutionManagement {
	versionCatalogs {
		libs {
			from(files("../gradle/libs.versions.toml"))
		}
	}
}
GRADLE

cat >"$TMP/fixtures/no-catalog.settings.gradle" <<'GRADLE'
rootProject.name = 'svc'
GRADLE

cat >"$TMP/fixtures/version-catalogs-only.settings.gradle" <<'GRADLE'
rootProject.name = 'svc'

dependencyResolutionManagement {
	versionCatalogs {
		libs {
			from(files("../../gradle/libs.versions.toml"))
		}
	}
}
GRADLE

# check_clean <fixture name> <reader> [reader arguments...]
check_clean() {
	local name="$1" reader="$2" result
	shift 2
	result="$("$reader" "$@")"
	if [ -z "$result" ]; then
		pass "fixture $name is accepted by $reader"
	else
		fail "fixture $name should be clean but was rejected: $result"
	fi
}

# check_rejected <fixture name> <reader> <reason the verdict must name|''> [reader arguments...]
check_rejected() {
	local name="$1" reader="$2" needle="$3" result
	shift 3
	result="$("$reader" "$@")"
	if [ -z "$result" ]; then
		fail "fixture $name was accepted, so $reader is not reading the file"
		return
	fi
	if [ -n "$needle" ] && ! printf '%s' "$result" | grep -Fq -- "$needle"; then
		fail "fixture $name was rejected, but not for $needle: $result"
		return
	fi
	pass "fixture $name is rejected ($(printf '%s' "$result" | head -1))"
}

check_clean "catalog.toml" catalog_count_verdict "$TMP/fixtures/catalog.toml"
check_rejected "unparsed-catalog.toml" catalog_count_verdict "carry a" "$TMP/fixtures/unparsed-catalog.toml"
check_rejected "empty-catalog.toml" catalog_count_verdict "no [libraries] entry" "$TMP/fixtures/empty-catalog.toml"

check_clean "clean.build.gradle" literal_coordinates "$TMP/fixtures/clean.build.gradle" "$TMP/fixtures/catalog.toml"
check_rejected "literal.build.gradle" literal_coordinates "org.springdoc:springdoc-openapi-starter-webmvc-ui" "$TMP/fixtures/literal.build.gradle" "$TMP/fixtures/catalog.toml"

check_clean "clean.build.gradle" version_literal_findings "$TMP/fixtures/clean.build.gradle" "$TMP/fixtures/tooling-catalog.toml"
check_rejected "tooling-literal.build.gradle" version_literal_findings "palantir-java-format" "$TMP/fixtures/tooling-literal.build.gradle" "$TMP/fixtures/tooling-catalog.toml"

check_clean "clean.build.gradle" plugin_version_literals "$TMP/fixtures/clean.build.gradle"
check_rejected "versioned-plugin.build.gradle" plugin_version_literals "declares a plugin version inline" "$TMP/fixtures/versioned-plugin.build.gradle"
check_rejected "other-versioned-plugin.build.gradle" plugin_version_literals "declares a plugin version inline" "$TMP/fixtures/other-versioned-plugin.build.gradle"

check_clean "clean.settings.gradle" catalog_declaration_findings "$TMP/fixtures/clean.settings.gradle"
check_rejected "no-catalog.settings.gradle" catalog_declaration_findings "dependencyResolutionManagement" "$TMP/fixtures/no-catalog.settings.gradle"
check_rejected "version-catalogs-only.settings.gradle" catalog_declaration_findings "does not declare the shared catalog" "$TMP/fixtures/version-catalogs-only.settings.gradle"

# The two counters below are what the readers above cannot answer: they only detect what is
# wrong in a file they were handed, so a build whose plugins block is empty and a catalog nobody
# reads both look clean. The fixtures are their proof.
if [ "$(alias_count "$TMP/fixtures/clean.build.gradle")" -ge 2 ]; then
	pass "the alias counter reads the two catalog plugins of the clean fixture"
else
	fail "the alias counter reads nothing from the clean fixture, so it cannot detect a build that stopped using the catalog"
fi
if [ "$(alias_count "$TMP/fixtures/no-alias.build.gradle")" -eq 0 ]; then
	pass "the alias counter reports zero for a plugins block with no catalog accessor"
else
	fail "the alias counter does not report zero for a plugins block with no catalog accessor"
fi
if [ "$(libs_reference_count "$TMP/fixtures/clean.build.gradle")" -ge 3 ]; then
	pass "the catalog-accessor counter reads the clean fixture"
else
	fail "the catalog-accessor counter reads nothing from the clean fixture"
fi

# ------------------------------------------
# 2. The catalog on the tree parses, and both modules declare it.
# ------------------------------------------
echo "== the shared catalog exists, parses and is declared by both modules =="
CATALOG_FILE="$(resolve_file catalog "$(catalog_path catalog)")"

if [ ! -r "$CATALOG_FILE" ]; then
	fail "the shared catalog exists ($CATALOG_FILE)"
else
	pass "the shared catalog exists ($(catalog_path catalog))"
	result="$(catalog_count_verdict "$CATALOG_FILE")"
	if [ -z "$result" ]; then
		pass "every [libraries] entry of the catalog yields a group:artifact coordinate"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "the shared catalog $line"
		done <<<"$result"
	fi
	COORDINATES="$(catalog_coordinates "$CATALOG_FILE")"
	COORDINATE_COUNT="$(printf '%s\n' "$COORDINATES" | sed '/^$/d' | wc -l | tr -d ' ')"
	if printf '%s\n' "$COORDINATES" | grep -Fxq -- "$CATALOG_SENTINEL"; then
		pass "the catalog reader finds $CATALOG_SENTINEL, a coordinate the catalog is known to manage"
	else
		fail "the catalog reader does not find $CATALOG_SENTINEL in $CATALOG_FILE, so it is not reading the file"
	fi
	printf '  the catalog manages %s coordinate(s)\n' "$COORDINATE_COUNT"
	VERSION_VALUE_COUNT="$(catalog_version_count "$CATALOG_FILE")"
	if [ "${VERSION_VALUE_COUNT:-0}" -ge 1 ]; then
		pass "the catalog declares $VERSION_VALUE_COUNT [versions] entr(ies), so the literal reader has values to compare"
	else
		fail "no [versions] entry was read from the shared catalog, so the version-literal reader has nothing to compare"
	fi
fi

for label in settings-gateway settings-api; do
	file="$(resolve_file "$label" "$(catalog_path "$label")")"
	result="$(catalog_declaration_findings "$file")"
	if [ -z "$result" ]; then
		pass "$label declares the shared catalog"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi
done

# ------------------------------------------
# 3. Neither build file writes a version the catalog owns.
# ------------------------------------------
echo "== no version the catalog manages is written outside the catalog =="
for label in build-gateway build-api; do
	file="$(resolve_file "$label" "$(catalog_path "$label")")"

	result="$(literal_coordinates "$file" "$CATALOG_FILE")"
	if [ -z "$result" ]; then
		pass "$label writes no catalog-managed coordinate with a version literal"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi

	result="$(plugin_version_literals "$file")"
	if [ -z "$result" ]; then
		pass "$label declares no plugin version inline"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi

	result="$(version_literal_findings "$file" "$CATALOG_FILE")"
	if [ -z "$result" ]; then
		pass "$label writes no catalog version value as a literal"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$label $line"
		done <<<"$result"
	fi

	# A reader that parsed an empty plugins block, or a build that silently stopped using the
	# catalog, would pass both checks above. These two counts are the cheap proof that both sides
	# of each comparison were read.
	aliases="$(alias_count "$file")"
	if [ "${aliases:-0}" -ge 2 ]; then
		pass "$label reaches $aliases plugin(s) through alias(libs.plugins.…)"
	else
		fail "$label reaches only ${aliases:-0} plugin(s) through alias(libs.plugins.…): it is not building from the catalog"
	fi

	accessors="$(libs_reference_count "$file")"
	if [ "${accessors:-0}" -ge 2 ]; then
		pass "$label uses $accessors catalog accessor(s)"
	else
		fail "$label uses only ${accessors:-0} catalog accessor(s): it is not resolving dependencies from the catalog"
	fi
done

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'version-catalog: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
