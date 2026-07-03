#!/usr/bin/env python3
r"""Une las partes (chunks) de una sesión incremental en un proyecto normal.

Entrada: carpeta de sesión con chunks\chunk_001.<ext> (+ el .srt/.txt/_palabras.json
de cada parte, generados por transcribir.py a medida que fueron llegando).
Salida (en la carpeta de la sesión, con el nombre de la sesión como base):
  <sesion>.wav            mono 16k, concatenación de todas las partes (para diarizar)
  <sesion>.srt / .txt     transcripción unida con los tiempos corridos al offset real
  <sesion>_palabras.json  tiempos por palabra, también corridos

Los offsets salen del LARGO REAL EN MUESTRAS de cada parte convertida a wav, así
el SRT unido queda alineado exactamente con el wav que después se diariza.

Uso:
    python unir_chunks.py proyectos\sesion_12331249_20260612-101500
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import util

AUDIO_EXT = {".m4a", ".mp3", ".wav", ".ogg", ".oga", ".opus", ".aac", ".flac", ".mp4", ".webm", ".m4b"}
SR = 16000


def parse_srt(path: Path):
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


def a_wav(chunk: Path):
    """Convierte la parte a wav mono 16k (cacheado junto al chunk).
    Devuelve None si el archivo está dañado (p. ej. grabación cortada en seco).

    Valida el cache antes de reusarlo: un _16k.wav pre-generado por el watcher pudo
    quedar truncado (reinicio/corte a mitad de la conversión); reusarlo correría
    todos los offsets siguientes en silencio. Y escribe a .tmp + rename para no
    dejar nunca un wav parcial cacheado.
    """
    wav = chunk.with_name(chunk.stem + "_16k.wav")
    if wav.exists():
        try:
            if sf.info(str(wav)).frames > 0:
                return wav
        except Exception:
            pass
        wav.unlink()  # cache inválido/truncado: regenerar
    tmp = wav.with_name(wav.name + ".tmp")
    # -f wav explícito: ffmpeg no infiere el formato de salida de la extensión ".tmp"
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(chunk),
                        "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", "-f", "wav", str(tmp)],
                       capture_output=True)
    if r.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
        if tmp.exists():
            tmp.unlink()
        return None
    os.replace(tmp, wav)
    return wav


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("sesion", help="Carpeta de la sesión (proyectos\\sesion_...)")
    args = ap.parse_args()

    ses = Path(args.sesion).resolve()
    chunks = [c for c in sorted((ses / "chunks").glob("chunk_*"))
              if c.suffix.lower() in AUDIO_EXT and not c.name.endswith("_16k.wav")]
    if not chunks:
        print("ERROR: no hay chunks de audio en la sesión", file=sys.stderr)
        return 1

    wav_out = ses / (ses.name + ".wav")
    srt_out = ses / (ses.name + ".srt")
    txt_out = ses / (ses.name + ".txt")
    pal_out = ses / (ses.name + "_palabras.json")

    segs, palabras = [], []
    offset = 0.0
    sin_texto = 0
    # el wav se escribe a .tmp y se renombra al final: un corte no deja un wav parcial
    # que la validación "por existencia" del pipeline dé por completo.
    wav_tmp = wav_out.with_name(wav_out.name + ".tmp")
    # format="WAV" explícito: SoundFile no puede inferirlo de la extensión ".wav.tmp"
    with sf.SoundFile(str(wav_tmp), "w", samplerate=SR, channels=1,
                      subtype="PCM_16", format="WAV") as out:
        for c in chunks:
            wav = a_wav(c)
            if wav is None:
                print(f"  {c.name}: DAÑADO, se omite (sin offset)")
                continue
            data, sr = sf.read(str(wav), dtype="int16")
            assert sr == SR, f"{wav} no está a {SR} Hz"
            out.write(data)
            srt_c = c.with_suffix(".srt")
            if srt_c.exists() and srt_c.stat().st_size > 0:
                for ini, fin, texto in parse_srt(srt_c):
                    segs.append((ini + offset, fin + offset, texto))
            else:
                sin_texto += 1
                print(f"  AVISO: {c.name} entra al audio SIN transcripción (.srt) — "
                      "su texto no aparecerá en los entregables", file=sys.stderr)
            pal_c = c.with_name(c.stem + "_palabras.json")
            if pal_c.exists() and pal_c.stat().st_size > 0:
                for w in json.loads(pal_c.read_text(encoding="utf-8")):
                    palabras.append({**w, "inicio": round(w["inicio"] + offset, 3),
                                     "fin": round(w["fin"] + offset, 3)})
            dur = len(data) / SR
            print(f"  {c.name}: {dur:.1f}s (offset {offset:.1f}s)")
            offset += dur
    os.replace(wav_tmp, wav_out)

    srt_txt = "".join(f"{i}\n{fmt_ts(ini)} --> {fmt_ts(fin)}\n{texto}\n\n"
                      for i, (ini, fin, texto) in enumerate(segs, 1))
    util.escribir_texto(srt_out, srt_txt)
    util.escribir_texto(txt_out, "".join(t + "\n" for _, _, t in segs))
    util.escribir_json(pal_out, palabras)

    if sin_texto:
        print(f"\nAVISO: {sin_texto} parte(s) entraron sin transcripción.", file=sys.stderr)
    print(f"\nUnido: {len(chunks)} partes, {offset/60:.1f} min, {len(segs)} segmentos")
    print(f"  {wav_out}\n  {srt_out}\n  {txt_out}\n  {pal_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
