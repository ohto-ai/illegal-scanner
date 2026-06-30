package com.illegalscanner.validator;

/**
 * Severity of a validation finding.
 */
public enum ValidationResult {
    /** Item is clean — no issues found. */
    PASS,
    /** Item has suspicious characteristics — may warrant investigation. */
    WARN,
    /** Item is definitely illegal/unobtainable in vanilla survival. */
    ILLEGAL
}
