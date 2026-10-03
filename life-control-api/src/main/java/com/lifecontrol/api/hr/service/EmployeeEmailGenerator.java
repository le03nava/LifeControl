package com.lifecontrol.api.hr.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Pure generator of the corporate email local part and address (decision T6, rule R2).
 *
 * <p>Static methods only, no Spring annotation and no static state: the rule is a one-way door
 * (renaming the address is renaming a login, decision D1), so it is isolated from the service and
 * pinned by its own spec. The caller decides the fallback when the local part is empty.</p>
 *
 * <p>Two classic defects are prevented by construction. Without NFD normalization plus
 * {@code \p{M}} stripping, {@code Pérez} collapses to {@code prez}; without {@link Locale#ROOT},
 * lowercasing under a Turkish default locale turns {@code I} into {@code ı}. Only the first given
 * name is used, and both parts are length-capped.</p>
 */
public final class EmployeeEmailGenerator {

    /** Cap of each side of the local part, before the join. */
    private static final int MAX_PART_LENGTH = 40;

    /** Cap of the joined local part, applied after the join. */
    private static final int MAX_LOCAL_PART_LENGTH = 64;

    private EmployeeEmailGenerator() {}

    /**
     * Builds the local part from the first given name and the paternal last name.
     *
     * <p>Only the first whitespace-delimited token of {@code firstName} is used; the paternal part
     * keeps every token, so particles survive concatenated ({@code De la Cruz} becomes
     * {@code delacruz}). Each part is capped at {@value #MAX_PART_LENGTH} characters, the join is a
     * single dot, and the joined result is capped at {@value #MAX_LOCAL_PART_LENGTH}. An empty result
     * is returned as the empty string; the caller falls back to the employee number.</p>
     */
    public static String localPart(String firstName, String paternalLastName) {
        var given = sanitize(firstToken(firstName));
        var paternal = sanitize(paternalLastName);

        if (given.isEmpty()) {
            return capJoined(paternal);
        }
        if (paternal.isEmpty()) {
            return capJoined(given);
        }
        return capJoined(given + "." + paternal);
    }

    /**
     * Joins a local part and a domain into an address, lowercasing the domain with
     * {@link Locale#ROOT}.
     *
     * @return {@code null} when either side is null or blank
     */
    public static String address(String localPart, String domain) {
        if (localPart == null || localPart.isBlank() || domain == null || domain.isBlank()) {
            return null;
        }
        return localPart + "@" + domain.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the candidate local part for a collision attempt: the plain local part for the first
     * attempt, and the local part with the attempt number appended from the second attempt on.
     */
    public static String suffixed(String localPart, int attempt) {
        if (attempt <= 1) {
            return localPart;
        }
        return localPart + attempt;
    }

    /**
     * Sanitizes an arbitrary raw token with the same rule {@link #localPart} applies to a name part:
     * NFD normalization plus {@code \p{M}} stripping, {@link Locale#ROOT} lowercasing, every
     * character outside {@code [a-z0-9]} removed, and the result capped at
     * {@value #MAX_PART_LENGTH}. Unlike {@link #localPart}, it does not split on whitespace, so the
     * whole token is sanitized as a single part.
     */
    public static String localPartFromToken(String rawToken) {
        return sanitize(rawToken);
    }

    /** Trims and lowercases an email with {@link Locale#ROOT}; returns {@code null} for a null input. */
    public static String normalized(String rawEmail) {
        if (rawEmail == null) {
            return null;
        }
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extracts the domain after the last at-sign, lowercased with {@link Locale#ROOT}.
     *
     * @return {@code null} when the input is null or carries no at-sign
     */
    public static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        var atIndex = email.lastIndexOf('@');
        if (atIndex < 0) {
            return null;
        }
        return email.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static String firstToken(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return trimmed.split("\\s+")[0];
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        var decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        var withoutMarks = decomposed.replaceAll("\\p{M}", "");
        var lowered = withoutMarks.toLowerCase(Locale.ROOT);
        var alphanumeric = lowered.replaceAll("[^a-z0-9]", "");
        if (alphanumeric.length() > MAX_PART_LENGTH) {
            return alphanumeric.substring(0, MAX_PART_LENGTH);
        }
        return alphanumeric;
    }

    private static String capJoined(String joined) {
        if (joined.length() <= MAX_LOCAL_PART_LENGTH) {
            return joined;
        }
        return joined.substring(0, MAX_LOCAL_PART_LENGTH);
    }
}
