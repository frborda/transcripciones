#!/usr/bin/env python3
r"""Servicio de diarización persistente (pre-warm del modelo pyannote).

Lo arranca el watcher y queda corriendo. Carga el pipeline UNA sola vez al
iniciar (en CPU, sin ocupar GPU), y atiende pedidos dejando los turnos en JSON.
Solo sube el modelo a la GPU mientras procesa un pedido y lo baja al terminar,
para no competir con la transcripción (whisper) durante la reunión.

Pedidos: archivos JSON en incoming\tg\diar_jobs\<id>.json con {"wav": "<ruta>"}.
Salida: <wav sin extensión>_turnos.json  (+ se renombra el pedido a .done).

Uso:  python diarizar_service.py
"""
import json
import os
import sys
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parent
ROOT = RAIZ.parent
JOBS = ROOT / "incoming" / "tg" / "diar_jobs"

sys.path.insert(0, str(RAIZ))
import util


def main() -> int:
    # instancia única: si ya hay un servicio corriendo, salir (evita duplicar el
    # modelo en RAM y carreras sobre la carpeta de pedidos al reiniciar el watcher).
    # use_last_error=True + get_last_error() es la forma confiable de leer
    # ERROR_ALREADY_EXISTS (windll.GetLastError puede quedar pisado entre llamadas).
    _mutex = None
    try:
        import ctypes
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        _mutex = k32.CreateMutexW(None, False, "Global\\grabador_diar_service")
        if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
            print("[diar] ya hay una instancia corriendo; salgo.", flush=True)
            return 0
    except Exception:
        pass

    os.environ.setdefault("OMP_NUM_THREADS", "6")
    import torch
    import soundfile as sf
    from pyannote.audio import Pipeline

    torch.set_num_threads(6)
    JOBS.mkdir(parents=True, exist_ok=True)

    token = (ROOT / ".hf_token").read_text(encoding="utf-8").strip()
    # community-1 (más preciso) con fallback al 3.1
    pipeline = None
    for modelo in ("pyannote/speaker-diarization-community-1", "pyannote/speaker-diarization-3.1"):
        try:
            print(f"[diar] cargando {modelo} (una vez, en CPU)...", flush=True)
            pipeline = Pipeline.from_pretrained(modelo, token=token)
            break
        except Exception as e:
            print(f"[diar] no pude cargar {modelo}: {e}", flush=True)
    if pipeline is None:
        print("[diar] ERROR: no se pudo cargar ningún modelo de diarización", flush=True)
        return 1
    tiene_gpu = torch.cuda.is_available()
    print(f"[diar] listo. GPU disponible: {tiene_gpu}. Esperando pedidos...", flush=True)

    def diarizar(wav_path: Path):
        data, sr = sf.read(str(wav_path), dtype="float32")
        if data.ndim == 1:
            data = data[None, :]
        else:
            data = data.T
        waveform = torch.from_numpy(data)
        if tiene_gpu:
            pipeline.to(torch.device("cuda"))  # subir a GPU solo ahora (libre tras la reunión)
        try:
            output = pipeline({"waveform": waveform, "sample_rate": sr})
        finally:
            if tiene_gpu:
                pipeline.to(torch.device("cpu"))
                torch.cuda.empty_cache()
        annotation = getattr(output, "exclusive_speaker_diarization", None)
        if annotation is None:
            annotation = getattr(output, "speaker_diarization", output)
        turnos = []
        for turn, _, speaker in annotation.itertracks(yield_label=True):
            turnos.append({"inicio": float(turn.start), "fin": float(turn.end), "hablante": speaker})
        turnos.sort(key=lambda t: t["inicio"])
        out = wav_path.with_name(wav_path.stem + "_turnos.json")
        # atómico: la sesión headless pollea por EXISTENCIA de este archivo; no debe
        # llegar a leer un JSON a medio escribir.
        util.escribir_json(out, turnos, indent=2)
        print(f"[diar] {wav_path.name}: {len(turnos)} turnos -> {out.name}", flush=True)

    while True:
        for jf in sorted(JOBS.glob("*.json")):
            try:
                datos = json.loads(jf.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                # JSON a medio escribir (no debería pasar si el productor escribe
                # atómico): reintentar en el próximo ciclo; si es viejo, está corrupto.
                try:
                    if time.time() - jf.stat().st_mtime > 5:
                        os.replace(jf, jf.with_suffix(".err"))
                except OSError:
                    pass
                continue
            try:
                wav = Path(datos["wav"])
                if not wav.exists():
                    raise FileNotFoundError(f"no existe el wav {wav}")
                print(f"[diar] procesando {wav.name}...", flush=True)
                diarizar(wav)
                os.replace(jf, jf.with_suffix(".done"))  # os.replace pisa si existe
            except Exception as e:  # noqa: BLE001
                print(f"[diar] error en {jf.name}: {e}", flush=True)
                try:
                    os.replace(jf, jf.with_suffix(".err"))
                except OSError:
                    pass
        time.sleep(1)


if __name__ == "__main__":
    raise SystemExit(main())
