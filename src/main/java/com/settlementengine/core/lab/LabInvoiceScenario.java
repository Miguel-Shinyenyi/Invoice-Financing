package com.settlementengine.core.lab;

/** Named invoice scenarios; each maps to rules in ml-service/main.py. */
public enum LabInvoiceScenario {
    CLEAN("A clean invoice", "Established account, unique customer reference, modest advance. No rule fires."),
    DUPLICATE_REFERENCE("Duplicate customer reference",
            "The same customer reference is already on another business's invoice. Rule: duplicate_customer_reference (+0.4)."),
    NEW_ACCOUNT_HIGH_ADVANCE("New account, high advance",
            "A brand-new business account asks for an advance above 500. Rule: new_account_high_advance (+0.3)."),
    RAPID_REFINANCING("Rapid refinancing",
            "The account already has three financed invoices outstanding. Rule: rapid_refinancing (+0.3)."),
    BLOCKED_COMBINATION("Duplicate reference on a new account",
            "Duplicate reference (+0.4) and new account with a high advance (+0.3) reach the 0.7 BLOCK threshold.");

    private final String title;
    private final String description;

    LabInvoiceScenario(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }
}
