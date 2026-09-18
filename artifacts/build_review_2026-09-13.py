#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「Aetheris 批判性代码审视与一周作战计划」Markdown 渲染成 HTML，并尝试打印 PDF。
路线：markdown -> 自写 HTML（封面 + 目录 + 打印级 CSS）-> Edge headless 打印 PDF。
用法：env -u PYTHONPATH python build_review_2026-09-13.py
"""
import markdown
import pathlib
import re
import shutil
import subprocess
import sys

BASE = pathlib.Path(__file__).resolve().parent
SRC = BASE / "Aetheris-批判性代码审视与一周作战计划-2026-09-13.md"
OUT_HTML = BASE / "Aetheris-批判性代码审视与一周作战计划-2026-09-13.html"
OUT_PDF = BASE / "Aetheris-批判性代码审视与一周作战计划-2026-09-13.pdf"

md_text = SRC.read_text(encoding="utf-8")

html_body = markdown.markdown(
    md_text,
    extensions=["tables", "fenced_code", "sane_lists", "nl2br", "attr_list"],
)


def slugify(text: str) -> str:
    s = re.sub(r"[^\w\u4e00-\u9fff-]+", "-", text.strip()).strip("-").lower()
    return s or "sec"


h_re = re.compile(r"<(h[1-4])>(.*?)</\1>", re.S)


def add_id(m):
    tag, inner = m.group(1), m.group(2)
    sid = slugify(re.sub(r"<[^>]+>", "", inner))
    return f'<{tag} id="{sid}">{inner}</{tag}>'


html_body = h_re.sub(add_id, html_body)

CSS = """
@font-face {
  font-family: "Noto Sans SC";
  font-weight: 400;
  src: local("Noto Sans SC"), url("file:///C:/Windows/Fonts/Noto%20Sans%20SC%20(TrueType).otf") format("opentype");
}
@font-face {
  font-family: "Noto Sans SC";
  font-weight: 500;
  src: local("Noto Sans SC Medium"), url("file:///C:/Windows/Fonts/Noto%20Sans%20SC%20Medium%20(TrueType).otf") format("opentype");
}
@font-face {
  font-family: "Noto Sans SC";
  font-weight: 700;
  src: local("Noto Sans SC Bold"), url("file:///C:/Windows/Fonts/Noto%20Sans%20SC%20Bold%20(TrueType).otf") format("opentype");
}

:root {
  --ink: #0f172a;
  --muted: #5b6472;
  --brand: #1e3a8a;
  --brand-soft: #eef2fb;
  --line: #e3e8f0;
  --red: #b91c1c;
  --red-soft: #fef2f2;
  --amber: #b45309;
  --amber-soft: #fffbeb;
  --green: #047857;
  --green-soft: #ecfdf5;
}

* { box-sizing: border-box; }

body {
  font-family: "Noto Sans SC", "Segoe UI", "Microsoft YaHei", sans-serif;
  color: var(--ink);
  font-size: 10.2pt;
  line-height: 1.78;
  margin: 0;
  padding: 34px 40px 60px;
  background: #ffffff;
  max-width: 1000px;
  margin-left: auto;
  margin-right: auto;
}

/* ---------- 封面 ---------- */
.cover {
  padding: 26px 0 30px;
  border-bottom: 3px solid var(--brand);
  margin-bottom: 26px;
}
.cover .kicker {
  font-size: 8.6pt; letter-spacing: .22em; text-transform: uppercase;
  color: var(--brand); font-weight: 700; margin-bottom: 14px;
}
.cover h1 {
  font-size: 27pt; line-height: 1.3; margin: 0 0 14px; font-weight: 700;
  letter-spacing: -.01em; border: none; padding: 0;
}
.cover .subtitle { font-size: 11pt; color: var(--muted); line-height: 1.7; }
.cover .rule { height: 4px; width: 76px; background: #f59e0b; border-radius: 2px; margin: 20px 0 18px; }
.cover .meta { display: flex; flex-wrap: wrap; gap: 10px 26px; font-size: 9.4pt; color: var(--muted); }
.cover .meta b { color: var(--ink); }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--brand); margin-right: 8px; vertical-align: 1px; }

/* ---------- 目录 ---------- */
.toc {
  background: var(--brand-soft);
  border: 1px solid #d9e1f5;
  border-radius: 12px;
  padding: 18px 24px 20px;
  margin: 0 0 30px;
}
.toc h2 { margin: 0 0 10px; font-size: 12pt; border: none; padding: 0; color: var(--brand); }
.toc ul { margin: 0; padding-left: 20px; columns: 2; column-gap: 32px; }
.toc li { margin: 3px 0; font-size: 9.6pt; }
.toc a { color: var(--ink); text-decoration: none; }

/* ---------- 标题 ---------- */
h1 {
  font-size: 18pt; margin: 34px 0 14px; padding-bottom: 9px;
  border-bottom: 2px solid var(--brand); font-weight: 700;
}
h2 { font-size: 14pt; margin: 26px 0 10px; color: var(--brand); font-weight: 700; }
h3 { font-size: 11.6pt; margin: 20px 0 8px; font-weight: 700; }
h4 { font-size: 10.4pt; margin: 16px 0 6px; color: var(--muted); font-weight: 700; }

p { margin: 9px 0; }
strong { font-weight: 700; }

/* ---------- 引用（用于"一句话结论"） ---------- */
blockquote {
  margin: 14px 0; padding: 14px 18px;
  background: #fffbeb; border-left: 4px solid #f59e0b;
  border-radius: 0 8px 8px 0; color: #422006; font-size: 10.4pt;
}
blockquote p { margin: 6px 0; }

