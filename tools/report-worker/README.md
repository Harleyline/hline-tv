# 装机量与反馈日志的接收端

个性化 → 通用 里的两行设置，发到这里：

| App 里 | 发出去的内容 | 这里怎么处理 |
| --- | --- | --- |
| 匿名统计（装机量） | 品牌、型号、设备代号、Android 版本、App 版本、语言，加一个随机安装 id | 写进 KV，一台电视一条 |
| 反馈日志 | 上面那些，加 `Diagnostics.report()` 的全文（地址已脱敏） | 开一个 GitHub Issue |
| `GET /stats?key=…` | — | 按品牌／型号／版本汇总，纯文本 |

App 侧不装任何第三方 SDK，就是 `HttpURLConnection` 一个 POST；地址是编译期参数，
**不填地址就一个字节也不发**（`system/UsageReport.kt`）。

## 部署（一次性，约 5 分钟）

```bash
cd tools/report-worker
npm i -g wrangler            # 或者 npx wrangler
wrangler login

# 1. 建 KV，把打印出来的 id 填进 wrangler.toml
wrangler kv namespace create REPORT

# 2. 三个密钥
wrangler secret put GITHUB_TOKEN   # 细粒度 token，只勾 hline-tv 的 Issues: Read and write
wrangler secret put GITHUB_REPO    # Harleyline/hline-tv
wrangler secret put STATS_KEY      # 查看汇总页的口令，自己定
# 可选
wrangler secret put REPORT_KEY     # 和 App 的 reportKey 对上，挡掉乱发的请求

# 3. 发布
wrangler deploy
# → https://hline-report.<你的账号>.workers.dev
```

先在浏览器里试一下：

```
https://hline-report.<账号>.workers.dev/stats?key=你设的STATS_KEY
```

## 接进 App

`local/` 不进版本库，所以地址写这里（也可以直接用命令行参数）：

```bash
mkdir -p local && cat > local/report.properties <<'EOF'
url=https://hline-report.<账号>.workers.dev
key=你设的REPORT_KEY（没设就留空）
EOF

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleStandardRelease

# 或者一次性：
./gradlew assembleStandardRelease -PreportUrl=https://... -PreportKey=...
```

装到电视上以后：个性化 → 通用 → 一张卡片问「帮忙统计装机量？」→ 允许。
允许之后，每次版本更新会补发一条；`/stats` 里能看到品牌和版本的分布。
反馈日志的入口就在那张卡片下面一行，「上传」按下去几秒后变成「已上传」，
Issue 会出现在仓库里，标题是 `[反馈] 小米 MiTV-MFTR0 · 1.3 (146)` 这样。

## 换成一个群机器人

不想维护 Worker 的话，`REPORT_URL` 直接填群机器人地址也能用（App 会自动改发文本），
支持的域名：`open.feishu.cn`（飞书）、`qyapi.weixin.qq.com`（企业微信）、`dingtalk.com`。

```
url=https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxx
```

代价：装机量只能肉眼看群消息，没有 `/stats` 汇总；日志超过 40 000 字符会被截断。

## 隐私

- 随机安装 id 是第一次启动时生成的 UUID，跟账号、序列号、MAC、IP 都没有关系，只用来去重。
- 不发送：地理位置、城市、应用列表、照片、任何账号信息。日志里手机页 token、IP、MAC 已被
  `Diagnostics.mask()` 换成 `<ip>` / `<mac>` / `<token>`。
- 不同意就不发：卡片没回答，或者回答「暂不」，`UsageReport` 一次请求都不做。
- Worker 侧不落盘 IP；KV 里只有上面那几个字段。想清空装机量，删 KV 里 `i:` 前缀的键即可。
