package com.elevate.engine;

import java.net.URI;

import com.elevate.model.Expectation;

/**
 * One kind of request a virtual user sends: an edge case (POST with a JSON body) or a
 * website page (GET).
 *
 * @param name        case label or page path; results are recorded under it
 * @param method      HTTP method
 * @param uri         full URL
 * @param body        JSON body, or {@code null} for none
 * @param expectation what a correct answer is, or {@code null} for website pages
 */
public record RequestTemplate(String name, String method, URI uri, String body, Expectation expectation) {

    /** An API edge case: POST of a JSON body. */
    public static RequestTemplate post(String name, URI uri, String jsonBody, Expectation expectation) {
        return new RequestTemplate(name, "POST", uri, jsonBody, expectation);
    }

    /** A website page: GET, judged by Locust's rule (status 400 or more fails). */
    public static RequestTemplate get(String path, URI uri) {
        return new RequestTemplate(path, "GET", uri, null, null);
    }
}
