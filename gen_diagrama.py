#!/usr/bin/env python3
"""Genera Diagrama.pdf: un diagrama de flujo del CONTENIDO/tema tratado.

El análisis profundo y la estructura del flujo los define Claude Code en un JSON;
este script solo lo dibuja con reportlab (sin graphviz ni node).

NO es un diagrama de quién habló: es el flujo lógico del asunto discutido.

JSON esperado:
{
  "titulo": "...",
  "pasos": [
    {"tipo": "inicio|proceso|decision|fin",
     "texto": "...",
     "ramas": [ {"etiqueta": "Sí", "texto": "..."} ]   # opcional, a la derecha
    }
  ]
}

Uso:
    python gen_diagrama.py diagrama.json --out Diagrama.pdf --titulo "..."
"""
import argparse
import json
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfgen import canvas

ANCHO, ALTO = A4

BORDE = {
    "inicio":   colors.HexColor("#2E7D32"),
    "fin":      colors.HexColor("#C62828"),
    "proceso":  colors.HexColor("#1565C0"),
    "decision": colors.HexColor("#E65100"),
}
RELLENO = {
    "inicio":   colors.HexColor("#E8F5E9"),
    "fin":      colors.HexColor("#FFEBEE"),
    "proceso":  colors.HexColor("#E3F2FD"),
    "decision": colors.HexColor("#FFF3E0"),
}

FUENTE, FUENTE_B = "Helvetica", "Helvetica-Bold"
SIZE = 9.5
PAD = 3 * mm


def wrap(c, text, maxw, font=FUENTE, size=None):
    if size is None:
        size = SIZE  # leerlo al llamar: el formato celu lo cambia después del import
    out, cur = [], ""
    for p in text.split():
        prueba = (cur + " " + p).strip()
        if not cur or c.stringWidth(prueba, font, size) <= maxw:
            cur = prueba
        else:
            out.append(cur); cur = p
    if cur:
        out.append(cur)
    return out or [""]


