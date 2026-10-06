/*
 * Copyright 2026 Scalar Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scalar.re.samples.re;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.re.samples.SamplesProperties;
import com.scalar.re.samples.config.ReConfig;
import com.scalar.re.sdk.auth.ReRequestSigner;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data-plane calls send an HMAC signature version 2 that the RE server can verify: the
 * signature covers method, path, timestamp and body, so it does not verify for another path.
 */
class ReRestClientSignatureTest {

  private static final String SVC1_KEY = "svc1-key";
  private static final String SVC2_KEY = "svc2-key";

  private record Captured(String method, String path, String version, String timestamp,
      String signature, String body) {}

  private final AtomicReference<Captured> captured = new AtomicReference<>();
  private HttpServer server;
  private ReRestClient client;

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst(ReRequestSigner.VERSION_HEADER),
          exchange.getRequestHeaders().getFirst(ReRequestSigner.TIMESTAMP_HEADER),
          exchange.getRequestHeaders().getFirst(ReRequestSigner.SIGNATURE_HEADER), body));
      byte[] resp = "{\"success\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, resp.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(resp);
      }
    });
    server.start();

    Path config = dir.resolve("scalar-re-config.yml");
    Files.writeString(config, """
        global:
          auth:
            api-key: test-api-key
        scalardb:
          default-storage: pg1
        storages:
          pg1: {host: localhost, port: 5432, database: db, username: u, password: p}
        namespaces:
          svc1:
            storage: pg1
            hmac: {key: %s}
            event-types: {}
          svc2:
            storage: pg1
            hmac: {key: %s}
            event-types: {}
        """.formatted(SVC1_KEY, SVC2_KEY));
    SamplesProperties props = new SamplesProperties();
    props.setReConfigPath(config.toString());
    props.setReBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    client = new ReRestClient(props, new ReConfig(props));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void notify_isSignedVersion2WithTheSourceKey() {
    client.notify("svc1", "OrderCreated", "e-1", List.of("svc2"));

    assertVerifies(captured.get(), SVC1_KEY, "/api/v1/re/notify");
  }

  @Test
  void pull_isSignedVersion2WithTheDestinationKey() {
    client.pull("svc2", "OrderCreated", 10);

    assertVerifies(captured.get(), SVC2_KEY, "/api/v1/re/pull");
  }

  @Test
  void poll_isSignedVersion2WithTheDestinationKey() {
    client.poll("svc2", "OrderCreated", 10);

    assertVerifies(captured.get(), SVC2_KEY, "/api/v1/re/poll");
  }

  @Test
  void signature_doesNotVerifyForAnotherPathOrKey() {
    client.notify("svc1", "OrderCreated", "e-1", List.of("svc2"));
    Captured c = captured.get();

    assertThat(ReRequestSigner.matches(expected(SVC1_KEY, "/api/v1/re/poll", c), c.signature()))
        .isFalse();
    assertThat(ReRequestSigner.matches(expected(SVC2_KEY, c.path(), c), c.signature())).isFalse();
  }

  private static void assertVerifies(Captured c, String key, String path) {
    assertThat(c.method()).isEqualTo("POST");
    assertThat(c.path()).isEqualTo(path);
    assertThat(c.version()).isEqualTo(ReRequestSigner.VERSION);
    assertThat(c.timestamp()).isNotBlank();
    assertThat(ReRequestSigner.matches(expected(key, path, c), c.signature())).isTrue();
  }

  private static byte[] expected(String key, String path, Captured c) {
    return ReRequestSigner.hmac(key,
        ReRequestSigner.stringToSign("POST", path, null, c.timestamp(), c.body()));
  }
}
