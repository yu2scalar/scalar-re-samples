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

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Put;
import com.scalar.db.io.Key;
import com.scalar.re.samples.Svcs;
import com.scalar.re.samples.re.ReRestClient;
import com.scalar.re.sdk.builder.NotifyPayload;
import com.scalar.re.sdk.builder.ReEventBodyBuilder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Producer side of the demo. Sending a message is the transactional-outbox
 * pattern: the event body is written to the source service's re_outbox in a
 * ScalarDB transaction (so it is COMMITTED and RE will pick it up), then a
 * notify call tells RE to transfer it. RE delivers it to the destination
 * service's inbox with exactly-once semantics.
 */
@Service
public class ProducerService {

  private final DistributedTransactionManager txManager;
  private final ReRestClient re;
  private final SecureRandom random = new SecureRandom();

  public ProducerService(DistributedTransactionManager txManager, ReRestClient re) {
    this.txManager = txManager;
    this.re = re;
  }

  public Map<String, Object> send(String svc, String deliveryType, Object payload) throws Exception {
    String eventType = Svcs.outboundEventType(svc, deliveryType);
    String destination = Svcs.other(svc);
    String eventId = newEventId();

    // Build the event body (SDK). The routing destination is the other service.
    NotifyPayload np = ReEventBodyBuilder.create()
        .deliveryType(deliveryType)
        .addStep(step -> step.stepId(0)
            .addSequence(seq -> seq.seq(0)
                .routing(destination)
                .payload(payload)))
        .toNotifyPayload();

    // Write the outbox row in a ScalarDB transaction (COMMITTED => RE picks it up).
    DistributedTransaction tx = txManager.begin();
    try {
      Put put = Put.newBuilder()
          .namespace(svc)
          .table("re_outbox")
          .partitionKey(Key.ofText("event_type", eventType))
          .clusteringKey(Key.ofText("event_id", eventId))
          .textValue("body", np.body())
          .bigIntValue("created_at", System.currentTimeMillis())
          .build();
      tx.put(put);
      tx.commit();
    } catch (Exception e) {
      try {
        tx.abort();
      } catch (Exception ignore) {
        // best effort
      }
      throw e;
    }

    // Trigger RE to transfer the just-written outbox row.
    re.notify(svc, eventType, eventId, new ArrayList<>(np.routingNamespaces()));

    return Map.of(
        "svc", svc,
        "eventType", eventType,
        "eventId", eventId,
        "destination", destination,
        "deliveryType", deliveryType);
  }

  /** Time-ordered id so outbox/inbox scans list messages in creation order. */
  private String newEventId() {
    return String.format("%013d-%08x", System.currentTimeMillis(), random.nextInt());
  }
}
