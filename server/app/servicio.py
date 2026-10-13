"""Lógica de negocio: traduce la estructura de Notion al contrato de la app.

Estructura de Notion usada (workspace "Gestión de Encargos - Peritaciones"):
- Encargos: ID_Encargo (título), Exp_Gabinete, Exp_Compania, Direccion Calle,
  "Piso, Puerta", Descripcion, Tipo_Siniestro, Estado, Ubicacion_Maps, Visitas, Contactos.
- Visitas: Titulo, Fecha_Visita (fecha con hora), Encargo (relación), Notas_Campo.
- Contactos: Contacto (título), Nombre, Rol, Telefono, Notas_Contacto, Encargo (relación).

El identificador que ve el móvil (id_encargo) es el Exp_Gabinete, p. ej. MA26043441.
Si un encargo no lo tiene, se usa el id de la página de Notion sin guiones.
"""
from __future__ import annotations

import json
import re
import threading
from datetime import datetime, time, timedelta

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python 3.8 (Xubuntu 20.04)
    from backports.zoneinfo import ZoneInfo

from .config import Config
from .notion import Notion

_HEX32 = re.compile(r"^[0-9a-fA-F]{32}$")
_NO_SEGURO = re.compile(r"[^A-Za-z0-9_-]")

# Tipos de bloque de Notion a los que se les pueden añadir hijos.
_CONTENEDORES = {"toggle", "callout", "quote", "tab", "column", "column_list", "synced_block"}


class EncargoNoEncontrado(Exception):
    pass


# --- lectura de propiedades -------------------------------------------------

def _texto(rich: list | None) -> str:
    return "".join(t.get("plain_text", "") for t in (rich or [])).strip()


def prop(pagina: dict, nombre: str):
    p = pagina.get("properties", {}).get(nombre)
    if not p:
        return None
    tipo = p.get("type")
    v = p.get(tipo)
    if tipo in ("title", "rich_text"):
        return _texto(v)
    if tipo in ("select", "status"):
        return v.get("name") if v else None
    if tipo == "date":
        return v.get("start") if v else None
    if tipo == "relation":
        return [r["id"] for r in (v or [])]
    if tipo in ("url", "phone_number", "email", "checkbox", "number"):
        return v
    return None


def _vacio_a_none(s):
    return s if s else None


# --- servicio ---------------------------------------------------------------

