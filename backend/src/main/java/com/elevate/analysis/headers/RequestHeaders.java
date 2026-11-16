package com.elevate.analysis.headers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.elevate.exception.BadRequestException;

import tools.jackson.databind.JsonNode;

/**
 * Optional request headers (e.g. {@code Authorization: Bearer ...}) sent with the probe and
 * with every load-test request.
 *
 * <p>Header <b>values</b> are secrets: they are held in memory for the run only, never
 * stored, logged or returned. Only the header <b>names</b> are stored with a run.
 *
 * @param values header name → value (validated)
 */
public record RequestHeaders(Map<String, String> values) {

    /** At most this many headers. */
    public static final int MAX_HEADERS = 20;
    /** Longest header value. */
    public static final int MAX_VALUE_LENGTH = 4096;

    /** RFC 7230 "token" characters. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    /** Headers java.net.http.HttpClient sets itself and refuses to take from callers. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    /** No headers. */
    public static final RequestHeaders NONE = new RequestHeaders(Map.of());

    /** An unmodifiable copy that keeps the order. */
    public RequestHeaders {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Validates headers sent as {@code {"Name": "value"}}. Messages name the header, never its value.
     *
     * @param raw the JSON value, or {@code null}
     * @return the headers ({@link #NONE} when none were sent)
     * @throws BadRequestException when a name or value is not allowed
     */
    public static RequestHeaders clean(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return NONE;
        }
        if (!raw.isObject()) {
            throw new BadRequestException("headers must be an object of name: value pairs");
        }
        if (raw.size() > MAX_HEADERS) {
            throw new BadRequestException("at most " + MAX_HEADERS + " headers are allowed");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : raw.properties()) {
            String name = entry.getKey();
            if (!NAME.matcher(name).matches()) {
                throw new BadRequestException("a header name may only contain letters, digits and - _ . (no spaces)");
            }
            if (RESTRICTED.contains(name.toLowerCase())) {
                throw new BadRequestException("header '" + name + "' is set by the HTTP client and cannot be overridden");
            }
            if (!entry.getValue().isString()) {
                throw new BadRequestException("the value of header '" + name + "' must be text");
            }
            String value = entry.getValue().stringValue().strip();
            if (value.contains("\r") || value.contains("\n")) {
                throw new BadRequestException("the value of header '" + name + "' must be a single line");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new BadRequestException("the value of header '" + name + "' is too long");
            }
            headers.put(name, value);
        }
        return new RequestHeaders(headers);
    }

    /** The names only, sorted: the part that is safe to store. */
    public List<String> names() {
        return values.keySet().stream().sorted().toList();
    }

    /**
     * Replaces every non-empty header value inside {@code text} with {@code ***}, for anything
     * that might echo a value back (error messages).
     *
     * @param text any text, or {@code null}
     * @return the redacted text
     */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String value : values.values()) {
            if (!value.isEmpty()) {
                result = result.replace(value, "***");
            }
        }
        return result;
    }
}
