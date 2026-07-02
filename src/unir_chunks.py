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
import re
import subprocess
import sys
from pathlib import Path

import soundfile as sf

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
    Devuelve None si el archivo está dañado (p. ej. grabación cortada en seco)."""
    wav = chunk.with_name(chunk.stem + "_16k.wav")
    if not wav.exists():
        r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(chunk),
                            "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(wav)],
                           capture_output=True)
        if r.returncode != 0 or not wav.exists() or wav.stat().st_size == 0:
            if wav.exists():
                wav.unlink()
            return None
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
    with sf.SoundFile(str(wav_out), "w", samplerate=SR, channels=1, subtype="PCM_16") as out:
        for c in chunks:
            wav = a_wav(c)
            if wav is None:
                print(f"  {c.name}: DAÑADO, se omite (sin offset)")
                continue
            data, sr = sf.read(str(wav), dtype="int16")
            assert sr == SR, f"{wav} no está a {SR} Hz"
            out.write(data)
            srt_c = c.with_suffix(".srt")
            if srt_c.exists():
                for ini, fin, texto in parse_srt(srt_c):
                    segs.append((ini + offset, fin + offset, texto))
            pal_c = c.with_name(c.stem + "_palabras.json")
            if pal_c.exists():
                for w in json.loads(pal_c.read_text(encoding="utf-8")):
                    palabras.append({**w, "inicio": round(w["inicio"] + offset, 3),
                                     "fin": round(w["fin"] + offset, 3)})
            dur = len(data) / SR
            print(f"  {c.name}: {dur:.1f}s (offset {offset:.1f}s)")
            offset += dur

    with srt_out.open("w", encoding="utf-8") as f:
        for i, (ini, fin, texto) in enumerate(segs, 1):
            f.write(f"{i}\n{fmt_ts(ini)} --> {fmt_ts(fin)}\n{texto}\n\n")
    txt_out.write_text("".join(t + "\n" for _, _, t in segs), encoding="utf-8")
    pal_out.write_text(json.dumps(palabras, ensure_ascii=False), encoding="utf-8")

    print(f"\nUnido: {len(chunks)} partes, {offset/60:.1f} min, {len(segs)} segmentos")
    print(f"  {wav_out}\n  {srt_out}\n  {txt_out}\n  {pal_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
