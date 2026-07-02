#!/usr/bin/env python3
"""Fusiona la transcripción (SRT de whisper) con los turnos de hablante (JSON pyannote).

Si existe <base>_palabras.json (lo escribe transcribir.py con word_timestamps),
la asignación es POR PALABRA: un segmento que cruza un cambio de turno se divide
en el límite exacto, en vez de irse entero al hablante con mayor solapamiento.
Sin ese archivo cae al modo anterior (mayor solapamiento por segmento).

Genera:
  - <nombre>_hablantes.txt : texto agrupado por hablante (Hablante 1, 2, ...)
  - <nombre>_hablantes.srt : SRT con etiqueta de hablante en cada subtítulo

Uso:
    python fusionar.py Voz.srt Voz_turnos.json [--palabras Voz_palabras.json]
"""
import argparse
import bisect
import json
import re
from pathlib import Path


def parse_srt(path: Path):
    """Devuelve lista de (inicio_seg, fin_seg, texto)."""
    bloques = re.split(r"\n\s*\n", path.read_text(encoding="utf-8").strip())
    segs = []
    for b in bloques:
        lineas = [l for l in b.splitlines() if l.strip()]
        if len(lineas) < 2:
            continue
        m = re.search(r"(\d\d):(\d\d):(\d\d),(\d+)\s*-->\s*(\d\d):(\d\d):(\d\d),(\d+)", b)
        if not m:
            continue
        g = list(map(int, m.groups()))
        ini = g[0]*3600 + g[1]*60 + g[2] + g[3]/1000
        fin = g[4]*3600 + g[5]*60 + g[6] + g[7]/1000
        texto = " ".join(lineas[2:]) if len(lineas) >= 3 else lineas[-1]
        segs.append((ini, fin, texto.strip()))
    return segs


def fmt_ts(s: float) -> str:
    h, r = divmod(int(s), 3600)
    m, sec = divmod(r, 60)
    ms = int((s - int(s)) * 1000)
    return f"{h:02d}:{m:02d}:{sec:02d},{ms:03d}"


def hablante_de(ini, fin, turnos):
    """Hablante con mayor solapamiento con [ini, fin] (modo sin palabras)."""
    mejor, mejor_ov = None, 0.0
    for t in turnos:
        ov = max(0.0, min(fin, t["fin"]) - max(ini, t["inicio"]))
        if ov > mejor_ov:
            mejor_ov, mejor = ov, t["hablante"]
    return mejor


def asignador_palabras(turnos):
    """Devuelve una función t -> hablante: el turno que contiene t, o el más cercano."""
    inicios = [t["inicio"] for t in turnos]

    def hab(t):
        i = bisect.bisect_right(inicios, t) - 1
        cand = [turnos[j] for j in (i, i + 1) if 0 <= j < len(turnos)]
        if not cand:
            return None

        def dist(tu):
            if tu["inicio"] <= t <= tu["fin"]:
                return 0.0
            return min(abs(t - tu["inicio"]), abs(t - tu["fin"]))

        return min(cand, key=dist)["hablante"]

    return hab


