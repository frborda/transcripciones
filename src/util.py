#!/usr/bin/env python3
"""Helpers compartidos del pipeline.

Escritura ATÓMICA: todo el pipeline valida "por existencia del archivo de salida"
(en Windows las libs CUDA a veces devuelven código != 0 al cerrar la GPU aunque el
trabajo terminó bien). Por eso un corte a mitad de escritura NO debe dejar un
archivo parcial que el siguiente paso dé por completo. Se escribe a un .tmp y se
renombra con os.replace, que es atómico y además pisa el destino si ya existe.
"""
import json
import os
from pathlib import Path


def escribir_texto(path, texto: str):
    """Escribe texto UTF-8 de forma atómica (tmp + os.replace)."""
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(texto)
    os.replace(tmp, path)


def escribir_json(path, obj, indent=None):
    """Serializa obj a JSON y lo escribe de forma atómica."""
    escribir_texto(path, json.dumps(obj, ensure_ascii=False, indent=indent))
