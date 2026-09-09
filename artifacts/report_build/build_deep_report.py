# -*- coding: utf-8 -*-
"""生成《落枪前验证》深度调研 PDF。复用主报告的字体与配色方案。"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from deep_report_data import META, KEY_FINDINGS, DIRECTIONS, DECISION, ACTION

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer, Table,
    TableStyle, PageBreak, KeepTogether, HRFlowable, NextPageTemplate,
)

FONT_REG = "YH"
FONT_BOLD = "YHB"

def register_fonts():
    global FONT_REG, FONT_BOLD
    try:
        pdfmetrics.registerFont(TTFont(FONT_REG, r"C:\Windows\Fonts\msyh.ttc", subfontIndex=0))
        pdfmetrics.registerFont(TTFont(FONT_BOLD, r"C:\Windows\Fonts\msyhbd.ttc", subfontIndex=0))
    except Exception:
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
        FONT_REG = FONT_BOLD = "STSong-Light"

register_fonts()

INK = colors.HexColor("#1a1d24")
ACCENT = colors.HexColor("#2563eb")
ACCENT_DARK = colors.HexColor("#1e40af")
MUTED = colors.HexColor("#5b6472")
LINE = colors.HexColor("#d8dde6")
BG_SOFT = colors.HexColor("#f3f6fb")
BG_HEAD = colors.HexColor("#e8eefc")
GOOD = colors.HexColor("#15803d")
WARN = colors.HexColor("#b45309")
BAD = colors.HexColor("#b91c1c")

PAGE_W, PAGE_H = A4
MARGIN = 18 * mm

def st(name, **kw):
    base = dict(fontName=FONT_REG, fontSize=10, leading=16, textColor=INK,
                alignment=TA_JUSTIFY, spaceAfter=4)
    base.update(kw)
    return ParagraphStyle(name, **base)

S = {
    "cover_title": st("cover_title", fontName=FONT_BOLD, fontSize=32, leading=42,
                      textColor=colors.white, alignment=TA_LEFT, spaceAfter=10),
    "cover_sub": st("cover_sub", fontSize=15, leading=24, textColor=colors.HexColor("#cdd9f0"),
                    alignment=TA_LEFT),
    "cover_meta": st("cover_meta", fontSize=10.5, leading=18, textColor=colors.HexColor("#9fb2d6"),
                     alignment=TA_LEFT),
    "h1": st("h1", fontName=FONT_BOLD, fontSize=17, leading=24, textColor=ACCENT_DARK,
             spaceBefore=4, spaceAfter=8, alignment=TA_LEFT),
    "h2": st("h2", fontName=FONT_BOLD, fontSize=12.5, leading=18, textColor=INK,
             spaceBefore=8, spaceAfter=4, alignment=TA_LEFT),
    "h3": st("h3", fontName=FONT_BOLD, fontSize=10.5, leading=15, textColor=ACCENT_DARK,
             spaceBefore=6, spaceAfter=3, alignment=TA_LEFT),
    "body": st("body"),
    "body_muted": st("body_muted", textColor=MUTED),
    "dir_head": st("dir_head", fontName=FONT_BOLD, fontSize=14, leading=20,
                   textColor=colors.white, alignment=TA_LEFT),
    "dir_verdict": st("dir_verdict", fontSize=10.5, leading=16,
                      textColor=colors.HexColor("#d3ddf3"), alignment=TA_LEFT),
    "cell": st("cell", fontSize=9, leading=13, alignment=TA_LEFT),
    "cell_center": st("cell_center", fontSize=9, leading=13, alignment=TA_CENTER),
    "cell_head": st("cell_head", fontName=FONT_BOLD, fontSize=9.5, leading=13,
                    textColor=ACCENT_DARK, alignment=TA_CENTER),
}

def score_style(total):
    if total >= 16:
        return GOOD
    if total >= 13:
        return WARN
    return BAD

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
    canvas.line(MARGIN, 14 * mm, PAGE_W - MARGIN, 14 * mm)
    canvas.drawCentredString(PAGE_W / 2, 9.5 * mm, f"{doc.page}")
    canvas.restoreState()

def build():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "三大主攻方向深度调研报告.pdf")
    doc = BaseDocTemplate(out, pagesize=A4,
                          leftMargin=MARGIN, rightMargin=MARGIN,
                          topMargin=20 * mm, bottomMargin=20 * mm,
                          title=META["title"], author=META["author"])
    frame = Frame(MARGIN, 20 * mm, PAGE_W - 2 * MARGIN, PAGE_H - 40 * mm, id="main")
    cover_frame = Frame(MARGIN, 20 * mm, PAGE_W - 2 * MARGIN, PAGE_H - 40 * mm, id="cover")
    doc.addPageTemplates([
        PageTemplate(id="Cover", frames=[cover_frame], onPage=on_cover),
        PageTemplate(id="Main", frames=[frame], onPage=on_page),
    ])
    story = []

    # 封面
    story.append(Spacer(1, 70 * mm))
    story.append(Paragraph(META["title"], S["cover_title"]))
    story.append(Spacer(1, 4 * mm))
    story.append(Paragraph(META["subtitle"], S["cover_sub"]))
    story.append(Spacer(1, 16 * mm))
    story.append(Paragraph(META["author"], S["cover_meta"]))
    story.append(Paragraph(f'{META["date"]}　·　{META["version"]}', S["cover_meta"]))
    story.append(PageBreak())
    story.append(NextPageTemplate("Main"))

    # 关键发现 / 修正
    story.append(Paragraph("关键发现与修正（先读这里）", S["h1"]))
    for title, body in KEY_FINDINGS:
        story.append(Paragraph(title, S["h2"]))
        story.append(Paragraph(body, S["body"]))
        story.append(Spacer(1, 1.5 * mm))
    story.append(Spacer(1, 3 * mm))

    # 决策结论
    story.append(Paragraph("落地建议总览", S["h1"]))
    rows = []
    for tag, body in DECISION:
        rows.append([
            Paragraph(f'<font name="{FONT_BOLD}" color="#1e40af">{tag}</font>', S["cell_center"]),
            Paragraph(body, S["cell"]),
        ])
    t = Table(rows, colWidths=[26 * mm, 148 * mm])
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
    story.append(PageBreak())

    # 三大方向明细
    for di, d in enumerate(DIRECTIONS, 1):
        total = sum(d["score"])
        # 方向头（深色条）
        head_tbl = Table(
            [[Paragraph(f'{d["id"]} · {d["name"]}', S["dir_head"]),
              Paragraph(f'综合 {total}/20', ParagraphStyle("sc", fontName=FONT_BOLD, fontSize=14,
                        leading=20, textColor=colors.HexColor("#7dd3fc"), alignment=2))]],
            colWidths=[140 * mm, 34 * mm])
        head_tbl.setStyle(TableStyle([
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("BACKGROUND", (0, 0), (-1, -1), ACCENT_DARK),
            ("TOPPADDING", (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ("LEFTPADDING", (0, 0), (-1, -1), 8),
            ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ]))
        story.append(head_tbl)
        story.append(Spacer(1, 2 * mm))
        story.append(Paragraph(f'<font name="{FONT_BOLD}" color="#1e40af">结论：</font>'
                               f'{d["verdict"]}', S["body"]))
        story.append(Spacer(1, 2 * mm))
        for sec_title, paras in d["sections"]:
            story.append(Paragraph(sec_title, S["h3"]))
            for p in paras:
                story.append(Paragraph(f'<bullet>&bull;</bullet>{p}', S["body"], bulletText=None))
            story.append(Spacer(1, 1.5 * mm))
        if di < len(DIRECTIONS):
            story.append(PageBreak())

    # 行动计划
    story.append(Paragraph("8 周并行行动计划", S["h1"]))
    rows = []
    for phase, body in ACTION:
        rows.append([
            Paragraph(f'<font name="{FONT_BOLD}" color="#1e40af">{phase}</font>', S["cell_center"]),
            Paragraph(body, S["cell"]),
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

    story.append(Paragraph(
        f'<font color="#5b6472" size="8.5">本报告数据基于 2026 年 9 月公开检索（欧盟 AI Act 官方 FAQ、'
        f'Acquire.com 官方成交数据与 2026 年估值报告、人民网/学术论文关于数据资产入表的公开统计），'
        f'政策条款以官方最新文本为准；市场判断为观点性内容，不构成投资建议。</font>', S["body"]))

    doc.build(story)
    print("OK:", out)

if __name__ == "__main__":
    build()
