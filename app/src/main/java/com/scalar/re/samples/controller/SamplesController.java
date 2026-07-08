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
package com.scalar.re.samples.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.re.samples.SamplesProperties;
import com.scalar.re.samples.Svcs;
import com.scalar.re.samples.config.ReConfig;
import com.scalar.re.samples.service.ConsumerService;
import com.scalar.re.samples.service.ProducerService;
import com.scalar.re.samples.service.ViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the Svc1/Svc2 demo. Browse it at /swagger-ui.html. */
@RestController
@RequestMapping("/api")
@Tag(name = "ScalarRE samples", description = "Svc1/Svc2 producer & consumer demo")
public class SamplesController {

  private final ProducerService producer;
  private final ConsumerService consumer;
  private final ViewService view;
  private final ReConfig reConfig;
  private final SamplesProperties props;

  public SamplesController(ProducerService producer, ConsumerService consumer, ViewService view,
      ReConfig reConfig, SamplesProperties props) {
    this.producer = producer;
    this.consumer = consumer;
    this.view = view;
    this.reConfig = reConfig;
    this.props = props;
  }

  public record SendRequest(String deliveryType, JsonNode payload) {}

  public record DeliveryRequest(String deliveryType) {}

  public record ProcessRequest(String eventType, long partition, String eventId, int stepId, int seq) {}

  @GetMapping("/services")
  @Operation(summary = "List the demo services and their DB / event-type info")
  public List<Map<String, Object>> services() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String svc : Svcs.SERVICES) {
      ReConfig.Namespace ns = reConfig.namespace(svc);
      out.add(Map.of(
          "name", svc,
          "destination", Svcs.other(svc),
          "database", reConfig.storageForNamespace(svc).jdbcUrl(),
          "eventTypes", ns.eventTypeDeliveryTypes(),
          "deliveryTypes", Svcs.DELIVERY_TYPES));
    }
    return out;
  }

  @GetMapping("/re/info")
  @Operation(summary = "RE server base URL and supported delivery types")
  public Map<String, Object> reInfo() {
    return Map.of("baseUrl", props.getReBaseUrl(), "deliveryTypes", Svcs.DELIVERY_TYPES);
  }

  @PostMapping("/svc/{svc}/send")
  @Operation(summary = "Send a message: write re_outbox (ScalarDB TX) + notify RE")
  public Map<String, Object> send(@PathVariable String svc, @RequestBody SendRequest req)
      throws Exception {
    return producer.send(svc, req.deliveryType(), req.payload());
  }

  @GetMapping("/svc/{svc}/outbox")
  @Operation(summary = "Undelivered outbox rows for messages this service sent")
  public List<JsonNode> outbox(@PathVariable String svc) {
    return view.outbox(svc);
  }

  @GetMapping("/svc/{svc}/transfer-state")
  @Operation(summary = "Unified RE transfer state (pending outbox + completed)")
  public Map<String, List<JsonNode>> transferState(@PathVariable String svc) {
    return view.transferState(svc);
  }

  @GetMapping("/svc/{svc}/inbox")
  @Operation(summary = "Inbox rows delivered to this service, with parsed payload")
  public List<JsonNode> inbox(@PathVariable String svc) {
    return view.inbox(svc);
  }

  @PostMapping("/svc/{svc}/inbox/pull")
  @Operation(summary = "Queue delivery: move queued records into this service's inbox")
  public JsonNode pull(@PathVariable String svc, @RequestBody DeliveryRequest req) {
    return consumer.pull(svc, req.deliveryType());
  }

  @PostMapping("/svc/{svc}/inbox/poll")
  @Operation(summary = "Remote-consumer view: available inbox keys only (no body)")
  public Map<String, Object> poll(@PathVariable String svc) {
    return consumer.pollKeys(svc);
  }

  @PostMapping("/svc/{svc}/inbox/process")
  @Operation(summary = "Exactly-once consume: lock the row, (business logic), delete, commit")
  public Map<String, Object> process(@PathVariable String svc, @RequestBody ProcessRequest req)
      throws Exception {
    return consumer.process(svc, req.eventType(), req.partition(), req.eventId(),
        req.stepId(), req.seq());
  }
}
