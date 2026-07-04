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


# Alucinaciones canónicas de whisper en español (artefactos de YouTube presentes en
# su corpus de entrenamiento, aparecen en silencios largos). Si un segmento ENTERO
# normalizado es una de estas frases, se descarta solo. Solo frases que jamás se
# dirían en una reunión; ante la duda, NO agregar acá (para eso están las pasadas).
ALUCINACIONES = {
    "gracias por ver el video",
    "gracias por ver el video hasta la proxima",
    "gracias por ver",
    "no olvides suscribirte",
    "suscribete al canal",
    "suscribete",
    "subtitulos realizados por la comunidad de amara org",
    "subtitulos por la comunidad de amara org",
}


def _norm_frase(texto: str) -> str:
    """minúsculas, sin acentos ni puntuación, espacios colapsados."""
    t = unicodedata.normalize("NFD", texto.lower())
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    return " ".join(re.findall(r"[a-z0-9]+", t))


def colapsar_bucles(texto, ws, umbral=5, dejar=3):
    """Colapsa los bucles de repetición del ASR: una misma palabra >= 'umbral' veces
    SEGUIDAS ("no, no, no, ... x17") nunca es habla real; se dejan 'dejar' copias.
    Recorta texto y tiempos por palabra JUNTOS (siguen sincronizados para la fusión).
    Devuelve (texto, ws, palabras_colapsadas)."""
    if not ws:
        return texto, ws, 0
    norm = [_norm_frase(w["palabra"]) for w in ws]
    keep, i, colapsado = [], 0, 0
    while i < len(ws):
        j = i
        while j + 1 < len(norm) and norm[j + 1] == norm[i] and norm[i]:
            j += 1
        run = j - i + 1
        if run >= umbral:
            keep.extend(range(i, i + dejar))
            colapsado += run - dejar
        else:
            keep.extend(range(i, j + 1))
        i = j + 1
    if not colapsado:
        return texto, ws, 0
    ws = [ws[k] for k in keep]
    # reconstruir el texto desde las palabras (la puntuación viene pegada a cada una)
    return " ".join(w["palabra"] for w in ws), ws, colapsado


def aplicar_wpe(onda, sr=16000, bloque_s=60, solapa_s=1):
    """DERREVERBERACIÓN WPE (nara-wpe): quita la cola de eco de salas grandes o de
    techos altos ANTES de whisper (el eco emborrona las sílabas y es de lo que más
    degrada la transcripción con micrófono lejano). Por bloques de 60 s con 1 s de
    contexto para que el filtro converja, así el audio largo no explota la RAM.
    El original nunca se toca: esto solo transforma la señal en memoria."""
    import numpy as np
    from nara_wpe.wpe import wpe
    from nara_wpe.utils import istft, stft

    n = len(onda)
    out = np.empty_like(onda)
    paso = bloque_s * sr
    solapa = solapa_s * sr
    ini = 0
    while ini < n:
        fin = min(n, ini + paso)
        a = max(0, ini - solapa)
        seg = onda[a:fin].astype(np.float64)[None, :]
        Y = stft(seg, size=512, shift=128)
        Z = wpe(Y.transpose(2, 0, 1), taps=10, delay=3, iterations=3).transpose(1, 2, 0)
        z = istft(Z, size=512, shift=128)[0]
        largo = fin - a
        if len(z) < largo:
            z = np.pad(z, (0, largo - len(z)))
        out[ini:fin] = z[ini - a:largo].astype(onda.dtype)
        ini = fin
    return out


def _norm_alinear(palabra: str) -> str:
    """Normaliza una palabra para el alineador MMS (vocabulario a-z): minúsculas y
    sin acentos. Lo que no queda representable (números, puntuación sola) va al
    comodín '*', que el alineador absorbe sin romper el resto del segmento."""
    w = unicodedata.normalize("NFD", palabra.lower())
    w = "".join(c for c in w if unicodedata.category(c) != "Mn")
    w = re.sub(r"[^a-z]", "", w)
    return w or "*"


def alinear_forzado(audio_path, segs, device, onda=None):
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
    # alinear sobre la MISMA onda que transcribió whisper (con WPE si se aplicó)
    audio = onda if onda is not None else decode_audio(str(audio_path), sampling_rate=sr)

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
    p.add_argument("--wpe", default="off", choices=["on", "off"],
                   help="Derreverberación WPE antes de whisper (salas con eco / techos "
                        "altos). El audio original no se modifica. Def: off")
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

    # decodificar UNA vez (whisper acepta el array a 16 kHz); la misma onda se
    # reutiliza para la alineación forzada, y es donde se aplica el WPE si se pidió
    from faster_whisper.audio import decode_audio
    onda = decode_audio(str(audio), sampling_rate=16000)
    if args.wpe == "on":
        try:
            print("Aplicando WPE (derreverberación)...", flush=True)
            onda = aplicar_wpe(onda)
        except Exception as e:
            print(f"AVISO: WPE falló ({e}); se transcribe el audio original.",
                  file=sys.stderr)

    print(f"Transcribiendo {audio.name} ...", flush=True)
    segments, info = model.transcribe(
        onda, language=idioma, vad_filter=True,
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

    # 1b) limpieza automática ANTES de alinear: descartar alucinaciones conocidas
    #     y colapsar bucles de repetición (así tampoco se gasta GPU alineándolos y
    #     la limpieza sobrevive a cualquier re-fusión posterior)
    descartadas = colapsadas = 0
    filtrados = []
    for s in segs:
        if _norm_frase(s["texto"]) in ALUCINACIONES:
            descartadas += 1
            continue
        s["texto"], s["ws"], c = colapsar_bucles(s["texto"], s["ws"])
        colapsadas += c
        filtrados.append(s)
    segs = filtrados
    if descartadas or colapsadas:
        print(f"Limpieza automática: {descartadas} alucinación(es) descartada(s), "
              f"{colapsadas} palabra(s) de bucles colapsada(s)", flush=True)

    # 2) NO liberar el modelo whisper: `del model` (ctranslate2) aborta el proceso
    #    en este entorno (teardown CUDA de Windows, ver Notas del runbook). El
    #    alineador va en fp16 (~0,6 GB) y convive con large-v3 (~3 GB) en 8 GB.

    # 3) alineación forzada de los tiempos por palabra (wav2vec2), con fallback
    if args.alinear != "off":
        try:
            ok, fallo = alinear_forzado(audio, segs, device, onda=onda)
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