/* ---------- 代码块 ---------- */
pre {
  background: #0f172a; color: #e2e8f0; padding: 14px 16px; border-radius: 8px;
  font-size: 8.8pt; line-height: 1.6; white-space: pre-wrap;
  word-break: break-word; margin: 12px 0; page-break-inside: avoid;
  font-family: "Cascadia Code", "JetBrains Mono", Consolas, monospace;
}
code {
  font-family: "Cascadia Code", "JetBrains Mono", Consolas, monospace;
  font-size: .92em; background: #eef2f7; color: var(--red);
  padding: 1px 5px; border-radius: 4px;
}
pre code { background: transparent; color: inherit; padding: 0; font-size: inherit; }

/* ---------- 表格 ---------- */
table { border-collapse: collapse; width: 100%; margin: 14px 0; font-size: 9.3pt; }
th {
  background: var(--brand); color: #fff; font-weight: 600;
  text-align: left; padding: 7px 10px; border: 1px solid var(--brand);
}
td { padding: 6px 10px; border: 1px solid #dfe4ec; vertical-align: top; }
tr:nth-child(even) td { background: #f7f9fc; }
tr { page-break-inside: avoid; }

/* ---------- 列表 ---------- */
ul, ol { margin: 8px 0; padding-left: 24px; }
li { margin: 4px 0; }

hr { border: none; border-top: 1px solid var(--line); margin: 24px 0; }

a { color: var(--brand); }

/* ---------- 打印 ---------- */
@page {
  size: A4;
  margin: 18mm 16mm 16mm 16mm;
  @top-center {
    content: "Aetheris · 批判性代码审视与一周作战计划";
    font-family: "Noto Sans SC", sans-serif; font-size: 8pt; color: #94a3b8;
  }
  @bottom-right {
    content: counter(page) " / " counter(pages);
    font-family: "Noto Sans SC", sans-serif; font-size: 8pt; color: #94a3b8;
  }
}
@media print {
  body { padding: 0; max-width: none; font-size: 9.6pt; }
  .cover { page-break-after: always; }
  .toc { page-break-after: always; }
  h1 { page-break-before: always; }
  h1:first-of-type { page-break-before: auto; }
  pre, table, blockquote { page-break-inside: avoid; }
}
"""

TITLE = "Aetheris 批判性代码审视与一周作战计划"

TOC_ITEMS = [
    ("0-一句话结论", "0 · 一句话结论"),
    ("1-审视方法与取证基线", "1 · 审视方法与取证基线"),
    ("2-两处必须更正的事实", "2 · 两处必须更正的事实"),
    ("3-问题总表", "3 · 问题总表"),
    ("4-阻断级问题详解", "4 · 阻断级问题详解（B1–B3）"),
    ("5-高危问题详解", "5 · 高危问题详解（H1–H7）"),
    ("6-中危问题", "6 · 中危问题（M1–M11）"),
    ("7-低危与仓库卫生", "7 · 低危与仓库卫生（L1–L5）"),
    ("8-下一步急需解决的东西", "8 · 下一步急需解决的东西"),
    ("9-接下来一周的详细作战计划", "9 · 接下来一周的详细作战计划"),
    ("10-一条逆耳的提醒", "10 · 一条逆耳的提醒（战略层）"),
    ("附录本次审视的取证命令索引", "附 · 取证命令索引"),
]

toc_html = "\n".join(
    f'<li><a href="#{sid}"><span class="t">{label}</span></a></li>' for sid, label in TOC_ITEMS
)

full_html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{TITLE}</title>
<style>{CSS}</style>
</head>
<body>

<div class="cover">
  <div class="kicker">Critical Code Review &amp; Weekly Battle Plan</div>
  <h1>先让它摔不坏<br>再让它交得出去</h1>
  <div class="subtitle">
    Aetheris 数据平台 · 批判性代码审视与一周作战计划<br>
    26 个问题的定位、取证与修复顺序 · 2026-09-14 — 09-20 逐日排期
  </div>
  <div class="rule"></div>
  <div class="meta">
    <div><span class="dot"></span><span>审视范围：<b>整个 Monorepo</b></span></div>
    <div><span class="dot" style="background:#b91c1c"></span><span>阻断级：<b>3 项</b></span></div>
    <div><span class="dot" style="background:#f59e0b"></span><span>高危：<b>7 项</b></span></div>
    <div><span class="dot" style="background:#10b981"></span><span>审视日期：<b>2026-09-13</b></span></div>
  </div>
</div>

<div class="toc">
  <h2>目录</h2>
  <ul>
{toc_html}
  </ul>
</div>

{html_body}

</body>
</html>
"""

OUT_HTML.write_text(full_html, encoding="utf-8")
print("HTML 生成完成:", OUT_HTML.name, "字符数:", len(full_html))

# ---------- 尝试用 Edge headless 打印 PDF ----------
candidates = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]
edge = next((c for c in candidates if pathlib.Path(c).exists()), None)
if edge is None:
    edge = shutil.which("msedge")
if not edge:
    print("未找到 Edge，跳过 PDF（HTML 已可用）")
    sys.exit(0)

cmd = [
    edge,
    "--headless",
    "--disable-gpu",
    "--no-pdf-header-footer",
    f"--print-to-pdf={OUT_PDF}",
    OUT_HTML.as_uri(),
]
print("打印 PDF:", edge)
proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
if OUT_PDF.exists():
    print("PDF 生成完成:", OUT_PDF.name, f"{OUT_PDF.stat().st_size/1024/1024:.2f} MB")
else:
    print("PDF 生成失败，退出码:", proc.returncode)
    print((proc.stderr or "")[-800:])
