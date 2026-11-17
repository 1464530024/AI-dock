# -*- coding: utf-8 -*-
"""把 4 个国外助手的官方矢量件渲染成 512px 透明 PNG（带内边距，便于后续裁圆）。

源：
  openai_si.svg   Simple Icons v13 · OpenAI 官方六瓣结（单色）
  gemini_si.svg   Simple Icons v13 · Google Gemini 四角星（单色，这里套官方蓝紫渐变）
  claude_si.svg   Simple Icons v13 · Claude 星芒（单色，这里套品牌珊瑚色）
  copilot_svgl.svg svgl · Microsoft Copilot 缎带（单色，这里套 Microsoft 蓝）
"""
import re, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = r"C:\Users\gzh17\AppData\Local\ms-playwright\chromium_headless_shell-1243\chrome-headless-shell-win64\chrome-headless-shell.exe"

CANVAS = 512
INNER = 400.0          # 内容占画布的 78%

SPECS = [
    # id, 源文件, 填充（solid 颜色 或 (起,中,止) 渐变）, 渐变方向
    ("chatgpt", "openai_si.svg", "#000000", None),
    ("gemini", "gemini_si.svg", ("#4285F4", "#9B72CB", "#D96570"), ("0%", "100%", "0%", "0%")),
    ("claude", "claude_si.svg", "#D97757", None),
    ("copilot", "copilot_svgl.svg", "#0F6CBD", None),
]


def read_svg(path):
    s = open(path, "r", encoding="utf-8").read()
    vb = re.search(r'viewBox="([\d.\-\s]+)"', s)
    if not vb:
        raise SystemExit("没有 viewBox: " + path)
    nums = [float(x) for x in vb.group(1).split()]
    # 取所有 path 的 d（简单图标都只有 1 条）
    ds = re.findall(r'<path[^>]*\sd="([^"]+)"', s)
    if not ds:
        raise SystemExit("没有 path: " + path)
    return nums, ds


def build_html(vb, ds, fill, grad, out_html):
    vx, vy, vw, vh = vb
    scale = INNER / max(vw, vh)
    dw, dh = vw * scale, vh * scale
    tx, ty = (CANVAS - dw) / 2, (CANVAS - dh) / 2

    defs = ""
    if isinstance(fill, tuple):
        a, b, c = fill
        stops = "".join(
            f'<stop offset="{o}" stop-color="{col}"/>'
            for o, col in zip((0, 0.5, 1), (a, b, c))
        )
        defs = (f'<defs><linearGradient id="g" x1="{grad[0]}" y1="{grad[1]}" '
                f'x2="{grad[2]}" y2="{grad[3]}">{stops}</linearGradient></defs>')
        fill = "url(#g)"

    paths = "".join(f'<path d="{d}" fill="{fill}"/>' for d in ds)
    html = f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}
svg{{display:block}}
</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" viewBox="0 0 {CANVAS} {CANVAS}">
{defs}
<g transform="translate({tx:.3f},{ty:.3f}) scale({scale:.6f}) translate({-vx},{-vy})">{paths}</g>
</svg></body></html>"""
    open(out_html, "w", encoding="utf-8").write(html)
    return scale


def shoot(html, png):
    cmd = [
        CHROME, "--disable-gpu", "--hide-scrollbars", "--no-sandbox",
        "--default-background-color=00000000",
        f"--window-size={CANVAS},{CANVAS}",
        "--force-device-scale-factor=1",
        f"--screenshot={png}",
        "file:///" + html.replace("\\", "/"),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    if not os.path.exists(png) or os.path.getsize(png) < 600:
        print("FAIL", os.path.basename(png), r.stdout[-400:], r.stderr[-400:])
        return False
    print(f"OK   {os.path.basename(png):22s} {os.path.getsize(png):7d} bytes")
    return True


for id_, src, fill, grad in SPECS:
    vb, ds = read_svg(os.path.join(HERE, src))
    h = os.path.join(HERE, f"_render_{id_}.html")
    p = os.path.join(HERE, f"{id_}_src.png")
    s = build_html(vb, ds, fill, grad, h)
    print(f"{id_:9s} viewBox={vb} paths={len(ds)} scale={s:.3f}")
    shoot(h, p)