class Servicio:
    def __init__(self, cfg: Config, notion: Notion):
        self.cfg = cfg
        self.notion = notion
        self.tz = ZoneInfo(cfg.zona_horaria)
        self._ids: dict[str, str] = {}  # id_encargo -> page_id de Notion
        self._lock = threading.Lock()
        cfg.dir_datos.mkdir(parents=True, exist_ok=True)
        self._registro = cfg.dir_datos / "envios.json"

    # fechas

    def _a_local(self, iso: str) -> datetime:
        """Fecha de Notion (con zona, UTC o solo día) -> datetime local sin zona."""
        if len(iso) == 10:
            return datetime.combine(datetime.fromisoformat(iso).date(), time(0, 0))
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            return dt
        return dt.astimezone(self.tz).replace(tzinfo=None)

    @staticmethod
    def _iso(dt: datetime) -> str:
        return dt.strftime("%Y-%m-%dT%H:%M:%S")

    def _hoy(self) -> datetime:
        return datetime.now(self.tz).replace(tzinfo=None)

    def _elegir_visita(self, visitas: list[tuple[datetime, dict]]):
        """La próxima visita desde hoy; si no hay, la última pasada."""
        if not visitas:
            return None
        visitas.sort(key=lambda v: v[0])
        inicio_hoy = datetime.combine(self._hoy().date(), time(0, 0))
        for v in visitas:
            if v[0] >= inicio_hoy:
                return v
        return visitas[-1]

    # identificadores

    def id_legible(self, encargo: dict) -> str:
        exp = _NO_SEGURO.sub("", prop(encargo, "Exp_Gabinete") or "")
        return exp or encargo["id"].replace("-", "")

    def _resolver(self, id_encargo: str, exacto: bool = False) -> dict:
        """Busca la página del encargo.

        Con `exacto` (para escribir el avance) solo vale un Exp_Gabinete idéntico y
        único: es preferible dar error a escribir en el expediente equivocado.
        """
        if _HEX32.match(id_encargo):
            return self.notion.pagina(id_encargo)
        if exacto:
            candidatos = self.notion.consultar(
                self.cfg.db_encargos, {"property": "Exp_Gabinete", "rich_text": {"contains": id_encargo}})
            res = [c for c in candidatos if self.id_legible(c) == id_encargo]
            if len(res) > 1:
                raise EncargoNoEncontrado(
                    f"Hay {len(res)} encargos con el expediente {id_encargo} en Notion; no se escribe el avance")
            if not res:
                raise EncargoNoEncontrado(f"No se encontró el encargo {id_encargo} en Notion")
            return res[0]
        page_id = self._ids.get(id_encargo)
        if page_id:
            return self.notion.pagina(page_id)
        for filtro in (
            {"property": "Exp_Gabinete", "rich_text": {"equals": id_encargo}},
            {"property": "Exp_Gabinete", "rich_text": {"contains": id_encargo}},
            {"property": "ID_Encargo", "title": {"contains": id_encargo}},
        ):
            res = self.notion.consultar(self.cfg.db_encargos, filtro)
            if res:
                self._ids[id_encargo] = res[0]["id"]
                return res[0]
        raise EncargoNoEncontrado(f"No se encontró el encargo {id_encargo} en Notion")

    # GET /citas-index

    def citas_index(self) -> list[dict]:
        hoy = self._hoy().date()
        desde = datetime.combine(hoy - timedelta(days=self.cfg.dias_atras), time(0, 0), self.tz)
        hasta = datetime.combine(hoy + timedelta(days=self.cfg.dias_adelante), time(23, 59), self.tz)
        visitas = self.notion.consultar(
            self.cfg.db_visitas,
            {"and": [
                {"property": "Fecha_Visita", "date": {"on_or_after": desde.isoformat()}},
                {"property": "Fecha_Visita", "date": {"on_or_before": hasta.isoformat()}},
            ]},
        )
        por_encargo: dict[str, list] = {}
        for v in visitas:
            fecha = prop(v, "Fecha_Visita")
            encargos = prop(v, "Encargo") or []
            if fecha and encargos:
                por_encargo.setdefault(encargos[0], []).append((self._a_local(fecha), v))

        citas = []
        for page_id, lista in por_encargo.items():
            encargo = self.notion.pagina(page_id)
            if encargo.get("archived") or encargo.get("in_trash"):
                continue
            if prop(encargo, "Estado") in self.cfg.estados_excluidos:
                continue
            fecha, _ = self._elegir_visita(lista)
            ident = self.id_legible(encargo)
            self._ids[ident] = encargo["id"]
            citas.append({"id_encargo": ident, "fecha_hora": self._iso(fecha)})
        citas.sort(key=lambda c: c["fecha_hora"])
        return citas

    # GET /citas/{id_encargo}

    def detalle(self, id_encargo: str) -> dict:
        encargo = self._resolver(id_encargo)
        page_id = encargo["id"]
        relacion = {"property": "Encargo", "relation": {"contains": page_id}}

        visitas = [
            (self._a_local(prop(v, "Fecha_Visita")), v)
            for v in self.notion.consultar(self.cfg.db_visitas, relacion)
            if prop(v, "Fecha_Visita")
        ]
        elegida = self._elegir_visita(visitas)

        contactos = []
        for c in self.notion.consultar(self.cfg.db_contactos, relacion):
            contactos.append({
                "rol": prop(c, "Rol") or "Otro",
                "nombre": prop(c, "Nombre") or prop(c, "Contacto") or "",
                "telefono": _vacio_a_none(prop(c, "Telefono")),
                "notas": _vacio_a_none(prop(c, "Notas_Contacto")),
            })
        asegurado = next((c for c in contactos if c["rol"] == "Asegurado"), contactos[0] if contactos else None)

        calle = prop(encargo, "Direccion Calle") or ""
        piso = prop(encargo, "Piso, Puerta") or ""
        direccion = ", ".join(p for p in (calle, piso) if p)

        ident = self.id_legible(encargo)
        self._ids[ident] = page_id
        return {
            "id_encargo": ident,
            "fecha_visita": self._iso(elegida[0]) if elegida else None,
            "direccion": direccion,
            "asegurado": asegurado["nombre"] if asegurado else "",
            "telefono_contacto": asegurado["telefono"] if asegurado else None,
            "tipo_siniestro": _vacio_a_none(prop(encargo, "Tipo_Siniestro")),
            "notas_gabinete": _vacio_a_none(prop(encargo, "Descripcion")),
            # Campos extra (el contrato permite añadir lo que traiga Notion).
            "titulo": prop(encargo, "ID_Encargo") or ident,
            "exp_compania": _vacio_a_none(prop(encargo, "Exp_Compania")),
            "estado_notion": prop(encargo, "Estado"),
            "url_maps": _vacio_a_none(prop(encargo, "Ubicacion_Maps")),
            "notas_visita": _vacio_a_none(prop(elegida[1], "Notas_Campo")) if elegida else None,
            "contactos": contactos,
        }

    # POST /avance

    def _ya_enviado(self, id_envio: str) -> bool:
        if not self._registro.exists():
            return False
        return id_envio in json.loads(self._registro.read_text() or "[]")

    def _apuntar_envio(self, id_envio: str):
        previos = json.loads(self._registro.read_text() or "[]") if self._registro.exists() else []
        previos = (previos + [id_envio])[-2000:]
        tmp = self._registro.with_suffix(".tmp")
        tmp.write_text(json.dumps(previos))
        tmp.replace(self._registro)

    def _buscar_destino(self, page_id: str) -> tuple[str, str | None] | None:
        """Busca el bloque titulado AVANCE APP (pestaña, desplegable o encabezado).

        Devuelve (padre, after): se añaden hijos a `padre`; si `after` no es None,
        se insertan justo debajo de ese bloque (caso de encabezado normal).
        """
        objetivo = self.cfg.titulo_avance.strip().upper()
        pendientes = [(page_id, 0)]
        while pendientes:
            padre, nivel = pendientes.pop(0)
            for b in self.notion.hijos(padre):
                tipo = b.get("type", "")
                datos = b.get(tipo, {}) or {}
                if _texto(datos.get("rich_text")).upper() == objetivo:
                    if tipo in _CONTENEDORES or datos.get("is_toggleable"):
                        return b["id"], None
                    return padre, b["id"]
                if b.get("has_children") and nivel < 3 and (tipo in _CONTENEDORES or tipo == "tab"):
                    pendientes.append((b["id"], nivel + 1))
        return None

    def _ya_en_notion(self, padre: str, ref: str) -> bool:
        for b in self.notion.hijos(padre):
            datos = b.get(b.get("type", ""), {}) or {}
            if _texto(datos.get("rich_text")).endswith(ref.strip()):
                return True
        return False

    @staticmethod
    def _parrafo(texto: str, negrita: bool = False) -> dict:
        trozos = [texto[i:i + 1900] for i in range(0, len(texto), 1900)] or [""]
        return {"object": "block", "type": "paragraph", "paragraph": {"rich_text": [
            {"type": "text", "text": {"content": t}, "annotations": {"bold": negrita}} for t in trozos
        ]}}

    def escribir_avance(self, id_encargo: str, fecha_visita_realizada: str, texto: str,
                        id_envio: str | None = None) -> str:
        with self._lock:
            if id_envio and self._ya_enviado(id_envio):
                return "ya_registrado"
            encargo = self._resolver(id_encargo, exacto=True)
            try:
                visita = self._a_local(fecha_visita_realizada).strftime("%d/%m/%Y %H:%M")
            except ValueError:
                visita = fecha_visita_realizada
            recibido = self._hoy().strftime("%d/%m/%Y %H:%M")
            # La referencia del envío va en la cabecera para reconocer un avance ya escrito
            # aunque se haya perdido el registro local (p. ej. Notion aplicó la escritura
            # pero la respuesta no llegó).
            ref = f" · ref {id_envio}" if id_envio else ""
            cabecera = f"Avance desde la app · visita {visita} · recibido {recibido}{ref}"
            bloques = [self._parrafo(cabecera, negrita=True)]
            bloques += [self._parrafo(linea) for linea in texto.splitlines()]
            bloques.append({"object": "block", "type": "divider", "divider": {}})

            destino = self._buscar_destino(encargo["id"])
            if destino is None:
                # No existe aún: se crea un desplegable "AVANCE APP" al final de la página.
                self.notion.anadir_hijos(encargo["id"], [{
                    "object": "block", "type": "heading_2",
                    "heading_2": {"is_toggleable": True, "rich_text": [
                        {"type": "text", "text": {"content": self.cfg.titulo_avance}}]},
                    "children": bloques[:99],
                }])
                if len(bloques) > 99:
                    destino = self._buscar_destino(encargo["id"])
                    self.notion.anadir_hijos(destino[0], bloques[99:])
            else:
                # Con encabezado normal se inserta justo debajo (lo más reciente arriba).
                padre, after = destino
                if id_envio and self._ya_en_notion(padre, ref):
                    self._apuntar_envio(id_envio)
                    return "ya_registrado"
                self.notion.anadir_hijos(padre, bloques, after=after)
            if id_envio:
                self._apuntar_envio(id_envio)
            return "ok"
