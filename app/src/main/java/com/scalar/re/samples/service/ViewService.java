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
package com.scalar.re.samples.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.re.samples.Svcs;
import com.scalar.re.samples.re.ReRestClient;
import com.scalar.re.sdk.model.InboxBody;
import com.scalar.re.sdk.parser.InboxBodyParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read-side views for the GUI, built from the RE scan APIs. The inbox rows are
 * enriched with the SDK-parsed payload so the Detail pane can show the received
 * message without any client-side parsing.
 */
@Service
public class ViewService {

  private final ReRestClient re;
  private final ObjectMapper mapper = new ObjectMapper();

  public ViewService(ReRestClient re) {
    this.re = re;
  }

  /** Undelivered outbox rows for the messages this service has sent. */
  public List<JsonNode> outbox(String svc) {
    List<JsonNode> rows = new ArrayList<>();
    for (String dt : Svcs.DELIVERY_TYPES) {
      addAll(rows, re.scanOutbox(Svcs.outboundEventType(svc, dt)));
    }
    return rows;
  }

  /** Inbox rows delivered to this service, enriched with the parsed payload. */
  public List<JsonNode> inbox(String svc) {
    List<JsonNode> rows = new ArrayList<>();
    for (String dt : Svcs.DELIVERY_TYPES) {
      JsonNode data = re.scanInbox(Svcs.inboundEventType(svc, dt), svc);
      if (data != null && data.isArray()) {
        for (JsonNode row : data) {
          rows.add(enrich(row));
        }
      }
    }
    return rows;
  }

  /**
   * Unified transfer-state view: outbox rows still pending plus completed rows,
   * for the messages this service has sent.
   */
  public Map<String, List<JsonNode>> transferState(String svc) {
    List<JsonNode> pending = new ArrayList<>();
    List<JsonNode> completed = new ArrayList<>();
    for (String dt : Svcs.DELIVERY_TYPES) {
      addAll(pending, re.scanOutbox(Svcs.outboundEventType(svc, dt)));
      addAll(completed, re.scanCompleted(Svcs.outboundEventType(svc, dt)));
    }
    return Map.of("pending", pending, "completed", completed);
  }

  private JsonNode enrich(JsonNode row) {
    ObjectNode out = row.deepCopy();
    try {
      JsonNode bodyNode = row.get("body");
      if (bodyNode != null && !bodyNode.isNull()) {
        InboxBody ib = InboxBodyParser.parse(bodyNode.asText());
        out.set("parsedPayload", ib.getPayload());
        out.set("parsedMetadata", ib.getMetadata());
      }
    } catch (Exception e) {
      out.put("parseError", e.getMessage());
    }
    return out;
  }

  private void addAll(List<JsonNode> rows, JsonNode data) {
    if (data != null && data.isArray()) {
      data.forEach(rows::add);
    }
  }
}
