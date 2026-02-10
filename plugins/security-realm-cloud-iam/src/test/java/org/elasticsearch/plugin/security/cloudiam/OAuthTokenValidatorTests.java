/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License v 1".
 */
package org.elasticsearch.plugin.security.cloudiam;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

public class OAuthTokenValidatorTests extends ESTestCase {

    public void testVerifyWrapsMalformedUserInfoResponse() throws Exception {
        OAuthTokenValidator validator = createValidator(request -> new StubHttpResponse(request, 200, "not-json"));

        VerificationResult result = verify(validator, CloudIamToken.fromHeaders(null, "Bearer test-token", 8192));

        assertThat(result.principal(), nullValue());
        assertThat(result.failure(), instanceOf(IllegalStateException.class));
        assertThat(result.failure().getMessage(), containsString("Failed to parse OAuth userinfo response"));
    }

    public void testVerifyPreservesUserInfoValidationFailure() throws Exception {
        OAuthTokenValidator validator = createValidator(request -> new StubHttpResponse(request, 200, "{}"));

        VerificationResult result = verify(validator, CloudIamToken.fromHeaders(null, "Bearer test-token", 8192));

        assertThat(result.principal(), nullValue());
        assertThat(result.failure(), instanceOf(IllegalStateException.class));
        assertThat(result.failure().getMessage(), containsString("missing user identifier"));
    }

    public void testVerifyConvertsInterruptedHttpCallToIoException() throws Exception {
        OAuthTokenValidator validator = createValidator(request -> {
            throw new InterruptedException("simulated interruption");
        });

        VerificationResult result = verify(validator, CloudIamToken.fromHeaders(null, "Bearer test-token", 8192));

        assertThat(result.principal(), nullValue());
        assertThat(result.failure(), instanceOf(IOException.class));
        assertThat(result.failure().getMessage(), containsString("interrupted"));
        assertTrue("verify should preserve thread interrupted status", Thread.currentThread().isInterrupted());
        Thread.interrupted(); // clear interrupted status for subsequent tests
    }

    private static OAuthTokenValidator createValidator(ResponseBehavior behavior) {
        return new OAuthTokenValidator(
            "https://oauth.aliyun.com/v1/userinfo",
            TimeValue.timeValueSeconds(5),
            new StubHttpClient(behavior)
        );
    }

    private static VerificationResult verify(OAuthTokenValidator validator, CloudIamToken token) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IamPrincipal> principalRef = new AtomicReference<>();
        AtomicReference<Exception> failureRef = new AtomicReference<>();

        validator.verify(token, ActionListener.wrap(principal -> {
            principalRef.set(principal);
            latch.countDown();
        }, e -> {
            failureRef.set(e);
            latch.countDown();
        }));

        boolean completed;
        boolean interrupted = false;
        try {
            completed = latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            completed = latch.getCount() == 0;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        assertTrue("verification callback should complete", completed);
        return new VerificationResult(principalRef.get(), failureRef.get());
    }

    private record VerificationResult(IamPrincipal principal, Exception failure) {}

    @FunctionalInterface
    private interface ResponseBehavior {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private static class StubHttpClient extends HttpClient {
        private final ResponseBehavior behavior;

        private StubHttpClient(ResponseBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException,
            InterruptedException {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) behavior.send(request);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync not used in tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("sendAsync not used in tests");
        }
    }

    private static class StubHttpResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;

        private StubHttpResponse(HttpRequest request, int statusCode, String body) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
