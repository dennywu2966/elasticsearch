/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License v 1".
 */
package org.elasticsearch.benchmark.security.cloudiam;

import org.elasticsearch.plugin.security.cloudiam.CloudIamToken;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CloudIamTokenBenchmark {
    private String header;

    @Setup
    public void setup() {
        String json = """
            {
              "Action": "GetCallerIdentity",
              "Version": "2015-04-01",
              "AccessKeyId": "AKID",
              "Signature": "sig",
              "SignatureMethod": "HMAC-SHA1",
              "SignatureVersion": "1.0",
              "SignatureNonce": "nonce",
              "Timestamp": "2025-01-01T00:00:00Z"
            }
            """;
        header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Benchmark
    public CloudIamToken parseSignedHeader() {
        return CloudIamToken.fromHeaders(header, 8192);
    }
}
