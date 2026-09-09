# -*- coding: utf-8 -*-
"""生成《下一枪打哪里》PDF 报告。"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from report_data import META, EXEC_SUMMARY, METHODOLOGY, CATEGORIES, TOP_PICKS, ACTION_PLAN

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer, Table,
    TableStyle, PageBreak, KeepTogether, HRFlowable,
)

# ---------- 字体 ----------
FONT_REG = "YH"
FONT_BOLD = "YHB"

def register_fonts():
    global FONT_REG, FONT_BOLD
    candidates = [
        (FONT_REG, r"C:\Windows\Fonts\msyh.ttc", 0),
        (FONT_BOLD, r"C:\Windows\Fonts\msyhbd.ttc", 0),
    ]
    try:
        for name, path, idx in candidates:
            pdfmetrics.registerFont(TTFont(name, path, subfontIndex=idx))
        return True
    except Exception:
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
        FONT_REG = FONT_BOLD = "STSong-Light"
        return False

register_fonts()

# ---------- 配色 ----------
INK = colors.HexColor("#1a1d24")        # 正文近黑
ACCENT = colors.HexColor("#2563eb")     # 主色 蓝
ACCENT_DARK = colors.HexColor("#1e40af")
MUTED = colors.HexColor("#5b6472")      # 次要文字
LINE = colors.HexColor("#d8dde6")       # 分隔线
BG_SOFT = colors.HexColor("#f3f6fb")    # 浅底
BG_HEAD = colors.HexColor("#e8eefc")    # 表头底
GOOD = colors.HexColor("#15803d")
WARN = colors.HexColor("#b45309")
BAD = colors.HexColor("#b91c1c")

PAGE_W, PAGE_H = A4
MARGIN = 18 * mm

# ---------- 样式 ----------
def st(name, **kw):
    base = dict(fontName=FONT_REG, fontSize=10, leading=16, textColor=INK,
                alignment=TA_JUSTIFY, spaceAfter=4)
    base.update(kw)
    return ParagraphStyle(name, **base)

S = {
    "cover_title": st("cover_title", fontName=FONT_BOLD, fontSize=34, leading=44,
                      textColor=colors.white, alignment=TA_LEFT, spaceAfter=10),
    "cover_sub": st("cover_sub", fontSize=15, leading=24, textColor=colors.HexColor("#cdd9f0"),
                    alignment=TA_LEFT),
    "cover_meta": st("cover_meta", fontSize=10.5, leading=18, textColor=colors.HexColor("#9fb2d6"),
                     alignment=TA_LEFT),
    "h1": st("h1", fontName=FONT_BOLD, fontSize=17, leading=24, textColor=ACCENT_DARK,
             spaceBefore=6, spaceAfter=8, alignment=TA_LEFT),
    "h2": st("h2", fontName=FONT_BOLD, fontSize=12.5, leading=18, textColor=INK,
             spaceBefore=8, spaceAfter=4, alignment=TA_LEFT),
    "body": st("body"),
    "body_muted": st("body_muted", textColor=MUTED),
    "item_name": st("item_name", fontName=FONT_BOLD, fontSize=11.5, leading=16,
                    textColor=INK, alignment=TA_LEFT, spaceAfter=2),
    "item_tag": st("item_tag", fontSize=9.5, leading=14, textColor=ACCENT_DARK,
                   alignment=TA_LEFT, spaceAfter=4),
    "field_label": st("field_label", fontName=FONT_BOLD, fontSize=9, leading=14,
                      textColor=MUTED, alignment=TA_LEFT),
    "field_val": st("field_val", fontSize=9.5, leading=14, alignment=TA_LEFT),
    "cell": st("cell", fontSize=8, leading=11, alignment=TA_LEFT),
    "cell_center": st("cell_center", fontSize=8, leading=11, alignment=TA_CENTER),
    "cell_head": st("cell_head", fontName=FONT_BOLD, fontSize=8.5, leading=12,
                    textColor=ACCENT_DARK, alignment=TA_CENTER),
    "score_good": st("score_good", fontName=FONT_BOLD, fontSize=8, leading=11,
                     textColor=GOOD, alignment=TA_CENTER),
    "score_mid": st("score_mid", fontName=FONT_BOLD, fontSize=8, leading=11,
                    textColor=WARN, alignment=TA_CENTER),
    "score_bad": st("score_bad", fontName=FONT_BOLD, fontSize=8, leading=11,
                    textColor=BAD, alignment=TA_CENTER),
}

# ---------- 页面模板 ----------
def on_cover(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(INK)
    canvas.rect(0, 0, PAGE_W, PAGE_H, stroke=0, fill=1)
    canvas.setFillColor(ACCENT)
    canvas.rect(0, PAGE_H - 14 * mm, PAGE_W, 3 * mm, stroke=0, fill=1)
    canvas.setFillColor(colors.HexColor("#232838"))
    canvas.circle(PAGE_W - 30 * mm, 40 * mm, 55 * mm, stroke=0, fill=1)
    canvas.circle(20 * mm, 60 * mm, 30 * mm, stroke=0, fill=1)
    canvas.restoreState()

def on_page(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN, PAGE_H - 14 * mm, PAGE_W - MARGIN, PAGE_H - 14 * mm)
    canvas.setFont(FONT_REG, 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(MARGIN, PAGE_H - 12 * mm, META["title"] + " · " + META["subtitle"])
    canvas.drawRightString(PAGE_W - MARGIN, PAGE_H - 12 * mm, META["date"])
    canvas.setStrokeColor(LINE)
    canvas.line(MARGIN, 14 * mm, PAGE_W - MARGIN, 14 * mm)
    canvas.drawCentredString(PAGE_W / 2, 9.5 * mm, f"{doc.page}")
    canvas.restoreState()

def build_doc(path):
    doc = BaseDocTemplate(path, pagesize=A4,
                          leftMargin=MARGIN, rightMargin=MARGIN,
                          topMargin=20 * mm, bottomMargin=20 * mm,
                          title=META["title"] + " - " + META["subtitle"],
                          author=META["author"])
    frame = Frame(MARGIN, 20 * mm, PAGE_W - 2 * MARGIN, PAGE_H - 40 * mm, id="main")
    cover_frame = Frame(MARGIN, 20 * mm, PAGE_W - 2 * MARGIN, PAGE_H - 40 * mm, id="cover")
    doc.addPageTemplates([
        PageTemplate(id="Cover", frames=[cover_frame], onPage=on_cover),
        PageTemplate(id="Main", frames=[frame], onPage=on_page),
    ])
    return doc

# ---------- 构件 ----------
def score_style(total):
    if total >= 16:
        return S["score_good"]
    if total >= 13:
        return S["score_mid"]
    return S["score_bad"]

def field_block(label, value):
    return Paragraph(f'<font name="{FONT_BOLD}" color="#5b6472">{label}</font>'
                     f'<font color="#1a1d24">　{value}</font>', S["field_val"])

def render_item(idx, item):
    total = sum(item["scores"])
    w, f, sp, m = item["scores"]
    head = Table(
        [[Paragraph(f'{idx}. {item["name"]}', S["item_name"]),
          Paragraph(f"综合 {total}/20", score_style(total))]],
        colWidths=[130 * mm, 30 * mm])
    head.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("ALIGN", (1, 0), (1, 0), "RIGHT"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    rows = [
        head,
        Paragraph(item["tagline"], S["item_tag"]),
        field_block("市场空缺 / 信息差", item["gap"]),
        field_block("需求证据", item["evidence"]),
        field_block("技能匹配", item["fit"]),
        field_block("变现路径", item["money"]),
        field_block("周期预估", item["cycle"]),
        field_block("核心风险", item["risk"]),
        Paragraph(f'<font name="{FONT_BOLD}" color="#5b6472">评分</font>'
                  f'<font color="#1a1d24">　窗口期 {w} · 技能匹配 {f} · 变现速度 {sp} · '
                  f'竞争烈度 {m}（5=竞争小）　→ 综合 <font name="{FONT_BOLD}">{total}</font>/20</font>',
                  S["field_val"]),
        Spacer(1, 2 * mm),
        HRFlowable(width="100%", thickness=0.5, color=LINE),
        Spacer(1, 3 * mm),
    ]
    return KeepTogether(rows)

def overview_table():
    header = ["#", "方向", "所属类别", "窗口", "匹配", "变现", "竞争", "综合"]
    data = [[Paragraph(h, S["cell_head"]) for h in header]]
    idx = 0
    for cat in CATEGORIES:
        for item in cat["items"]:
            idx += 1
            total = sum(item["scores"])
            data.append([
                Paragraph(str(idx), S["cell_center"]),
                Paragraph(item["name"], S["cell"]),
                Paragraph(cat["title"].split("（")[0], S["cell"]),
                Paragraph(str(item["scores"][0]), S["cell_center"]),
                Paragraph(str(item["scores"][1]), S["cell_center"]),
                Paragraph(str(item["scores"][2]), S["cell_center"]),
                Paragraph(str(item["scores"][3]), S["cell_center"]),
                Paragraph(f"<b>{total}</b>", score_style(total)),
            ])
    widths = [9 * mm, 62 * mm, 38 * mm, 12 * mm, 12 * mm, 12 * mm, 12 * mm, 17 * mm]
    t = Table(data, colWidths=widths, repeatRows=1)
    style = [
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("BACKGROUND", (0, 0), (-1, 0), BG_HEAD),
        ("LINEBELOW", (0, 0), (-1, 0), 0.8, ACCENT),
        ("GRID", (0, 1), (-1, -1), 0.4, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 2.5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 2.5),
        ("LEFTPADDING", (0, 0), (-1, -1), 3),
        ("RIGHTPADDING", (0, 0), (-1, -1), 3),
    ]
    for i in range(2, len(data), 2):
        style.append(("BACKGROUND", (0, i), (-1, i), BG_SOFT))
    t.setStyle(TableStyle(style))
    return t

# ---------- 组装 ----------
def build():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "独立开发者55个细分方向全景报告.pdf")
    doc = build_doc(out)
    story = []

    # 封面
    story.append(Spacer(1, 70 * mm))
    story.append(Paragraph(META["title"], S["cover_title"]))
    story.append(Spacer(1, 4 * mm))
    story.append(Paragraph(META["subtitle"], S["cover_sub"]))
    story.append(Spacer(1, 16 * mm))
    story.append(Paragraph(META["author"], S["cover_meta"]))
    story.append(Paragraph(f'{META["date"]}　·　{META["version"]}　·　共 {sum(len(c["items"]) for c in CATEGORIES)} 个方向', S["cover_meta"]))
    story.append(PageBreak())

    # 切换到主模板
    from reportlab.platypus import NextPageTemplate
    story.insert(1, NextPageTemplate("Main"))

    # 摘要
    story.append(Paragraph("执行摘要", S["h1"]))
    for p in EXEC_SUMMARY:
        story.append(Paragraph(p, S["body"]))
        story.append(Spacer(1, 2 * mm))
    story.append(Spacer(1, 4 * mm))

    # Top picks
    story.append(Paragraph("Top 3 推荐组合", S["h1"]))
    rows = []
    for tp in TOP_PICKS:
        rows.append([
            Paragraph(f'<font name="{FONT_BOLD}" color="#1e40af">{tp["rank"]}</font>', S["cell_center"]),
            Paragraph(f'<font name="{FONT_BOLD}">{tp["name"]}</font><br/>'
                      f'<font color="#5b6472" size="8.5">{tp["reason"]}</font>', S["cell"]),
        ])
    t = Table(rows, colWidths=[18 * mm, 156 * mm])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, LINE),
        ("BACKGROUND", (0, 0), (0, -1), BG_HEAD),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]))
    story.append(t)
    story.append(Spacer(1, 6 * mm))

    # 方法论
    story.append(Paragraph("筛选方法论：三种错位", S["h1"]))
    for title, desc in METHODOLOGY:
        story.append(Paragraph(title, S["h2"]))
        story.append(Paragraph(desc, S["body"]))
        story.append(Spacer(1, 1.5 * mm))
    story.append(PageBreak())

    # 总览表
    story.append(Paragraph("55 个方向评分总览", S["h1"]))
    story.append(Paragraph("评分维度：窗口（机会衰减速度，5=按天计）· 匹配（与现有技能/资产复用度）· "
                           "变现（从动工到首笔收入）· 竞争（5=几乎无人防守）。综合满分 20，"
                           "≥16 强烈推荐，13-15 推荐，10-12 观察。", S["body_muted"]))
    story.append(Spacer(1, 2 * mm))
    story.append(overview_table())
    story.append(PageBreak())

    # 分类明细
    idx = 0
    for ci, cat in enumerate(CATEGORIES, 1):
        story.append(Paragraph(f"第{"一二三四五六七八九十"[ci-1]}类 · {cat['title']}", S["h1"]))
        story.append(Paragraph(cat["intro"], S["body_muted"]))
        story.append(Spacer(1, 2 * mm))
        story.append(HRFlowable(width="100%", thickness=1, color=ACCENT))
        story.append(Spacer(1, 3 * mm))
        for item in cat["items"]:
            idx += 1
            story.append(render_item(idx, item))
        if ci < len(CATEGORIES):
            story.append(PageBreak())

    # 行动计划
    story.append(Paragraph("8 周行动计划建议", S["h1"]))
    rows = []
    for phase, desc in ACTION_PLAN:
        rows.append([
            Paragraph(f'<font name="{FONT_BOLD}" color="#1e40af">{phase}</font>', S["cell_center"]),
            Paragraph(desc, S["cell"]),
        ])
    t = Table(rows, colWidths=[22 * mm, 152 * mm])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, LINE),
        ("BACKGROUND", (0, 0), (0, -1), BG_HEAD),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]))
    story.append(t)
    story.append(Spacer(1, 6 * mm))

    # 结语
    story.append(Paragraph("结语", S["h1"]))
    story.append(Paragraph(
        "这个量级（1-2 个月、单人）的合理预期，是验证出一条月入几千到几万的小现金流，而不是爆款。"
        "爆款是概率游戏，现金流是工程游戏——后者才是你的主场。"
        "本报告给出的 55 个方向不是菜单，是一张标出了水位、暗流与鱼群的航海图："
        "选水位最急（窗口期）、离你船最近（技能匹配）、别人没下网（竞争烈度）的那片海下钩。",
        S["body"]))
    story.append(Spacer(1, 3 * mm))
    story.append(Paragraph(
        f'<font color="#5b6472" size="8.5">本报告基于 2026 年 9 月公开信息整理分析，'
        f'涉及政策条款请以官方最新文本为准；市场判断为观点性内容，不构成投资建议。</font>',
        S["body"]))

    doc.build(story)
    print("OK:", out)

if __name__ == "__main__":
    build()
