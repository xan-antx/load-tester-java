package com.elevate.analysis.edgecase;

import java.util.List;

/** SQL injection strings in text fields ({@code sqli_<field>_0..2}). */
public final class SqlInjectionGenerator extends AttackPayloadGenerator {

    /** The payloads, as in the Python version. */
    public static final List<String> PAYLOADS = List.of("' OR '1'='1", "'; DROP TABLE users; --", "1 OR 1=1");

    @Override
    protected String prefix() {
        return "sqli";
    }

    @Override
    protected List<String> payloads() {
        return PAYLOADS;
    }

    @Override
    protected String attackName() {
        return "SQL injection attempt";
    }
}
