/* Copyright 2026 Scalar Inc. Licensed under the Apache License, Version 2.0. */
// ScalarRE samples — minimal vanilla-JS front end.
// Builds one panel per service from the <template>, then polls the backend.

const api = (path, opts) => fetch("/api" + path, opts).then((r) => {
  if (!r.ok) return r.text().then((t) => Promise.reject(new Error(t || r.status)));
  return r.status === 204 ? null : r.json();
});
const post = (path, body) =>
  api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

const state = {}; // per-svc: { inboxRows: [], selected: null, el: {...} }

async function init() {
  const services = await api("/services");
  const host = document.getElementById("panels");
  const tpl = document.getElementById("panel-tpl");

  for (const svc of services) {
    const node = tpl.content.cloneNode(true);
    const panel = node.querySelector(".panel");
    panel.querySelector(".svc-title").textContent = svc.name.toUpperCase();
    panel.querySelector(".svc-sub").textContent =
      `sends to ${svc.destination} · ${svc.database}`;

    const delivery = panel.querySelector(".delivery");
    for (const dt of svc.deliveryTypes) {
      const opt = document.createElement("option");
      opt.value = dt;
      opt.textContent = dt;
      delivery.appendChild(opt);
    }

    const el = {
      compose: panel.querySelector(".compose"),
      delivery,
      outbox: panel.querySelector(".outbox"),
      transfer: panel.querySelector(".transfer"),
      inbox: panel.querySelector(".inbox"),
      detail: panel.querySelector(".detail"),
      process: panel.querySelector(".process"),
    };
    state[svc.name] = { inboxRows: [], selected: null, el };

    panel.querySelector(".send").onclick = () => send(svc.name);
    panel.querySelector(".pull").onclick = () => pull(svc.name);
    panel.querySelector(".poll").onclick = () => poll(svc.name);
    panel.querySelector(".refresh").onclick = () => refresh(svc.name);
    el.process.onclick = () => processMsg(svc.name);

    host.appendChild(node);
    refresh(svc.name);
  }

  // Poll modal close wiring (shared across panels)
  const modal = document.getElementById("poll-modal");
  document.getElementById("poll-close").onclick = () => modal.classList.add("hidden");
  modal.onclick = (e) => {
    if (e.target === modal) modal.classList.add("hidden");
  };

  setInterval(() => Object.keys(state).forEach(refresh), 3000);
}

async function send(svc) {
  const raw = state[svc].el.compose.value.trim();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    payload = { message: raw };
  }
  const deliveryType = state[svc].el.delivery.value;
  try {
    await post(`/svc/${svc}/send`, { deliveryType, payload });
    refresh(svc);
  } catch (e) {
    alert("Send failed: " + e.message);
  }
}

async function pull(svc) {
  try {
    await post(`/svc/${svc}/inbox/pull`, { deliveryType: "qpull" });
    refresh(svc);
  } catch (e) {
    alert("Pull failed: " + e.message);
  }
}

async function poll(svc) {
  try {
    const res = await post(`/svc/${svc}/inbox/poll`, {});
    showPollModal(svc, res);
  } catch (e) {
    alert("Poll failed: " + e.message);
  }
}

function showPollModal(svc, res) {
  document.getElementById("poll-title").textContent =
    `Poll — ${svc.toUpperCase()} available keys (${res.total})`;
  const ul = document.getElementById("poll-list");
  ul.innerHTML = "";
  (res.records || []).forEach((r) => {
    const li = document.createElement("li");
    li.textContent =
      `${r.event_type}  p${r.partition}  ${r.event_id}  step${r.step_id} seq${r.seq}`;
    ul.appendChild(li);
  });
  document.getElementById("poll-modal").classList.remove("hidden");
}

async function processMsg(svc) {
  const row = state[svc].selected;
  if (!row) return;
  try {
    await post(`/svc/${svc}/inbox/process`, {
      eventType: row.eventType,
      partition: row.partition,
      eventId: row.eventId,
      stepId: row.stepId,
      seq: row.seq,
    });
    state[svc].selected = null;
    state[svc].el.detail.textContent = "";
    state[svc].el.process.disabled = true;
    refresh(svc);
  } catch (e) {
    alert("Process failed: " + e.message);
  }
}

async function refresh(svc) {
  const el = state[svc].el;
  try {
    const [outbox, transfer, inbox] = await Promise.all([
      api(`/svc/${svc}/outbox`),
      api(`/svc/${svc}/transfer-state`),
      api(`/svc/${svc}/inbox`),
    ]);
    renderOutbox(el.outbox, outbox);
    renderTransfer(el.transfer, transfer);
    renderInbox(svc, inbox);
  } catch (e) {
    // transient during startup / restarts; ignore
  }
}

function renderOutbox(ul, rows) {
  ul.innerHTML = "";
  for (const r of rows) {
    const li = document.createElement("li");
    li.textContent = `${r.eventType}  ${r.eventId}`;
    ul.appendChild(li);
  }
}

function renderTransfer(ul, state) {
  ul.innerHTML = "";
  for (const r of state.pending || []) ul.appendChild(tagged("pending", `${r.eventType} ${r.eventId}`));
  for (const r of state.completed || [])
    ul.appendChild(tagged("done", `${r.eventType} ${r.eventId} → ${r.target}`));
}

function tagged(kind, text) {
  const li = document.createElement("li");
  const tag = document.createElement("span");
  tag.className = "tag" + (kind === "done" ? " done" : "");
  tag.textContent = kind;
  li.appendChild(tag);
  li.appendChild(document.createTextNode(text));
  return li;
}

function renderInbox(svc, rows) {
  const el = state[svc].el;
  state[svc].inboxRows = rows;
  el.inbox.innerHTML = "";
  rows.forEach((r, i) => {
    const li = document.createElement("li");
    li.textContent = `${r.eventType}  p${r.partition}  ${r.eventId}`;
    if (state[svc].selected && state[svc].selected.eventId === r.eventId) li.classList.add("selected");
    li.onclick = () => selectInbox(svc, i);
    el.inbox.appendChild(li);
  });
  // keep selection valid
  if (state[svc].selected && !rows.some((r) => r.eventId === state[svc].selected.eventId)) {
    state[svc].selected = null;
    el.detail.textContent = "";
    el.process.disabled = true;
  }
}

function selectInbox(svc, i) {
  const row = state[svc].inboxRows[i];
  state[svc].selected = row;
  state[svc].el.detail.textContent = JSON.stringify(row.parsedPayload, null, 2);
  state[svc].el.process.disabled = false;
  renderInbox(svc, state[svc].inboxRows);
}

init();
