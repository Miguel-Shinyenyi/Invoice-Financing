package com.settlementengine.core.lab;

/** The verdict of one end-of-run check against the database. */
public record LabInvariant(String id, String title, String status, String detail) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIPPED = "SKIPPED";

    public static LabInvariant pass(String id, String title, String detail) {
        return new LabInvariant(id, title, PASS, detail);
    }

    public static LabInvariant fail(String id, String title, String detail) {
        return new LabInvariant(id, title, FAIL, detail);
    }

    public static LabInvariant skipped(String id, String title, String detail) {
        return new LabInvariant(id, title, SKIPPED, detail);
    }
}
