package com.elevate.mock;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * What the mock login endpoint answers for a request body. Pure logic, no HTTP.
 *
 * <ul>
 *   <li>Valid credentials are {@code demo} / {@code demo123} → 200 with a token.</li>
 *   <li>Missing, null, empty or non-string username or password, or wrong credentials → 400.</li>
 *   <li>{@code expiresInMins} is optional: missing, null or 0 → accepted with the default 60;
 *       negative → 400.</li>
 *   <li><b>Deliberate bug 1 and 2:</b> {@code expiresInMins} that is not a whole number, or is
 *       above 1,000,000, is never validated and "crashes" the server: {@link UnhandledInputException},
 *       which the handler turns into a 500.</li>
 *   <li><b>Deliberate bug 3:</b> a username longer than 256 characters is never length-checked
 *       and skips the credential check, so it is accepted with 200. The edge case
 *       {@code very_long_string_username} expects a 4xx, so the load tester reports
 *       "accepted invalid input".</li>
 * </ul>
 */
public final class LoginRules {

    static final String VALID_USERNAME = "demo";
    static final String VALID_PASSWORD = "demo123";
    static final int DEFAULT_EXPIRES_IN_MINS = 60;
    static final int BUGGY_USERNAME_LENGTH = 256;
    static final long MAX_EXPIRES_IN_MINS = 1_000_000;

    /** Thrown for input the "server" was never written to handle (becomes a 500). */
    public static final class UnhandledInputException extends RuntimeException {
        UnhandledInputException(String message) {
            super(message);
        }
    }

    /**
     * Decides the answer to one login request.
     *
     * @param body the parsed JSON body, or {@code null} when it was not valid JSON
     * @return the response to send
     * @throws UnhandledInputException for the two deliberate crash inputs
     */
    public MockResponse evaluate(JsonNode body) {
        if (body == null || !body.isObject()) {
            return MockResponse.message(400, "Request body must be a JSON object");
        }
        JsonNode username = body.get("username");
        JsonNode password = body.get("password");

        if (username != null && username.isString()
                && username.stringValue().codePointCount(0, username.stringValue().length()) > BUGGY_USERNAME_LENGTH) {
            return token(username.stringValue().substring(0, 20) + "...", DEFAULT_EXPIRES_IN_MINS);
        }
        if (!isNonEmptyString(username) || !isNonEmptyString(password)) {
            return MockResponse.message(400, "Username and password are required strings");
        }
        if (!VALID_USERNAME.equals(username.stringValue()) || !VALID_PASSWORD.equals(password.stringValue())) {
            return MockResponse.message(400, "Invalid credentials");
        }
        JsonNode expires = body.get("expiresInMins");
        if (expires != null && expires.isIntegralNumber() && expires.bigIntegerValue().signum() < 0) {
            return MockResponse.message(400, "expiresInMins must not be negative");
        }
        return token(username.stringValue(), resolveExpiry(expires));
    }

    /** Null, missing, 0 (and false, which Python treats as 0) mean "use the default". */
    private static long resolveExpiry(JsonNode expires) {
        if (expires == null || expires.isNull()
                || (expires.isNumber() && expires.doubleValue() == 0)
                || (expires.isBoolean() && !expires.booleanValue())) {
            return DEFAULT_EXPIRES_IN_MINS;
        }
        if (!expires.isIntegralNumber()
                || expires.bigIntegerValue().compareTo(BigInteger.valueOf(MAX_EXPIRES_IN_MINS)) > 0) {
            throw new UnhandledInputException("cannot compute token expiry from " + expires);
        }
        return expires.longValue();
    }

    private static boolean isNonEmptyString(JsonNode value) {
        return value != null && value.isString() && !value.stringValue().isEmpty();
    }

    private static MockResponse token(String username, long expiresInMins) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", "mock-access-token");
        body.put("username", username);
        body.put("expiresInMins", expiresInMins);
        return new MockResponse(200, body);
    }
}
