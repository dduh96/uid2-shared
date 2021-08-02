// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.shared.vertx;

import com.uid2.shared.jmx.AdminApi;
import com.uid2.shared.middleware.AuthMiddleware;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;

public class RequestCapturingHandler implements Handler<RoutingContext> {
    private static final ZoneId ZONE_GMT = ZoneId.of("GMT");
    private Queue<String> _capturedRequests = null;
    private final Map<String, Counter> _apiMetricCounters = new HashMap<>();

    private static String formatRFC1123DateTime(long time) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZONE_GMT));
    }

    @Override
    public void handle(RoutingContext context) {
        if (!AdminApi.instance.getCaptureRequests() && !AdminApi.instance.getPublishApiMetrics()) {
            context.next();
            return;
        }

        if (_capturedRequests == null) {
            _capturedRequests = AdminApi.instance.allocateCapturedRequestQueue();
        }

        long timestamp = System.currentTimeMillis();
        String remoteClient = getClientAddress(context.request().remoteAddress());
        HttpMethod method = context.request().method();
        String uri = context.request().uri();
        HttpVersion version = context.request().version();
        context.addBodyEndHandler(v -> capture(context, timestamp, remoteClient, version, method, uri));
        context.next();
    }

    private String getClientAddress(SocketAddress inetSocketAddress) {
        if (inetSocketAddress == null) {
            return null;
        }
        return inetSocketAddress.host();
    }

    private void capture(RoutingContext context, long timestamp, String remoteClient, HttpVersion version, HttpMethod method, String uri) {
        HttpServerRequest request = context.request();

        int status = request.response().getStatusCode();
        String apiContact;
        try {
            apiContact = (String) context.data().get(AuthMiddleware.API_CONTACT_PROP);
            apiContact = apiContact == null ? "unknown" : apiContact;
        } catch (Exception ex) {
            apiContact = "error: " + ex.getMessage();
        }

        String host = request.headers().contains("host") ? request.headers().get("host") : "NotSpecified";
        if (host.startsWith("10.")) {
            // mask ip address form of host to reduce the metrics tag pollution
            host = "10.x.x.x:xx";
        }
        incrementMetricCounter(apiContact, host, status);

        if (AdminApi.instance.getCaptureFailureOnly() && status < 400) {
            return;
        }

        Matcher m = AdminApi.instance.getApiContactPattern().matcher(apiContact);
        if (!m.find()) {
            return;
        }

        while (_capturedRequests.size() >= AdminApi.instance.getMaxCapturedRequests()) {
            _capturedRequests.remove();
        }

        long contentLength = request.response().bytesWritten();
        String versionFormatted = "-";
        switch (version) {
            case HTTP_1_0:
                versionFormatted = "HTTP/1.0";
                break;
            case HTTP_1_1:
                versionFormatted = "HTTP/1.1";
                break;
            case HTTP_2:
                versionFormatted = "HTTP/2.0";
                break;
        }

        final MultiMap headers = request.headers();

        // as per RFC1945 the header is referer but it is not mandatory some implementations use referrer
        String referrer = headers.contains("referrer") ? headers.get("referrer") : headers.get("referer");
        String userAgent = request.headers().get("user-agent");
        referrer = referrer == null ? "-" : referrer;
        userAgent = userAgent == null ? "-" : userAgent;

        String summary = String.format(
            "-->[%s] %s - - [%s] \"%s %s %s\" %d %d %s \"%s\" \"%s\"",
            apiContact,
            remoteClient,
            formatRFC1123DateTime(timestamp),
            method,
            uri,
            versionFormatted,
            status,
            contentLength,
            (System.currentTimeMillis() - timestamp),
            referrer,
            userAgent);

        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append(summary);
        messageBuilder.append("\n");

        for (Map.Entry<String, String> h : headers) {
            messageBuilder.append(h.getKey());
            messageBuilder.append(": ");
            messageBuilder.append(h.getValue());
            messageBuilder.append("\n");
        }

        messageBuilder.append("<--\n");
        for (Map.Entry<String, String> h : request.response().headers()) {
            messageBuilder.append(h.getKey());
            messageBuilder.append(": ");
            messageBuilder.append(h.getValue());
            messageBuilder.append("\n");
        }

        _capturedRequests.add(messageBuilder.toString());
    }

    private void incrementMetricCounter(String apiContact, String host, int status) {
        assert apiContact != null;
        String key = apiContact + "|" + host + "|" + status;
        if (!_apiMetricCounters.containsKey(key)) {
            Counter counter = Counter
                .builder("uid2.http_requests")
                .description("counter for how many http requests are processed per each api contact and status code")
                .tags("api_contact", apiContact, "host", host, "status", String.valueOf(status))
                .register(Metrics.globalRegistry);
            _apiMetricCounters.put(key, counter);
        }

        _apiMetricCounters.get(key).increment();
    }
}