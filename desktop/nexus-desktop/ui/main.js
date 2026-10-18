// Nexus desktop UI.
//
// The backend owns all state; the webview just renders the snapshot returned
// by `get_status` and posts user actions. Polling keeps this file tiny and
// removes any need to reason about event ordering.

const invoke = window.__TAURI__.core.invoke;

const $ = (id) => document.getElementById(id);

// Controls that would be re-created every poll; keep references once.
const clipToggle = $("clip-toggle");

let pollTimer = null;
let lastLogLength = -1;
let busy = false;

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[c]);
}

function setText(id, value) {
  const el = $(id);
  if (el && el.textContent !== value) {
    el.textContent = value;
  }
}

async function act(name, args) {
  if (busy) return;
  busy = true;
  try {
    await invoke(name, args ?? {});
  } catch (err) {
    console.error(name, err);
  } finally {
    busy = false;
    await refresh();
  }
}

function renderDevice(status) {
  setText("d-name", status.device_name || "—");
  setText("d-id", status.device_id || "—");
  setText("d-platform", status.platform || "—");
  setText("d-port", status.port ? String(status.port) : "—");
  setText("d-discovery", status.discovery_state || "—");
  setText("d-datadir", status.data_dir || "—");
}

function renderBadge(status) {
  const badge = $("conn-badge");
  if (status.connection) {
    badge.textContent = "connected to " + status.connection.name;
    badge.className = "badge badge-online";
  } else {
    badge.textContent = "offline";
    badge.className = "badge badge-offline";
  }
}

function renderConnection(status) {
  const body = $("conn-body");
  const conn = status.connection;
  if (conn) {
    body.className = "";
    body.innerHTML =
      '<dl class="kv">' +
      "<dt>Peer</dt><dd>" + esc(conn.name) + "</dd>" +
      '<dt>Device ID</dt><dd class="mono small">' + esc(conn.device_id) + "</dd>" +
      "<dt>Platform</dt><dd>" + esc(conn.platform) + "</dd>" +
      "<dt>Role</dt><dd>" + esc(conn.role) + "</dd>" +
      "</dl>";
  } else {
    body.className = "empty";
    body.textContent = "Not connected.";
  }
  $("btn-disconnect").disabled = !conn;

  const hint = $("pairing-mode");
  if (status.pairing_mode) {
    hint.classList.remove("hidden");
    hint.textContent = "Pairing window is open — another device can send a pairing request.";
  } else {
    hint.classList.add("hidden");
  }
}

function renderClipboard(status) {
  clipToggle.checked = !!status.clipboard_enabled;
  $("clip-toggle-label").textContent = status.clipboard_enabled ? "Enabled" : "Disabled";
  setText("clip-status", status.clipboard_state || "—");
  setText("clip-preview", status.clipboard_preview ?? "(nothing yet)");
}

function peerRow(peer, status) {
  const pills = [];
  if (peer.connected) pills.push('<span class="pill pill-live">connected</span>');
  if (peer.trusted) pills.push('<span class="pill pill-ok">trusted</span>');
  if (!peer.compatible) pills.push('<span class="pill pill-warn">incompatible</span>');
  if (!peer.trusted && peer.compatible) pills.push('<span class="pill">not paired</span>');

  const buttons = [];
  if (peer.connected) {
    buttons.push(
      '<button class="btn btn-danger" data-act="disconnect">Disconnect</button>'
    );
  } else if (peer.trusted) {
    buttons.push(
      '<button class="btn btn-primary" data-act="connect" data-id="' +
        esc(peer.device_id) +
        '">Connect</button>'
    );
  } else if (peer.compatible) {
    buttons.push(
      '<button class="btn" data-act="pair" data-id="' +
        esc(peer.device_id) +
        '">Pair</button>'
    );
  }

  const row = document.createElement("div");
  row.className = "row";
  row.innerHTML =
    '<div class="meta">' +
    '<div class="name">' + esc(peer.name) + " " + pills.join(" ") + "</div>" +
    '<div class="sub">' + esc(peer.platform) + " · " + esc(peer.address) +
    ' · id ' + esc(peer.device_id.slice(0, 8)) + "</div>" +
    "</div>" +
    '<div class="buttons">' + buttons.join("") + "</div>";
  return row;
}

