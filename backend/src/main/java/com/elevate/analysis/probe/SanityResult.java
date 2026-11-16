package com.elevate.analysis.probe;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of the reachability probe ({@code "sanity"} in the API). Fields that do not
 * apply are left out of the JSON, as in the Python version.
 *
 * @param validFormat       the URL is a well-formed http(s) URL
 * @param reachable         the server answered with a status below 500
 * @param statusCode        status of the deciding response
 * @param finalUrl          URL after redirects
 * @param headers           response headers of the deciding response
 * @param checkedWithMethod the method of the deciding response
 * @param attempts          every response the probe got
 * @param methodNotAllowed  HEAD and GET were refused (404/405) but the server answered
 * @param allowedMethods    the {@code Allow} header in that case
 * @param error             why the probe failed
 * @param blocked           a redirect pointed at a blocked host
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SanityResult(boolean validFormat, boolean reachable, Integer statusCode, String finalUrl,
                           Map<String, String> headers, String checkedWithMethod, List<ProbeAttempt> attempts,
                           Boolean methodNotAllowed, String allowedMethods, String error, Boolean blocked) {

    /** The URL is not a valid http(s) URL. */
    public static SanityResult malformed() {
        return new SanityResult(false, false, null, null, null, null, null, null, null, "Malformed URL", null);
    }

    /** The probe could not get any answer. */
    static SanityResult failed(String method, String error, boolean blocked) {
        return new SanityResult(true, false, null, null, null, method, null, null, null, error, blocked ? true : null);
    }
}
