#!/usr/bin/env python3
"""Reemplaza las etiquetas 'Hablante N' por nombres reales en
<base>_hablantes.txt y <base>_hablantes.srt, para luego generar el PDF.
Si la etiqueta vieja estaba enrolada en la banca de voces (voces.json en la
raíz), renombra también esa clave: la voz sigue a la persona, no a la etiqueta.

Uso:
    python renombrar.py Voz_hablantes.txt "Hablante 1=Juan Pérez" "Hablante 2=María"
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import util

ROOT = Path(__file__).resolve().parent.parent


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

    # ordenar por longitud descendente: evita que "Hablante 1" pise dentro de
    # "Hablante 10" y funciona con etiquetas sin dígitos ("Coordinador=Juan").
    etiquetas = sorted(mapa, key=len, reverse=True)

    def reemplazar(texto: str) -> str:
        for etq in etiquetas:
            texto = re.sub(rf"(?<!\d){re.escape(etq)}(?!\d)", mapa[etq], texto)
        return texto

    objetivos = [txt]
    srt = txt.with_suffix(".srt")
    if srt.exists():
        objetivos.append(srt)

    for f in objetivos:
        # atómico: no perder el transcript corregido (horas de GPU + 2 pasadas) si
        # se corta a mitad de escritura.
        util.escribir_texto(f, reemplazar(f.read_text(encoding="utf-8")))
        print(f"Actualizado: {f}")

    # banca de voces: si la etiqueta vieja estaba enrolada, la clave sigue a la persona
    voces_path = ROOT / "voces.json"
    if voces_path.exists():
        try:
            banca = json.loads(voces_path.read_text(encoding="utf-8-sig"))
            voces, cambios = banca.get("voces", {}), 0
            for viejo, nuevo in mapa.items():
                if viejo in voces and viejo != nuevo:
                    if nuevo not in voces:  # si el nombre nuevo ya existe, se respeta el suyo
                        voces[nuevo] = voces.pop(viejo)
                        cambios += 1
            if cambios:
                util.escribir_json(voces_path, banca, indent=2)
                print(f"Banca de voces: {cambios} clave(s) renombrada(s)")
        except Exception as e:  # la banca nunca debe frenar un renombrado
            print(f"AVISO: no pude actualizar voces.json: {e}", file=sys.stderr)

    print("Nombres aplicados: " + ", ".join(f"{k} -> {v}" for k, v in mapa.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
