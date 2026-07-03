#!/usr/bin/env python3
"""Login interactivo de Telethon (una sola vez) para crear tg_user.session.

Necesita .tg_config.json con api_id / api_hash (y opcional phone).
Ejecutar a mano una vez:  python tg_login.py
Te va a pedir el código que Telegram manda a tu app (y la contraseña 2FA si tenés).
Si ya hay sesión, solo confirma sin pedir nada.
"""
import asyncio
import json
from pathlib import Path

from telethon import TelegramClient

RAIZ = Path(__file__).resolve().parent
ROOT = RAIZ.parent
cfg = json.loads((ROOT / ".tg_config.json").read_text(encoding="utf-8-sig"))


async def main():
    client = TelegramClient(str(ROOT / "tg_user"), int(cfg["api_id"]), cfg["api_hash"])
    if cfg.get("phone"):
        await client.start(phone=cfg["phone"])
    else:
        await client.start()  # pide el teléfono y el código por consola
    me = await client.get_me()
    print(f"Login OK como: {me.username or me.first_name} (id {me.id})")
    print("Sesión guardada en tg_user.session. Ya podés arrancar tg_watcher.ps1")
    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
