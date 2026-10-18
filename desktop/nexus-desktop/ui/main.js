// Nexus desktop UI — presentation layer only.
//
// The Rust backend owns all state. This file renders the snapshot returned by
// `get_status` and posts user actions; it never invents device state, metrics
// or settings that the backend does not actually have.

const invoke = window.__TAURI__.core.invoke;

/* ------------------------------------------------------------------ icons */

const ICONS = {
  home: '<path d="M3.5 10.5 12 4l8.5 6.5"/><path d="M5.5 9.4V20h13V9.4"/><path d="M10 20v-5h4v5"/>',
  devices:
    '<rect x="2.8" y="4.5" width="13" height="9.5" rx="1.6"/><path d="M6.5 18.5h6"/><path d="M9 14v4.5"/><rect x="16.8" y="9" width="4.6" height="10.5" rx="1.4"/>',
  clipboard:
    '<rect x="6" y="4.6" width="12" height="15" rx="2"/><path d="M9.6 4.6A2.4 2.4 0 0 1 12 2.9a2.4 2.4 0 0 1 2.4 1.7"/><path d="M9.6 4.6h4.8"/>',
  files:
    '<path d="M3.5 7.2A1.7 1.7 0 0 1 5.2 5.5h3.1l1.6 1.8h9.9a1.7 1.7 0 0 1 1.7 1.7v7.8a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7z"/>',
  bell: '<path d="M12 3.6a5 5 0 0 0-5 5v3.1L5.6 14.6h12.8L17 11.7V8.6a5 5 0 0 0-5-5z"/><path d="M10 17.6a2 2 0 0 0 4 0"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="M12 3.6v2M12 18.4v2M20.4 12h-2M5.6 12h-2M17.9 6.1l-1.4 1.4M7.5 16.5 6.1 17.9M17.9 17.9l-1.4-1.4M7.5 7.5 6.1 6.1"/>',
  monitor: '<rect x="3" y="5" width="18" height="11" rx="1.8"/><path d="M9 20h6"/><path d="M12 16v4"/>',
  phone: '<rect x="7" y="3" width="10" height="18" rx="2.2"/><path d="M11 18h2"/>',
  shield: '<path d="M12 3.6 19 6.1v5.3c0 4-2.9 7.4-7 8.6-4.1-1.2-7-4.6-7-8.6V6.1z"/><path d="m9 11.9 2 2 4-4"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V9h-4.5"/>',
  check: '<circle cx="12" cy="12" r="8.5"/><path d="m8.4 12 2.4 2.4 4.8-4.8"/>',
  alert: '<path d="M12 4.6 21 19.4H3z"/><path d="M12 10v4"/><path d="M12 16.6h.01"/>',
  x: '<path d="M6.6 6.6 17.4 17.4M17.4 6.6 6.6 17.4"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M15 9V6.6A2.6 2.6 0 0 0 12.4 4H6.6A2.6 2.6 0 0 0 4 6.6v5.8A2.6 2.6 0 0 0 6.6 15H9"/>',
  folder:
    '<path d="M3.5 7.2A1.7 1.7 0 0 1 5.2 5.5h3.1l1.6 1.8h9.9a1.7 1.7 0 0 1 1.7 1.7v7.8a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7z"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.6V12l3 2"/>',
  power: '<path d="M12 4v8"/><path d="M7.6 7a7 7 0 1 0 8.8 0"/>',
  link: '<path d="M9.5 14.5 14.5 9.5"/><path d="M8 12.2 6.6 13.6a3.5 3.5 0 0 0 5 5l1.4-1.4"/><path d="M16 11.8l1.4-1.4a3.5 3.5 0 0 0-5-5L11 6.8"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11.2v4.4"/><path d="M12 8.2h.01"/>',
  plus: '<path d="M12 5.6v12.8M5.6 12h12.8"/>',
};

