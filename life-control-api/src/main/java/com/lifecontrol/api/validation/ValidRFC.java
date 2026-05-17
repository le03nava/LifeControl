package com.lifecontrol.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom Jakarta Validation constraint for Mexican RFC (Registro Federal de Contribuyentes).
 * <p>
 * Validates that the annotated string matches the Mexican RFC format:
 * <ul>
 *   <li>Personas físicas: 4 letters + 6 digits (YYMMDD) + 3 alphanumeric characters = 13 chars</li>
 *   <li>Personas morales: 3 letters + 6 digits (YYMMDD) + 3 alphanumeric characters = 12 chars</li>
 * </ul>
 * <p>
 * Accepts {@code null} values — use {@code @NotBlank} alongside if the field is required.
 */
@Documented
@Constraint(validatedBy = RFCValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRFC {

    String message() default "RFC inválido. Debe tener formato válido: 3-4 letras seguidas de 6 dígitos y 3 caracteres alfanuméricos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
