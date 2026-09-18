#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Aetheris 全项目审查报告 (Markdown) 渲染成精美 PDF。
路线：markdown -> 自写 HTML + 打印级 CSS -> Edge headless 打印 PDF。
"""
import markdown
import pathlib
import re

BASE = pathlib.Path(__file__).resolve().parent
SRC = BASE / "Aetheris-全项目审查报告.md"
OUT_HTML = BASE / "Aetheris-全项目审查报告.html"
OUT_PDF = BASE / "Aetheris-全项目审查报告.pdf"

md_text = SRC.read_text(encoding="utf-8")

# 使用支持表格/代码高亮/目录锚点的扩展
html_body = markdown.markdown(
    md_text,
    extensions=["tables", "fenced_code", "sane_lists", "nl2br"],
)

# 给标题添加锚点 id（用于目录跳转）—— 用中文标题做 id 不稳定，改为自动编号锚点。
# 简单方案：处理 <h1>/<h2> 生成 slug 锚点。
def slugify(text: str) -> str:
    s = re.sub(r"[^\w\u4e00-\u9fff-]+", "-", text.strip()).strip("-").lower()
    return s or "sec"

# 递归遍历 HTML，为标题加 id
h_re = re.compile(r"<(h[1-4])>(.*?)</\1>", re.S)
counter = {"n": 0}
def add_id(m):
    counter["n"] += 1
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

@page {
  size: A4;
  margin: 22mm 18mm 20mm 18mm;
  @top-center {
    content: "Aetheris 数据平台 · 全项目深度审查报告";
    font-family: "Noto Sans SC"; font-size: 8.5pt; color: #9aa3b2;
    border-bottom: 0.5pt solid #e4e8ef; width: 100%; padding-bottom: 4px;
  }
  @bottom-center {
    content: counter(page) " / " counter(pages);
    font-family: "Noto Sans SC"; font-size: 8.5pt; color: #9aa3b2;
  }
  @bottom-right {
    content: "2026-09-11";
    font-family: "Noto Sans SC"; font-size: 8pt; color: #b8bfca;
  }
}

* { box-sizing: border-box; }

html, body {
  margin: 0; padding: 0;
  font-family: "Noto Sans SC", "Microsoft YaHei", "PingFang SC", sans-serif;
  font-size: 10.5pt; line-height: 1.75; color: #2b3340;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}

/* ---------- 封面 ---------- */
.cover {
  page-break-after: always;
  break-after: page;
  min-height: 240mm;
  padding-top: 90mm;
  padding-left: 4mm; padding-right: 4mm;
  position: relative;
}
.cover .kicker {
  font-size: 11pt; letter-spacing: 6px; color: #6b7d99; text-transform: uppercase;
  margin-bottom: 18px;
}
.cover h1 {
  font-size: 34pt; font-weight: 700; line-height: 1.25; margin: 0 0 14px 0;
  color: #101828; letter-spacing: 1px;
}
.cover .subtitle {
  font-size: 13pt; color: #4b5568; line-height: 1.7; margin: 0 0 26px 0;
}
.cover .rule {
  width: 72px; height: 5px; background: linear-gradient(90deg, #3b82f6, #8b5cf6);
  border-radius: 3px; margin-bottom: 26px;
}
.cover .meta {
  display: flex; flex-wrap: wrap; gap: 14px 28px; font-size: 9.5pt; color: #6b7280;
}
.cover .meta b { color: #111827; font-weight: 600; }
.cover .meta div { display: flex; align-items: center; gap: 6px; }
.cover .dot {
  width: 8px; height: 8px; border-radius: 50%; display: inline-block;
  background: #3b82f6;
}

/* ---------- 目录 ---------- */
.toc { page-break-before: always; page-break-after: always; break-before: page; break-after: page; }
.toc h2 { font-size: 18pt; color: #101828; margin: 0 0 16px 0; border: none; padding: 0; }
.toc ul { list-style: none; padding: 0; margin: 0; }
.toc li { margin: 0; border-bottom: 0.5pt dotted #d9dee7; }
.toc a {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 7px 2px; text-decoration: none; color: #334155; font-size: 10.5pt;
}
.toc a .t { font-weight: 500; }
.toc a .p { color: #9aa3b2; font-size: 9pt; }
.toc a:hover .t { color: #2563eb; }

/* ---------- 标题 ---------- */
h1 { font-size: 20pt; color: #0f172a; margin: 0 0 14px 0;
     padding-bottom: 8px; border-bottom: 2px solid #e5e9f0; page-break-after: avoid; }
h2 { font-size: 15pt; color: #0f172a; margin: 26px 0 10px 0;
     padding-left: 10px; border-left: 4px solid #3b82f6; page-break-after: avoid; }
h3 { font-size: 12.5pt; color: #1e293b; margin: 20px 0 8px 0; page-break-after: avoid; }
h4 { font-size: 11pt; color: #334155; margin: 16px 0 6px 0; page-break-after: avoid; }

p { margin: 8px 0; text-align: justify; }
strong { color: #0f172a; font-weight: 600; }
a { color: #2563eb; text-decoration: none; }

/* ---------- 引用块 ---------- */
blockquote {
  margin: 14px 0; padding: 12px 16px; border-left: 4px solid #8b5cf6;
  background: #f6f4ff; border-radius: 0 8px 8px 0; color: #3f3a63;
}
blockquote p { margin: 4px 0; }

/* ---------- 代码块 ---------- */
pre {
  background: #0f172a; color: #e2e8f0; padding: 14px 16px; border-radius: 8px;
  font-size: 8.8pt; line-height: 1.55; overflow-x: hidden; white-space: pre-wrap;
  word-break: break-word; margin: 12px 0; page-break-inside: avoid;
  font-family: "Cascadia Code", "JetBrains Mono", Consolas, monospace;
}
code {
  font-family: "Cascadia Code", "JetBrains Mono", Consolas, monospace;
  font-size: 0.92em; background: #eef2f7; color: #b91c1c; padding: 1px 5px;
  border-radius: 4px;
}
pre code { background: transparent; color: inherit; padding: 0; font-size: inherit; }

/* ---------- 表格 ---------- */
table {
  border-collapse: collapse; width: 100%; margin: 14px 0; font-size: 9.3pt;
  page-break-inside: auto;
}
th {
  background: #1e3a8a; color: #ffffff; font-weight: 600; text-align: left;
  padding: 7px 10px; border: 1px solid #1e3a8a;
}
td { padding: 6px 10px; border: 1px solid #dfe4ec; vertical-align: top; }
tr:nth-child(even) td { background: #f7f9fc; }
tr { page-break-inside: avoid; }

/* ---------- 列表 ---------- */
ul, ol { margin: 8px 0; padding-left: 24px; }
li { margin: 4px 0; }

/* ---------- 分隔线 ---------- */
hr { border: none; border-top: 1px solid #e5e9f0; margin: 22px 0; }

/* 让大章节独立起页 */
h1 { page-break-before: always; }
h1:first-of-type { page-break-before: auto; }
"""

