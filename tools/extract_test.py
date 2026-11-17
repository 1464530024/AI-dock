# -*- coding: utf-8 -*-
"""把 ConversationExporter.kt 里的注入脚本抽出来，在无头 Chromium 里对 3 种对话 DOM 跑一遍。

目的：这段 JS 是导出功能的核心，但手机上很难造出真实的对话 DOM。
用 mock 页面 + 真实布局，可以验证：
  1. 脚本本身没有语法错误（有错会直接返回 error）；
  2. 站点适配器能按 data-* 标记分出 user / ai；
  3. 通用启发式在没有任何标记时也能靠对齐分出说话人；
  4. HTML → Markdown 的代码块 / 列表 / 粗体 / 链接 / 表格转换正确。
"""
import json, os, re, subprocess, sys, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "aidock", "app", "export",
                  "ConversationExporter.kt")
CHROME = (r"C:\Users\gzh17\AppData\Local\ms-playwright"
          r"\chromium_headless_shell-1243\chrome-headless-shell-win64\chrome-headless-shell.exe")

src = open(KT, encoding="utf-8").read()
m = re.search(r'private val JS: String = """(.*?)"""\.trimIndent\(\)', src, re.S)
if not m:
    sys.exit("没找到 JS 块")
JS = m.group(1).strip()
# Kotlin 只把 $标识符 与 ${...} 当插值；正则里的行尾 $ 是合法字面量
if re.search(r"\$\{|\$[A-Za-z_]", JS):
    sys.exit("JS 里出现 Kotlin 会插值的 $，必须改掉")
print("抽出 JS %d 字符" % len(JS))

BASE_CSS = """
*{box-sizing:border-box}
body{margin:0;font:15px/1.6 system-ui;background:#fff}
.chat{width:760px;margin:0 auto;padding:20px}
pre{background:#f6f8fa;padding:10px;border-radius:8px;overflow:auto}
"""

PAGES = {}

PAGES["chatgpt"] = """<!doctype html><html><head><meta charset="utf-8"><style>%s</style></head><body>
<main><div class="chat">
  <div data-message-author-role="user"><div class="whitespace-pre-wrap">帮我写一个 Python 快排，并说明复杂度</div></div>
  <div data-message-author-role="assistant"><div class="markdown">
    <p>下面是一个<strong>原地快排</strong>实现：</p>
    <pre><code class="language-python">def qsort(a, lo, hi):
    if lo &gt;= hi: return
    p = a[lo]
    i, j = lo, hi
    while i &lt; j:
        while i &lt; j and a[j] &gt;= p: j -= 1
        a[i] = a[j]
        while i &lt; j and a[i] &lt;= p: i += 1
        a[j] = a[i]
    a[i] = p
    qsort(a, lo, i-1); qsort(a, i+1, hi)</code></pre>
    <p>要点：</p>
    <ul><li>平均 <code>O(nlogn)</code></li><li>最坏 <code>O(n^2)</code>，退化为已排序输入</li></ul>
    <blockquote>空间 O(logn)，来自递归栈。</blockquote>
    <p>参考 <a href="https://example.com/qsort">维基百科</a></p>
    <div role="button">复制</div>
  </div></div>
  <div data-message-author-role="user"><div class="whitespace-pre-wrap">再给个非递归版本</div></div>
  <div data-message-author-role="assistant"><div class="markdown"><p>用显式栈即可，这里略。</p>
    <table><tr><th>版本</th><th>空间</th></tr><tr><td>递归</td><td>O(logn)</td></tr><tr><td>迭代</td><td>O(logn)</td></tr></table>
  </div></div>
</div></main>
<script>__INJECT__</script></body></html>"""

