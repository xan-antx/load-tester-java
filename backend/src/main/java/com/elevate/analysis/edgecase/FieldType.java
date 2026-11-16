package com.elevate.analysis.edgecase;

import tools.jackson.databind.JsonNode;

/** The JSON type of a sample field, named as the Python version names them. */
public enum FieldType {
    BOOL("bool"), INT("int"), FLOAT("float"), STR("str"), OBJECT("object"), ARRAY("array"), NULL("null");

    private final String pythonName;

    FieldType(String pythonName) {
        this.pythonName = pythonName;
    }

    /** The name used in descriptions, e.g. {@code int} or {@code str}. */
    public String pythonName() {
        return pythonName;
    }

    /** Type mismatch, boundary and attack cases only make sense for scalars. */
    public boolean isScalar() {
        return this == BOOL || this == INT || this == FLOAT || this == STR;
    }

    /** Whether the field holds a number. */
    public boolean isNumeric() {
        return this == INT || this == FLOAT;
    }

    /** The type of a JSON value. */
    public static FieldType of(JsonNode value) {
        if (value == null || value.isNull()) {
            return NULL;
        }
        if (value.isBoolean()) {
            return BOOL;
        }
        if (value.isIntegralNumber()) {
            return INT;
        }
        if (value.isNumber()) {
            return FLOAT;
        }
        if (value.isString()) {
            return STR;
        }
        return value.isObject() ? OBJECT : ARRAY;
    }
}
