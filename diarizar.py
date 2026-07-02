#!/usr/bin/env python3
"""Diarización de hablantes con pyannote.audio sobre un WAV.

Escribe los turnos (inicio, fin, hablante) a un JSON reutilizable.
Carga el audio con soundfile para esquivar torchcodec.
Usa GPU (CUDA) si está disponible; si no, CPU.

Uso (PowerShell):
    python diarizar.py Voz.wav
    python diarizar.py Voz.wav --hilos 6 --device cpu
"""
import argparse
import json
import os
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("wav", help="Ruta al WAV (mono 16k)")
    ap.add_argument("--hilos", type=int, default=6, help="Hilos de torch en CPU (def 6)")
    ap.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"],
                    help="Dispositivo: auto (GPU si hay), cuda o cpu. Def: auto")
    ap.add_argument("--token-file", default=".hf_token")
    args = ap.parse_args()

    # limitar hilos ANTES de importar torch para no competir con la transcripción
    os.environ.setdefault("OMP_NUM_THREADS", str(args.hilos))

    import torch
    import soundfile as sf
    from pyannote.audio import Pipeline

    torch.set_num_threads(args.hilos)

    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"

    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    wav = Path(args.wav)
    if not wav.exists():
        print(f"ERROR: no existe {wav}", file=sys.stderr)
        return 1

    # community-1 (más preciso) con fallback al 3.1 si no carga
    pipeline = None
    for modelo in ("pyannote/speaker-diarization-community-1", "pyannote/speaker-diarization-3.1"):
        try:
            print(f"Cargando pipeline {modelo} (device={device})...", flush=True)
            pipeline = Pipeline.from_pretrained(modelo, token=token)
            break
        except Exception as e:
            print(f"AVISO: no pude cargar {modelo} ({e}). Pruebo el siguiente.", file=sys.stderr)
    if pipeline is None:
        print("ERROR: no se pudo cargar ningún modelo de diarización", file=sys.stderr)
        return 1
    pipeline.to(torch.device(device))

    print(f"Leyendo {wav.name} con soundfile...", flush=True)
    data, sr = sf.read(str(wav), dtype="float32")
    if data.ndim == 1:
        data = data[None, :]            # (1, n)
    else:
        data = data.T                   # (canales, n)
    waveform = torch.from_numpy(data)

    print("Ejecutando diarización (esto tarda en CPU)...", flush=True)
    output = pipeline({"waveform": waveform, "sample_rate": sr})

    # pyannote.audio 4.x devuelve DiarizeOutput; usamos la versión exclusiva (sin
    # solapamientos) que es la recomendada para mapear contra una transcripción.
    annotation = getattr(output, "exclusive_speaker_diarization", None)
    if annotation is None:
        annotation = getattr(output, "speaker_diarization", output)

    turnos = []
    for turn, _, speaker in annotation.itertracks(yield_label=True):
        turnos.append({"inicio": float(turn.start), "fin": float(turn.end), "hablante": speaker})
    turnos.sort(key=lambda t: t["inicio"])

    out = wav.with_name(wav.stem + "_turnos.json")
    out.write_text(json.dumps(turnos, ensure_ascii=False, indent=2), encoding="utf-8")

    hablantes = sorted({t["hablante"] for t in turnos})
    print(f"\nListo: {len(turnos)} turnos, {len(hablantes)} hablantes detectados: {hablantes}")
    print(f"Guardado en {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
