/**
 * HLine TV 的上报接收端（Cloudflare Worker）。
 *
 * 两个入口，都只接受 POST，都可以用 REPORT_KEY 加一道门槛：
 *
 *   POST /v1/install    装机量。一台电视一个随机 id，第一次出现时记一条；
 *                       同一台电视升级版本只更新那条记录，不重复计数。
 *   POST /v1/feedback   反馈日志。把 Diagnostics.report() 的内容开成一个 GitHub Issue。
 *
 * 另有一个只读的查看页：
 *
 *   GET  /stats?key=... 按品牌、型号、版本汇总的装机量（纯文本，浏览器里直接看）。
 *
 * 不存 IP、不存任何个人数据：装机的记录只有 App 发来的那几个字段（品牌、型号、
 * 安卓版本、App 版本），外加一个随机 id。
 *
 * 依赖：一个 KV 命名空间（绑定名 REPORT）与三个 secret（GITHUB_TOKEN / GITHUB_REPO /
 * STATS_KEY，可选 REPORT_KEY）。部署步骤见同目录 README.md。
 */

const MAX_BODY = 64 * 1024;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/v1/install") return await install(request, env);
      if (request.method === "POST" && url.pathname === "/v1/feedback") return await feedback(request, env);
      if (request.method === "GET" && (url.pathname === "/stats" || url.pathname === "/")) return await stats(url, env);
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: String(e && e.message ? e.message : e) }, 500);
    }
  },
};

/** 一道很轻的门：设了 REPORT_KEY，就必须带对 X-Report-Key。 */
function allowed(request, env) {
  if (!env.REPORT_KEY) return true;
  return request.headers.get("X-Report-Key") === env.REPORT_KEY;
}

async function body(request) {
  const text = await request.text();
  if (text.length > MAX_BODY) throw new Error("body too large");
  return JSON.parse(text);
}

/** 装机：一条记录一台电视，id 是 App 第一次启动时生成的随机 UUID。 */
async function install(request, env) {
  if (!allowed(request, env)) return json({ error: "forbidden" }, 403);
  const b = await body(request);
  const id = String(b.id || "").slice(0, 64);
  if (!id) return json({ error: "no id" }, 400);

  const key = `i:${id}`;
  const before = await env.REPORT.get(key);
  const record = {
    brand: str(b.brand),
    manufacturer: str(b.manufacturer),
    model: str(b.model),
    device: str(b.device),
    sdk: Number(b.sdk) || 0,
    android: str(b.android),
    version: str(b.version),
    code: Number(b.code) || 0,
    flavor: str(b.flavor),
    lang: str(b.lang),
    first: before ? JSON.parse(before).first : new Date().toISOString().slice(0, 10),
    last: new Date().toISOString().slice(0, 10),
  };
  await env.REPORT.put(key, JSON.stringify(record));
  // 204：App 只关心成不成，不要回包。
  return new Response(null, { status: 204 });
}

/** 反馈：开一个 Issue。日志里已经脱敏（地址、手机页 token）。 */
async function feedback(request, env) {
  if (!allowed(request, env)) return json({ error: "forbidden" }, 403);
  const b = await body(request);
  if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) return json({ error: "github not configured" }, 501);

  const title = `[反馈] ${str(b.brand) || "?"} ${str(b.model) || "?"} · ${str(b.version) || "?"} (${Number(b.code) || 0})`;
  const meta = [
    "| 字段 | 值 |",
    "| --- | --- |",
    `| 品牌 | ${cell(b.manufacturer)} / ${cell(b.brand)} |`,
    `| 型号 | ${cell(b.model)} |`,
    `| 设备代号 | ${cell(b.device)} |`,
    `| Android | ${cell(b.android)} (SDK ${Number(b.sdk) || 0}) |`,
    `| HLine TV | ${cell(b.version)} (${Number(b.code) || 0}) · ${cell(b.flavor)} |`,
    `| Aerial 语言 | ${cell(b.lang) || "跟随电视"} |`,
    `| 装机 id | \`${String(b.id || "").slice(0, 64)}\` |`,
    `| 上报时间 | ${new Date().toISOString()} |`,
  ].join("\n");
  const report = String(b.report || "").slice(0, 128 * 1024);
  const text = [
    "### 来自电视的反馈",
    "",
    meta,
    "",
    "<details><summary>诊断与日志</summary>",
    "",
    "~~~~",
    report,
    "~~~~",
    "",
    "</details>",
  ].join("\n");

  const labels = (env.ISSUE_LABELS || "").split(",").map((s) => s.trim()).filter(Boolean);
  const res = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}/issues`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "Content-Type": "application/json",
      "User-Agent": "hline-tv-report",
    },
    body: JSON.stringify({ title, body: text, labels }),
  });
  if (!res.ok) {
    const why = await res.text();
    return json({ error: `github ${res.status}`, detail: why.slice(0, 500) }, 502);
  }
  const issue = await res.json();
  return json({ url: issue.html_url, number: issue.number });
}

/** 汇总：把 i: 前缀的记录按品牌、型号、版本数一遍。装机量小的时候这就是精确值。 */
async function stats(url, env) {
  if (env.STATS_KEY && url.searchParams.get("key") !== env.STATS_KEY) {
    return new Response("forbidden\n", { status: 403 });
  }
  const all = [];
  let cursor = undefined;
  do {
    const page = await env.REPORT.list({ prefix: "i:", cursor });
    for (const k of page.keys) {
      const raw = await env.REPORT.get(k.name);
      if (raw) all.push(JSON.parse(raw));
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);

  const count = (pick) => {
    const m = new Map();
    for (const r of all) {
      const k = pick(r) || "?";
      m.set(k, (m.get(k) || 0) + 1);
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  };
  const line = (label, pairs) =>
    [`${label}`, ...pairs.map(([k, n]) => `  ${k}: ${n}`), ""].join("\n");

  const days = all.map((r) => r.first).filter(Boolean).sort();
  const text = [
    `HLine TV 装机量：${all.length} 台`,
    days.length ? `最早 ${days[0]}，最近 ${days[days.length - 1]}` : "",
    "",
    line("按品牌", count((r) => r.brand || r.manufacturer)),
    line("按型号", count((r) => `${r.brand || r.manufacturer} ${r.model}`)),
    line("按版本", count((r) => `${r.version} (${r.code})`)),
    line("按 Android", count((r) => `SDK ${r.sdk}`)),
    line("按语言", count((r) => r.lang || "跟随电视")),
  ].join("\n");
  return new Response(text, { headers: { "content-type": "text/plain; charset=utf-8" } });
}

function str(v) {
  return String(v == null ? "" : v).slice(0, 120);
}

function cell(v) {
  return str(v).replace(/\|/g, "\\|") || "-";
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
