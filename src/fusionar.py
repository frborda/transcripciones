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


# Pausa mínima (s) para admitir un cambio de hablante. Un cambio de turno real
# SIEMPRE cae en una pausa; si dos palabras están pegadas (habla continua) no puede
# haber cambio de hablante entre ellas. Medido empíricamente: sin esta regla, ~72%
# de los cambios de hablante caían entre palabras pegadas (cortes en medio de frase).
PAUSA_CAMBIO = 0.25


# Una frase corta necesita evidencia REAL para cambiar de hablante: si dura menos
# que FRASE_CORTA y el turno del ganador no la cubre al menos COBERTURA_MIN (o sea,
# se asignó por cercanía a un borde, no por contención), es ruido de límite de la
# diarización y hereda el hablante de la frase anterior.
FRASE_CORTA = 1.0
COBERTURA_MIN = 0.5


def asignar_por_frase(palabras, hab_palabra, turnos, pausa=PAUSA_CAMBIO):
    """Devuelve un hablante por palabra, pero forzando que el cambio de hablante solo
    ocurra en pausas >= 'pausa'. Agrupa las palabras en FRASES (rachas de habla
    continua sin pausa) y le asigna a toda la frase el hablante ganador por
    solapamiento de duración con los turnos. Así ninguna frase se corta al medio."""
    raw = [hab_palabra((w["inicio"] + w["fin"]) / 2) for w in palabras]

    por_hablante = {}
    for t in turnos:
        por_hablante.setdefault(t["hablante"], []).append((t["inicio"], t["fin"]))

    def cobertura(t0, t1, hablante):
        """Fracción de [t0, t1] cubierta por los turnos de 'hablante'."""
        if t1 <= t0:
            return 0.0
        cub = sum(max(0.0, min(t1, b) - max(t0, a))
                  for a, b in por_hablante.get(hablante, ()))
        return cub / (t1 - t0)

    spk = [None] * len(palabras)
    i = 0
    anterior = None
    while i < len(palabras):
        j = i
        while j + 1 < len(palabras) and palabras[j + 1]["inicio"] - palabras[j]["fin"] < pausa:
            j += 1
        # voto ponderado por duración dentro de la frase [i..j]
        peso = {}
        for k in range(i, j + 1):
            h = raw[k]
            if h:
                peso[h] = peso.get(h, 0.0) + (palabras[k]["fin"] - palabras[k]["inicio"])
        ganador = max(peso, key=peso.get) if peso else raw[i]
        t0, t1 = palabras[i]["inicio"], palabras[j]["fin"]
        if (anterior is not None and ganador != anterior
                and t1 - t0 < FRASE_CORTA
                and cobertura(t0, t1, ganador) < COBERTURA_MIN):
            ganador = anterior  # sin evidencia real: continúa hablando el mismo
        for k in range(i, j + 1):
            spk[k] = ganador
        anterior = ganador
        i = j + 1
    return spk


# Palabras que forman interjecciones reales (asentimientos, muletillas): un turno
# corto hecho SOLO de estas sí suele ser una intervención genuina y se conserva.
INTERJECCIONES = {
    "sí", "si", "no", "ok", "okey", "claro", "bien", "bueno", "dale", "exacto",
    "perfecto", "genial", "obvio", "ah", "eh", "ajá", "aha", "mhm", "gracias",
    "listo", "ya", "va", "cierto", "tal", "cual", "correcto", "eso",
}


def absorber_fragmentos(turnos_finales, max_palabras=3):
    """Funde los turnos-fragmento (<=max_palabras, no interjección) con el turno al
    que pertenece su frase. Dos señales, en orden:
      1. sandwich: encajado entre dos turnos del MISMO otro hablante;
      2. continuación: sin puntuación de cierre y el turno siguiente arranca en
         minúscula (el fragmento es el comienzo de la frase del vecino), o el
         fragmento arranca en minúscula y el turno anterior quedó sin cerrar.
    Devuelve (turnos, absorbidos)."""
    absorbidos = 0
    cambio = True
    while cambio:  # iterar: al fundir puede aparecer un nuevo caso
        cambio = False
        out = []
        i = 0
        while i < len(turnos_finales):
            t = turnos_finales[i]
            sig = turnos_finales[i + 1] if i + 1 < len(turnos_finales) else None
            prev = out[-1] if out else None
            pal = re.findall(r"[\wáéíóúñü]+", t[2].lower())
            es_frag = (0 < len(pal) <= max_palabras
                       and not all(p in INTERJECCIONES for p in pal))
            abierto = not t[2].rstrip().endswith((".", "!", "?", "…"))  # frase sin cerrar
            if not es_frag:
                out.append(t)
                i += 1
                continue
            if prev is not None and sig is not None and prev[3] == sig[3] != t[3]:
                # 1) sandwich: fragmento + turno siguiente se funden en el anterior
                prev[2] = f"{prev[2]} {t[2]} {sig[2]}".strip()
                prev[1] = sig[1]
                absorbidos += 1
                cambio = True
                i += 2
            elif (prev is not None and t[3] != prev[3] and t[2][:1].islower()
                    and not prev[2].rstrip().endswith((".", "!", "?", "…"))):
                # 2a) continúa la frase del turno anterior
                prev[2] = f"{prev[2]} {t[2]}".strip()
                prev[1] = t[1]
                absorbidos += 1
                cambio = True
                i += 1
            elif (sig is not None and t[3] != sig[3] and abierto
                    and sig[2][:1].islower()):
                # 2b) es el comienzo de la frase del turno siguiente
                sig[2] = f"{t[2]} {sig[2]}".strip()
                sig[0] = t[0]
                absorbidos += 1
                cambio = True
                i += 1
            else:
                out.append(t)
                i += 1
        turnos_finales = out
    return turnos_finales, absorbidos