def alto_caja(lines):
    return max(11 * mm, len(lines) * (SIZE + 2) + 2 * PAD)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("json")
    ap.add_argument("--out", default=None)
    ap.add_argument("--titulo", default=None)
    ap.add_argument("--formato", default="desktop", choices=["desktop", "celu"],
                    help="desktop (A4, ramas al costado) o celu (angosto, letra grande, ramas dentro)")
    args = ap.parse_args()

    global SIZE
    data = json.loads(Path(args.json).read_text(encoding="utf-8-sig"))
    titulo = args.titulo or data.get("titulo", "Diagrama de flujo")
    pasos = data["pasos"]
    out = Path(args.out) if args.out else Path(args.json).with_suffix(".pdf")

    if args.formato == "celu":
        ANCHO, ALTO = 105 * mm, 185 * mm
        SIZE = 12
        margen = 8 * mm
        spine_w, spine_cx = 88 * mm, ANCHO / 2
        rama_w, rama_cx = 0, 0
        gap = 8 * mm
        side_ramas = False
    else:  # desktop
        ANCHO, ALTO = A4
        SIZE = 9.5
        margen = 15 * mm
        spine_w, spine_cx = 92 * mm, 72 * mm
        rama_w, rama_cx = 58 * mm, 162 * mm
        gap = 9 * mm
        side_ramas = True
    top, bottom = ALTO - 22 * mm, 18 * mm

    c = canvas.Canvas(str(out), pagesize=(ANCHO, ALTO))
    c.setTitle(titulo)

    def encabezado():
        c.setFillColor(colors.HexColor("#1565C0"))
        c.setFont(FUENTE_B, 15)
        c.drawString(margen, ALTO - 14 * mm, titulo)
        c.setStrokeColor(colors.HexColor("#1565C0"))
        c.setLineWidth(1)
        c.line(margen, ALTO - 16 * mm, ANCHO - margen, ALTO - 16 * mm)

    def texto_centrado(cx, y_top, h, lines):
        total = len(lines) * (SIZE + 2)
        ty = y_top - h / 2 + total / 2 - SIZE * 0.85
        c.setFillColor(colors.HexColor("#1A1A1A"))
        c.setFont(FUENTE, SIZE)
        for ln in lines:
            c.drawCentredString(cx, ty, ln)
            ty -= (SIZE + 2)

    def caja(cx, y_top, w, lines, tipo):
        h = alto_caja(lines)
        x = cx - w / 2
        c.setLineWidth(1.2)
        c.setStrokeColor(BORDE.get(tipo, BORDE["proceso"]))
        c.setFillColor(RELLENO.get(tipo, RELLENO["proceso"]))
        if tipo in ("inicio", "fin"):
            c.roundRect(x, y_top - h, w, h, 5 * mm, stroke=1, fill=1)
        elif tipo == "decision":
            p = c.beginPath()
            p.moveTo(cx, y_top)
            p.lineTo(x + w, y_top - h / 2)
            p.lineTo(cx, y_top - h)
            p.lineTo(x, y_top - h / 2)
            p.close()
            c.drawPath(p, stroke=1, fill=1)
        else:
            c.rect(x, y_top - h, w, h, stroke=1, fill=1)
        texto_centrado(cx, y_top, h, lines)
        return h

    def flecha_v(x, y1, y2):
        c.setStrokeColor(colors.HexColor("#888888"))
        c.setLineWidth(1)
        c.line(x, y1, x, y2)
        c.line(x, y2, x - 1.5 * mm, y2 + 2.5 * mm)
        c.line(x, y2, x + 1.5 * mm, y2 + 2.5 * mm)

    def flecha_h(x1, x2, y, label=None):
        c.setStrokeColor(colors.HexColor("#888888"))
        c.setLineWidth(1)
        c.line(x1, y, x2, y)
        c.line(x2, y, x2 - 2.5 * mm, y + 1.5 * mm)
        c.line(x2, y, x2 - 2.5 * mm, y - 1.5 * mm)
        if label:
            c.setFillColor(colors.HexColor("#E65100"))
            c.setFont(FUENTE_B, 8)
            c.drawCentredString((x1 + x2) / 2, y + 1.2 * mm, label)

    encabezado()
    y = top
    prev_bottom = None

    for paso in pasos:
        tipo = paso.get("tipo", "proceso")
        maxw = spine_w * 0.58 if tipo == "decision" else spine_w - 2 * PAD - 2 * mm
        lines = wrap(c, paso.get("texto", ""), maxw)
        # en celu las ramas van como líneas dentro del recuadro (no hay lugar al costado)
        if not side_ramas:
            for rama in paso.get("ramas", []):
                etq = rama.get("etiqueta", "")
                pre = f"({etq}) " if etq else ""
                lines += wrap(c, "» " + pre + rama.get("texto", ""), maxw)
        h = alto_caja(lines)
        if tipo == "decision":
            h += 4 * mm

        if y - h < bottom:
            c.showPage()
            encabezado()
            y = top
            prev_bottom = None

        if prev_bottom is not None:
            flecha_v(spine_cx, prev_bottom, y)

        # caja del spine (recalcular h real para decision)
        x = spine_cx - spine_w / 2
        c.setLineWidth(1.2)
        c.setStrokeColor(BORDE.get(tipo, BORDE["proceso"]))
        c.setFillColor(RELLENO.get(tipo, RELLENO["proceso"]))
        if tipo in ("inicio", "fin"):
            c.roundRect(x, y - h, spine_w, h, 5 * mm, stroke=1, fill=1)
        elif tipo == "decision":
            p = c.beginPath()
            p.moveTo(spine_cx, y)
            p.lineTo(x + spine_w, y - h / 2)
            p.lineTo(spine_cx, y - h)
            p.lineTo(x, y - h / 2)
            p.close()
            c.drawPath(p, stroke=1, fill=1)
        else:
            c.rect(x, y - h, spine_w, h, stroke=1, fill=1)
        texto_centrado(spine_cx, y, h, lines)

        if side_ramas:
            for rama in paso.get("ramas", []):
                rlines = wrap(c, rama.get("texto", ""), rama_w - 2 * PAD - 2 * mm)
                rh = alto_caja(rlines)
                ry_top = y - max(0.0, (h - rh) / 2)
                flecha_h(spine_cx + spine_w / 2, rama_cx - rama_w / 2, y - h / 2, rama.get("etiqueta"))
                caja(rama_cx, ry_top, rama_w, rlines, "proceso")

        prev_bottom = y - h
        y = prev_bottom - gap

    c.showPage()
    c.save()
    print(f"Diagrama generado: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
