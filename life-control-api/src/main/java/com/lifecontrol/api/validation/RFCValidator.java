package com.lifecontrol.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates that a string is a properly formatted Mexican RFC.
 * <p>
 * Accepted formats:
 * <ul>
 *   <li>Persona física (13 chars): 4 uppercase letters, 6 digits, 3 alphanumeric</li>
 *   <li>Persona moral (12 chars): 3 uppercase letters, 6 digits, 3 alphanumeric</li>
 * </ul>
 * <p>
 * The regex allows letters including Ñ and &amp; as valid RFC characters.
 * Homoclave (last 3 chars) can be alphanumeric.
 * <p>
 * {@code null} values pass validation — combine with {@code @NotBlank} for required fields.
 */
public class RFCValidator implements ConstraintValidator<ValidRFC, String> {

    /**
     * RFC pattern:
     * <ul>
     *   <li>{@code [A-ZÑ&]{3,4}} — 3 or 4 uppercase letters (Ñ and &amp; are valid RFC characters)</li>
     *   <li>{@code \d{6}} — exactly 6 digits (birth date YYMMDD for persons, foundation date for entities)</li>
     *   <li>{@code [A-Za-z0-9]{3}} — 3 alphanumeric characters (homoclave + verification digit)</li>
     * </ul>
     */
    private static final Pattern RFC_PATTERN = Pattern.compile(
            "^[A-ZÑ&]{3,4}\\d{6}[A-Za-z0-9]{3}$"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return RFC_PATTERN.matcher(value.toUpperCase()).matches();
    }
}
