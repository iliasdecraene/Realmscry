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
 *   { t: "profile", icon, ign }  member's avatar sprite id + in-game name;
 *                                stored per member and merged into members
 * Server->client frames:
 *   { t: "history", events: [...] }        on join
 *   { t: "members", members: [{id,name,icon,ign}] } on join/leave/profile
 *   { t: "loot"|"boss", from, fromId, ...} relayed events (also echoed to
 *                                          sender so all UIs share one path)
 *   { t: "pong" }
 */

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no 0/O/1/I/L/U
const CODE_LEN = 6;
const MAX_MEMBERS = 10;
const MAX_FRAME = 16 * 1024;
const HISTORY_MAX = 100;
const ALLOWED_TYPES = new Set(["loot", "boss", "death"]);

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
      return new Response("Realmscry relay v6 — see github.com/iliasdecraene/Realmscry\n");
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
    // Accounts + guilds live in one SQLite-backed Durable Object.
    if (url.pathname.startsWith("/api/")) {
      return env.REGISTRY.get(env.REGISTRY.idFromName("global")).fetch(req);
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
    await this.broadcastMembers();
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
      // Piggyback a liveness sweep on the 30s pings: nudge every socket so
      // dead peers (crash, network drop) are noticed within a ping cycle.
      let lost = false;
      for (const sock of this.ctx.getWebSockets()) {
        if (sock === ws) continue;
        try { sock.send('{"t":"pong"}'); } catch {
          lost = true;
          try { sock.close(1011, "gone"); } catch {}
        }
      }
      if (lost) await this.broadcastMembers();
      return;
    }
    if (ev.t === "profile") {
      const who = ws.deserializeAttachment();
      if (who) {
        await this.ctx.storage.put("p:" + who.id, {
          icon: Number(ev.icon) || 0,
          ign: String(ev.ign || "").slice(0, 24),
        });
        await this.broadcastMembers();
      }
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
    // A send that throws means the peer died without a close handshake —
    // shut its socket so it drops out of the member list right away.
    let lostSomeone = false;
    for (const sock of this.ctx.getWebSockets()) {
      try {
        sock.send(out);
      } catch {
        lostSomeone = true;
        try { sock.close(1011, "gone"); } catch {}
      }
    }
    if (lostSomeone) await this.broadcastMembers();
  }

  async webSocketClose() {
    await this.broadcastMembers();
  }

  async webSocketError() {
    await this.broadcastMembers();
  }

  async broadcastMembers() {
    const profiles = await this.ctx.storage.list({ prefix: "p:" });
    const members = [];
    for (const sock of this.ctx.getWebSockets()) {
      const a = sock.deserializeAttachment();
      if (!a) continue;
      const p = profiles.get("p:" + a.id) || {};
      members.push({ id: a.id, name: a.name, icon: p.icon || 0, ign: p.ign || "" });
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

/**
 * Accounts + guilds. One SQLite-backed Durable Object holds everything —
 * at guild scale a single SQLite is exactly what D1 would be anyway.
 *
 * Auth: "Authorization: Bearer <token>". The token is generated at
 * registration and returned exactly once; only its SHA-256 is stored.
 * Identity is the token — the detected game account / IGN are profile
 * data, so accounts cannot be hijacked by spoofing packet contents.
 */
const GUILD_MEMBER_CAP = 50;
const GUILD_EVENT_KEEP = 3000; // per guild; liked events are never trimmed
const EVENT_DATA_MAX = 8 * 1024;

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export class Registry {
  constructor(ctx) {
    this.ctx = ctx;
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS accounts(
      id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL,
      ign TEXT DEFAULT '', icon INTEGER DEFAULT 0,
      game_account TEXT DEFAULT '', created INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS guilds(
      id TEXT PRIMARY KEY, name TEXT NOT NULL, owner TEXT NOT NULL,
      code TEXT UNIQUE NOT NULL, created INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS members(
      guild TEXT NOT NULL, account TEXT NOT NULL,
      role TEXT DEFAULT 'member', joined INTEGER,
      PRIMARY KEY(guild, account))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS events(
      id INTEGER PRIMARY KEY AUTOINCREMENT, guild TEXT NOT NULL,
      account TEXT NOT NULL, type TEXT NOT NULL, ts INTEGER NOT NULL,
      data TEXT NOT NULL)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS ev_guild ON events(guild, id)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS likes(
      event INTEGER NOT NULL, account TEXT NOT NULL, ts INTEGER,
      PRIMARY KEY(event, account))`);
    // v1.4.1: user-chosen display name (ign is only auto-detected at a map
    // join, so fresh accounts showed as "Unknown").
    try { this.sql.exec("ALTER TABLE accounts ADD COLUMN name TEXT DEFAULT ''"); } catch {}
  }

  one(query, ...args) {
    const rows = this.sql.exec(query, ...args).toArray();
    return rows.length ? rows[0] : null;
  }

  async auth(req) {
    const h = req.headers.get("Authorization") || "";
    if (!h.startsWith("Bearer ")) return null;
    const hash = await sha256hex(h.slice(7).trim());
    return this.one("SELECT * FROM accounts WHERE token_hash = ?", hash);
  }

  myGuild(accountId) {
    return this.one(
      "SELECT g.*, m.role FROM members m JOIN guilds g ON g.id = m.guild WHERE m.account = ?",
      accountId);
  }

  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    let body = {};
    if (req.method === "POST") {
      try { body = await req.json(); } catch { return json({ ok: false, error: "bad json" }, 400); }
    }
    try {
      if (path === "/api/register" && req.method === "POST") {
        const token = crypto.randomUUID() + crypto.randomUUID();
        const id = crypto.randomUUID();
        this.sql.exec(
          "INSERT INTO accounts(id, token_hash, ign, icon, game_account, created) VALUES(?,?,?,?,?,?)",
          id, await sha256hex(token),
          String(body.ign || "").slice(0, 24), Number(body.icon) || 0,
          String(body.gameAccount || "").slice(0, 64), Date.now());
        return json({ ok: true, accountId: id, token });
      }

      const acc = await this.auth(req);
      if (!acc) return json({ ok: false, error: "unauthorized" }, 401);

      if (path === "/api/profile" && req.method === "POST") {
        this.sql.exec(
          "UPDATE accounts SET ign = ?, icon = ?, game_account = ?, name = ? WHERE id = ?",
          String(body.ign || acc.ign).slice(0, 24),
          Number(body.icon) || acc.icon,
          String(body.gameAccount || acc.game_account).slice(0, 64),
          String(body.name !== undefined ? body.name : acc.name || "").slice(0, 24),
          acc.id);
        return json({ ok: true });
      }

      if (path === "/api/guild/create" && req.method === "POST") {
        if (this.myGuild(acc.id)) return json({ ok: false, error: "already in a guild - leave it first" });
        const name = String(body.name || "").trim().slice(0, 24);
        if (name.length < 2) return json({ ok: false, error: "guild name too short" });
        const id = crypto.randomUUID();
        const code = genCode() + genCode().slice(0, 2); // 8 chars
        this.sql.exec("INSERT INTO guilds(id, name, owner, code, created) VALUES(?,?,?,?,?)",
            id, name, acc.id, code, Date.now());
        this.sql.exec("INSERT INTO members(guild, account, role, joined) VALUES(?,?,?,?)",
            id, acc.id, "owner", Date.now());
        return json({ ok: true, guildId: id, name, code });
      }

      if (path === "/api/guild/join" && req.method === "POST") {
        if (this.myGuild(acc.id)) return json({ ok: false, error: "already in a guild - leave it first" });
        const code = String(body.code || "").trim().toUpperCase();
        const g = this.one("SELECT * FROM guilds WHERE code = ?", code);
        if (!g) return json({ ok: false, error: "no guild with that invite code" });
        const n = this.one("SELECT COUNT(*) c FROM members WHERE guild = ?", g.id).c;
        if (n >= GUILD_MEMBER_CAP) return json({ ok: false, error: "guild is full" });
        this.sql.exec("INSERT INTO members(guild, account, role, joined) VALUES(?,?,?,?)",
            g.id, acc.id, "member", Date.now());
        return json({ ok: true, guildId: g.id, name: g.name, code: g.code });
      }

      if (path === "/api/guild/leave" && req.method === "POST") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: false, error: "not in a guild" });
        this.sql.exec("DELETE FROM members WHERE guild = ? AND account = ?", g.id, acc.id);
        const rest = this.sql.exec(
            "SELECT account FROM members WHERE guild = ? ORDER BY joined LIMIT 1", g.id).toArray();
        if (!rest.length) { // last one out: guild + its history dissolve
          this.sql.exec("DELETE FROM likes WHERE event IN (SELECT id FROM events WHERE guild = ?)", g.id);
          this.sql.exec("DELETE FROM events WHERE guild = ?", g.id);
          this.sql.exec("DELETE FROM guilds WHERE id = ?", g.id);
        } else if (g.owner === acc.id) { // ownership passes to the oldest member
          this.sql.exec("UPDATE guilds SET owner = ? WHERE id = ?", rest[0].account, g.id);
          this.sql.exec("UPDATE members SET role = 'owner' WHERE guild = ? AND account = ?",
              g.id, rest[0].account);
        }
        return json({ ok: true });
      }

      if (path === "/api/guild/state") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: true, inGuild: false, accountId: acc.id });
        const members = this.sql.exec(
            "SELECT a.id, a.ign, a.icon, a.name, m.role FROM members m " +
            "JOIN accounts a ON a.id = m.account WHERE m.guild = ? ORDER BY m.joined",
            g.id).toArray();
        return json({ ok: true, inGuild: true, guildId: g.id, name: g.name,
            code: g.code, role: g.role, accountId: acc.id, members });
      }

      if (path === "/api/guild/event" && req.method === "POST") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: false, error: "not in a guild" });
        const type = body.type === "death" ? "death" : "loot";
        const data = JSON.stringify(body.data || {});
        if (data.length > EVENT_DATA_MAX) return json({ ok: false, error: "event too large" });
        const ts = Number(body.ts) || Date.now();
        this.sql.exec("INSERT INTO events(guild, account, type, ts, data) VALUES(?,?,?,?,?)",
            g.id, acc.id, type, ts, data);
        const id = this.one("SELECT last_insert_rowid() id").id;
        // Trim: keep the newest GUILD_EVENT_KEEP plus anything with a like.
        this.sql.exec(
            "DELETE FROM events WHERE guild = ?1 " +
            "AND id NOT IN (SELECT id FROM events WHERE guild = ?1 ORDER BY id DESC LIMIT ?2) " +
            "AND id NOT IN (SELECT event FROM likes)", g.id, GUILD_EVENT_KEEP);
        return json({ ok: true, eventId: id });
      }

      if (path === "/api/guild/timeline") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: false, error: "not in a guild" });
        const filter = url.searchParams.get("filter") || "all";
        const before = Number(url.searchParams.get("before")) || Number.MAX_SAFE_INTEGER;
        const limit = Math.min(100, Number(url.searchParams.get("limit")) || 50);
        let where = "e.guild = ?1 AND e.id < ?2";
        if (filter === "deaths") where += " AND e.type = 'death'";
        if (filter === "liked") where += " AND EXISTS (SELECT 1 FROM likes l WHERE l.event = e.id)";
        const rows = this.sql.exec(
            "SELECT e.id, e.account, e.type, e.ts, e.data, a.ign, a.icon, a.name dname, " +
            "(SELECT COUNT(*) FROM likes l WHERE l.event = e.id) likes, " +
            "EXISTS (SELECT 1 FROM likes l WHERE l.event = e.id AND l.account = ?3) likedByMe " +
            "FROM events e JOIN accounts a ON a.id = e.account " +
            "WHERE " + where + " ORDER BY e.id DESC LIMIT ?4",
            g.id, before, acc.id, limit).toArray();
        for (const r of rows) {
          try { r.data = JSON.parse(r.data); } catch { r.data = {}; }
          r.mine = r.account === acc.id;
        }
        return json({ ok: true, accountId: acc.id, events: rows });
      }

      // Like one of MY OWN events addressed by its original timestamp —
      // lets the tracker's local timeline hearts reach the guild without
      // the client having to remember server event ids.
      if (path === "/api/guild/likeByTs" && req.method === "POST") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: false, error: "not in a guild" });
        const ev = this.one(
            "SELECT id FROM events WHERE guild = ? AND account = ? AND ts = ? ORDER BY id DESC",
            g.id, acc.id, Number(body.ts) || 0);
        if (!ev) return json({ ok: false, error: "event not in guild timeline" });
        if (body.on) {
          this.sql.exec("INSERT OR IGNORE INTO likes(event, account, ts) VALUES(?,?,?)",
              ev.id, acc.id, Date.now());
        } else {
          this.sql.exec("DELETE FROM likes WHERE event = ? AND account = ?", ev.id, acc.id);
        }
        const likes = this.one("SELECT COUNT(*) c FROM likes WHERE event = ?", ev.id).c;
        return json({ ok: true, eventId: ev.id, likes });
      }

      if (path === "/api/guild/like" && req.method === "POST") {
        const g = this.myGuild(acc.id);
        if (!g) return json({ ok: false, error: "not in a guild" });
        const ev = this.one("SELECT id FROM events WHERE id = ? AND guild = ?",
            Number(body.eventId) || 0, g.id);
        if (!ev) return json({ ok: false, error: "no such event" });
        if (body.on) {
          this.sql.exec("INSERT OR IGNORE INTO likes(event, account, ts) VALUES(?,?,?)",
              ev.id, acc.id, Date.now());
        } else {
          this.sql.exec("DELETE FROM likes WHERE event = ? AND account = ?", ev.id, acc.id);
        }
        const likes = this.one("SELECT COUNT(*) c FROM likes WHERE event = ?", ev.id).c;
        return json({ ok: true, likes });
      }

      return new Response("not found\n", { status: 404 });
    } catch (e) {
      return json({ ok: false, error: "server error: " + (e.message || e) }, 500);
    }
  }
}
