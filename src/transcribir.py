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

Los tiempos por palabra se refinan con ALINEACIÓN FORZADA (wav2vec2, torchaudio
MMS_FA): los de whisper salen de la cross-attention y son ruidosos (sesgo ~+0,3 s
y jitter en los bordes), y la atribución de hablante de fusionar.py depende 100%
de ellos. Si la alineación no está disponible (sin torchaudio/modelo), se usan
los de whisper tal cual (--alinear off la desactiva).
"""
import argparse
import json
import re
import sys
import unicodedata
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


def _norm_alinear(palabra: str) -> str:
    """Normaliza una palabra para el alineador MMS (vocabulario a-z): minúsculas y
    sin acentos. Lo que no queda representable (números, puntuación sola) va al
    comodín '*', que el alineador absorbe sin romper el resto del segmento."""
    w = unicodedata.normalize("NFD", palabra.lower())
    w = "".join(c for c in w if unicodedata.category(c) != "Mn")
    w = re.sub(r"[^a-z]", "", w)
    return w or "*"


def alinear_forzado(audio_path, segs, device):
    """Refina los tiempos por palabra con ALINEACIÓN FORZADA (torchaudio MMS_FA,
    wav2vec2 CTC multilingüe). Los tiempos de whisper salen de la cross-attention
    y traen sesgo (~+0,3 s) y jitter; la atribución de hablante de fusionar.py
    depende de ellos, así que alinear acá ataca la raíz de los cortes mal asignados.

    Muta seg["ws"] (inicio/fin por palabra) DENTRO de los límites de cada segmento.
    Devuelve (segmentos_alineados, segmentos_con_fallback). Si un segmento falla,
    conserva los tiempos de whisper (degradación elegante).
    """
    import torch
    import torchaudio
    from faster_whisper.audio import decode_audio

    # NOTA: F.forced_align está deprecado en torchaudio 2.9 (transición a
    # mantenimiento). Anclados a 2.8; si se actualiza torchaudio, revisar esto.
    bundle = torchaudio.pipelines.MMS_FA
    sr = bundle.sample_rate
    modelo = bundle.get_model().to(device).eval()
    if device == "cuda":
        modelo = modelo.half()  # fp16: ~0,6 GB, convive con whisper large-v3 en 8 GB
    tokenizer = bundle.get_tokenizer()
    aligner = bundle.get_aligner()
    audio = decode_audio(str(audio_path), sampling_rate=sr)

    ok = fallo = 0
    with torch.inference_mode():
        for s in segs:
            ws = s["ws"]
            if not ws:
                continue
            i0 = max(0, int(s["ini"] * sr))
            i1 = min(len(audio), int(s["fin"] * sr))
            if i1 - i0 < sr // 10:  # menos de 0,1 s: nada que alinear
                fallo += 1
                continue
            try:
                wav = torch.from_numpy(audio[i0:i1]).unsqueeze(0).to(device)
                if device == "cuda":
                    wav = wav.half()
                emission, _ = modelo(wav)
                # el aligner (forced_align) trabaja en fp32
                spans = aligner(emission[0].float(),
                                tokenizer([_norm_alinear(w["palabra"]) for w in ws]))
            except Exception:
                fallo += 1  # texto/audio inconsistentes (p. ej. alucinación): quedan los de whisper
                continue
            paso = (i1 - i0) / sr / emission.size(1)  # segundos por frame de emisión
            for w, sp in zip(ws, spans):
                if sp:
                    w["inicio"] = round(s["ini"] + sp[0].start * paso, 3)
                    w["fin"] = round(s["ini"] + sp[-1].end * paso, 3)
            ok += 1
    del modelo
    if device == "cuda":
        torch.cuda.empty_cache()
    return ok, fallo


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
    p.add_argument("--alinear", default="auto", choices=["auto", "off"],
                   help="Alineación forzada de tiempos por palabra (wav2vec2/MMS_FA). "
                        "auto: se usa si está disponible, con fallback a los tiempos de "
                        "whisper. off: siempre tiempos de whisper. Def: auto")
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

    # 1) consumir el generador YA (con eco de avance); los tiempos por palabra de
    #    whisper quedan como fallback de la alineación forzada
    segs = []
    for seg in segments:
        texto = seg.text.strip()
        ws = [{"inicio": round(w.start, 3), "fin": round(w.end, 3),
               "palabra": w.word.strip(), "p": round(w.probability, 3)}
              for w in (seg.words or [])]
        segs.append({"ini": seg.start, "fin": seg.end, "texto": texto, "ws": ws})
        print(f"[{fmt_ts(seg.start)}] {texto}", flush=True)

    # 2) NO liberar el modelo whisper: `del model` (ctranslate2) aborta el proceso
    #    en este entorno (teardown CUDA de Windows, ver Notas del runbook). El
    #    alineador va en fp16 (~0,6 GB) y convive con large-v3 (~3 GB) en 8 GB.

    # 3) alineación forzada de los tiempos por palabra (wav2vec2), con fallback
    if args.alinear != "off":
        try:
            ok, fallo = alinear_forzado(audio, segs, device)
            print(f"Alineación forzada (MMS_FA): {ok} segmentos alineados"
                  + (f", {fallo} con tiempos de whisper" if fallo else ""), flush=True)
        except Exception as e:
            print(f"AVISO: alineación forzada no disponible ({e}); "
                  "se usan los tiempos de whisper.", file=sys.stderr)

    # 4) aplanar palabras y asegurar monotonicidad (fusionar.py ordena por inicio;
    #    un tiempo fuera de orden re-mezclaría palabras de segmentos vecinos)
    palabras = [w for s in segs for w in s["ws"]]
    prev = 0.0
    for w in palabras:
        if w["inicio"] < prev:
            w["inicio"] = prev
        if w["fin"] < w["inicio"]:
            w["fin"] = w["inicio"]
        prev = w["inicio"]

    txt_path = audio.with_suffix(".txt")
    srt_path = audio.with_suffix(".srt")
    # Escribir a .tmp y renombrar al final: el pipeline valida por existencia de
    # la salida, y una corrida interrumpida no debe dejar un .srt parcial que el
    # siguiente run dé por completo.
    txt_tmp = txt_path.with_name(txt_path.name + ".tmp")
    srt_tmp = srt_path.with_name(srt_path.name + ".tmp")
    pal_path = audio.with_name(audio.stem + "_palabras.json")
    pal_tmp = pal_path.with_name(pal_path.name + ".tmp")

    with txt_tmp.open("w", encoding="utf-8") as ftxt, srt_tmp.open("w", encoding="utf-8") as fsrt:
        for i, s in enumerate(segs, start=1):
            ftxt.write(s["texto"] + "\n")
            fsrt.write(f"{i}\n{fmt_ts(s['ini'])} --> {fmt_ts(s['fin'])}\n{s['texto']}\n\n")

    pal_tmp.write_text(json.dumps(palabras, ensure_ascii=False), encoding="utf-8")
    txt_tmp.replace(txt_path)
    srt_tmp.replace(srt_path)
    pal_tmp.replace(pal_path)

    print(f"\nListo:\n  {txt_path}\n  {srt_path}\n  {pal_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
