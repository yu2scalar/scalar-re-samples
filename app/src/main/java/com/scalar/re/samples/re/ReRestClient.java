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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.re.samples.SamplesProperties;
import com.scalar.re.samples.config.ReConfig;
import com.scalar.re.sdk.auth.ReRequestSigner;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client over the RE REST API. Read-only scans use the API key (Bearer);
 * the data-plane calls (notify / pull / poll) use per-namespace HMAC. Send/list
 * therefore go through RE wherever an API exists; only the consume step is done
 * natively (see {@code ConsumerService}).
 */
@Component
public class ReRestClient {

  private final RestClient http;
  private final ReConfig reConfig;
  private final String apiKey;
  private final ObjectMapper mapper = new ObjectMapper();

  public ReRestClient(SamplesProperties props, ReConfig reConfig) {
    this.http = RestClient.builder().baseUrl(props.getReBaseUrl()).build();
    this.reConfig = reConfig;
    this.apiKey = reConfig.apiKey();
  }

  /** POST /notify — signed with the SOURCE namespace HMAC key. */
  public JsonNode notify(String sourceNamespace, String eventType, String eventId,
      List<String> routingNamespaces) {
    ObjectNode body = mapper.createObjectNode();
    body.put("event_type", eventType);
    body.put("event_id", eventId);
    body.set("routing_namespaces", mapper.valueToTree(routingNamespaces));
    return postHmac("/api/v1/re/notify", sourceNamespace, body);
  }

  /** POST /pull — signed with the DESTINATION namespace HMAC key. */
  public JsonNode pull(String destinationNamespace, String eventType, int limit) {
    ObjectNode body = mapper.createObjectNode();
    body.put("event_type", eventType);
    body.put("destination", destinationNamespace);
    body.put("limit", limit);
    return postHmac("/api/v1/re/pull", destinationNamespace, body);
  }

  /** POST /poll — signed with the DESTINATION namespace HMAC key. Returns keys only. */
  public JsonNode poll(String destinationNamespace, String eventType, int limit) {
    ObjectNode body = mapper.createObjectNode();
    body.put("event_type", eventType);
    body.put("destination", destinationNamespace);
    body.put("limit", limit);
    return postHmac("/api/v1/re/poll", destinationNamespace, body);
  }

  public JsonNode scanOutbox(String eventType) {
    return records(getBearer("/api/v1/re-outbox/scan/" + eventType));
  }

  public JsonNode scanInbox(String eventType, String destination) {
    return records(getBearer("/api/v1/re-inbox/scan/" + eventType + "?destination=" + destination));
  }

  public JsonNode scanCompleted(String eventType) {
    return records(getBearer("/api/v1/re-completed/scan/" + eventType));
  }

  // ---- internals ----------------------------------------------------------

  private JsonNode postHmac(String path, String namespace, ObjectNode body) {
    String payload;
    try {
      payload = mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize request", e);
    }
    String hmacKey = reConfig.namespace(namespace).hmacKey();
    // HMAC signature version 2 (SDK signer): method, path, query, timestamp and body are signed
    Map<String, String> signed = ReRequestSigner.headers(
        hmacKey, "POST", path, null, payload, System.currentTimeMillis());
    String resp = http.post().uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> signed.forEach(h::set))
        .body(payload)
        .retrieve()
        .body(String.class);
    return readData(resp);
  }

  private JsonNode getBearer(String path) {
    String resp = http.get().uri(path)
        .header("Authorization", "Bearer " + apiKey)
        .retrieve()
        .body(String.class);
    return readData(resp);
  }

  /**
   * The scan APIs return one page: {@code {"records":[...],"next_cursor":...}} (RE 0.9.6+). The demo
   * shows the first page only, so this returns the {@code records} array.
   */
  static JsonNode records(JsonNode page) {
    return page != null && page.has("records") ? page.get("records") : page;
  }

  /** Unwraps the RE ApiResponse envelope and returns its {@code data} node. */
  private JsonNode readData(String resp) {
    try {
      JsonNode root = mapper.readTree(resp);
      return root.has("data") ? root.get("data") : root;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse RE response: " + resp, e);
    }
  }
}
