package com.aidock.app.export

import android.webkit.WebView
import com.aidock.app.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** 一条抽出来的消息 */
data class ChatMessage(val role: String, val markdown: String) {
    val isUser: Boolean get() = role == "user"
}

data class CaptureResult(
    val ok: Boolean,
    val messages: List<ChatMessage> = emptyList(),
    /** adapter / generic / page —— 调试与提示用 */
    val source: String = "",
    val note: String = "",
    val title: String = "",
    val url: String = "",
    val error: String = ""
) {
    val chars: Int get() = messages.sumOf { it.markdown.length }
}

/**
 * 从当前 WebView 页面里抽取对话记录，并转成 Markdown。
 *
 * 为什么不用站点 API：这些站点没有对外的对话导出接口，App 也不该拿到用户的
 * token。所以走「当前页面已渲染的 DOM」这条路，注入一段 JS 在前端解析。
 *
 * 抽取按三层降级：
 *  1. 站点适配器：命中已知域名时用该站点的稳定选择器（data-testid / 自定义元素）；
 *  2. 通用启发式：找"子节点最多且文本最规整"的容器当消息列表，按属性/对齐/头像交替判定说话人；
 *  3. 全文兜底：整页可见文本作为一段导出，保证用户总能拿到内容。
 *
 * 已知限制：虚拟滚动只渲染可视区附近的消息，超长对话导不全；这点在导出预览里会明说。
 */
object ConversationExporter {