def dividir_segmento(ini, fin, texto, pal_seg, spk_seg):
    """Corta el texto del segmento en los cambios de hablante (según los hablantes
    por palabra YA suavizados por asignar_por_frase). Devuelve
    [(t0, t1, trozo, SPEAKER_xx), ...] o None si no se pudo asignar."""
    runs = []  # [hablante, t0, t1, n_palabras_crudas]
    for w, h in zip(pal_seg, spk_seg):
        if h is None:
            continue
        if runs and runs[-1][0] == h:
            runs[-1][2] = w["fin"]
            runs[-1][3] += 1
        else:
            runs.append([h, w["inicio"], w["fin"], 1])
    if not runs:
        return None
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

    # asignar hablante por palabra con la regla de pausa (el cambio de hablante solo
    # cae en pausas reales, nunca en medio de habla continua); sin _palabras.json,
    # por mayor solapamiento del segmento entero.
    hab_palabra = asignador_palabras(turnos)
    spk_por_palabra = asignar_por_frase(palabras, hab_palabra, turnos) if palabras else None
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
            partes = dividir_segmento(ini, fin, texto, palabras[j:k], spk_por_palabra[j:k])
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

    # AGRUPAR en turnos (bloques de hablante consecutivo). Los cambios de hablante ya
    # caen solo en pausas (asignar_por_frase), así que no hace falta corregir bordes.
    turnos_finales = []  # [ini, fin, texto, hab]
    for ini, fin, texto, hab in asignados:
        if turnos_finales and turnos_finales[-1][3] == hab:
            turnos_finales[-1][1] = fin
            turnos_finales[-1][2] = (turnos_finales[-1][2] + " " + texto).strip()
        else:
            turnos_finales.append([ini, fin, texto, hab])

    # ABSORBER fragmentos: un "turno" de <=3 palabras encajado entre dos turnos del
    # MISMO otro hablante, que no es una interjección real (sí/no/ok/claro...), es
    # casi siempre un pedazo robado de la frase del vecino por ruido de borde de la
    # diarización. Se funde con los vecinos. Las interjecciones reales se conservan.
    turnos_finales, n_absorbidos = absorber_fragmentos(turnos_finales)

    # un solo .stem: el nombre del audio puede contener puntos ("reunion.v2")
    txt_out = srt.with_name(srt.stem + "_hablantes.txt")
    srt_out = srt.with_name(srt.stem + "_hablantes.srt")

    # TXT: una entrada por turno
    with txt_out.open("w", encoding="utf-8") as f:
        for ini, fin, texto, hab in turnos_finales:
            if texto.strip():
                f.write(f"[{fmt_ts(ini)}] {hab}:\n{texto.strip()}\n\n")

    # SRT: una entrada por turno (con etiqueta de hablante)
    with srt_out.open("w", encoding="utf-8") as f:
        for i, (ini, fin, texto, hab) in enumerate(turnos_finales, 1):
            if texto.strip():
                f.write(f"{i}\n{fmt_ts(ini)} --> {fmt_ts(fin)}\n{hab}: {texto.strip()}\n\n")

    modo = (f"por palabra con regla de pausa ({n_divididos} segmentos con cambio de hablante, "
            f"{n_absorbidos} fragmentos absorbidos)"
            if palabras else "por solapamiento de segmento (no hay _palabras.json)")
    print(f"Fusión {modo}")
    print(f"Hablantes: {', '.join(f'{v} ({k})' for k, v in mapa.items())}")
    print(f"Generado:\n  {txt_out}\n  {srt_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
