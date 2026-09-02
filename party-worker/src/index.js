/**
 * Realmscry party relay.
 *
 * A party is a Durable Object room addressed by a short join code. Each
 * member's tracker connects over WebSocket and publishes its own loot /
 * boss events; the room stamps them with the sender and broadcasts them to
 * everyone else. The last HISTORY_MAX events are kept so a member joining
 * mid-session still sees recent drops.
 *
 * Routes:
 *   POST /party                  -> { code }         (new random party code)
 *   GET  /party/{code}/ws?name=X&id=Y   (WebSocket upgrade; id = stable
 *                                        per-install uuid, name = display name)
 *
 * Client->server frames (JSON text, <= 16 KB):
 *   { t: "loot", ... }   { t: "boss", ... }   { t: "ping" }
 * Server->client frames:
 *   { t: "history", events: [...] }        on join
 *   { t: "members", members: [{id,name}] } on any join/leave
 *   { t: "loot"|"boss", from, fromId, ...} relayed events (also echoed to
 *                                          sender so all UIs share one path)
 *   { t: "pong" }
 */

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no 0/O/1/I/L/U
const CODE_LEN = 6;
const MAX_MEMBERS = 10;
const MAX_FRAME = 16 * 1024;
const HISTORY_MAX = 100;
const ALLOWED_TYPES = new Set(["loot", "boss"]);

function genCode() {
  const buf = new Uint8Array(CODE_LEN);
  crypto.getRandomValues(buf);
  let s = "";
  for (const b of buf) s += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return s;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "") {
      return new Response("Realmscry party relay — see github.com/iliasdecraene/Realmscry\n");
    }
    if (url.pathname === "/party" && req.method === "POST") {
      return json({ code: genCode() });
    }
    const m = url.pathname.match(/^\/party\/([A-Za-z0-9]{4,12})\/ws$/);
    if (m) {
      const code = m[1].toUpperCase();
      const id = env.PARTY.idFromName(code);
      return env.PARTY.get(id).fetch(req);
    }
    return new Response("not found\n", { status: 404 });
  },
};

export class Party {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch(req) {
    if (req.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket\n", { status: 426 });
    }
    if (this.ctx.getWebSockets().length >= MAX_MEMBERS) {
      return new Response("party full\n", { status: 409 });
    }
    const url = new URL(req.url);
    const name = (url.searchParams.get("name") || "Unknown").slice(0, 24);
    const id = (url.searchParams.get("id") || crypto.randomUUID()).slice(0, 40);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    // Hibernation-friendly: attachment survives eviction, handlers below
    // are invoked without keeping the object pinned in memory.
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ id, name });

    await this.sendHistory(server);
    this.broadcastMembers();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > MAX_FRAME) return;
    let ev;
    try {
      ev = JSON.parse(msg);
    } catch {
      return;
    }
    if (ev == null || typeof ev !== "object") return;
    if (ev.t === "ping") {
      try { ws.send('{"t":"pong"}'); } catch {}
      return;
    }
    if (!ALLOWED_TYPES.has(ev.t)) return;

    const who = ws.deserializeAttachment() || { id: "?", name: "Unknown" };
    ev.from = who.name;
    ev.fromId = who.id;
    ev.relayTs = Date.now();
    const out = JSON.stringify(ev);

    await this.appendHistory(ev);
    // Echo to the sender too: every UI renders party events the same way.
    for (const sock of this.ctx.getWebSockets()) {
      try { sock.send(out); } catch {}
    }
  }

  webSocketClose() {
    this.broadcastMembers();
  }

  webSocketError() {
    this.broadcastMembers();
  }

  broadcastMembers() {
    const members = [];
    for (const sock of this.ctx.getWebSockets()) {
      const a = sock.deserializeAttachment();
      if (a) members.push({ id: a.id, name: a.name });
    }
    const out = JSON.stringify({ t: "members", members });
    for (const sock of this.ctx.getWebSockets()) {
      try { sock.send(out); } catch {}
    }
  }

  async appendHistory(ev) {
    const seq = ((await this.ctx.storage.get("seq")) || 0) + 1;
    await this.ctx.storage.put("seq", seq);
    await this.ctx.storage.put("h:" + String(seq).padStart(10, "0"), ev);
    if (seq > HISTORY_MAX) {
      const dead = await this.ctx.storage.list({
        prefix: "h:",
        end: "h:" + String(seq - HISTORY_MAX).padStart(10, "0"),
      });
      await this.ctx.storage.delete([...dead.keys()]);
    }
  }

  async sendHistory(ws) {
    const stored = await this.ctx.storage.list({ prefix: "h:" });
    const events = [...stored.values()];
    try { ws.send(JSON.stringify({ t: "history", events })); } catch {}
  }
}