    suspend fun capture(webView: WebView, assistant: Assistant): CaptureResult {
        val raw = withTimeoutOrNull(9_000L) { evaluate(webView, JS) }
            ?: return CaptureResult(ok = false, error = "页面没有响应（超时）。请等页面加载完再试一次。")

        val json = decode(raw)
            ?: return CaptureResult(ok = false, error = "读取页面内容失败，请刷新后重试。")

        val ok = json.optBoolean("ok", false)
        val source = json.optString("source", "")
        val note = json.optString("note", "")
        val title = json.optString("title", "")
        val url = json.optString("url", "")

        if (!ok) {
            return CaptureResult(
                ok = false, source = source, note = note, title = title, url = url,
                error = json.optString("error", "当前页面没有找到对话内容。")
            )
        }

        val arr = json.optJSONArray("messages")
        val list = ArrayList<ChatMessage>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val md = o.optString("md", "").trim()
                if (md.isEmpty()) continue
                list += ChatMessage(o.optString("role", ""), md)
            }
        }
        if (list.isEmpty()) {
            return CaptureResult(ok = false, source = source, title = title, url = url,
                error = "页面已识别到容器，但内容为空。可能是对话还没加载完。")
        }
        return CaptureResult(true, list, source, note, title, url)
    }

    /** evaluateJavascript 的字符串结果需要先去掉 JSON 引号层 */
    private fun decode(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val inner = (JSONTokener(raw).nextValue() as? String) ?: raw
            JSONObject(inner)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    webView.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    fun fileName(assistant: Assistant, at: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "AI-dock_${assistant.id}_${f.format(Date(at))}.md"
    }

    /** 组装最终 Markdown 文档 */
    fun buildMarkdown(
        assistant: Assistant,
        url: String,
        title: String,
        messages: List<ChatMessage>,
        note: String,
        version: String,
        at: Long = System.currentTimeMillis()
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(at))
        val chars = messages.sumOf { it.markdown.length }
        val sb = StringBuilder()

        sb.append("# ").append(assistant.name).append(" · 对话记录\n\n")
        sb.append("- 导出时间：").append(time).append('\n')
        sb.append("- 来源：").append(url.ifBlank { assistant.url }).append('\n')
        if (title.isNotBlank()) sb.append("- 页面标题：").append(title).append('\n')
        sb.append("- 消息数：").append(messages.size)
            .append(" · 约 ").append(chars).append(" 字\n")
        if (note.isNotBlank()) sb.append("- 说明：").append(note).append('\n')
        sb.append("- 工具：AI dock ").append(version).append("（第三方站点网页内容的本地摘录）\n")
        sb.append("\n---\n")

        val myLabel = "我"
        val aiLabel = assistant.name

        messages.forEachIndexed { index, m ->
            val label = when {
                m.isUser -> myLabel
                m.role == "ai" -> aiLabel
                else -> "消息 ${index + 1}"
            }
            sb.append("\n## ").append(label).append("\n\n")
            sb.append(m.markdown.trim()).append('\n')
        }

        sb.append("\n---\n\n")
        sb.append("> 由 AI dock 从页面内容摘录，可能与原始对话排版略有差异；")
        sb.append("网页端的虚拟滚动会导致超出可视区的历史消息不被导出。\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ JS

    private val JS: String = """
(function () {
  var R = { ok: false, source: "", title: (document.title || "").slice(0, 120), url: location.href, note: "", messages: [], error: "" };
  try {
    var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, SVG: 1, CANVAS: 1, INPUT: 1, TEXTAREA: 1, SELECT: 1, BUTTON: 1, VIDEO: 1, AUDIO: 1, IFRAME: 1, TEMPLATE: 1, HEAD: 1, NAV: 1, FOOTER: 1, HEADER: 1 };
    var CHROME = /^(复制|复制代码|重新生成|重新回答|重新发送|停止生成|继续生成|赞|踩|点赞|点踩|分享|朗读|播放|编辑|删除|收藏|反馈|新建对话|新对话|关闭|Copy|Copy code|Regenerate|Rewrite|Share|Read aloud|Edit|Retry|Like|Dislike|Good response|Bad response|More|Stop|Send|Scroll to bottom)$/i;

    function norm(s) {
      return (s || "").replace(/\u00a0/g, " ").replace(/\r/g, "")
        .replace(/[ \t]+\n/g, "\n").replace(/[ \t]{2,}/g, " ")
        .replace(/\n{3,}/g, "\n\n").trim();
    }
    // 代码块必须保留行首缩进，norm 会把连续空格压成一个，这里单独处理
    function codeNorm(s) {
      return (s || "").replace(/\r/g, "").replace(/\u00a0/g, " ")
        .split("\n").map(function (l) {
          return l.replace(/[ \t]+$/, "").replace(/^\t/, "    ");
        }).join("\n").replace(/^\n+/, "").replace(/\n+$/, "");
    }
    // 整条消息收尾清洗：只去行尾空白与多余空行，绝不碰行首缩进（否则代码块会塌）
    function finalNorm(s) {
      return (s || "").replace(/\r/g, "").replace(/\u00a0/g, " ")
        .split("\n").map(function (l) { return l.replace(/[ \t]+$/, ""); })
        .join("\n").replace(/\n{3,}/g, "\n\n").trim();
    }
    function tlen(el) { try { return norm(el.innerText).length; } catch (e) { return 0; } }
    function skipNode(el) {
      if (!el || el.nodeType !== 1) return false;
      if (SKIP[el.tagName]) return true;
      if (el.getAttribute && el.getAttribute("aria-hidden") === "true") return true;
      if (el.getAttribute && el.getAttribute("role") === "button") return true;
      if (el.hidden) return true;
      var t = norm(el.innerText);
      if (!t) return true;
      if (t.length <= 14 && CHROME.test(t) && el.children.length === 0) return true;
      return false;
    }

    // ---------------- HTML -> Markdown ----------------
    function inlineMd(node) {
      var s = "", kids = node.childNodes;
      for (var i = 0; i < kids.length; i++) {
        var c = kids[i];
        if (c.nodeType === 3) { s += c.nodeValue.replace(/[\t\r\n ]+/g, " "); continue; }
        if (c.nodeType !== 1) continue;
        if (SKIP[c.tagName]) continue;
        if (c.getAttribute && c.getAttribute("aria-hidden") === "true") continue;
        var tag = c.tagName;
        if (tag === "BR") { s += "  \n"; continue; }
        if (tag === "IMG") {
          var alt = c.getAttribute("alt") || "图片";
          var src = c.getAttribute("src") || "";
          if (src.indexOf("data:") !== 0 && src) s += "![" + alt + "](" + src + ")";
          continue;
        }
        var inner = inlineMd(c);
        if (!inner.trim() && tag !== "BR") continue;
        if (tag === "STRONG" || tag === "B") s += "**" + inner.trim() + "**";
        else if (tag === "EM" || tag === "I") s += "*" + inner.trim() + "*";
        else if (tag === "DEL" || tag === "S") s += "~~" + inner.trim() + "~~";
        else if (tag === "CODE") s += "`" + norm(c.textContent) + "`";
        else if (tag === "A") {
          var h = c.getAttribute("href") || "";
          if (h && h.indexOf("javascript") !== 0 && h.indexOf("http") === 0) s += "[" + inner.trim() + "](" + h + ")";
          else s += inner;
        } else s += inner;
      }
      return s;
    }

    function hasBlockChild(el) {
      var ks = el.children;
      for (var i = 0; i < ks.length; i++) {
        if (/^(P|PRE|UL|OL|H1|H2|H3|H4|H5|H6|TABLE|BLOCKQUOTE|HR|DIV|SECTION|ARTICLE|FIGURE)$/.test(ks[i].tagName)) return true;
      }
      return false;
    }

    function tableMd(tb) {
      var rows = tb.querySelectorAll("tr"), out = [], maxc = 0;
      for (var i = 0; i < rows.length; i++) {
        var cs = rows[i].children, cells = [];
        for (var j = 0; j < cs.length; j++) cells.push(norm(inlineMd(cs[j])).replace(/\|/g, "\\|"));
        if (cells.length > maxc) maxc = cells.length;
        out.push(cells);
      }
      if (!out.length) return "";
      var lines = [];
      for (var a = 0; a < out.length; a++) {
        var r = out[a].slice();
        while (r.length < maxc) r.push("");
        lines.push("| " + r.join(" | ") + " |");
        if (a === 0) {
          var sep = [];
          for (var b = 0; b < maxc; b++) sep.push("---");
          lines.push("| " + sep.join(" | ") + " |");
        }
      }
      return lines.join("\n");
    }

    function prefixLines(s, p) {
      return s.split("\n").map(function (l) { return l ? p + l : p.trim(); }).join("\n");
    }

    function walk(node, out) {
      var kids = node.childNodes;
      for (var i = 0; i < kids.length; i++) {
        var c = kids[i];
        if (c.nodeType === 3) {
          var t = c.nodeValue.replace(/[\t\r\n ]+/g, " ");
          if (t.trim()) out.push(t);
          continue;
        }
        if (c.nodeType !== 1) continue;
        if (SKIP[c.tagName]) continue;
        if (c.getAttribute && c.getAttribute("aria-hidden") === "true") continue;
        if (skipNode(c)) continue;
        var tag = c.tagName;

        if (/^H[1-6]$/.test(tag)) {
          out.push("\n\n" + new Array((+tag.charAt(1)) + 1).join("#") + " " + norm(inlineMd(c)) + "\n\n");
          continue;
        }
        if (tag === "P") { out.push("\n\n" + norm(inlineMd(c)) + "\n\n"); continue; }
        if (tag === "HR") { out.push("\n\n---\n\n"); continue; }
        if (tag === "PRE") {
          var code = c.querySelector("code");
          var lang = "";
          if (code) {
            var cls = code.className || "";
            var m = /language-([A-Za-z0-9+#-]+)/.exec(cls);
            if (m) lang = m[1];
          }
          var body = codeNorm((code || c).innerText);
          out.push("\n\n```" + lang + "\n" + body + "\n```\n\n");
          continue;
        }
        if (tag === "BLOCKQUOTE") {
          var sub = [];
          walk(c, sub);
          out.push("\n\n" + prefixLines(norm(sub.join("")), "> ") + "\n\n");
          continue;
        }
        if (tag === "UL" || tag === "OL") {
          var lines = [], idx = 1;
          for (var j = 0; j < c.children.length; j++) {
            var li = c.children[j];
            if (li.tagName !== "LI") continue;
            var marker = (tag === "OL") ? ((idx++) + ". ") : "- ";
            var liMd = "";
            var tmp = [];
            walk(li, tmp);
            liMd = norm(tmp.join(""));
            if (!liMd) liMd = norm(inlineMd(li));
            lines.push(marker + liMd.replace(/\n/g, "\n   "));
          }
          out.push("\n\n" + lines.join("\n") + "\n\n");
          continue;
        }
        if (tag === "TABLE") { out.push("\n\n" + tableMd(c) + "\n\n"); continue; }
        if (tag === "DIV" || tag === "SECTION" || tag === "ARTICLE" || tag === "MAIN" || tag === "LI" || tag === "FIGURE" || tag === "SPAN" || tag === "FONT") {
          if (hasBlockChild(c)) { walk(c, out); }
          else {
            var tt = norm(inlineMd(c));
            if (tt) out.push("\n\n" + tt + "\n\n");
          }
          continue;
        }
        var t3 = norm(inlineMd(c));
        if (t3) out.push(t3);
      }
    }

    function mdOf(el) {
      var out = [];
      walk(el, out);
      var s = out.join("");
      s = s.replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n");
      return s.trim();
    }

    // ---------------- 说话人判定 ----------------
    function roleOf(el) {
      var n = el, guard = 0;
      while (n && n.nodeType === 1 && guard < 7) {
        var attr = (n.getAttribute("data-message-author-role") ||
                    n.getAttribute("data-role") ||
                    n.getAttribute("data-author") ||
                    n.getAttribute("data-message-role") || "").toLowerCase();
        if (attr) {
          if (/user|human|me|self/.test(attr)) return "user";
          if (/assistant|bot|ai|model|gpt|system/.test(attr)) return "ai";
        }
        var sig = ((n.getAttribute("data-testid") || "") + " " + (typeof n.className === "string" ? n.className : "") + " " + (n.id || "")).toLowerCase();
        if (/(^|[^a-z])(user|human|query|question|prompt|mine|self)([^a-z]|$)/.test(sig)) return "user";
        if (/(^|[^a-z])(assistant|answer|response|bot|robot|model|markdown|prose|ai-|aimsg|botmsg)([^a-z]|$)/.test(sig)) return "ai";
        n = n.parentElement; guard++;
      }
      return "";
    }

    function roleByAvatar(el) {
      var im = el.querySelectorAll("img[alt]");
      for (var i = 0; i < im.length && i < 4; i++) {
        var a = (im[i].getAttribute("alt") || "").toLowerCase();
        if (/用户|user|you|我 |头像|avatar of you/.test(a)) return "user";
        if (/助手|assistant|bot|ai|gpt|claude|gemini|豆包|千问|元宝|kimi|星火|清言|文心|copilot/.test(a)) return "ai";
      }
      var txt = (el.getAttribute("aria-label") || "") + " " + (el.getAttribute("title") || "");
      txt = txt.toLowerCase();
      if (/user|you|我/.test(txt)) return "user";
      if (/assistant|bot|ai/.test(txt)) return "ai";
      return "";
    }

    function roleByAlign(el, box) {
      try {
        var r = el.getBoundingClientRect(), b = box.getBoundingClientRect();
        if (!r.width || !b.width) return "";
        var lg = r.left - b.left, rg = b.right - r.right;
        if (rg < 28 && lg > Math.min(72, b.width * 0.18) && r.width < b.width * 0.86) return "user";
        if (lg < 28 && rg > Math.min(72, b.width * 0.18) && r.width < b.width * 0.86) return "ai";
      } catch (e) {}
      return "";
    }

    // ---------------- 站点适配器 ----------------
    var host = (location.hostname || "").toLowerCase();
    var ADAPTERS = [
      { re: /(^|\.)chatgpt\.com$/, sel: "[data-message-author-role]", role: function (el) {
          return el.getAttribute("data-message-author-role") === "user" ? "user" : "ai"; },
        body: function (el) { return el.querySelector(".markdown") || el; } },
      { re: /(^|\.)chat\.openai\.com$/, sel: "[data-message-author-role]", role: function (el) {
          return el.getAttribute("data-message-author-role") === "user" ? "user" : "ai"; },
        body: function (el) { return el.querySelector(".markdown") || el; } },
      { re: /(^|\.)gemini\.google\.com$/, sel: "user-query, model-response", role: function (el) {
          return el.tagName.toLowerCase() === "user-query" ? "user" : "ai"; },
        body: function (el) { return el.querySelector(".markdown, message-content, .query-text") || el; } },
      { re: /(^|\.)claude\.ai$/, sel: '[data-testid="user-message"], [data-testid="assistant-message"], .font-claude-message', role: function (el) {
          var t = (el.getAttribute("data-testid") || "").toLowerCase();
          if (t.indexOf("user") >= 0) return "user";
          if (t.indexOf("assistant") >= 0) return "ai";
          return "ai"; },
        body: function (el) { return el; } },
      { re: /(^|\.)copilot\.microsoft\.com$/, sel: '[data-content="user-message"], [data-content="ai-message"]', role: function (el) {
          return (el.getAttribute("data-content") || "").indexOf("user") >= 0 ? "user" : "ai"; },
        body: function (el) { return el; } },
      { re: /chat\.deepseek\.com$/, sel: ".ds-message, .fbb737a4, .aaff8b8f", role: function (el) {
          var c = (typeof el.className === "string" ? el.className : "");
          if (/fbb737a4/.test(c)) return "user";
          if (/aaff8b8f/.test(c)) return "ai";
          return ""; },
        body: function (el) { return el; } },
      { re: /(^|\.)kimi\.com$|moonshot\.cn$/, sel: ".segment-content, .chat-content-item", role: function (el) {
          var n = el.closest ? el.closest("[class*=user],[class*=assistant],[class*=self],[class*=robot]") : null;
          if (!n) return "";
          var c = (typeof n.className === "string" ? n.className : "").toLowerCase();
          if (/user|self/.test(c)) return "user";
          if (/assistant|robot/.test(c)) return "ai";
          return ""; },
        body: function (el) { return el; } },
      { re: /(^|\.)doubao\.com$/, sel: '[data-testid="send_message"], [data-testid="receive_message"], .message-content', role: function (el) {
          var t = (el.getAttribute("data-testid") || "").toLowerCase();
          if (t.indexOf("send") >= 0) return "user";
          if (t.indexOf("receive") >= 0) return "ai";
          return ""; },
        body: function (el) { return el; } },
      { re: /(^|\.)tongyi\.aliyun\.com$/, sel: '[class*="questionItem"], [class*="answerItem"], .bubble', role: function (el) {
          var c = (typeof el.className === "string" ? el.className : "").toLowerCase();
          if (/question/.test(c)) return "user";
          if (/answer/.test(c)) return "ai";
          return ""; },
        body: function (el) { return el; } },
      { re: /(^|\.)yuanbao\.tencent\.com$/, sel: '[class*="agent-chat__bubble"], .hyc-content-md, .hyc-common-markdown', role: function (el) {
          var n = el.closest ? el.closest('[class*="bubble"]') : null;
          if (!n) return "";
          if (n.querySelector('[class*="user"], [class*="human"]')) return "user";
          if (n.querySelector('[class*="ai"], [class*="robot"], [class*="agent"]')) return "ai";
          return "";
        }, body: function (el) { return el; } }
    ];

    function runAdapter(ad) {
      var nodes = document.querySelectorAll(ad.sel);
      if (!nodes || nodes.length < 2) return null;
      var msgs = [];
      for (var i = 0; i < nodes.length && i < 400; i++) {
        var el = nodes[i];
        if (skipNode(el)) continue;
        var body = ad.body(el) || el;
        var md = mdOf(body);
        if (!md || md.length < 1) continue;
        var role = ad.role(el) || "";
        if (!role) role = roleOf(el) || roleByAvatar(el);
        msgs.push({ role: role, md: md });
      }
      if (msgs.length < 2 && msgs.length !== 1) return null;
      return msgs;
    }

    // ---------------- 通用启发式 ----------------
    function findList() {
      var scope = document.querySelector("main") || document.querySelector('[role="main"]') || document.body;
      var all = scope.querySelectorAll("div, section, article, ul, [role=list]");
      var best = null, bestScore = 0;
      var limit = Math.min(all.length, 3000);
      for (var i = 0; i < limit; i++) {
        var el = all[i];
        var kids = el.children;
        if (!kids || kids.length < 2 || kids.length > 120) continue;
        var n = 0, total = 0, sameTag = true, firstTag = "";
        for (var j = 0; j < kids.length; j++) {
          var k = kids[j];
          var L = tlen(k);
          if (L < 2) continue;
          n++; total += L;
          if (!firstTag) firstTag = k.tagName;
          else if (k.tagName !== firstTag) sameTag = false;
        }
        if (n < 2) continue;
        if (total < 12) continue;
        var score = n * (sameTag ? 2.2 : 1) + Math.min(total / 60, 24);
        if (el === scope) score *= 0.6;
        if (score > bestScore) { bestScore = score; best = el; }
      }
      return best;
    }

    function genericFrom(box) {
      var kids = [], msgs = [];
      for (var i = 0; i < box.children.length; i++) {
        var k = box.children[i];
        if (tlen(k) < 1) continue;
        kids.push(k);
      }
      if (kids.length < 2) return null;
      for (var j = 0; j < kids.length; j++) {
        var el = kids[j];
        if (skipNode(el)) continue;
        var md = mdOf(el);
        if (!md) continue;
        var role = roleOf(el) || roleByAvatar(el) || roleByAlign(el, box);
        msgs.push({ role: role, md: md });
      }
      return msgs.length >= 2 ? msgs : null;
    }

    function fillRoles(msgs) {
      var guessed = 0, last = "";
      for (var i = 0; i < msgs.length; i++) {
        if (!msgs[i].role) {
          msgs[i].role = (last === "user") ? "ai" : "user";
          guessed++;
        }
        last = msgs[i].role;
      }
      return guessed;
    }

    // ---------------- 主流程 ----------------
    var msgs = null, src = "", note = "";
    for (var a = 0; a < ADAPTERS.length; a++) {
      if (ADAPTERS[a].re.test(host)) {
        msgs = runAdapter(ADAPTERS[a]);
        if (msgs && msgs.length) { src = "adapter"; break; }
      }
    }
    if (!msgs || !msgs.length) {
      var box = findList();
      if (box) {
        msgs = genericFrom(box);
        if (msgs && msgs.length) {
          src = "generic";
          note = "说话人由页面结构推断，可能与实际角色不完全一致。";
        }
      }
    }
    if (!msgs || !msgs.length) {
      var main = document.querySelector("main") || document.body;
      var whole = norm(main.innerText);
      if (whole.length >= 20) {
        msgs = [{ role: "", md: whole }];
        src = "page";
        note = "未能识别对话结构，已导出整页可见文本。";
      }
    }
    if (!msgs || !msgs.length) {
      R.error = "这个页面还没有可导出的对话内容，先发一条消息试试。";
      return JSON.stringify(R);
    }

    // 未知角色按顺序补齐
    var guessed = 0;
    for (var g = 0; g < msgs.length; g++) { if (!msgs[g].role) { guessed++; } }
    if (guessed > 0 && src !== "page") {
      var filled = fillRoles(msgs);
      if (filled > 0 && src === "generic") {
        note = "有 " + filled + " 条消息的说话人按对话顺序推断，请核对后再使用。";
      }
    }

    // 去掉纯导航/水印之类的超短块
    var clean = [];
    for (var q = 0; q < msgs.length; q++) {
      var t = finalNorm(msgs[q].md);
      if (!t) continue;
      if (t.length < 3 && src !== "page") continue;
      clean.push({ role: msgs[q].role, md: t });
    }
    if (!clean.length) { R.error = "页面里没有可导出的文字。"; return JSON.stringify(R); }

    R.ok = true;
    R.source = src;
    R.note = note;
    R.messages = clean.length > 300 ? clean.slice(clean.length - 300) : clean;
    return JSON.stringify(R);
  } catch (e) {
    R.ok = false;
    R.error = "解析页面时出错：" + (e && e.message ? e.message : "unknown");
    return JSON.stringify(R);
  }
})()
""".trimIndent()
}
