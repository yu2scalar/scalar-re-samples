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
import com.scalar.re.samples.Svcs;
import com.scalar.re.samples.config.ReConfig;
import com.scalar.re.samples.re.ReRestClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Consumer side of the demo.
 *
 * <p>Consumption is deliberately NOT a RE API call: per the consumer contract
 * (consumer-contract.md §2.2/§2.3) exactly-once consume is owned by the consumer
 * and done in a single native transaction on the same physical database as the
 * inbox — lock the row, do the business work, delete the row, commit. This class
 * is the teaching centerpiece of the sample.
 *
 * <p>pull / poll DO use the RE API: they move queued records into the inbox
 * (pull) or report which keys are available (poll) for the queue-based delivery
 * types.
 */
@Service
public class ConsumerService {

  private final ReConfig reConfig;
  private final ReRestClient re;

  public ConsumerService(ReConfig reConfig, ReRestClient re) {
    this.reConfig = reConfig;
    this.re = re;
  }

  /**
   * Exactly-once consume of a single selected inbox row, in one native TX on the
   * consumer's own database (the destination namespace = {@code svc}).
   */
  public Map<String, Object> process(String svc, String eventType, long partition,
      String eventId, int stepId, int seq) throws Exception {
    ReConfig.Storage db = reConfig.storageForNamespace(svc);
    String inbox = svc + ".re_inbox"; // schema-qualified

    String selectSql = "SELECT event_id FROM " + inbox
        + " WHERE event_type=? AND partition=? AND event_id=? AND step_id=? AND seq=?"
        + " AND tx_state=3"          // ScalarDB COMMITTED (hidden metadata column, not `status`)
        + " FOR UPDATE";             // claim the row for the duration of this TX
    String deleteSql = "DELETE FROM " + inbox
        + " WHERE event_type=? AND partition=? AND event_id=? AND step_id=? AND seq=?";

    try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
      conn.setAutoCommit(false);
      try {
        boolean present;
        try (PreparedStatement sel = conn.prepareStatement(selectSql)) {
          bindKey(sel, eventType, partition, eventId, stepId, seq);
          try (ResultSet rs = sel.executeQuery()) {
            present = rs.next();
          }
        }
        if (!present) {
          conn.rollback();
          return Map.of("processed", false, "reason", "row not found or not yet committed");
        }

        // ------------------------------------------------------------------
        // >>> Your business logic goes here <<<
        // Do the application work in THIS SAME transaction. Because the work
        // and the DELETE below commit atomically, processing is exactly-once
        // and needs no idempotency handling: if we crash before commit, the
        // row stays and is reprocessed; there is never a duplicate.
        // ------------------------------------------------------------------

        try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
          bindKey(del, eventType, partition, eventId, stepId, seq);
          del.executeUpdate();
        }
        conn.commit();
        return Map.of("processed", true, "eventId", eventId);
      } catch (Exception e) {
        conn.rollback();
        throw e;
      }
    }
  }

  /** Queue-based delivery: move queued records into this service's inbox. */
  public JsonNode pull(String svc, String deliveryType) {
    return re.pull(svc, Svcs.inboundEventType(svc, deliveryType), 100);
  }

  /** Report which inbox keys are available for this service (non-destructive). */
  public JsonNode poll(String svc, String deliveryType) {
    return re.poll(svc, Svcs.inboundEventType(svc, deliveryType), 100);
  }

  private static void bindKey(PreparedStatement ps, String eventType, long partition,
      String eventId, int stepId, int seq) throws Exception {
    ps.setString(1, eventType);
    ps.setLong(2, partition);
    ps.setString(3, eventId);
    ps.setInt(4, stepId);
    ps.setInt(5, seq);
  }
}
