#!/usr/bin/env python3
"""Transcribe audio (m4a, mp3, wav, etc.) usando faster-whisper en GPU o CPU.

Uso (PowerShell):
    python transcribir.py audio.m4a
    python transcribir.py audio.m4a --modelo small --idioma es
    python transcribir.py audio.m4a --device cpu

Por defecto usa la GPU (CUDA) si está disponible y cae a CPU si no.

Genera junto al audio: <nombre>.txt, <nombre>.srt y <nombre>_palabras.json
(tiempos por palabra, para que fusionar.py divida los segmentos en los cambios
de hablante).
"""
import argparse
import json
import sys
from pathlib import Path

# La consola de Windows usa cp1252 y crashea si whisper emite un carácter raro
# (p. ej. una alucinación en otro alfabeto). Forzamos UTF-8 tolerante a errores
# para que el eco a consola NUNCA tumbe la transcripción.
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from faster_whisper import WhisperModel


def fmt_ts(segundos: float) -> str:
    h, resto = divmod(int(segundos), 3600)
    m, s = divmod(resto, 60)
    ms = int((segundos - int(segundos)) * 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def main() -> int:
    p = argparse.ArgumentParser(description="Transcribe audio con faster-whisper")
    p.add_argument("audio", help="Ruta al archivo de audio")
    p.add_argument("--modelo", default="large-v3",
                   help="Modelo whisper (tiny, base, small, medium, large-v3). Def: large-v3")
    p.add_argument("--idioma", default="es", help="Idioma (es, en, ...) o 'auto'. Def: es")
    p.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"],
                   help="Dispositivo: auto (GPU si hay), cuda o cpu. Def: auto")
    p.add_argument("--glosario", default=None,
                   help="Archivo de términos del dominio (uno por línea) para sesgar el "
                        "decodificado (hotwords). Def: glosario.txt en la raíz del repo, si existe")
    args = p.parse_args()

    audio = Path(args.audio)
    if not audio.exists():
        print(f"ERROR: no existe el archivo {audio}", file=sys.stderr)
        return 1

    idioma = None if args.idioma == "auto" else args.idioma

    # Glosario del dominio -> hotwords: sesga el decodificado hacia los términos
    # que el ASR suele errar (siglas, jerga). Con condition_on_previous_text=False
    # las hotwords aplican a CADA ventana, justo lo que necesitamos.
    glosario = Path(args.glosario) if args.glosario else Path(__file__).resolve().parent.parent / "glosario.txt"
    hotwords = None
    if glosario.exists():
        terminos = [t.strip() for t in glosario.read_text(encoding="utf-8-sig").splitlines()
                    if t.strip() and not t.strip().startswith("#")]
        if terminos:
            hotwords = ", ".join(terminos)
            print(f"Glosario: {len(terminos)} términos de {glosario.name}", flush=True)
            # el prompt de whisper admite ~224 tokens y las hotwords viven ahí:
            # un glosario gigante diluye el sesgo y termina recortado. ~900 chars
            # ≈ 200+ tokens en español: avisar para que se pode antes de crecer más.
            if len(hotwords) > 900:
                print(f"AVISO: glosario muy largo ({len(hotwords)} chars); whisper puede "
                      "recortarlo. Conviene podar términos que ya no falle.", file=sys.stderr)

    # selección de dispositivo: GPU (float16) si está disponible, si no CPU (int8)
    device = args.device
    if device == "auto":
        try:
            import torch
            device = "cuda" if torch.cuda.is_available() else "cpu"
        except Exception:
            device = "cpu"
    compute_type = "float16" if device == "cuda" else "int8"

    print(f"Cargando modelo '{args.modelo}' ({device}, {compute_type})...", flush=True)
    try:
        model = WhisperModel(args.modelo, device=device, compute_type=compute_type)
    except Exception as e:
        if device == "cuda":
            print(f"AVISO: fallo al iniciar en GPU ({e}). Cayendo a CPU/int8.", file=sys.stderr)
            device, compute_type = "cpu", "int8"
            model = WhisperModel(args.modelo, device=device, compute_type=compute_type)
        else:
            raise

    print(f"Transcribiendo {audio.name} ...", flush=True)
    segments, info = model.transcribe(
        str(audio), language=idioma, vad_filter=True,
        # tiempos por palabra: habilitan la fusión fina con la diarización y el
        # umbral de alucinación de abajo
        word_timestamps=True,
        # sin condicionar en el texto previo: corta los bucles de repetición
        # (la alucinación típica de whisper en silencios/música)
        condition_on_previous_text=False,
        # descarta texto "inventado" dentro de silencios largos
        hallucination_silence_threshold=2.0,
        # términos del dominio (glosario.txt): reduce errores tipo "cuervo" por "QR"
        hotwords=hotwords,
    )
    print(f"Idioma detectado: {info.language} (prob {info.language_probability:.2f})", flush=True)

    txt_path = audio.with_suffix(".txt")
    srt_path = audio.with_suffix(".srt")
    # Escribir a .tmp y renombrar al final: el pipeline valida por existencia de
    # la salida, y una corrida interrumpida no debe dejar un .srt parcial que el
    # siguiente run dé por completo.
    txt_tmp = txt_path.with_name(txt_path.name + ".tmp")
    srt_tmp = srt_path.with_name(srt_path.name + ".tmp")
    pal_path = audio.with_name(audio.stem + "_palabras.json")
    pal_tmp = pal_path.with_name(pal_path.name + ".tmp")
    palabras = []

    with txt_tmp.open("w", encoding="utf-8") as ftxt, srt_tmp.open("w", encoding="utf-8") as fsrt:
        for i, seg in enumerate(segments, start=1):
            texto = seg.text.strip()
            ftxt.write(texto + "\n")
            fsrt.write(f"{i}\n{fmt_ts(seg.start)} --> {fmt_ts(seg.end)}\n{texto}\n\n")
            for w in (seg.words or []):
                palabras.append({"inicio": round(w.start, 3), "fin": round(w.end, 3),
                                 "palabra": w.word.strip(), "p": round(w.probability, 3)})
            # eco en consola para ver el avance
            print(f"[{fmt_ts(seg.start)}] {texto}", flush=True)

    pal_tmp.write_text(json.dumps(palabras, ensure_ascii=False), encoding="utf-8")
    txt_tmp.replace(txt_path)
    srt_tmp.replace(srt_path)
    pal_tmp.replace(pal_path)

    print(f"\nListo:\n  {txt_path}\n  {srt_path}\n  {pal_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