function renderPeers(status) {
  const container = $("peers");
  container.innerHTML = "";
  setText("peers-count", String(status.peers.length));
  if (status.peers.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent =
      "No Nexus devices seen yet. Make sure both devices are on the same network and the other app is open.";
    container.appendChild(empty);
    return;
  }
  for (const peer of status.peers) {
    container.appendChild(peerRow(peer, status));
  }
}

function renderTrusted(status) {
  const container = $("trusted");
  container.innerHTML = "";
  setText("trusted-count", String(status.trusted.length));
  if (status.trusted.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No paired devices yet.";
    container.appendChild(empty);
    return;
  }
  for (const device of status.trusted) {
    const row = document.createElement("div");
    row.className = "row";
    const paired = device.paired_at
      ? new Date(device.paired_at * 1000).toLocaleString()
      : "unknown";
    row.innerHTML =
      '<div class="meta">' +
      '<div class="name">' + esc(device.name) +
      ' <span class="pill pill-ok">trusted</span></div>' +
      '<div class="sub">' + esc(device.platform) + " · paired " + esc(paired) + "</div>" +
      '<div class="sub mono">' + esc(device.cert_sha256.slice(0, 32)) + "…</div>" +
      "</div>" +
      '<div class="buttons">' +
      '<button class="btn btn-danger" data-act="unpair" data-id="' +
      esc(device.device_id) + '">Forget</button>' +
      "</div>";
    container.appendChild(row);
  }
}

function renderLog(status) {
  const el = $("log");
  if (lastLogLength === status.log.length) return;
  lastLogLength = status.log.length;
  el.textContent = status.log.join("\n");
  el.scrollTop = el.scrollHeight;
}

function renderPrompt(status) {
  const overlay = $("prompt-overlay");
  const prompt = status.pairing_prompt;
  if (!prompt) {
    overlay.classList.add("hidden");
    return;
  }
  overlay.classList.remove("hidden");
  $("prompt-title").textContent = prompt.we_initiated
    ? "Confirm pairing with " + prompt.peer_name
    : prompt.peer_name + " wants to pair";
  $("prompt-code").textContent = prompt.code;
  $("prompt-sub").textContent = prompt.we_initiated
    ? "The other device should be showing this same code. If it does not, reject."
    : "Check that this matches the code on the other device before accepting.";
  $("btn-accept").dataset.token = prompt.token;
  $("btn-reject").dataset.token = prompt.token;
}

function renderFatal(status) {
  const el = $("fatal");
  if (status.error) {
    el.classList.remove("hidden");
    el.textContent = status.error;
  } else {
    el.classList.add("hidden");
  }
}

function render(status) {
  renderFatal(status);
  renderDevice(status);
  renderBadge(status);
  renderConnection(status);
  renderClipboard(status);
  renderPeers(status);
  renderTrusted(status);
  renderLog(status);
  renderPrompt(status);
}

async function refresh() {
  try {
    const status = await invoke("get_status");
    render(status);
  } catch (err) {
    console.error("get_status", err);
  }
}

function wire() {
  clipToggle.addEventListener("change", () =>
    act("set_clipboard_enabled", { enabled: clipToggle.checked })
  );
  $("btn-push").addEventListener("click", () => act("push_clipboard"));
  $("btn-allow").addEventListener("click", () => act("allow_pairing"));
  $("btn-disconnect").addEventListener("click", () => act("disconnect"));
  $("btn-accept").addEventListener("click", (e) =>
    act("respond_pairing", { token: e.target.dataset.token, accept: true })
  );
  $("btn-reject").addEventListener("click", (e) =>
    act("respond_pairing", { token: e.target.dataset.token, accept: false })
  );
  $("btn-clear-log").addEventListener("click", () => {
    const el = $("log");
    el.textContent = "";
    lastLogLength = -1;
  });

  document.body.addEventListener("click", (e) => {
    const button = e.target.closest("[data-act]");
    if (!button) return;
    const { act: action, id } = button.dataset;
    if (action === "pair") act("pair", { deviceId: id });
    else if (action === "connect") act("connect", { deviceId: id });
    else if (action === "unpair") act("unpair", { deviceId: id });
    else if (action === "disconnect") act("disconnect");
  });
}

wire();
refresh();
pollTimer = setInterval(refresh, 500);
