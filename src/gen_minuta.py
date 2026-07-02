#!/usr/bin/env python3
"""Genera una minuta formal en PDF a partir de un archivo Markdown.

El CONTENIDO (análisis de la reunión) lo redacta Claude Code en un .md;
este script solo lo maqueta. Soporta:
  # / ## / ###  encabezados
  **negrita**, *cursiva*
  listas con '-' o '*'  y listas numeradas '1.'
  '---'  regla horizontal
  párrafos normales

Uso:
    python gen_minuta.py Minuta.md --out Minuta.pdf --titulo "Minuta - ..."
"""
import argparse
import re
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, HRFlowable, ListFlowable, ListItem,
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle

AZUL = colors.HexColor("#1565C0")
GRIS = colors.HexColor("#555555")
GRIS_CLARO = colors.HexColor("#CCCCCC")


def inline(t: str) -> str:
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", t)
    return t


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("md")
    ap.add_argument("--out", default=None)
    ap.add_argument("--titulo", default=None)
    ap.add_argument("--formato", default="desktop", choices=["desktop", "celu"],
                    help="desktop (A4) o celu (página angosta, letra grande)")
    args = ap.parse_args()

    src = Path(args.md)
    if not src.exists():
        print(f"ERROR: no existe {src}")
        return 1
    out = Path(args.out) if args.out else src.with_suffix(".pdf")

    if args.formato == "celu":
        pagesize = (98 * mm, 175 * mm)
        margen = 8 * mm
        fs_t, fs_h2, fs_h3, fs_p = 18, 15, 13, 12.5
    else:  # desktop
        pagesize = A4
        margen = 18 * mm
        fs_t, fs_h2, fs_h3, fs_p = 20, 14, 12, 10.5

    ss = getSampleStyleSheet()
    st_title = ParagraphStyle("t", parent=ss["Title"], fontSize=fs_t, leading=fs_t + 4,
                              textColor=AZUL, spaceAfter=4)
    st_h2 = ParagraphStyle("h2", parent=ss["Heading2"], fontSize=fs_h2, leading=fs_h2 + 4,
                           textColor=AZUL, spaceBefore=12, spaceAfter=4)
    st_h3 = ParagraphStyle("h3", parent=ss["Heading3"], fontSize=fs_h3, leading=fs_h3 + 4,
                           textColor=GRIS, spaceBefore=8, spaceAfter=2)
    st_p = ParagraphStyle("p", parent=ss["Normal"], fontSize=fs_p, leading=fs_p + 5, spaceAfter=4)
    st_li = ParagraphStyle("li", parent=st_p, spaceAfter=2)

    doc = SimpleDocTemplate(
        str(out), pagesize=pagesize,
        leftMargin=margen, rightMargin=margen, topMargin=margen, bottomMargin=margen,
        title=args.titulo or src.stem, author="Minuta generada por Claude Code",
    )

    flow = []
    items, tipo = [], None  # acumulador de lista

    def flush():
        nonlocal items, tipo
        if items:
            bt = "1" if tipo == "num" else "bullet"
            kw = {} if tipo == "num" else {"start": "•"}
            flow.append(ListFlowable(
                [ListItem(Paragraph(x, st_li)) for x in items],
                bulletType=bt, leftIndent=14, **kw))
            flow.append(Spacer(1, 3))
            items, tipo = [], None

    for raw in src.read_text(encoding="utf-8-sig").splitlines():
        s = raw.strip()
        if not s:
            flush(); flow.append(Spacer(1, 4)); continue
        if s.startswith("### "):
            flush(); flow.append(Paragraph(inline(s[4:]), st_h3)); continue
        if s.startswith("## "):
            flush(); flow.append(Paragraph(inline(s[3:]), st_h2)); continue
        if s.startswith("# "):
            flush()
            flow.append(Paragraph(inline(s[2:]), st_title))
            flow.append(HRFlowable(width="100%", thickness=1, color=AZUL, spaceAfter=6))
            continue
        if s in ("---", "***", "___"):
            flush()
            flow.append(HRFlowable(width="100%", thickness=0.5, color=GRIS_CLARO,
                                   spaceBefore=4, spaceAfter=4))
            continue
        m = re.match(r"^[-*]\s+(.*)", s)
        if m:
            if tipo == "num":
                flush()
            tipo = "bul"
            items.append(inline(m.group(1)))
            continue
        m = re.match(r"^\d+\.\s+(.*)", s)
        if m:
            if tipo == "bul":
                flush()
            tipo = "num"
            items.append(inline(m.group(1)))
            continue
        flush()
        flow.append(Paragraph(inline(s), st_p))

    flush()
    doc.build(flow)
    print(f"Minuta generada: {out}  ({out.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