def dividir_segmento(ini, fin, texto, pal_seg, hab_palabra):
    """Corta [ini, fin] donde cambia el hablante según los tiempos de palabra.
    Devuelve [(ini, fin, texto, SPEAKER_xx), ...] o None si no se pudo asignar."""
    runs = []  # [hablante, t0, t1, n_palabras_crudas]
    for w in pal_seg:
        h = hab_palabra((w["inicio"] + w["fin"]) / 2)
        if h is None:
            continue
        if runs and runs[-1][0] == h:
            runs[-1][2] = w["fin"]
            runs[-1][3] += 1
        else:
            runs.append([h, w["inicio"], w["fin"], 1])
    if not runs:
        return None
    # absorber "interjecciones" de 1 palabra rodeadas por el mismo hablante:
    # suele ser ruido del límite de turnos, no una intervención real
    i = 1
    while i < len(runs) - 1:
        if runs[i][3] == 1 and runs[i - 1][0] == runs[i + 1][0]:
            runs[i - 1][2] = runs[i + 1][2]
            runs[i - 1][3] += runs[i][3] + runs[i + 1][3]
            del runs[i:i + 2]
        else:
            i += 1
    if len(runs) == 1:
        return [(ini, fin, texto, runs[0][0])]
    # repartir el texto del SRT (ya corregido en la pasada 1) proporcionalmente a
    # las palabras crudas de cada tramo: las correcciones cambian palabras pero
    # no los tiempos, así que el conteo crudo es la mejor guía de dónde cortar
    corr = texto.split()
    total = sum(r[3] for r in runs)
    out, pos, acum = [], 0, 0
    for idx, (h, t0, t1, n) in enumerate(runs):
        acum += n
        corte = len(corr) if idx == len(runs) - 1 else round(acum * len(corr) / total)
        trozo = " ".join(corr[pos:max(pos, corte)])
        pos = max(pos, corte)
        if trozo:
            out.append((t0, t1, trozo, h))
    return out or None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("srt")
    ap.add_argument("turnos_json")
    ap.add_argument("--palabras", default=None,
                    help="JSON de tiempos por palabra (def: <base>_palabras.json junto al SRT)")
    args = ap.parse_args()

    srt = Path(args.srt)
    turnos = json.loads(Path(args.turnos_json).read_text(encoding="utf-8"))
    turnos.sort(key=lambda t: t["inicio"])
    segs = parse_srt(srt)

    pal_path = Path(args.palabras) if args.palabras else srt.with_name(srt.stem + "_palabras.json")
    palabras = None
    if pal_path.exists():
        palabras = json.loads(pal_path.read_text(encoding="utf-8"))
        palabras.sort(key=lambda w: w["inicio"])

    # asignar hablante: por palabra (dividiendo segmentos en los cambios de turno)
    # o, sin _palabras.json, por mayor solapamiento del segmento entero
    hab_palabra = asignador_palabras(turnos)
    crudos = []          # (ini, fin, texto, SPEAKER_xx)
    n_divididos = 0
    j = 0
    for ini, fin, texto in segs:
        partes = None
        if palabras:
            while j < len(palabras) and palabras[j]["inicio"] < ini - 0.01:
                j += 1
            k = j
            while k < len(palabras) and palabras[k]["inicio"] < fin - 0.001:
                k += 1
            partes = dividir_segmento(ini, fin, texto, palabras[j:k], hab_palabra)
            j = k
            if partes and len(partes) > 1:
                n_divididos += 1
        if not partes:
            partes = [(ini, fin, texto, hablante_de(ini, fin, turnos) or "SPEAKER_?")]
        crudos.extend(partes)

    # mapa SPEAKER_xx -> "Hablante N" en orden de primera aparición
    orden, mapa = [], {}
    asignados = []
    for ini, fin, texto, spk in crudos:
        if spk not in mapa:
            orden.append(spk)
            mapa[spk] = f"Hablante {len(orden)}"
        asignados.append((ini, fin, texto, mapa[spk]))

    # un solo .stem: el nombre del audio puede contener puntos ("reunion.v2")
    txt_out = srt.with_name(srt.stem + "_hablantes.txt")
    srt_out = srt.with_name(srt.stem + "_hablantes.srt")

    # TXT agrupado: una entrada por bloque de hablante consecutivo
    with txt_out.open("w", encoding="utf-8") as f:
        actual, buffer, t_ini = None, [], None
        def flush():
            if buffer:
                f.write(f"[{fmt_ts(t_ini)}] {actual}:\n{' '.join(buffer)}\n\n")
        for ini, fin, texto, hab in asignados:
            if hab != actual:
                flush()
                actual, buffer, t_ini = hab, [], ini
            buffer.append(texto)
        flush()

    # SRT con etiqueta de hablante
    with srt_out.open("w", encoding="utf-8") as f:
        for i, (ini, fin, texto, hab) in enumerate(asignados, 1):
            f.write(f"{i}\n{fmt_ts(ini)} --> {fmt_ts(fin)}\n{hab}: {texto}\n\n")

    modo = (f"por palabra ({n_divididos} segmentos divididos en cambios de turno)"
            if palabras else "por solapamiento de segmento (no hay _palabras.json)")
    print(f"Fusión {modo}")
    print(f"Hablantes: {', '.join(f'{v} ({k})' for k, v in mapa.items())}")
    print(f"Generado:\n  {txt_out}\n  {srt_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
