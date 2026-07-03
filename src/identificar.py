#!/usr/bin/env python3
r"""Identifica a los hablantes de una reunión por su VOZ, contra una banca de voces
conocidas (voces.json en la raíz del repo). Son reuniones recurrentes con el mismo
elenco: enrolada una vez la voz de cada persona, las siguientes reuniones salen con
nombre real sin renombrar a mano.

Cómo funciona: para cada etiqueta del <base>_hablantes.srt toma sus turnos más
largos, calcula embeddings de voz (wespeaker via pyannote), promedia un centroide
robusto y lo compara (coseno) contra la banca. Matchea con asignación única
(el mejor coseno primero) por encima del umbral.

Uso:
    python identificar.py <base>_hablantes.srt <audio.wav>
        -> imprime la tabla de matches y deja identificacion.json en el proyecto.
           Salida lista para renombrar.py: "Hablante 1=Adrián" ...

    python identificar.py <srt> <wav> --enrolar "Hablante 1=Adrián" "Hablante 4=Darío"
        -> enrola (o refresca) esas voces en la banca con esos nombres.

    python identificar.py <srt> <wav> --enrolar-etiquetas
        -> enrola cada etiqueta del srt con su nombre actual (útil tras renombrar).

    --actualizar   refresca en la banca los hablantes que matchearon (EMA), para que
                   el centroide siga a la voz a través de micrófonos/salas distintas.
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np

RAIZ = Path(__file__).resolve().parent
ROOT = RAIZ.parent
sys.path.insert(0, str(RAIZ))
import util  # noqa: E402

MODELO_EMB = "pyannote/wespeaker-voxceleb-resnet34-LM"
# Umbrales de coseno, calibrados con la sesión parlamentaria de 90 min:
#   mismo hablante (centroides de mitades distintas): 0.892 - 0.947
#   hablantes distintos: mediana ~0.42, peor par 0.834 (turnos contaminados por
#   interjecciones del otro; el resto de los pares < 0.61).
# Dos niveles: "seguro" (por encima del peor par distinto) se aplica directo;
# "probable" requiere confirmación por contexto de la conversación.
UMBRAL_SEGURO = 0.86
UMBRAL_DEF = 0.70   # piso de "probable" (ajustable con --umbral)
MARGEN_2DO = 0.05   # un "probable" debe superar por esto al 2.º candidato
MIN_DUR = 2.5     # s: turnos más cortos no dan un embedding confiable
MAX_CROP = 10.0   # s: recorte máximo por turno (el centro, lejos de los bordes)


def parse_hablantes_srt(path: Path):
    """Devuelve {etiqueta: [(ini, fin), ...]} desde un _hablantes.srt."""
    turnos = {}
    for b in re.split(r"\n\s*\n", path.read_text(encoding="utf-8").strip()):
        lineas = [l for l in b.splitlines() if l.strip()]
        if len(lineas) < 3:
            continue
        m = re.search(r"(\d\d):(\d\d):(\d\d),(\d+)\s*-->\s*(\d\d):(\d\d):(\d\d),(\d+)", lineas[1])
        if not m:
            continue
        g = list(map(int, m.groups()))
        ini = g[0]*3600 + g[1]*60 + g[2] + g[3]/1000
        fin = g[4]*3600 + g[5]*60 + g[6] + g[7]/1000
        texto = " ".join(lineas[2:])
        if ": " not in texto:
            continue
        etiqueta = texto.split(": ", 1)[0].strip()
        if 0 < len(etiqueta) <= 40:
            turnos.setdefault(etiqueta, []).append((ini, fin))
    return turnos


def centroide(inf, audio, sr, tramos, max_turnos):
    """Embedding promedio (robusto) de un hablante a partir de sus turnos.
    Toma los turnos más largos, recorta el CENTRO de cada uno (los bordes suelen
    pisar al hablante anterior/siguiente) y descarta los crops atípicos (habla
    solapada) antes de promediar."""
    import torch
    utiles = sorted((t for t in tramos if t[1] - t[0] >= MIN_DUR),
                    key=lambda t: t[1] - t[0], reverse=True)[:max_turnos]
    if not utiles:
        return None, 0
    embs = []
    for ini, fin in utiles:
        dur = fin - ini
        if dur > MAX_CROP:  # el centro del turno
            ini += (dur - MAX_CROP) / 2
            fin = ini + MAX_CROP
        i0, i1 = int(ini * sr), min(len(audio), int(fin * sr))
        if i1 - i0 < int(MIN_DUR * sr) * 0.8:
            continue
        w = torch.from_numpy(audio[i0:i1]).unsqueeze(0)
        e = np.asarray(inf({"waveform": w, "sample_rate": sr}), dtype=np.float32).ravel()
        n = np.linalg.norm(e)
        if n > 0:
            embs.append(e / n)
    if not embs:
        return None, 0
    embs = np.stack(embs)
    c = embs.mean(axis=0)
    c /= np.linalg.norm(c)
    if len(embs) >= 3:
        # descartar crops atípicos (solapamiento/ruido) y re-promediar
        cos = embs @ c
        umbral_crop = float(np.median(cos)) - 0.1
        buenos = embs[cos >= umbral_crop]
        if len(buenos) >= 2:
            c = buenos.mean(axis=0)
            c /= np.linalg.norm(c)
            embs = buenos
    return c, len(embs)


def cargar_banca(path: Path):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8-sig"))
    return {"version": 1, "modelo": MODELO_EMB, "voces": {}}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("srt", help="<base>_hablantes.srt (etiquetas + tiempos por turno)")
    ap.add_argument("wav", help="Audio de la reunión (el wav 16k que usó la diarización)")
    ap.add_argument("--voces", default=str(ROOT / "voces.json"),
                    help="Banca de voces (def: voces.json en la raíz del repo)")
    ap.add_argument("--umbral", type=float, default=UMBRAL_DEF,
                    help=f"Coseno mínimo para reconocer una voz (def: {UMBRAL_DEF})")
    ap.add_argument("--max-turnos", type=int, default=8,
                    help="Turnos (los más largos) por hablante para el centroide (def: 8)")
    ap.add_argument("--enrolar", nargs="+", metavar="ETIQ=NOMBRE", default=None,
                    help='Enrolar: "Hablante 1=Adrián" ... (usa el audio de esa etiqueta)')
    ap.add_argument("--enrolar-etiquetas", action="store_true",
                    help="Enrolar cada etiqueta del srt con su nombre actual")
    ap.add_argument("--actualizar", action="store_true",
                    help="Refrescar en la banca (EMA) las voces que matchearon")
    args = ap.parse_args()

    srt, wav = Path(args.srt), Path(args.wav)
    for f in (srt, wav):
        if not f.exists():
            print(f"ERROR: no existe {f}", file=sys.stderr)
            return 1

    turnos = parse_hablantes_srt(srt)
    if not turnos:
        print("ERROR: el srt no tiene turnos con etiqueta de hablante", file=sys.stderr)
        return 1

    import soundfile as sf
    import torch
    from pyannote.audio import Inference, Model

    token = (ROOT / ".hf_token").read_text(encoding="utf-8").strip()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    modelo = Model.from_pretrained(MODELO_EMB, token=token)
    # audio pre-cargado en memoria: el decodificador propio de pyannote 4
    # (torchcodec) no funciona en este entorno
    inf = Inference(modelo, window="whole", device=device)
    audio, sr = sf.read(str(wav), dtype="float32")
    if audio.ndim > 1:
        audio = audio.mean(axis=1)

    print(f"Hablantes en el srt: {', '.join(sorted(turnos))}", flush=True)
    centroides = {}
    for etiq, tramos in sorted(turnos.items()):
        c, n = centroide(inf, audio, sr, tramos, args.max_turnos)
        if c is None:
            print(f"  {etiq}: sin turnos largos suficientes, no se puede identificar")
            continue
        centroides[etiq] = c
        print(f"  {etiq}: centroide de {n} turnos", flush=True)

    banca_path = Path(args.voces)
    banca = cargar_banca(banca_path)
    hoy = time.strftime("%Y-%m-%d")

    def enrolar(nombre, emb):
        v = banca["voces"].get(nombre)
        if v is None:
            banca["voces"][nombre] = {"emb": [round(float(x), 6) for x in emb],
                                      "reuniones": 1, "actualizado": hoy}
            return "enrolada"
        viejo = np.asarray(v["emb"], dtype=np.float32)
        nuevo = 0.7 * viejo + 0.3 * emb          # EMA: la voz manda, el ruido se diluye
        nuevo /= np.linalg.norm(nuevo)
        v["emb"] = [round(float(x), 6) for x in nuevo]
        v["reuniones"] = v.get("reuniones", 1) + 1
        v["actualizado"] = hoy
        return f"refrescada ({v['reuniones']} reuniones)"

    # ---- modo enrolar ----
    if args.enrolar or args.enrolar_etiquetas:
        pares = []
        if args.enrolar:
            for p in args.enrolar:
                if "=" not in p:
                    print(f"Par inválido (falta '='): {p}", file=sys.stderr)
                    return 1
                etiq, nombre = (x.strip() for x in p.split("=", 1))
                pares.append((etiq, nombre))
        if args.enrolar_etiquetas:
            pares += [(e, e) for e in centroides]
        for etiq, nombre in pares:
            if etiq not in centroides:
                print(f"  {etiq}: no está en el srt (o sin audio suficiente), se omite")
                continue
            print(f"  {nombre}: {enrolar(nombre, centroides[etiq])}")
        util.escribir_json(banca_path, banca, indent=2)
        print(f"Banca guardada: {banca_path} ({len(banca['voces'])} voces)")
        return 0

    # ---- modo identificar ----
    if not banca["voces"]:
        print(f"Banca vacía ({banca_path}): enrolá voces primero (--enrolar).")
        return 0
    nombres = list(banca["voces"])
    matriz = np.stack([np.asarray(banca["voces"][n]["emb"], dtype=np.float32) for n in nombres])

    candidatos = []  # (cos, etiqueta, nombre)
    puntajes = {}    # etiqueta -> [(cos, nombre) ordenado desc]
    for etiq, c in centroides.items():
        fila = sorted(((float(cos), n) for n, cos in zip(nombres, matriz @ c)), reverse=True)
        puntajes[etiq] = fila
        candidatos += [(cos, etiq, n) for cos, n in fila]
    candidatos.sort(reverse=True)

    # asignación única, del mejor coseno hacia abajo:
    #   - "seguro":   cos >= UMBRAL_SEGURO (por encima del peor par distinto medido)
    #   - "probable": cos >= umbral Y supera al 2.º candidato por MARGEN_2DO
    asignado, usado = {}, set()
    for cos, etiq, nombre in candidatos:
        if cos < args.umbral or etiq in asignado or nombre in usado:
            continue
        if cos >= UMBRAL_SEGURO:
            nivel = "seguro"
        else:
            segundo = next((c for c, n in puntajes[etiq] if n != nombre and n not in usado), 0.0)
            if cos - segundo < MARGEN_2DO:
                continue  # demasiado parejo con otra voz: mejor no arriesgar
            nivel = "probable"
        asignado[etiq] = (nombre, cos, nivel)
        usado.add(nombre)

    print(f"\nUmbrales: seguro>={UMBRAL_SEGURO}, probable>={args.umbral} (+{MARGEN_2DO} sobre el 2.º)")
    pares = []
    for etiq in sorted(centroides):
        if etiq in asignado:
            nombre, cos, nivel = asignado[etiq]
            print(f"  ✔ {etiq} = {nombre}  (cos {cos:.3f}, {nivel})")
            if etiq != nombre:
                pares.append(f"{etiq}={nombre}")
            if args.actualizar and nivel == "seguro":
                enrolar(nombre, centroides[etiq])  # solo los seguros refrescan la banca
        else:
            nombre, cos = puntajes[etiq][0][1], puntajes[etiq][0][0]
            print(f"  ✘ {etiq}: sin match (más cercano: {nombre}, cos {cos:.3f})")
    if args.actualizar and asignado:
        util.escribir_json(banca_path, banca, indent=2)

    out = srt.with_name("identificacion.json")
    util.escribir_json(out, {
        "umbral_seguro": UMBRAL_SEGURO, "umbral_probable": args.umbral,
        "asignaciones": {e: {"nombre": n, "cos": round(c, 3), "nivel": nv}
                         for e, (n, c, nv) in asignado.items()},
        "sin_match": {e: {"mas_cercano": puntajes[e][0][1], "cos": round(puntajes[e][0][0], 3)}
                      for e in centroides if e not in asignado},
    }, indent=2)
    print(f"\nGuardado: {out}")
    if pares:
        print("Para aplicar: renombrar.py con " + " ".join(f'"{p}"' for p in pares))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
