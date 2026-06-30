package com.illegalscanner.validator;

/**
 * Describes a single violation found on an item.
 */
public record Violation(
        /** Machine-readable violation type code, e.g. "ENCHANT_LEVEL_EXCEEDED" */
        String type,
        /** Human-readable description of the violation */
        String message,
        /** Severity of this specific violation */
        ValidationResult severity
) {
    /**
     * Create an ILLEGAL violation.
     */
    public static Violation illegal(String type, String message) {
        return new Violation(type, message, ValidationResult.ILLEGAL);
    }

    /**
     * Create a WARN/suspicious violation.
     */
    public static Violation warn(String type, String message) {
        return new Violation(type, message, ValidationResult.WARN);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + type + ": " + message;
    }
}
