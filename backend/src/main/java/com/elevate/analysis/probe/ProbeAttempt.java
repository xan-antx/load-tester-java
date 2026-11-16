package com.elevate.analysis.probe;

/**
 * What type detection needs to know about one probe response.
 *
 * @param method      the probe method
 * @param statusCode  HTTP status
 * @param contentType {@code Content-Type}, lowercase ("" when absent)
 * @param allow       methods the server says it accepts ({@code Allow}, or the CORS equivalent)
 */
public record ProbeAttempt(String method, int statusCode, String contentType, String allow) {
}
