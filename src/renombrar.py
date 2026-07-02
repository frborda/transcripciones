#!/usr/bin/env python3
"""Reemplaza las etiquetas 'Hablante N' por nombres reales en
<base>_hablantes.txt y <base>_hablantes.srt, para luego generar el PDF.

Uso:
    python renombrar.py Voz_hablantes.txt "Hablante 1=Juan Pérez" "Hablante 2=María"
"""
import re
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 3:
        print('Uso: python renombrar.py <_hablantes.txt> "Hablante 1=Nombre" ...',
              file=sys.stderr)
        return 1

    txt = Path(sys.argv[1])
    if not txt.exists():
        print(f"ERROR: no existe {txt}", file=sys.stderr)
        return 1

    mapa = {}
    for par in sys.argv[2:]:
        if "=" not in par:
            print(f"Par inválido (falta '='): {par}", file=sys.stderr)
            return 1
        k, v = par.split("=", 1)
        mapa[k.strip()] = v.strip()

    # ordenar por número descendente para no romper 'Hablante 1' vs 'Hablante 10'
    etiquetas = sorted(mapa, key=lambda e: -int(re.search(r"\d+", e).group()))

    def reemplazar(texto: str) -> str:
        for etq in etiquetas:
            texto = re.sub(rf"(?<!\d){re.escape(etq)}(?!\d)", mapa[etq], texto)
        return texto

    objetivos = [txt]
    srt = txt.with_suffix(".srt")
    if srt.exists():
        objetivos.append(srt)

    for f in objetivos:
        f.write_text(reemplazar(f.read_text(encoding="utf-8")), encoding="utf-8")
        print(f"Actualizado: {f}")

    print("Nombres aplicados: " + ", ".join(f"{k} -> {v}" for k, v in mapa.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
