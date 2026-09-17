package dev.px.core.util.net;

import dev.px.core.layout.Content;

import dev.px.core.util.Validate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

/**
 * A minimal blocking HTTP client for the small requests a client makes.
 *
 * <p>Update checks, a cape or capemod list, a session validation, a now-playing
 * lookup. Each of those is one request returning a few kilobytes of JSON, and
 * pulling in a HTTP library to make it would put a dependency in a project whose
 * whole premise is that it has almost none. This is {@link HttpURLConnection}
 * with the four things it does not do by default: a timeout on both ends, a user
 * agent, the error body on a failure response, and UTF-8.
 *
 * <p><b>Every method blocks.</b> Called from the game thread, a slow or
 * unreachable host freezes the game for the length of the timeout, which is the
 * single most common way a client ends up with a reputation for stuttering. Run
 * these through {@link dev.px.core.concurrent.ThreadService}:
 *
 * <pre>{@code
 * Core.threads().submit(() -> {
 *     String latest = Http.getOrNull("https://example.invalid/version");
 *     if (latest != null && !latest.equals(version)) {
 *         Core.notifications().info("Update", "Version " + latest + " is available");
 *     }
 * });
 * }</pre>
 *
 * <p>There is no useful complexity to quote: the cost is one round trip plus the
 * size of the response, both dominated by the network rather than by anything
 * here. The timeouts below are the only bound that matters.
 *
 * <p>The timeouts are short on purpose. A request that has not answered in ten
 * seconds is not going to, and a background task holding a thread for two minutes
 * is worse than a failed update check.
 */
public final class Http {

    /** How long to wait for the connection itself, in milliseconds. */
    public static final int CONNECT_TIMEOUT = 5_000;

    /** How long to wait for the response once connected, in milliseconds. */
    public static final int READ_TIMEOUT = 10_000;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String DEFAULT_USER_AGENT = "dev.px.core";

    private static String userAgent = DEFAULT_USER_AGENT;

    private Http() {
    }

    /**
     * Sets the user agent sent with every request.
     *
     * <p>Worth setting to the client's name and version at startup. Several APIs
     * rate-limit or reject the default agent outright, and an identifiable one
     * gets a useful answer when a host starts refusing requests.
     */
    public static void setUserAgent(String agent) {
        userAgent = agent == null || agent.trim().isEmpty() ? DEFAULT_USER_AGENT : agent;
    }

    // ----------------------------------------------------------------- GET

    /**
     * @return the response body
     * @throws IOException on a transport failure or any non-2xx status, with the
     *         status and the response body in the message &mdash; an API that
     *         explains a rejection in its body is no help if the body is discarded
     */
    public static String get(String url) throws IOException {
        return get(url, Collections.<String, String>emptyMap());
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        return request(url, "GET", headers, null, null);
    }

    /**
     * @return the response body, or {@code null} if the request failed for any reason
     *
     * <p>For the calls where failure is unremarkable and there is nothing to do
     * about it: an update check on a host that is down should be silent, not a
     * stack trace in the log every launch.
     */
    public static String getOrNull(String url) {
        try {
            return get(url);
        } catch (IOException failed) {
            return null;
        }
    }

    // ---------------------------------------------------------------- POST

    /** Sends {@code body} as {@code application/json}. */
    public static String postJson(String url, String body) throws IOException {
        return postJson(url, body, Collections.<String, String>emptyMap());
    }

    public static String postJson(String url, String body, Map<String, String> headers) throws IOException {
        return request(url, "POST", headers, body, "application/json; charset=utf-8");
    }

    public static String post(String url, String body, String contentType) throws IOException {
        return request(url, "POST", Collections.<String, String>emptyMap(), body, contentType);
    }

    // -------------------------------------------------------------- plumbing

    private static String request(String url, String method, Map<String, String> headers,
                                  String body, String contentType) throws IOException {
        Validate.notBlank(url, "url");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            if (body != null) {
                connection.setDoOutput(true);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                byte[] payload = body.getBytes(UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            // The error stream, not the input stream, carries the body of a 4xx.
            // Reading the wrong one turns "invalid token" into "IOException: 401".
            boolean failed = status < 200 || status > 299;
            InputStream stream = failed ? connection.getErrorStream() : connection.getInputStream();
            String response = stream == null ? "" : read(stream);
            if (failed) {
                throw new IOException("HTTP " + status + " from " + url
                        + (response.isEmpty() ? "" : ": " + truncate(response)));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), UTF_8);
        }
    }

    /** Keeps a server's HTML error page out of the exception message. */
    private static String truncate(String response) {
        String single = response.replace('\n', ' ').trim();
        return single.length() <= 200 ? single : single.substring(0, 200) + "...";
    }
}