function icon(name, cls) {
  const body = ICONS[name] || "";
  return `<svg class="${cls || "ic"}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
}

/* ---------------------------------------------------------------- helpers */

const $ = (sel) => document.querySelector(sel);

function esc(v) {
  return String(v == null ? "" : v).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[c]);
}

function cap(s) {
  s = String(s || "");
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

function platformName(p) {
  const k = String(p || "").toLowerCase();
  if (k.includes("android")) return "Android";
  if (k.includes("windows") || k === "win" || k === "win32") return "Windows";
  if (k.includes("mac") || k.includes("darwin")) return "macOS";
  if (k.includes("linux")) return "Linux";
  if (k.includes("ios")) return "iOS";
  return cap(p) || "Unknown";
}

function platformIcon(p) {
  const k = String(p || "").toLowerCase();
  return k.includes("android") || k.includes("ios") || k.includes("phone") ? "phone" : "monitor";
}

// Peer addresses arrive as "host:port"; the UI only shows the host.
function addrHost(addr) {
  const s = String(addr || "");
  const v4 = /^(\d{1,3}(?:\.\d{1,3}){3}):\d+$/.exec(s);
  if (v4) return v4[1];
  const v6 = /^\[(.+)\]:\d+$/.exec(s);
  if (v6) return v6[1];
  return s.replace(/:\d+$/, "");
}

function timeLabel(ms) {
  if (!ms) return "";
  const diff = Date.now() - ms;
  if (diff < 8000) return "just now";
  const s = Math.floor(diff / 1000);
  if (s < 60) return s + "s ago";
  const m = Math.floor(s / 60);
  if (m < 60) return m + " min ago";
  const h = Math.floor(m / 60);
  if (h < 24) return h === 1 ? "1 hour ago" : h + " hours ago";
  return clockLabel(ms);
}

function clockLabel(ms) {
  if (!ms) return "";
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function dateLabel(seconds) {
  if (!seconds) return "";
  return new Date(seconds * 1000).toLocaleDateString();
}

/* ------------------------------------------------------------------ state */

const NAV = [
  { id: "home", label: "Home", icon: "home" },
  { id: "devices", label: "Devices", icon: "devices" },
  { id: "clipboard", label: "Clipboard", icon: "clipboard" },
  { id: "files", label: "Files", icon: "files", soon: true },
  { id: "notifications", label: "Notifications", icon: "bell", soon: true },
  { id: "settings", label: "Settings", icon: "settings" },
];

const PAGES = {
  home: { title: "Nexus", sub: "Your devices, connected locally." },
  devices: { title: "Devices", sub: "Manage devices connected to Nexus." },
  clipboard: { title: "Clipboard", sub: "Keep text copied on your devices in sync." },
  files: { title: "Files", sub: "Secure local file transfer is planned for a future release." },
  notifications: { title: "Notifications", sub: "Notifications are planned for a future release." },
  settings: { title: "Settings", sub: "Preferences for this Nexus device." },
};

const state = {
  status: null,
  page: "home",
  devicesTab: "discovered",
  pending: {}, // device_id -> { action, at }
  pairingStartedAt: null,
  clearedActivityAt: 0,
  devOpen: false,
};

let lastSig = null;

/* ------------------------------------------------------------------ shell */

function renderNav() {
  const nav = $("#nav");
  nav.innerHTML =
    '<p class="nav-label">Navigation</p>' +
    NAV.map(
      (n) => `<button class="nav-item${state.page === n.id ? " active" : ""}" data-page="${n.id}" type="button">
        ${icon(n.icon)}
        <span class="nav-text">${esc(n.label)}</span>
        ${n.soon ? '<span class="soon">Soon</span>' : ""}
      </button>`
    ).join("");
}

function renderHeader() {
  const meta = PAGES[state.page] || PAGES.home;
  $("#page-title").textContent = meta.title;
  $("#page-sub").textContent = meta.sub;
  const chip = $("#header-status");
  if (state.status && state.status.connection) {
    chip.className = "status-chip is-on";
    chip.innerHTML = '<span class="dot dot-green"></span>Connected';
  } else {
    chip.className = "status-chip";
    chip.innerHTML = '<span class="dot dot-muted"></span>No active connection';
  }
}

function renderFatal() {
  const bar = $("#fatal");
  const err = state.status && state.status.error;
  if (err) {
    bar.hidden = false;
    bar.innerHTML = icon("alert", "ic-sm") + "<span>" + esc(err) + "</span>";
  } else {
    bar.hidden = true;
  }
}

/* ---------------------------------------------------------------- widgets */

function btn(label, act, o) {
  o = o || {};
  const attrs = [`class="${o.cls || "btn"}"`, 'type="button"', `data-act="${act}"`];
  if (o.id) attrs.push(`data-id="${esc(o.id)}"`);
  if (o.disabled) attrs.push("disabled");
  return `<button ${attrs.join(" ")}>${o.icon ? icon(o.icon, "ic-sm") : ""}${esc(label)}</button>`;
}

function emptyState(title, sub) {
  return `<div class="empty"><div class="empty-title">${esc(title)}</div><div class="empty-sub">${esc(sub)}</div></div>`;
}

function dot(kind) {
  return `<span class="dot dot-${kind}"></span>`;
}

function clipToggle(checked) {
  return `<label class="switch"><input type="checkbox" data-clip-toggle${checked ? " checked" : ""}><span class="track"><span class="thumb"></span></span></label>`;
}

function peerStatus(p) {
  if (p.connected) return { label: "Connected", kind: "green" };
  if (!p.compatible) return { label: "Incompatible", kind: "red" };
  if (p.trusted) return { label: "Trusted", kind: "green-soft" };
  return { label: "New", kind: "amber" };
}

function peerActions(p) {
  const pending = state.pending[p.device_id];
  const busy = pending ? pending.action : null;
  if (p.connected) {
    return btn(busy ? "Disconnecting…" : "Disconnect", "disconnect", {
      cls: "btn btn-danger-outline",
      icon: "power",
      disabled: !!busy,
    });
  }
  if (p.trusted && p.compatible) {
    return btn(busy ? "Connecting…" : "Connect", "connect", {
      cls: "btn btn-primary",
      icon: "link",
      id: p.device_id,
      disabled: !!busy,
    });
  }
  if (p.compatible) {
    return btn(busy ? "Pairing…" : "Pair", "pair", {
      cls: "btn btn-primary",
      icon: "plus",
      id: p.device_id,
      disabled: !!busy,
    });
  }
  return '<span class="muted small">Not supported</span>';
}

function deviceRow(o) {
  return `<div class="device-row">
    <div class="device-row-icon">${icon(o.iconName)}</div>
    <div class="device-row-main">
      <div class="device-row-name">${esc(o.name)}</div>
      <div class="device-row-sub">${esc(o.sub)}</div>
      ${o.meta ? `<div class="device-row-meta">${esc(o.meta)}</div>` : ""}
    </div>
    <div class="device-row-state"><span class="state">${dot(o.stateKind)}${esc(o.stateLabel)}</span></div>
    <div class="device-row-actions">${o.actions}</div>
  </div>`;
}

function discoveredRows(s) {
  const peers = s.peers || [];
  if (!peers.length)
    return emptyState(
      "No devices found",
      "Make sure Nexus is open on the other device and both are on the same network."
    );
  return peers
    .map((p) => {
      const st = peerStatus(p);
      const sub = [platformName(p.platform), addrHost(p.address)].filter(Boolean).join(" · ");
      return deviceRow({
        iconName: platformIcon(p.platform),
        name: p.name,
        sub,
        stateLabel: st.label,
        stateKind: st.kind,
        actions: peerActions(p),
      });
    })
    .join("");
}

function trustedRows(s) {
  const devices = s.trusted || [];
  if (!devices.length)
    return emptyState("No trusted devices", "Pair a device to add it here.");
  const byId = {};
  (s.peers || []).forEach((p) => (byId[p.device_id] = p));
  return devices
    .map((d) => {
      const p = byId[d.device_id];
      const connected = !!(p && p.connected);
      const meta = [];
      if (d.paired_at) meta.push("Paired " + dateLabel(d.paired_at));
      if (d.cert_sha256) meta.push("Fingerprint " + d.cert_sha256.slice(0, 16) + "…");

      let actions = "";
      if (connected) {
        actions += btn("Disconnect", "disconnect", { cls: "btn btn-danger-outline", icon: "power" });
      } else if (p) {
        actions += btn("Connect", "connect", { cls: "btn btn-primary", icon: "link", id: d.device_id });
      }
      actions += btn("Forget", "unpair", { cls: "btn", icon: "trash" });

      return deviceRow({
        iconName: platformIcon(d.platform),
        name: d.name,
        sub: platformName(d.platform),
        stateLabel: connected ? "Connected" : "Trusted",
        stateKind: connected ? "green" : "green-soft",
        meta: meta.join(" · "),
        actions,
      });
    })
    .join("");
}

function deviceTabs(nearbyLabel) {
  const s = state.status || {};
  const counts = { discovered: (s.peers || []).length, trusted: (s.trusted || []).length };
  const tab = (id, label) =>
    `<button class="tab${state.devicesTab === id ? " active" : ""}" data-tab="${id}" type="button">${esc(label)}<span class="tab-count">${counts[id]}</span></button>`;
  return `<div class="tabs">${tab("discovered", nearbyLabel || "Discovered")}${tab("trusted", "Trusted")}</div>`;
}

function devicesBlock(s, heading, nearbyLabel) {
  return `<section class="card">
    <div class="card-head">
      ${heading ? `<h3>${esc(heading)}</h3>` : "<span></span>"}
      <button class="btn btn-ghost btn-sm" data-act="refresh" type="button">${icon("refresh", "ic-sm")}Refresh</button>
    </div>
    ${deviceTabs(nearbyLabel)}
    <div class="device-list">${state.devicesTab === "trusted" ? trustedRows(s) : discoveredRows(s)}</div>
  </section>`;
}

function activityList(list) {
  const items = (list || []).filter((a) => a.at > state.clearedActivityAt);
  if (!items.length)
    return emptyState("Nothing yet", "Activity appears here as Nexus connects and syncs.");
  return (
    '<ul class="activity">' +
    items
      .slice()
      .reverse()
      .map((a) => {
        const lvl = a.level === "success" || a.level === "warn" || a.level === "error" ? a.level : "info";
        const glyph = lvl === "success" ? "check" : lvl === "error" || lvl === "warn" ? "alert" : "info";
        return `<li class="activity-item">
          <span class="activity-icon activity-${lvl}">${icon(glyph, "ic-sm")}</span>
          <span class="activity-text">${esc(a.text)}</span>
          <span class="activity-time">${esc(clockLabel(a.at))}</span>
        </li>`;
      })
      .join("") +
    "</ul>"
  );
}

/* -------------------------------------------------------------- fragments */

function heroCard(s) {
  const conn = s.connection;
  if (!conn) {
    return `<section class="card hero hero-empty">
      <div class="hero-empty-body">
        <div class="empty-icon">${icon("link")}</div>
        <h2>No device connected</h2>
        <p class="muted small">Connect a trusted device to get started.</p>
        <button class="btn btn-primary" data-page="devices" type="button">${icon("search", "ic-sm")}Find devices</button>
      </div>
    </section>`;
  }
  const peer = (s.peers || []).find((p) => p.device_id === conn.device_id);
  const meta = [platformName(conn.platform), peer ? addrHost(peer.address) : ""].filter(Boolean).join(" · ");
  const clipOn = !!s.clipboard_enabled;
  const clipSub = clipOn
    ? "Synced" + (s.clipboard_last_at ? " · " + timeLabel(s.clipboard_last_at) : "")
    : "Paused";

  return `<section class="card hero">
    <div class="hero-main">
      <div class="hero-icon">${icon(platformIcon(conn.platform))}</div>
      <div class="hero-id">
        <div class="eyebrow">Connected to</div>
        <h2 class="hero-name">${esc(conn.name)}</h2>
        <div class="hero-meta">${esc(meta)}</div>
        <div class="hero-badges">
          <span class="badge badge-green">${dot("green")}Connected</span>
          <span class="badge badge-neutral">${icon("shield", "ic-sm")}Trusted</span>
        </div>
      </div>
    </div>
    <div class="hero-side">
      <div class="info-row">
        <div class="info-label"><span class="info-title">Clipboard sync</span><span class="info-sub">${esc(clipSub)}</span></div>
        ${clipToggle(clipOn)}
      </div>
      <div class="info-row">
        <div class="info-label"><span class="info-title">Secure connection</span><span class="info-sub">Certificate pinned</span></div>
        <span class="info-value">${icon("shield", "ic-sm")}TLS 1.3</span>
      </div>
      <div class="hero-actions">
        ${btn("Disconnect", "disconnect", { cls: "btn btn-danger-outline", icon: "power" })}
      </div>
    </div>
  </section>`;
}

function quickCards(s) {
  const connected = !!s.connection;
  const clipOn = !!s.clipboard_enabled;

  const deviceCard = `<article class="card mini">
    <div class="mini-head"><span class="mini-title">This device</span></div>
    <div class="identity">
      <div class="identity-icon">${icon("monitor")}</div>
      <div>
        <div class="identity-name">${esc(s.device_name || "—")}</div>
        <div class="muted small">${esc(platformName(s.platform))}</div>
      </div>
    </div>
    <div class="kv-list">
      <div class="kv-row"><span class="kv-k">Device ID</span><span class="kv-v mono truncate">${esc(s.device_id || "—")}<button class="icon-btn" data-act="copy-id" data-value="${esc(s.device_id || "")}" title="Copy device ID">${icon("copy", "ic-sm")}</button></span></div>
      <div class="kv-row"><span class="kv-k">Listening</span><span class="kv-v mono">${s.port ? "0.0.0.0:" + s.port : "—"}</span></div>
    </div>
  </article>`;

  let clipState = "Waiting for connection";
  let clipKind = "amber";
  if (!clipOn) {
    clipState = "Paused";
    clipKind = "muted";
  } else if (connected) {
    clipState = "Synced";
    clipKind = "green";
  }
  const clipCard = `<article class="card mini">
    <div class="mini-head"><span class="mini-title">Clipboard</span></div>
    <div class="status-line">${dot(clipKind)}<span class="status-text">${esc(clipState)}</span></div>
    <div class="muted small">${s.clipboard_last_at ? "Last synced " + esc(timeLabel(s.clipboard_last_at)) : clipOn ? "No clipboard synced yet" : "Sync is turned off"}</div>
    <div class="mini-actions">${btn("Send clipboard", "push-clipboard", { cls: "btn btn-sm", icon: "clipboard", disabled: !connected || !clipOn })}</div>
  </article>`;

  const securityCard = `<article class="card mini">
    <div class="mini-head"><span class="mini-title">Security</span></div>
    ${
      connected
        ? `<div class="status-line">${dot("green")}<span class="status-text">Trusted</span></div>
           <div class="muted small">Encrypted connection</div>
           <div class="mini-kv">${icon("shield", "ic-sm")}<span>TLS 1.3</span><span class="muted">· certificate pinned</span></div>`
        : `<div class="status-line">${dot("muted")}<span class="status-text">No active session</span></div>
           <div class="muted small">Security is established when a trusted device connects.</div>`
    }
  </article>`;

  return deviceCard + clipCard + securityCard;
}

function pairingCard(s) {
  const active = !!s.pairing_mode;
  let body;
  if (active) {
    const secs = state.pairingStartedAt
      ? Math.max(0, 60 - Math.floor((Date.now() - state.pairingStartedAt) / 1000))
      : 60;
    body = `<div class="pairing-live">
      <div class="pairing-live-main">${dot("amber")}<span class="pairing-live-title">Pairing available</span></div>
      <div class="pairing-live-sub">${secs} second${secs === 1 ? "" : "s"} remaining</div>
      <div class="progress"><span style="width:${Math.round((secs / 60) * 100)}%"></span></div>
    </div>`;
  } else {
    body = btn("Allow new pairing", "allow-pairing", { cls: "btn btn-strong", icon: "link" });
  }
  return `<section class="card">
    <div class="card-head">
      <div class="card-head-text">
        <h3>Pair a new device</h3>
        <p class="muted small">Allow another Nexus device to securely pair with this computer.</p>
      </div>
    </div>
    ${body}
  </section>`;
}

function activityCard(s) {
  return `<section class="card">
    <div class="card-head">
      <h3>Recent activity</h3>
      <button class="btn btn-ghost btn-sm" data-act="clear-activity" type="button">Clear</button>
    </div>
    ${activityList(s.activities)}
  </section>`;
}

/* ------------------------------------------------------------------ pages */

function renderHome(s) {
  return (
    heroCard(s) +
    `<div class="grid-3">${quickCards(s)}</div>` +
    pairingCard(s) +
    devicesBlock(s, "Devices", "Discovered") +
    activityCard(s)
  );
}

function renderDevices(s) {
  return devicesBlock(s, null, "Nearby");
}

function renderClipboardPage(s) {
  const connected = !!s.connection;
  const on = !!s.clipboard_enabled;
  const last = s.clipboard_last_at ? timeLabel(s.clipboard_last_at) : "";
  const preview = s.clipboard_preview;
  const clipActivities = (s.activities || []).filter((a) => /clipboard/i.test(a.text));

  return `<section class="card">
    <div class="card-head">
      <div class="card-head-text">
        <h3>Clipboard Sync</h3>
        <p class="muted small">Plain text copied on one device can be pasted on the other.</p>
      </div>
      <span class="badge ${on ? "badge-green" : "badge-neutral"}">${dot(on ? "green" : "muted")}${on ? "Enabled" : "Disabled"}</span>
    </div>
    <div class="kv-list">
      <div class="kv-row"><span class="kv-k">Connected device</span><span class="kv-v">${connected ? esc(s.connection.name) : '<span class="muted">None</span>'}</span></div>
      <div class="kv-row"><span class="kv-k">Last synced</span><span class="kv-v">${last ? esc(last) : '<span class="muted">—</span>'}</span></div>
      <div class="kv-row"><span class="kv-k">Last value</span><span class="kv-v mono truncate">${preview ? "“" + esc(preview) + "”" : '<span class="muted">(nothing yet)</span>'}</span></div>
    </div>
    <div class="row-actions">
      ${btn("Send clipboard now", "push-clipboard", { icon: "clipboard", disabled: !connected || !on })}
      <div class="switch-row"><span class="muted small">Clipboard synchronization</span>${clipToggle(on)}</div>
    </div>
    ${!connected ? `<p class="note">${icon("info", "ic-sm")}Clipboard sync is waiting for a device to connect.</p>` : ""}
  </section>
  <section class="card">
    <div class="card-head"><h3>Recent clipboard activity</h3></div>
    ${activityList(clipActivities)}
  </section>`;
}

function renderSettings(s) {
  return `<section class="card">
    <div class="card-head"><h3>General</h3></div>
    <div class="kv-list">
      <div class="kv-row"><span class="kv-k">Device name</span><span class="kv-v">${esc(s.device_name || "—")}</span></div>
      <div class="kv-row"><span class="kv-k">Platform</span><span class="kv-v">${esc(platformName(s.platform))}</span></div>
      <div class="kv-row"><span class="kv-k">Device ID</span><span class="kv-v mono truncate">${esc(s.device_id || "—")}<button class="icon-btn" data-act="copy-id" data-value="${esc(s.device_id || "")}" title="Copy device ID">${icon("copy", "ic-sm")}</button></span></div>
      <div class="kv-row"><span class="kv-k">Listening on</span><span class="kv-v mono">${s.port ? "0.0.0.0:" + s.port : "—"}</span></div>
      <div class="kv-row"><span class="kv-k">Discovery</span><span class="kv-v">${dot(s.discovery_state === "browsing" ? "green" : "amber")}${esc(cap(s.discovery_state) || "—")}</span></div>
      <div class="kv-row"><span class="kv-k">Data directory</span><span class="kv-v mono truncate">${esc(s.data_dir || "—")}</span></div>
    </div>
  </section>
  <section class="card">
    <div class="card-head"><h3>Clipboard</h3></div>
    <div class="switch-row">
      <div><div>Clipboard synchronization</div><div class="muted small">Share plain text between this computer and its peer.</div></div>
      ${clipToggle(!!s.clipboard_enabled)}
    </div>
  </section>
  <section class="card">
    <div class="card-head"><h3>Security</h3></div>
    <div class="kv-list">
      <div class="kv-row"><span class="kv-k">Trusted devices</span><span class="kv-v">${(s.trusted || []).length} paired</span></div>
    </div>
    <div class="row-actions">${btn("Manage trusted devices", "goto-trusted", { icon: "shield" })}</div>
  </section>
  <section class="card">
    <div class="card-head"><h3>About</h3></div>
    <div class="about">
      <div class="about-name">Nexus</div>
      <div class="muted small">Version 0.1.0 · Local-first device companion</div>
      <div class="muted small">No cloud connection — all traffic stays on your local network.</div>
    </div>
  </section>
  <section class="card">
    <details class="dev-logs"${state.devOpen ? " open" : ""}>
      <summary>${icon("settings", "ic-sm")}Developer logs</summary>
      <div class="row-actions"><button class="btn btn-ghost btn-sm" data-act="clear-logs" type="button">Clear view</button></div>
      <pre class="log">${esc((s.log || []).join("\n"))}</pre>
    </details>
  </section>`;
}

function renderPlaceholder(page) {
  const isFiles = page === "files";
  return `<section class="card placeholder">
    <div class="placeholder-icon">${icon(isFiles ? "files" : "bell")}</div>
    <h3>${isFiles ? "Files" : "Notifications"}</h3>
    <p class="muted small">${
      isFiles
        ? "Secure local file transfer is planned for a future Nexus release. It is not implemented yet."
        : "Notifications are planned for a future Nexus release. They are not implemented yet."
    }</p>
    <span class="soon-note">Coming soon</span>
  </section>`;
}

/* ----------------------------------------------------------------- render */

function render() {
  renderNav();
  renderHeader();
  renderFatal();

  const content = $("#content");
  const s = state.status;
  if (!s) {
    content.innerHTML = '<div class="loading">Connecting to Nexus…</div>';
    return;
  }

  if (s.pairing_mode) {
    if (!state.pairingStartedAt) state.pairingStartedAt = Date.now();
  } else {
    state.pairingStartedAt = null;
  }

  let html;
  switch (state.page) {
    case "devices":
      html = renderDevices(s);
      break;
    case "clipboard":
      html = renderClipboardPage(s);
      break;
    case "settings":
      html = renderSettings(s);
      break;
    case "files":
    case "notifications":
      html = renderPlaceholder(state.page);
      break;
    default:
      html = renderHome(s);
  }

  const scrollTop = content.scrollTop;
  content.innerHTML = html;
  content.scrollTop = scrollTop;

  renderModal(s);
}

function renderModal(s) {
  const root = $("#modal");
  const p = s.pairing_prompt;
  if (!p) {
    root.hidden = true;
    root.innerHTML = "";
    return;
  }
  const peer = (s.peers || []).find((x) => x.device_id === p.peer_device_id);
  const platform = peer ? platformName(peer.platform) : "";
  root.hidden = false;
  root.innerHTML = `<div class="dialog" role="dialog" aria-modal="true">
    <div class="dialog-head"><h2>${p.we_initiated ? "Confirm pairing" : "Pairing request"}</h2></div>
    <div class="dialog-device">
      <div class="device-row-icon">${icon(peer ? platformIcon(peer.platform) : "phone")}</div>
      <div>
        <div class="dialog-device-name">${esc(p.peer_name)}</div>
        ${platform ? `<div class="muted small">${esc(platform)}</div>` : ""}
      </div>
    </div>
    <p class="muted small">Verify that this code matches the code shown on the other device.</p>
    <div class="code">${esc(p.code)}</div>
    <div class="dialog-actions">
      <button class="btn" data-act="reject-pair" data-token="${esc(p.token)}" type="button">Reject</button>
      <button class="btn btn-primary" data-act="accept-pair" data-token="${esc(p.token)}" type="button">${icon("check", "ic-sm")}Accept</button>
    </div>
  </div>`;
}

/* ------------------------------------------------------------------ toasts */

function toast(message, level) {
  const host = $("#toasts");
  const node = document.createElement("div");
  node.className = "toast toast-" + (level || "info");
  const glyph = level === "success" ? "check" : level === "info" ? "info" : "alert";
  node.innerHTML = icon(glyph, "ic-sm") + "<span>" + esc(message) + "</span>";
  host.appendChild(node);
  setTimeout(() => {
    node.style.transition = "opacity .2s ease";
    node.style.opacity = "0";
    setTimeout(() => node.remove(), 220);
  }, 3400);
}

/* ------------------------------------------------------------------ data */

async function refresh() {
  let s;
  try {
    s = await invoke("get_status");
  } catch (err) {
    console.error("get_status", err);
    return;
  }
  state.status = s;

  const now = Date.now();
  for (const id of Object.keys(state.pending)) {
    if (now - state.pending[id].at > 12000) delete state.pending[id];
  }
  if (s.connection) delete state.pending[s.connection.device_id];

  const sig = JSON.stringify([s, state.page, state.devicesTab, state.pending]);
  if (sig !== lastSig) {
    lastSig = sig;
    render();
  }
}

/* ----------------------------------------------------------------- actions */

async function act(name, args) {
  try {
    await invoke(name, args || {});
  } catch (err) {
    console.error(name, err);
    toast("That action could not be completed.", "error");
    return;
  }
  await refresh();
}

function markPending(id, action) {
  state.pending[id] = { action, at: Date.now() };
}

async function copyText(text, label) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const area = document.createElement("textarea");
      area.value = text;
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      document.execCommand("copy");
      area.remove();
    }
    toast(label + " copied", "success");
  } catch (err) {
    console.error("copy", err);
    toast("Could not copy to the clipboard.", "error");
  }
}

function navigate(page) {
  if (!(page in PAGES)) return;
  state.page = page;
  render();
  $("#content").scrollTop = 0;
}

/* ------------------------------------------------------------------ wiring */

document.addEventListener("click", async (e) => {
  const nav = e.target.closest("[data-page]");
  if (nav) {
    navigate(nav.dataset.page);
    return;
  }

  const tab = e.target.closest("[data-tab]");
  if (tab) {
    state.devicesTab = tab.dataset.tab;
    render();
    return;
  }

  const target = e.target.closest("[data-act]");
  if (!target || target.dataset.act === "toggle-clip") return;
  const action = target.dataset.act;
  const id = target.dataset.id;

  switch (action) {
    case "disconnect":
      toast("Disconnecting…", "info");
      await act("disconnect");
      break;
    case "connect":
      markPending(id, "connecting");
      render();
      await act("connect", { deviceId: id });
      break;
    case "pair":
      markPending(id, "pairing");
      render();
      await act("pair", { deviceId: id });
      break;
    case "unpair":
      await act("unpair", { deviceId: id });
      toast("Device forgotten", "info");
      break;
    case "allow-pairing":
      await act("allow_pairing");
      break;
    case "push-clipboard":
      await act("push_clipboard");
      toast("Sending clipboard…", "info");
      break;
    case "accept-pair":
      await act("respond_pairing", { token: target.dataset.token, accept: true });
      break;
    case "reject-pair":
      await act("respond_pairing", { token: target.dataset.token, accept: false });
      break;
    case "copy-id":
      await copyText(target.dataset.value || "", "Device ID");
      break;
    case "refresh":
      await refresh();
      toast("Refreshed", "info");
      break;
    case "clear-activity":
      state.clearedActivityAt = Date.now();
      render();
      break;
    case "clear-logs": {
      const pre = document.querySelector(".log");
      if (pre) pre.textContent = "";
      break;
    }
    case "goto-trusted":
      state.devicesTab = "trusted";
      navigate("devices");
      break;
    default:
      break;
  }
});

document.addEventListener("change", (e) => {
  const input = e.target.closest("[data-clip-toggle]");
  if (!input) return;
  const enabled = input.checked;
  if (state.status) state.status.clipboard_enabled = enabled; // optimistic
  render();
  act("set_clipboard_enabled", { enabled });
});

document.addEventListener("toggle", (e) => {
  const d = e.target;
  if (d && d.classList && d.classList.contains("dev-logs")) state.devOpen = d.open;
}, true);

/* -------------------------------------------------------------------- boot */

render();
refresh();
setInterval(refresh, 500);
// Keep relative timestamps ("just now", "2 min ago") honest without a full
// re-render: only refresh when the snapshot is unchanged but time has passed.
setInterval(() => {
  lastSig = null; // force exactly one re-render per interval
  refresh();
}, 20000);
