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
import java.util.ArrayList;
import java.util.List;
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

    // This sample consumes ONE row picked in the UI, so it targets the exact
    // primary key -- no ORDER BY / LIMIT is needed, and FOR UPDATE alone is
    // enough (a concurrent consumer of the same key simply waits, then finds
    // the row already deleted).
    //
    // INDEX ALIGNMENT: the WHERE lists the full primary key in key order
    // (event_type, partition, event_id, step_id, seq), so this is a point
    // lookup on the primary-key index. You need not use every key column, but
    // the ones you use must form a LEFT-PREFIX (leading columns, in order, none
    // skipped): event_type, or event_type+partition, etc. Skipping a leading
    // column -- or sorting off key order -- disables the index and forces a
    // full table scan, which is very costly once the inbox holds many rows.
    //
    // A background worker that periodically drains the inbox oldest-first would
    // instead claim a batch per (event_type, partition), e.g.:
    //
    //   SELECT event_id, step_id, seq, body FROM <ns>.re_inbox
    //     WHERE event_type = ? AND partition = ? AND tx_state = 3
    //     ORDER BY event_id, step_id, seq   -- oldest first (event_id is time-ordered)
    //     FOR UPDATE SKIP LOCKED            -- skip rows another worker already holds
    //     LIMIT ?;                          -- batch size: throughput vs. lock duration
    //   -- ... business logic ...
    //   -- DELETE each claimed row by its full key, then COMMIT.
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

  /**
   * Report which inbox keys are available for this service, across all inbound
   * event types (non-destructive). This is what a REMOTE consumer sees: keys
   * only, no body — it would then read/consume each row by key. It contrasts
   * with this demo's Inbox list, which is an operator-style scan that shows the
   * full rows including the body.
   */
  public Map<String, Object> pollKeys(String svc) {
    List<JsonNode> records = new ArrayList<>();
    for (String dt : Svcs.DELIVERY_TYPES) {
      JsonNode res = re.poll(svc, Svcs.inboundEventType(svc, dt), 100);
      JsonNode recs = res == null ? null : res.get("records");
      if (recs != null && recs.isArray()) {
        recs.forEach(records::add);
      }
    }
    return Map.of("total", records.size(), "records", records);
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
