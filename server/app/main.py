"""Servidor del PC: puente entre la app del móvil y Notion.

Endpoints (todos con Authorization: Bearer <APP_TOKEN>):
  GET  /citas-index          índice ligero de citas (id_encargo + fecha_hora)
  GET  /citas/{id_encargo}   detalle de una cita
  POST /avance               escribe el avance en el bloque AVANCE APP del encargo
  GET  /salud                comprobación de conexión desde la app
"""
from __future__ import annotations

import hmac
import logging
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import config
from .notion import Notion, NotionError
from .servicio import EncargoNoEncontrado, Servicio

log = logging.getLogger("peritaje")


class AvanceIn(BaseModel):
    id_encargo: str
    fecha_visita_realizada: str
    texto_avance: str
    # Opcional: identificador único del envío para que un reintento no duplique el avance.
    id_envio: Optional[str] = None


def crear_app(servicio: Optional[Servicio] = None, app_token: Optional[str] = None) -> FastAPI:
    if servicio is None:
        cfg = config.cargar()
        servicio = Servicio(cfg, Notion(cfg.notion_token))
        app_token = cfg.app_token

    app = FastAPI(title="Peritaje PC", docs_url=None, redoc_url=None, openapi_url=None)

    def autorizar(authorization: str = Header(default="")):
        esperado = f"Bearer {app_token}"
        if not hmac.compare_digest(authorization.encode(), esperado.encode()):
            raise HTTPException(status_code=401, detail="Token no válido")

    @app.get("/salud", dependencies=[Depends(autorizar)])
    def salud():
        return {"estado": "ok"}

    @app.get("/citas-index", dependencies=[Depends(autorizar)])
    def citas_index():
        try:
            return {"citas": servicio.citas_index()}
        except NotionError as e:
            log.exception("Error leyendo citas")
            raise HTTPException(status_code=502, detail=str(e))

    @app.get("/citas/{id_encargo}", dependencies=[Depends(autorizar)])
    def cita(id_encargo: str):
        try:
            return servicio.detalle(id_encargo)
        except EncargoNoEncontrado as e:
            raise HTTPException(status_code=404, detail=str(e))
        except NotionError as e:
            log.exception("Error leyendo cita %s", id_encargo)
            raise HTTPException(status_code=502, detail=str(e))

    @app.post("/avance", dependencies=[Depends(autorizar)])
    def avance(datos: AvanceIn):
        try:
            servicio.escribir_avance(datos.id_encargo, datos.fecha_visita_realizada,
                                     datos.texto_avance, datos.id_envio)
            return {"estado": "ok"}
        except EncargoNoEncontrado as e:
            return {"estado": "error", "detalle": str(e)}
        except NotionError as e:
            log.exception("Error escribiendo avance %s", datos.id_encargo)
            return {"estado": "error", "detalle": str(e)}

    return app


def app():
    """Fábrica para uvicorn: uvicorn app.main:app --factory"""
    logging.basicConfig(level=logging.INFO)
    return crear_app()