PAGES["gemini"] = """<!doctype html><html><head><meta charset="utf-8"><style>%s</style></head><body>
<main><div class="chat">
  <user-query><div class="query-text"><p>解释一下 CAP 定理</p></div></user-query>
  <model-response><div class="markdown">
    <p>CAP 指 <strong>一致性</strong>、<strong>可用性</strong>、<strong>分区容错</strong>。</p>
    <ol><li>网络分区发生时必须在 C 与 A 之间取舍</li><li>现实系统多选 CP 或 AP</li></ol>
  </div></model-response>
  <user-query><div class="query-text"><p>CP 和 AP 各举一个例子</p></div></user-query>
  <model-response><div class="markdown"><p>CP：ZooKeeper；AP：Cassandra。</p></div></model-response>
</div></main>
<script>__INJECT__</script></body></html>"""

PAGES["generic"] = """<!doctype html><html><head><meta charset="utf-8"><style>%s
.msg{display:flex;margin:16px 0}
.msg.me{justify-content:flex-end}
.msg.me .box{background:#dbe7ff;max-width:70%%}
.msg.ai .box{background:#f2f3f5;max-width:70%%}
.box{padding:10px 14px;border-radius:14px}
</style></head><body>
<main><div class="chat">
  <div class="msg me"><div class="box"><p>你好，帮我总结一下这篇论文</p></div></div>
  <div class="msg ai"><div class="box"><p>这篇论文提出了一种<strong>稀疏注意力</strong>机制，主要贡献有三点：</p>
    <ul><li>把复杂度从 O(n²) 降到 O(n√n)</li><li>在长文本任务上精度基本不掉</li><li>训练显存下降约 40%%</li></ul></div></div>
  <div class="msg me"><div class="box"><p>有什么局限性？</p></div></div>
  <div class="msg ai"><div class="box"><p>作者自己也承认两点：短文本上收益不明显；实现依赖自定义 kernel，移植成本高。</p></div></div>
</div></main>
<script>__INJECT__</script></body></html>"""


def run_page(name, html):
    path = os.path.join(HERE, "mock_%s.html" % name)
    open(path, "w", encoding="utf-8").write(html)
    out = subprocess.run(
        [CHROME, "--disable-gpu", "--hide-scrollbars", "--no-sandbox",
         "--window-size=900,1200", "--virtual-time-budget=4000", "--dump-dom",
         "file:///" + path.replace("\\", "/")],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
    dom = out.stdout or ""
    t = re.search(r"<title>(.*?)</title>", dom, re.S)
    if not t:
        return {"fatal": "no title", "dom": dom[:300]}
    raw = t.group(1)
    for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&amp;", "&")):
        raw = raw.replace(a, b)
    try:
        return json.loads(urllib.parse.unquote(raw))
    except Exception as exc:
        return {"fatal": "decode: %s" % exc, "raw": raw[:300]}


results = {}
# file:// 下 location.hostname 为空，站点适配器不会命中；
# 测试时按页把 host 钉死，才能真正验证 adapter 分支。
HOSTS = {"chatgpt": "chatgpt.com", "gemini": "gemini.google.com", "generic": "example.org"}
HOST_LINE = 'var host = (location.hostname || "").toLowerCase();'

for name, tpl in PAGES.items():
    js = JS.replace(HOST_LINE, 'var host = "%s";' % HOSTS[name])
    if js == JS and name != "generic":
        print("!! 没替换到 host 行，adapter 分支未验证")
    inject = "var __r = " + js + ";\ndocument.title = encodeURIComponent(__r);"
    html = tpl % BASE_CSS
    html = html.replace("__INJECT__", inject)
    results[name] = run_page(name, html)

for name, r in results.items():
    print("\n" + "=" * 74)
    print("PAGE:", name)
    if "fatal" in r:
        print("  FATAL:", r)
        continue
    msgs = r.get("messages") or []
    print("  ok=%s source=%s count=%d chars=%d" % (
        r.get("ok"), r.get("source"), len(msgs), sum(len(x["md"]) for x in msgs)))
    if r.get("note"):
        print("  note:", r["note"])
    if r.get("error"):
        print("  error:", r["error"])
    for i, msg in enumerate(msgs):
        print("  --- [%d] role=%s len=%d" % (i, msg["role"], len(msg["md"])))
        print("      " + msg["md"].replace("\n", "\n      ")[:560])