full_html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>Aetheris 全项目深度审查报告</title>
<style>{CSS}</style>
</head>
<body>

<div class="cover">
  <div class="kicker">Technical Review Report</div>
  <h1>让数据有序流动<br>让价值清晰发生</h1>
  <div class="subtitle">Aetheris 数据平台 · 全项目深度审查报告<br>从代码实现到模块连接，从安全、性能到功能设计的完整剖析</div>
  <div class="rule"></div>
  <div class="meta">
    <div><span class="dot"></span><span>审查范围：<b>整个 Monorepo</b></span></div>
    <div><span class="dot" style="background:#8b5cf6"></span><span>后端：<b>10 个微服务</b></span></div>
    <div><span class="dot" style="background:#10b981"></span><span>前端：<b>React 18 控制台</b></span></div>
    <div><span class="dot" style="background:#f59e0b"></span><span>报告日期：<b>2026-09-11</b></span></div>
  </div>
</div>

<div class="toc">
  <h2>目录</h2>
  <ul>
    <li><a href="#0-一句话结论"><span class="t">0 · 一句话结论</span></a></li>
    <li><a href="#1-这个项目是什么"><span class="t">1 · 这个项目是什么（给大一新生的导读）</span></a></li>
    <li><a href="#2-代码体量全景"><span class="t">2 · 代码体量全景</span></a></li>
    <li><a href="#3-总体架构与模块职责地图"><span class="t">3 · 总体架构与模块职责地图</span></a></li>
    <li><a href="#4-服务间如何连接"><span class="t">4 · 服务间如何连接：一张通信关系网</span></a></li>
    <li><a href="#5-安全设计"><span class="t">5 · 安全设计：这不是演示项目，这是安全工程</span></a></li>
    <li><a href="#6-性能设计"><span class="t">6 · 性能设计：从 SQL 分区到分布式调度</span></a></li>
    <li><a href="#7-功能设计上的巧妙与创新点"><span class="t">7 · 功能设计上的巧妙与创新点</span></a></li>
    <li><a href="#8-每个模块深入剖析"><span class="t">8 · 每个模块深入剖析</span></a></li>
    <li><a href="#9-工程纪律与踩坑记录"><span class="t">9 · 工程纪律与踩坑记录</span></a></li>
    <li><a href="#10-已知边界与下一步建议"><span class="t">10 · 已知边界与下一步建议</span></a></li>
    <li><a href="#附报告如何阅读"><span class="t">附 · 报告如何阅读</span></a></li>
  </ul>
</div>

{html_body}

</body>
</html>
"""

OUT_HTML.write_text(full_html, encoding="utf-8")
print("HTML 生成完成:", OUT_HTML)
print("字符数:", len(full_html))
