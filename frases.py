#!/usr/bin/env python3
"""Muestra, por cada hablante, sus frases más largas/identificativas.

Sirve para decidir qué nombre real ponerle a cada "Hablante N" antes del PDF.
Lee <base>_hablantes.srt (etiquetas "Hablante N: texto").

Uso:
    python frases.py Voz_hablantes.srt --n 6
"""
import argparse
import re
import sys
from pathlib import Path

# Eco a consola en UTF-8 tolerante (la consola cp1252 de Windows crashea con
# caracteres raros que pueda haber en la transcripción).
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def parse(path: Path):
    bloques = re.split(r"\n\s*\n", path.read_text(encoding="utf-8").strip())
    segs = []
    for b in bloques:
        lineas = [l for l in b.splitlines() if l.strip()]
        if len(lineas) < 2:
            continue
        m = re.search(r"(\d\d:\d\d:\d\d),\d+\s*-->", b)
        ts = m.group(1) if m else "00:00:00"
        texto = " ".join(lineas[2:]) if len(lineas) >= 3 else lineas[-1]
        mm = re.match(r"\s*(Hablante \d+)\s*:\s*(.*)", texto)
        if not mm:
            continue
        segs.append((mm.group(1), ts, mm.group(2).strip()))
    return segs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("srt")
    ap.add_argument("--n", type=int, default=6, help="Frases por hablante (def 6)")
    ap.add_argument("--min-len", type=int, default=12, help="Largo mínimo de frase (def 12)")
    args = ap.parse_args()

    segs = parse(Path(args.srt))
    orden, porhab = [], {}
    for hab, ts, txt in segs:
        if hab not in porhab:
            porhab[hab] = []
            orden.append(hab)
        porhab[hab].append((ts, txt))

    for hab in orden:
        frases = porhab[hab]
        vistas, elegidas = set(), []
        for ts, txt in sorted(frases, key=lambda x: len(x[1]), reverse=True):
            k = txt.lower()
            if k in vistas or len(txt) < args.min_len:
                continue
            vistas.add(k)
            elegidas.append((ts, txt))
            if len(elegidas) >= args.n:
                break
        # ordenar las elegidas por tiempo para que se lean naturales
        elegidas.sort(key=lambda x: x[0])
        print(f"=== {hab}  ({len(frases)} intervenciones) ===")
        for ts, txt in elegidas:
            print(f"  [{ts}] {txt}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
