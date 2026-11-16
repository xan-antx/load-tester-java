package com.elevate.analysis.edgecase;

import java.util.List;

/** Cross-site scripting strings in text fields ({@code xss_<field>_0..1}). */
public final class XssGenerator extends AttackPayloadGenerator {

    /** The payloads, as in the Python version. */
    public static final List<String> PAYLOADS = List.of("<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>");

    @Override
    protected String prefix() {
        return "xss";
    }

    @Override
    protected List<String> payloads() {
        return PAYLOADS;
    }

    @Override
    protected String attackName() {
        return "XSS attempt";
    }
}
