package com.legalarchive.orchestrator.json2csv;

/**
 * Translates a date MASK in this product's dialect into a {@code java.time} pattern.
 *
 * <p>One method, and a seam rather than a copy. {@code recordBusinessDateFormat} carries forms like
 * {@code YYYY/MM/DD}, where {@code DD} is day-of-month; handing that straight to
 * {@code DateTimeFormatter.ofPattern} is the defect recorded in CLAUDE.md under "the date MASK is not
 * a java.time pattern", which silently broke {@code businessDateNotBefore} for the life of the
 * product. {@code InternalSteps.fmtToJavaPattern} already does the translation correctly.
 *
 * <p>This package cannot reference {@code InternalSteps}: that class is Spring-coupled, and the point
 * of this package is that it compiles and runs with the JDK alone. So the executor supplies the real
 * translator and this package never learns a second dialect. Reimplementing it here is the one thing
 * this interface exists to prevent.
 */
public interface MaskTranslator {
    /** @return the java.time pattern, or null when the mask is null. */
    String toJavaPattern(String mask);
}
