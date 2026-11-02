'use strict'

/**
 * The other end of HLine TV's two reports — an install count and a feedback log.
 *
 * The app posts JSON to `<this server>/v1/install` and `<this server>/v1/feedback` (see
 * app/src/main/java/app/orchard/tv/system/UsageReport.kt). This server writes every log as a file
 * whose name says which set and which build it came from, and answers with a link to it:
 *
 *   HLINE-TV_Xiaomi_MiTV-MFTP0_Android9_v1.6-150_20261103-001530.log
 *   └ brand     └ maker  └ model     └ OS    └ app     └ time (Asia/Shanghai)
 *
 * Anything the platform does not accept in a file name (spaces, slashes, CJK) becomes a dash, and
 * every field is capped, so a TV with a strange model name cannot break the listing.
 */

const http = require('http')
const fs = require('fs')
const path = require('path')
const { URL } = require('url')

const PORT = Number(process.env.PORT || 8787)
const ROOT = path.join(__dirname, 'data')
const LOGS = path.join(ROOT, 'logs')
const INSTALLS = path.join(ROOT, 'installs.jsonl')
// Optional: the same key the app sends as X-Report-Key. Set REPORT_KEY to require it.
const KEY = (process.env.REPORT_KEY || '').trim()
const MAX_BODY = 256 * 1024

fs.mkdirSync(LOGS, { recursive: true })

/** One field of a file name: safe characters only, no runs of dashes, capped. Dots stay, so a
 *  version keeps its shape (v1.6-150 rather than v1-6-150). */
function field(value, fallback, cap) {
  const clean = String(value == null ? '' : value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^A-Za-z0-9.]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '')
  return (clean || fallback).slice(0, cap)
}

/** 20261103-001530 in the owner's own time, whatever the host's clock is set to. */
function stamp(date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  }).formatToParts(date).reduce((all, p) => ((all[p.type] = p.value), all), {})
  return `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}${parts.second}`
}

function logName(body, date) {
  const maker = field(body.manufacturer, 'TV', 18)
  const model = field(body.model, 'unknown', 24)
  const os = field(body.android, 'Android', 12)
  const version = field(body.version, 'app', 12)
  const code = field(String(body.code == null ? '' : body.code), '0', 8)
  return `HLINE-TV_${maker}_${model}_Android${os}_v${version}-${code}_${stamp(date)}.log`
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY) {
        reject(new Error('too large'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function send(res, code, body, type) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(code, {
    'Content-Type': type || 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  res.end(payload)
}

function allowed(req) {
  return !KEY || req.headers['x-report-key'] === KEY
}

/** What a person opening the server's address sees: the last few logs, with their names. */
function index() {
  const names = fs.readdirSync(LOGS).filter((n) => n.endsWith('.log')).sort().reverse()
  const rows = names
    .slice(0, 60)
    .map((n) => {
      const size = fs.statSync(path.join(LOGS, n)).size
      return `<li><a href="/logs/${encodeURIComponent(n)}">${n}</a> <small>${(size / 1024).toFixed(1)} KB</small></li>`
    })
    .join('\n')
  const installs = fs.existsSync(INSTALLS)
    ? fs.readFileSync(INSTALLS, 'utf8').trim().split('\n').filter(Boolean)
    : []
  const sets = new Map()
  for (const line of installs) {
    try {
      const r = JSON.parse(line)
      const key = `${r.manufacturer || '?'} ${r.model || '?'} · ${r.version || '?'} (${r.code || '?'})`
      sets.set(key, (sets.get(key) || 0) + 1)
    } catch (_) { /* a half-written line is not worth a page error */ }
  }
  const table = [...sets.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `<tr><td>${k}</td><td>${n}</td></tr>`)
    .join('\n')
  return `<!doctype html><meta charset="utf-8"><title>HLine TV reports</title>
<style>
 body{font:15px/1.5 -apple-system,system-ui,sans-serif;margin:32px;color:#111;background:#fafafa}
 h1{font-size:20px;margin:0 0 4px} h2{font-size:15px;margin:28px 0 8px;color:#555}
 a{color:#0a58ca;text-decoration:none} a:hover{text-decoration:underline}
 li{margin:2px 0} small{color:#888;margin-left:8px}
 table{border-collapse:collapse} td{border-bottom:1px solid #e5e5e5;padding:4px 16px 4px 0}
</style>
<h1>HLine TV 报告</h1>
<div>${names.length} 份日志 · ${installs.length} 条安装记录</div>
<h2>最近的日志</h2><ul>${rows || '<li>还没有日志。</li>'}</ul>
<h2>安装（按设备与版本）</h2>
<table>${table || '<tr><td>还没有安装记录。</td></tr>'}</table>`
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    return send(res, 200, index(), 'text/html; charset=utf-8')
  }

  if (req.method === 'GET' && url.pathname.startsWith('/logs/')) {
    const name = decodeURIComponent(url.pathname.slice('/logs/'.length))
    // A name from the URL may not walk out of the logs folder.
    if (name.includes('/') || name.includes('..')) return send(res, 400, { error: 'bad name' })
    const file = path.join(LOGS, name)
    if (!fs.existsSync(file)) return send(res, 404, { error: 'not found' })
    return send(res, 200, fs.readFileSync(file), 'text/plain; charset=utf-8')
  }

  if (req.method !== 'POST') return send(res, 404, { error: 'not found' })
  if (!allowed(req)) return send(res, 401, { error: 'key' })

  const kind = url.pathname.replace(/^\/v1\//, '')
  if (kind !== 'install' && kind !== 'feedback') return send(res, 404, { error: 'not found' })

  let body
  try {
    body = JSON.parse(await readBody(req))
  } catch (e) {
    return send(res, 400, { error: 'bad json' })
  }

  const now = new Date()
  const line = JSON.stringify({
    at: now.toISOString(),
    kind,
    id: body.id || null,
    brand: body.brand || null,
    manufacturer: body.manufacturer || null,
    model: body.model || null,
    android: body.android || null,
    sdk: body.sdk || null,
    version: body.version || null,
    code: body.code || null,
    flavor: body.flavor || null,
    lang: body.lang || null,
  })

  if (kind === 'install') {
    fs.appendFileSync(INSTALLS, line + '\n')
    return send(res, 200, { ok: true })
  }

  const name = logName(body, now)
  const header = [
    `HLine TV feedback log`,
    `file      ${name}`,
    `received  ${now.toISOString()}`,
    `brand     ${body.brand || ''} / ${body.manufacturer || ''}`,
    `model     ${body.model || ''}`,
    `android   ${body.android || ''} (SDK ${body.sdk || ''})`,
    `app       ${body.version || ''} (${body.code || ''}) ${body.flavor || ''}`,
    `language  ${body.lang || ''}`,
    `install   ${body.id || ''}`,
    '',
    '----------------------------------------',
    '',
  ].join('\n')
  fs.writeFileSync(path.join(LOGS, name), header + (body.report || '') + '\n')

  const base = process.env.PUBLIC_BASE || `http://${req.headers.host || 'localhost'}`
  return send(res, 200, { ok: true, file: name, url: `${base}/logs/${encodeURIComponent(name)}` })
})

server.listen(PORT, () => {
  console.log(`hline report server on :${PORT}, logs in ${LOGS}`)
})
