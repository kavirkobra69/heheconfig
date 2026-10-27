// Cloudflare Worker v3: read public Telegram channel posts and return proxy configs.
const KEY = "MmdKavir85";
const MAX_PAGES = 15;
const LINK_RE = /(?:vmess|vless|trojan|ss|hysteria2|hy2|tuic):\/\/[^\s<>"'`]+/g;
const UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status,
  headers: { "content-type": "application/json; charset=utf-8" },
});

function toText(html) {
  return html.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "")
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
}

export default {
  async fetch(request) {
    const u = new URL(request.url);
    if (u.searchParams.get("key") !== KEY) return json({ ok: false, error: "forbidden" }, 403);
    const channel = (u.searchParams.get("channel") || "").replace(/^@/, "");
    if (!/^[A-Za-z0-9_]{4,64}$/.test(channel)) return json({ ok: false, error: "bad_channel" }, 400);
    let since = parseInt(u.searchParams.get("since") || "0", 10);
    if (!since) since = Math.floor(Date.now() / 1000) - 86400;

    const configs = new Set();
    let scanned = 0, inRange = 0, before = null, pages = 0;
    while (pages < MAX_PAGES) {
      pages++;
      const target = `https://t.me/s/${channel}` + (before ? `?before=${before}` : "");
      let res;
      try {
        res = await fetch(target, { headers: { "User-Agent": UA, "Accept-Language": "en-US,en;q=0.9" }, redirect: "follow" });
      } catch {
        return json({ ok: false, error: "fetch_failed" }, 502);
      }
      if (!res.ok) return json({ ok: false, error: `telegram_http_${res.status}` }, 502);
      const html = await res.text();
      const chunks = html.split("tgme_widget_message_wrap").slice(1);
      if (!chunks.length) {
        if (pages === 1) return json({ ok: false, error: "no_preview" });
        break;
      }
      let oldestTs = Infinity, oldestId = null;
      for (const c of chunks) {
        const tm = /<time[^>]*datetime="([^"]+)"/.exec(c);
        if (!tm) continue;
        const idm = /data-post="[^"\/]+\/(\d+)"/.exec(c);
        const ts = Math.floor(Date.parse(tm[1]) / 1000);
        const id = idm ? parseInt(idm[1], 10) : null;
        scanned++;
        if (ts < oldestTs) { oldestTs = ts; oldestId = id; }
        if (ts >= since) {
          inRange++;
          for (const m of toText(c).matchAll(LINK_RE)) configs.add(m[0].replace(/[.,;)]+$/, ""));
        }
      }
      if (oldestTs < since || !oldestId || oldestId === before) break;
      before = oldestId;
    }
    return json({ ok: true, channel, pages, posts_scanned: scanned, posts_in_range: inRange, configs: [...configs] });
  },
};
