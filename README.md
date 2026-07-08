# ScalarRE Samples

A small, runnable demo of exactly-once DB-to-DB messaging with
[ScalarRE](https://github.com/yu2scalar/scalar-re). Two services — **Svc1** and
**Svc2** — send messages to each other. ScalarRE moves each message from the
sender's outbox to the receiver's inbox exactly once; the receiver then consumes
it by locking and deleting the inbox row in a single transaction.

```
  Svc1                         RE                          Svc2
  ────                     ──────────                      ────
  compose ─Send─▶ re_outbox ─transfer▶ (re_queue) ─▶ re_inbox ─Process─▶ (deleted)
                                                        ▲
                                            Pull/Poll ──┘  (queue delivery)
```

- **Two delivery types**, chosen per message:
  - `atomic` — RE pushes the message straight to the destination inbox.
  - `qpull` — RE stages the message in a queue; the consumer **Pull**s it into the inbox.
- **Bidirectional** — Svc1 → Svc2 and Svc2 → Svc1.
- **GUI** at `http://localhost:8090` (two panels) and a **Swagger UI** at
  `http://localhost:8090/swagger-ui.html`.

## Prerequisites

- Docker (with Docker Compose)
- JDK 17+
- Linux is assumed (the RE container uses host networking so it and the
  host-side app share one config and reach the databases at `localhost:543x`).
  On Docker Desktop, enable host networking or adjust `config/scalar-re-config.yml`.

## Run

**1. Clone this repository:**

```bash
git clone https://github.com/yu2scalar/scalar-re-samples.git
cd scalar-re-samples
```

**2. Install the SDK into your local Maven repository.** The sample app depends
on `com.scalar:scalar-re-sdk`, which is not published to a remote Maven
repository yet, so build it locally once (cloned here as a sibling directory):

```bash
git clone https://github.com/yu2scalar/scalar-re-sdk.git ../scalar-re-sdk
(cd ../scalar-re-sdk && ./gradlew publishToMavenLocal)
```

**3. Start the databases and the RE server** (from this repo's root):

```bash
docker compose up -d
```

This starts three PostgreSQL databases (`re`/`svc1`/`svc2` on host ports
`5433`/`5434`/`5435`), runs the one-shot **schema initializer**, then starts the
**RE server** on `:8080`. The RE and init images are pulled from the public
ScalarRE container registry.

**4. Start the sample app:**

```bash
cd app
./gradlew bootRun
```

**5. Open the GUI:** <http://localhost:8090> — and the REST API at
<http://localhost:8090/swagger-ui.html>.

### Try it

1. In the **Svc1** panel, edit the JSON message, pick a delivery type, click **Send MSG**.
2. Watch it move: **Outbox** → **RE transfer state** (pending → completed).
3. In the **Svc2** panel, click **Refresh** (for `qpull`, click **Pull** first) — the
   message appears in the **Inbox**.
4. Click the inbox row to see the parsed message in **Detail**, then click
   **Process MSG** to consume it (lock &amp; delete).

> **Inbox vs Poll vs Pull.** The Inbox list is an operator-style scan that shows
> the full rows (including the body). **Poll (keys)** shows what a *remote*
> consumer sees instead — just the available keys, no body — which it would then
> use to read and consume each row by key. **Pull** applies to `qpull`: it moves
> queued messages into the inbox.

## How consume works (the important part)

Consuming a message is **not** a ScalarRE API call — by design, exactly-once
consume is owned by the consumer and runs as a single native transaction on the
same database as the inbox. See
[`ConsumerService.process`](app/src/main/java/com/scalar/re/samples/service/ConsumerService.java):

A real consumer drains its inbox with a worker that claims a batch per
`(event_type, partition)`, oldest first. `SKIP LOCKED` lets multiple workers
share the load without blocking each other, and `LIMIT` bounds how long each
transaction holds its locks:

```sql
BEGIN;                                            -- native TX on the consumer's DB (svc2db)
  SELECT event_id, step_id, seq, body FROM svc2.re_inbox
    WHERE event_type = ? AND partition = ?         -- one inbox partition
      AND tx_state = 3                             -- 3 = ScalarDB COMMITTED (hidden column)
    ORDER BY event_id, step_id, seq                -- oldest first (event_id is time-ordered)
    FOR UPDATE SKIP LOCKED                         -- skip rows another worker already holds
    LIMIT ?;                                       -- batch size: throughput vs. lock duration

  -- >>> your business logic goes here <<<
  --     Do the application work in THIS SAME transaction, for each claimed row.

  DELETE FROM svc2.re_inbox                        -- delete each claimed row by its full key
    WHERE event_type = ? AND partition = ?
      AND event_id = ? AND step_id = ? AND seq = ?;
COMMIT;                                            -- business work + delete commit atomically
```

**Index alignment matters.** The `re_inbox` primary key is `event_type,
partition, event_id, step_id, seq`. You don't have to use every key column, but
the columns you do use must form a **left-prefix** — the leading columns, in
order, with none skipped. Filtering on `event_type`, or `event_type, partition`,
uses the index; skipping a leading column (e.g. `partition` without
`event_type`) — or sorting off the key order — makes the index unusable and
forces a full table scan. The queries above keep `WHERE` and `ORDER BY` in key
order for exactly this reason. Off-index scans get dramatically slower as the
inbox grows, so always lead with `event_type` and go left-to-right.

Because the business work and the `DELETE` commit together, processing is
exactly-once and needs no idempotency handling: crash before commit and the rows
simply stay, to be reprocessed; there is never a duplicate.

> In this demo the **Process MSG** button consumes the one row you selected in
> the UI, so the code targets that exact primary key (no `ORDER BY` / `LIMIT`
> needed) — see `ConsumerService.process`. The batch form above is what a
> background worker would run.

Sending, in contrast, is the transactional-outbox pattern:
[`ProducerService.send`](app/src/main/java/com/scalar/re/samples/service/ProducerService.java)
writes the event to `re_outbox` in a ScalarDB transaction and then calls RE's
`notify` to trigger the transfer.

## Configuration

Everything is driven by [`config/scalar-re-config.yml`](config/scalar-re-config.yml)
— the same file the RE server loads. The sample app reads it too, to discover
the service database addresses and credentials. Credentials in this file are
weak demo placeholders; do not reuse them.

## Tear down

```bash
# stop the sample app: Ctrl+C in its terminal, then:
docker compose down          # stop & remove the RE server and databases
docker compose down -v       # ...and also delete the database volumes
```

Verify nothing is left: `docker ps -a`.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
