"""Configuración del servidor, leída de variables de entorno.

Las credenciales (token de Notion, token de la app y autorización de Google Drive) nunca van en el código ni
en git: se cargan desde /etc/peritaje/peritaje.env (ver deploy/) con permisos 640.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _req(nombre: str) -> str:
    valor = os.environ.get(nombre, "").strip()
    if not valor:
        raise RuntimeError(f"Falta la variable de entorno {nombre}")
    return valor


@dataclass(frozen=True)
class Config:
    notion_token: str
    app_token: str
    db_encargos: str
    db_visitas: str
    db_contactos: str
    # Ventana de citas que se mandan al móvil: desde hace N días hasta dentro de M días.
    dias_atras: int = 1
    dias_adelante: int = 1
    zona_horaria: str = "Europe/Madrid"
    # Texto exacto del bloque/pestaña de Notion donde se escribe el avance.
    titulo_avance: str = "AVANCE APP"
    # Estados de encargo que no se mandan al móvil aunque tengan visita.
    estados_excluidos: tuple = ("ANULADO", "PNDT ANULADO")
    dir_datos: Path = field(default_factory=lambda: Path("./datos"))
    # Google Drive (fotos). Opcional: sin refresh token el PC no acepta fotos todavía.
    google_client_id: str = ""
    google_client_secret: str = ""
    google_refresh_token: str = ""
    # Id de la carpeta Expedientes_2026; si va vacío se busca por nombre en "Mi unidad".
    drive_carpeta_raiz: str = ""
    drive_nombre_raiz: str = "Expedientes_2026"

    @property
    def drive_configurado(self) -> bool:
        return bool(self.google_client_id and self.google_client_secret and self.google_refresh_token)


def cargar() -> Config:
    excluidos = os.environ.get("ESTADOS_EXCLUIDOS", "ANULADO,PNDT ANULADO")
    return Config(
        notion_token=_req("NOTION_TOKEN"),
        app_token=_req("APP_TOKEN"),
        db_encargos=_req("NOTION_DB_ENCARGOS"),
        db_visitas=_req("NOTION_DB_VISITAS"),
        db_contactos=_req("NOTION_DB_CONTACTOS"),
        dias_atras=int(os.environ.get("DIAS_ATRAS", "1")),
        dias_adelante=int(os.environ.get("DIAS_ADELANTE", "1")),
        zona_horaria=os.environ.get("ZONA_HORARIA", "Europe/Madrid"),
        titulo_avance=os.environ.get("TITULO_AVANCE", "AVANCE APP"),
        estados_excluidos=tuple(e.strip() for e in excluidos.split(",") if e.strip()),
        dir_datos=Path(os.environ.get("DIR_DATOS", "./datos")),
        google_client_id=os.environ.get("GOOGLE_CLIENT_ID", "").strip(),
        google_client_secret=os.environ.get("GOOGLE_CLIENT_SECRET", "").strip(),
        google_refresh_token=os.environ.get("GOOGLE_REFRESH_TOKEN", "").strip(),
        drive_carpeta_raiz=os.environ.get("DRIVE_CARPETA_RAIZ", "").strip(),
        drive_nombre_raiz=os.environ.get("DRIVE_NOMBRE_RAIZ", "Expedientes_2026").strip() or "Expedientes_2026",
    )
