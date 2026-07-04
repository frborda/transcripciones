#!/usr/bin/env python3
"""Genera un PDF legible en celular a partir de Voz_hablantes.txt.

Cada intervención: timestamp pequeño + nombre en color + texto grande.
Página angosta (tipo A5 vertical) para que al ajustar al ancho en el móvil
la letra se vea grande.

Uso:
    .venv/bin/python gen_pdf.py Voz_hablantes.txt "Reunión - Sistema GPD parlamentario"
"""
import re
import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4, A5
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, HRFlowable, KeepTogether, PageBreak,
    Table, TableStyle,
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle

# paleta de colores por hablante (se asigna en orden de aparición)
PALETA = [
    colors.HexColor("#1565C0"),  # azul
    colors.HexColor("#C62828"),  # rojo
    colors.HexColor("#2E7D32"),  # verde
    colors.HexColor("#6A1B9A"),  # violeta
    colors.HexColor("#E65100"),  # naranja
    colors.HexColor("#00838F"),  # cian
]


def parse(path: Path):
    bloques = re.split(r"\n\s*\n", path.read_text(encoding="utf-8").strip())
    items = []
    for b in bloques:
        lineas = [l for l in b.splitlines() if l.strip()]
        if len(lineas) < 2:
            continue
        m = re.match(r"^\[([0-9:,]+)\]\s*(.+?):\s*$", lineas[0])
        if not m:
            continue
        ts_full = m.group(1)
        nombre = m.group(2)
        ts = ts_full.split(",")[0]  # HH:MM:SS
        texto = " ".join(lineas[1:]).strip()
        items.append((ts, nombre, texto))
    return items


def esc(t: str) -> str:
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser(description="Genera el PDF de conversación por hablante")
    ap.add_argument("txt", nargs="?", default="Voz_hablantes.txt", help="Archivo <base>_hablantes.txt")
    ap.add_argument("titulo", nargs="?", default="Transcripción de la reunión", help="Título del PDF")
    ap.add_argument("--out", default=None, help="Ruta del PDF de salida (def: junto al txt)")
    ap.add_argument("--formato", default="desktop", choices=["desktop", "celu"],
                    help="desktop (A4) o celu (página angosta, letra grande)")
    args = ap.parse_args()

    src = Path(args.txt)
    titulo = args.titulo
    items = parse(src)
    if not items:
        print("No se pudo parsear el archivo de entrada", file=sys.stderr)
        return 1

    # asignar color por hablante en orden de aparición
    color_de = {}
    for _, nombre, _ in items:
        if nombre not in color_de:
            color_de[nombre] = PALETA[len(color_de) % len(PALETA)]

    # perfil de maquetado según el formato
    if args.formato == "celu":
        pagesize = (95 * mm, 170 * mm)
        mlat, mtb = 7 * mm, 9 * mm
        fs_tit, ld_tit = 17, 21
        fs_sub = 9.5
        fs_meta = 12
        fs_texto, ld_texto = 13, 18
    else:  # desktop
        pagesize = A4
        mlat, mtb = 22 * mm, 20 * mm
        fs_tit, ld_tit = 24, 28
        fs_sub = 11
        fs_meta = 12
        fs_texto, ld_texto = 12, 17

    out = Path(args.out) if args.out else src.with_suffix(".pdf")
    doc = SimpleDocTemplate(
        str(out), pagesize=pagesize,
        leftMargin=mlat, rightMargin=mlat, topMargin=mtb, bottomMargin=mtb,
        title=titulo, author="Transcripción automática",
    )
    ss = getSampleStyleSheet()
    AZUL = PALETA[0]
    GRIS = colors.HexColor("#555555")
    st_sub = ParagraphStyle("s", parent=ss["Normal"], fontSize=fs_sub, leading=fs_sub + 4,
                            textColor=colors.HexColor("#666666"))
    st_meta = ParagraphStyle("m", parent=ss["Normal"], fontSize=fs_meta, leading=fs_meta + 6)
    st_texto = ParagraphStyle("tx", parent=ss["Normal"], fontSize=fs_texto, leading=ld_texto,
                              spaceAfter=2, alignment=TA_JUSTIFY)

    def pie(canv, _doc):
        canv.saveState()
        canv.setFont("Helvetica", 8)
        canv.setFillColor(GRIS)
        canv.drawCentredString(pagesize[0] / 2, mtb * 0.4, f"— {canv.getPageNumber()} —")
        canv.restoreState()

    flow = []

    # --- portada: banda de título (tipo de documento + nombre de la reunión) ---
    nombre_reunion = titulo
    for sep in ("—", " - "):
        if sep in titulo:
            nombre_reunion = titulo.split(sep, 1)[1].strip()
            break
    st_kick = ParagraphStyle("kick", parent=ss["Normal"], fontSize=fs_sub,
                             leading=fs_sub + 2, textColor=colors.white)
    st_tt = ParagraphStyle("tt", parent=ss["Title"], fontSize=fs_tit, leading=ld_tit,
                           textColor=colors.white, alignment=0,
                           spaceBefore=0, spaceAfter=0)
    banda = Table([[[Paragraph("<b>CONVERSACIÓN</b>", st_kick),
                     Spacer(1, 3),
                     Paragraph(f"<b>{esc(nombre_reunion)}</b>", st_tt)]]],
                  colWidths=[pagesize[0] - 2 * mlat])
    banda.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), AZUL),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
    ]))
    flow.append(banda)
    dur = items[-1][0]
    flow.append(Spacer(1, 5))
    flow.append(Paragraph(f"<i>Duración ~{dur} &nbsp;·&nbsp; {len(items)} intervenciones</i>", st_sub))
    flow.append(Spacer(1, 10*mm))
    flow.append(Paragraph("<b>Participantes</b>", st_meta))
    flow.append(Spacer(1, 2*mm))
    # conteo por hablante
    conteo = {}
    for _, n, _ in items:
        conteo[n] = conteo.get(n, 0) + 1
    for nombre, col in color_de.items():
        flow.append(Paragraph(
            f'<font color="#{col.hexval()[2:]}">●</font> <b>{esc(nombre)}</b> '
            f'<font size="9" color="#888888">({conteo[nombre]} intervenciones)</font>', st_meta))
    flow.append(Spacer(1, 10*mm))
    flow.append(Paragraph("Generado automáticamente a partir del audio. "
                          "Los hablantes se identifican por su voz y por el contexto "
                          "de la conversación; puede haber pequeños errores de "
                          "transcripción o de atribución en momentos de habla "
                          "simultánea.", st_sub))
    flow.append(PageBreak())

    # --- cuerpo ---
    ultimo = None
    for ts, nombre, texto in items:
        col = color_de[nombre]
        bloque = []
        # separador + nombre solo cuando cambia el hablante
        if nombre != ultimo:
            if ultimo is not None:
                flow.append(Spacer(1, 3*mm))
            cab = Paragraph(
                f'<font color="#{col.hexval()[2:]}"><b>{esc(nombre)}</b></font> '
                f'<font size="8" color="#999999">{ts}</font>',
                ParagraphStyle("cab", parent=st_meta, fontSize=fs_meta, leading=fs_meta + 4, spaceAfter=2))
            flow.append(cab)
            ultimo = nombre
        flow.append(Paragraph(esc(texto), st_texto))

    doc.build(flow, onFirstPage=pie, onLaterPages=pie)
    print(f"PDF generado: {out}  ({out.stat().st_size//1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
