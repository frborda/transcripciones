#!/usr/bin/env python3
"""Interfaz que usa la sesión HEADLESS de Claude para hablar por Telegram.

No toca Telegram directamente (eso lo hace el watcher, único dueño de la sesión
Telethon). Se comunica con el watcher por archivos:
  - Para ENVIAR: deja un "trabajo" en incoming/tg/outbox/ y espera el ack del watcher.
  - Para RECIBIR: lee incoming/tg/replies_<chat_id>.jsonl (que llena el watcher).

Subcomandos:
  send-message  <chat_id> <texto>
  send-document <chat_id> <archivo> [<archivo> ...]
  wait-reply    <chat_id> [--timeout 600]    -> imprime el próximo texto, o "TIMEOUT"
"""
import argparse
import json
import time
import uuid
from pathlib import Path

RAIZ = Path(__file__).resolve().parent
TG_DIR = RAIZ / "incoming" / "tg"
OUTBOX = TG_DIR / "outbox"


def _enviar_job(job: dict, ack_timeout: int):
    OUTBOX.mkdir(parents=True, exist_ok=True)
    jid = uuid.uuid4().hex
    tmp = OUTBOX / f"{jid}.tmp"
    tmp.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8")
    tmp.rename(OUTBOX / f"{jid}.json")            # publicación atómica
    done, err = OUTBOX / f"{jid}.done", OUTBOX / f"{jid}.err"
    fin = time.time() + ack_timeout
    while time.time() < fin:
        if done.exists():
            done.unlink(missing_ok=True)
            return True, ""
        if err.exists():
            m = err.read_text(encoding="utf-8")
            err.unlink(missing_ok=True)
            return False, m
        time.sleep(0.5)
    return False, "timeout esperando al watcher (¿está corriendo tg_watcher?)"


def drain(chat_id) -> int:
    """Descarta los mensajes viejos del chat: deja el cursor al final.
    Llamar JUSTO ANTES de enviar una pregunta, para que wait-reply solo
    devuelva la respuesta nueva (no un mensaje anterior que quedó en la cola)."""
    q = TG_DIR / f"replies_{chat_id}.jsonl"
    cur = TG_DIR / f"replies_{chat_id}.cursor"
    n = len(q.read_text(encoding="utf-8-sig").splitlines()) if q.exists() else 0
    cur.write_text(str(n))
    print(f"drain: cursor={n}")
    return 0


def wait_reply(chat_id, timeout: int) -> int:
    q = TG_DIR / f"replies_{chat_id}.jsonl"
    cur = TG_DIR / f"replies_{chat_id}.cursor"
    consumed = int(cur.read_text()) if cur.exists() else 0
    fin = time.time() + timeout
    while time.time() < fin:
        if q.exists():
            lineas = q.read_text(encoding="utf-8-sig").splitlines()
            if len(lineas) > consumed:
                msg = json.loads(lineas[consumed])
                cur.write_text(str(consumed + 1))
                print(msg.get("text", ""))
                return 0
        time.sleep(2)
    print("TIMEOUT")
    return 2


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("send-message"); p.add_argument("chat_id"); p.add_argument("texto")
    p = sub.add_parser("send-document"); p.add_argument("chat_id"); p.add_argument("archivos", nargs="+")
    p = sub.add_parser("wait-reply"); p.add_argument("chat_id"); p.add_argument("--timeout", type=int, default=600)
    p = sub.add_parser("drain"); p.add_argument("chat_id")
    args = ap.parse_args()

    TG_DIR.mkdir(parents=True, exist_ok=True)
    if args.cmd == "send-message":
        ok, msg = _enviar_job({"type": "message", "chat_id": args.chat_id, "text": args.texto}, 60)
        print("OK" if ok else f"ERROR: {msg}")
        return 0 if ok else 1
    if args.cmd == "send-document":
        archivos = [str(Path(a).resolve()) for a in args.archivos]
        ok, msg = _enviar_job({"type": "document", "chat_id": args.chat_id, "files": archivos}, 300)
        print("OK" if ok else f"ERROR: {msg}")
        return 0 if ok else 1
    if args.cmd == "wait-reply":
        return wait_reply(args.chat_id, args.timeout)
    if args.cmd == "drain":
        return drain(args.chat_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
