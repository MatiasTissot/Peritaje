"""Servidor del PC: puente entre la app del móvil y Notion.

Endpoints (todos con Authorization: Bearer <APP_TOKEN>):
  GET  /citas-index          índice ligero: una entrada por visita (id_encargo, id_visita, fecha_hora)
  GET  /citas/{id_encargo}   detalle de una cita (datos antiguos: una visita por encargo)
  GET  /visitas/{id_visita}  detalle de una visita concreta
  POST /avance               escribe el avance en el bloque AVANCE APP del encargo
  PUT  /fotos                recibe una foto y la sube a Google Drive (carpeta del encargo)
  GET  /salud                comprobación de conexión desde la app
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import re
import uuid
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

from . import VERSION, config
from .drive import Drive, DriveError
from .notion import Notion, NotionError
from .servicio import EncargoNoEncontrado, Servicio

log = logging.getLogger("peritaje")


class ContactoIn(BaseModel):
    rol: str = "Otro"
    nombre: str
    telefono: Optional[str] = None
    email: Optional[str] = None
    dni: Optional[str] = None
    direccion: Optional[str] = None
    notas: Optional[str] = None


class AvanceIn(BaseModel):
    id_encargo: str
    fecha_visita_realizada: str
    texto_avance: str
    # Opcional: identificador único del envío para que un reintento no duplique el avance.
    id_envio: Optional[str] = None
    # Opcional: visita concreta (un encargo puede tener varias). None en datos antiguos.
    id_visita: Optional[str] = None
    # Opcional: contactos nuevos tomados en la visita (inquilino, causante...). Se crean
    # en la base Contactos de Notion enlazados al encargo; los repetidos no se duplican.
    contactos: List[ContactoIn] = []


_NOMBRE_FOTO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,120}\.(jpg|jpeg)$", re.IGNORECASE)
_DIA = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
# Una foto de móvil ronda 3-8 MB; el límite solo evita llenar el disco del PC por error.
MAX_FOTO = 40 * 1024 * 1024


def crear_app(servicio: Optional[Servicio] = None, app_token: Optional[str] = None,
              drive: Optional[Drive] = None) -> FastAPI:
    if servicio is None:
        cfg = config.cargar()
        servicio = Servicio(cfg, Notion(cfg.notion_token))
        app_token = cfg.app_token
        if cfg.drive_configurado:
            drive = Drive(cfg.google_client_id, cfg.google_client_secret, cfg.google_refresh_token,
                          cfg.drive_carpeta_raiz, cfg.drive_nombre_raiz)
    temporales = servicio.cfg.dir_datos / "fotos_entrantes"

    app = FastAPI(title="Peritaje PC", docs_url=None, redoc_url=None, openapi_url=None)

    def autorizar(authorization: str = Header(default="")):
        esperado = f"Bearer {app_token}"
        if not hmac.compare_digest(authorization.encode(), esperado.encode()):
            raise HTTPException(status_code=401, detail="Token no válido")

    @app.get("/salud", dependencies=[Depends(autorizar)])
    def salud():
        return {"estado": "ok", "version": VERSION}

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

    @app.get("/visitas/{id_visita}", dependencies=[Depends(autorizar)])
    def visita(id_visita: str):
        try:
            return servicio.detalle_visita(id_visita)
        except EncargoNoEncontrado as e:
            raise HTTPException(status_code=404, detail=str(e))
        except NotionError as e:
            log.exception("Error leyendo visita %s", id_visita)
            raise HTTPException(status_code=502, detail=str(e))

    @app.post("/avance", dependencies=[Depends(autorizar)])
    def avance(datos: AvanceIn):
        try:
            servicio.escribir_avance(datos.id_encargo, datos.fecha_visita_realizada,
                                     datos.texto_avance, datos.id_envio, datos.id_visita,
                                     [c.model_dump() if hasattr(c, "model_dump") else c.dict() for c in datos.contactos])
            return {"estado": "ok"}
        except EncargoNoEncontrado as e:
            return {"estado": "error", "detalle": str(e)}
        except NotionError as e:
            log.exception("Error escribiendo avance %s", datos.id_encargo)
            return {"estado": "error", "detalle": str(e)}

    @app.put("/fotos", dependencies=[Depends(autorizar)])
    async def foto(
        request: Request,
        id_encargo: str = Query(..., min_length=1, max_length=64),
        fecha_visita: str = Query(..., description="AAAA-MM-DD"),
        nombre: str = Query(...),
        x_sha256: str = Header(...),
    ):
        """Recibe una foto (cuerpo = bytes del JPEG) y la sube a
        Expedientes_2026/<encargo>/Fotografias/Visita_<fecha>/<nombre>.

        Solo responde "ok" cuando Drive ha confirmado la foto completa. Es idempotente:
        si la misma foto (mismo sha256) ya está en la carpeta, no se sube otra vez.
        """
        if drive is None:
            raise HTTPException(status_code=503, detail="Google Drive no está configurado en el PC")
        sha = x_sha256.strip().lower()
        if not _NOMBRE_FOTO.match(nombre) or not _DIA.match(fecha_visita) or not _SHA256.match(sha) \
                or not re.match(r"^[A-Za-z0-9_-]+$", id_encargo):
            raise HTTPException(status_code=400, detail="Datos de la foto no válidos")

        # La foto se guarda entera en disco antes de subirla (no en memoria: el PC tiene poca).
        temporales.mkdir(parents=True, exist_ok=True)
        ruta = temporales / f"{uuid.uuid4().hex}.jpg"
        resumen = hashlib.sha256()
        tamano = 0
        try:
            with ruta.open("wb") as f:
                async for trozo in request.stream():
                    tamano += len(trozo)
                    if tamano > MAX_FOTO:
                        raise HTTPException(status_code=413, detail="La foto es demasiado grande")
                    resumen.update(trozo)
                    f.write(trozo)
            if tamano == 0 or resumen.hexdigest() != sha:
                # Llegó incompleta o dañada: el móvil la reintentará.
                raise HTTPException(status_code=400, detail="La foto llegó incompleta")
            try:
                titulo = await run_in_threadpool(servicio.titulo_encargo, id_encargo)
                carpeta = await run_in_threadpool(drive.carpeta_visita, id_encargo, titulo, fecha_visita)
                try:
                    id_drive = await run_in_threadpool(drive.subir, carpeta, nombre, ruta, sha)
                except DriveError as e:
                    if e.status == 404:
                        # Alguien borró o movió la carpeta a mano: se busca de nuevo una vez.
                        drive.olvidar_carpetas()
                        carpeta = await run_in_threadpool(drive.carpeta_visita, id_encargo, titulo, fecha_visita)
                        id_drive = await run_in_threadpool(drive.subir, carpeta, nombre, ruta, sha)
                    else:
                        raise
            except DriveError as e:
                log.exception("Error subiendo la foto %s de %s", nombre, id_encargo)
                return {"estado": "error", "detalle": str(e)}
            return {"estado": "ok", "id_drive": id_drive}
        finally:
            ruta.unlink(missing_ok=True)

    return app


def app():
    """Fábrica para uvicorn: uvicorn app.main:app --factory"""
    logging.basicConfig(level=logging.INFO)
    return crear_app()
