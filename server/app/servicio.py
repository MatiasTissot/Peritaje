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
from .notion import Notion, NotionError

_HEX32 = re.compile(r"^[0-9a-fA-F]{32}$")
_NO_SEGURO = re.compile(r"[^A-Za-z0-9_-]")

# Tipos de bloque de Notion a los que se les pueden añadir hijos.
_CONTENEDORES = {"toggle", "callout", "quote", "column", "column_list", "synced_block"}


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


def _clave(s) -> str:
    """Para comparar contactos: sin mayúsculas ni espacios."""
    return "".join((s or "").split()).lower()


def _rico(texto: str) -> list:
    return [{"type": "text", "text": {"content": texto[:1900]}}]


def _sin_guiones(page_id: str) -> str:
    return page_id.replace("-", "")


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
            return self._vigente(id_encargo, self.notion.pagina(id_encargo))
        if exacto:
            candidatos = self.notion.consultar(
                self.cfg.db_encargos, {"property": "Exp_Gabinete", "rich_text": {"contains": id_encargo}})
            res = [c for c in candidatos if self.id_legible(c) == id_encargo
                   and not (c.get("archived") or c.get("in_trash"))]
            if len(res) > 1:
                raise EncargoNoEncontrado(
                    f"Hay {len(res)} encargos con el expediente {id_encargo} en Notion; no se escribe el avance")
            if not res:
                raise EncargoNoEncontrado(f"No se encontró el encargo {id_encargo} en Notion")
            return res[0]
        page_id = self._ids.get(id_encargo)
        if page_id:
            return self._vigente(id_encargo, self.notion.pagina(page_id))
        for filtro in (
            {"property": "Exp_Gabinete", "rich_text": {"equals": id_encargo}},
            {"property": "Exp_Gabinete", "rich_text": {"contains": id_encargo}},
            {"property": "ID_Encargo", "title": {"contains": id_encargo}},
        ):
            res = self.notion.consultar(self.cfg.db_encargos, filtro)
            if res:
                self._ids[id_encargo] = res[0]["id"]
                return self._vigente(id_encargo, res[0])
        raise EncargoNoEncontrado(f"No se encontró el encargo {id_encargo} en Notion")

    def _vigente(self, id_encargo: str, pagina: dict) -> dict:
        """Un encargo borrado (papelera) cuenta como no encontrado, para que desaparezca del móvil."""
        if pagina.get("archived") or pagina.get("in_trash"):
            self._ids.pop(id_encargo, None)
            raise EncargoNoEncontrado(f"El encargo {id_encargo} está borrado en Notion")
        return pagina

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
        # Una entrada por visita (un encargo puede tener varias). Cada encargo se lee una vez.
        encargos: dict[str, dict | None] = {}
        citas = []
        for v in visitas:
            fecha = prop(v, "Fecha_Visita")
            relacion = prop(v, "Encargo") or []
            if not fecha or not relacion:
                continue
            page_id = relacion[0]
            if page_id not in encargos:
                encargo = self.notion.pagina(page_id)
                valido = not (encargo.get("archived") or encargo.get("in_trash")) \
                    and prop(encargo, "Estado") not in self.cfg.estados_excluidos
                encargos[page_id] = encargo if valido else None
            encargo = encargos[page_id]
            if encargo is None:
                continue
            ident = self.id_legible(encargo)
            self._ids[ident] = encargo["id"]
            citas.append({"id_encargo": ident, "id_visita": _sin_guiones(v["id"]),
                          "fecha_hora": self._iso(self._a_local(fecha))})
        citas.sort(key=lambda c: (c["fecha_hora"], c["id_visita"]))
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
        return self._ficha(encargo, self._elegir_visita(visitas))

    # GET /visitas/{id_visita}

    def _visita(self, id_visita: str) -> dict:
        """Página de la visita, o EncargoNoEncontrado si no existe o está borrada."""
        if not _HEX32.match(id_visita):
            raise EncargoNoEncontrado(f"Visita {id_visita} no válida")
        try:
            visita = self.notion.pagina(id_visita)
        except NotionError as e:
            if e.status in (400, 404):
                raise EncargoNoEncontrado(f"No se encontró la visita {id_visita} en Notion") from e
            raise
        if visita.get("archived") or visita.get("in_trash"):
            raise EncargoNoEncontrado(f"La visita {id_visita} está borrada en Notion")
        return visita

    def detalle_visita(self, id_visita: str) -> dict:
        visita = self._visita(id_visita)
        relacion = prop(visita, "Encargo") or []
        if not relacion:
            raise EncargoNoEncontrado(f"La visita {id_visita} no tiene encargo")
        # Siempre se lee la página (no la caché) para detectar encargos borrados.
        page_id = _sin_guiones(relacion[0])
        encargo = self._vigente(page_id, self.notion.pagina(page_id))
        fecha = prop(visita, "Fecha_Visita")
        ficha = self._ficha(encargo, (self._a_local(fecha), visita) if fecha else None)
        if not fecha:
            ficha["notas_visita"] = _vacio_a_none(prop(visita, "Notas_Campo"))
        ficha["id_visita"] = _sin_guiones(visita["id"])
        return ficha

    def _ficha(self, encargo: dict, elegida) -> dict:
        page_id = encargo["id"]
        relacion = {"property": "Encargo", "relation": {"contains": page_id}}
        contactos, con_titulo = [], set()
        for c in self.notion.consultar(self.cfg.db_contactos, relacion):
            rol = prop(c, "Rol") or "Otro"
            nombre = (prop(c, "Nombre") or "").strip()
            titulo = (prop(c, "Contacto") or "").strip()
            # Si el título dice algo más que "Rol - Nombre" (p. ej. "PRESIDENTE"), se
            # muestra delante del nombre: si no, en el móvil no se sabría quién es.
            if titulo and _clave(titulo) not in (_clave(f"{rol} - {nombre}"), _clave(nombre)):
                nombre = f"{titulo} ({nombre})" if nombre else titulo
                con_titulo.add(len(contactos))
            contactos.append({
                "rol": rol,
                "nombre": nombre,
                "telefono": _vacio_a_none(prop(c, "Telefono")),
                "notas": _vacio_a_none(prop(c, "Notas_Contacto")),
                "email": _vacio_a_none(prop(c, "Email")),
                "direccion": _vacio_a_none(prop(c, "Direccion")),
            })
        # El asegurado de la ficha: mejor uno con título normal ("Asegurado - Nombre") que
        # uno con título propio como "PRESIDENTE", aunque los dos tengan rol Asegurado.
        asegurados = [i for i, c in enumerate(contactos) if c["rol"] == "Asegurado"]
        asegurados.sort(key=lambda i: i in con_titulo)
        asegurado = contactos[asegurados[0]] if asegurados else (contactos[0] if contactos else None)

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

    def _buscar_destino(self, page_id: str) -> tuple[tuple[str, str | None] | None, str | None]:
        """Busca dónde escribir el avance.

        Devuelve (destino, pestanas). `destino` es (padre, after): se añaden hijos a
        `padre`; si `after` no es None, se insertan justo debajo de ese bloque (caso de
        encabezado normal). `pestanas` es el primer bloque de pestañas de la página.

        Gana la pestaña AVANCE APP (o AVANCE APK). Si la página tiene pestañas pero no
        esa, destino es None para que se cree la pestaña; un desplegable o encabezado
        con ese título (avances antiguos) solo se usa en páginas sin pestañas.
        """
        objetivos = {self.cfg.titulo_avance.strip().upper(), "AVANCE APP", "AVANCE APK"}
        pendientes = [(page_id, 0)]
        otro, pestanas = None, None
        while pendientes:
            padre, nivel = pendientes.pop(0)
            for b in self.notion.hijos(padre):
                tipo = b.get("type", "")
                if tipo == "tab":
                    # Cada pestaña es un párrafo hijo del bloque "tab": su texto es el
                    # nombre y su contenido va como hijos de ese párrafo.
                    pestanas = pestanas or b["id"]
                    for p in self.notion.hijos(b["id"]):
                        if _texto((p.get(p.get("type", ""), {}) or {}).get("rich_text")).upper() in objetivos:
                            return (p["id"], None), b["id"]
                    continue
                datos = b.get(tipo, {}) or {}
                if otro is None and _texto(datos.get("rich_text")).upper() in objetivos:
                    if tipo in _CONTENEDORES or datos.get("is_toggleable"):
                        otro = (b["id"], None)
                    else:
                        otro = (padre, b["id"])
                elif b.get("has_children") and nivel < 3 and tipo in _CONTENEDORES:
                    pendientes.append((b["id"], nivel + 1))
        return (None if pestanas else otro), pestanas

    def _crear_pestana(self, page_id: str, pestanas: str | None) -> str:
        """Crea la pestaña del avance y devuelve el id del párrafo que la representa."""
        titulo = {"object": "block", "type": "paragraph", "paragraph": {"rich_text": [
            {"type": "text", "text": {"content": self.cfg.titulo_avance}}]}}
        if pestanas:
            self.notion.anadir_hijos(pestanas, [titulo])
        else:
            # Los hijos van dentro de "tab", como en cualquier bloque; fuera Notion da 400.
            self.notion.anadir_hijos(page_id, [{"object": "block", "type": "tab", "tab": {"children": [titulo]}}])
        destino, _ = self._buscar_destino(page_id)
        if destino is None:
            raise NotionError("No se pudo crear la pestaña del avance en Notion")
        return destino[0]

    def _crear_contactos(self, page_id: str, contactos: list[dict]):
        relacion = {"property": "Encargo", "relation": {"contains": page_id}}
        existentes = {
            (_clave(prop(c, "Nombre")), _clave(prop(c, "Telefono")))
            for c in self.notion.consultar(self.cfg.db_contactos, relacion)
        }
        for c in contactos:
            nombre = (c.get("nombre") or "").strip()
            if not nombre:
                continue
            clave = (_clave(nombre), _clave(c.get("telefono")))
            if clave in existentes:
                continue
            rol = (c.get("rol") or "Otro").strip() or "Otro"
            props = {
                "Contacto": {"title": _rico(f"{rol} - {nombre}")},
                "Nombre": {"rich_text": _rico(nombre)},
                "Rol": {"select": {"name": rol}},
                "Encargo": {"relation": [{"id": page_id}]},
            }
            for campo, columna in (("telefono", "Telefono"), ("email", "Email"), ("dni", "DNI_CIF"),
                                   ("direccion", "Direccion"), ("notas", "Notas_Contacto")):
                valor = (c.get(campo) or "").strip()
                if valor:
                    props[columna] = {"rich_text": _rico(valor)}
            self.notion.crear_pagina(self.cfg.db_contactos, props)
            existentes.add(clave)

    def _ya_en_notion(self, padre: str, ref: str) -> bool:
        for b in self.notion.hijos(padre):
            datos = b.get(b.get("type", ""), {}) or {}
            if _texto(datos.get("rich_text")).endswith(ref.strip()):
                return True
        return False

    def _fecha_visita_de(self, id_visita: str, encargo: dict) -> str | None:
        """Fecha programada de la visita para la cabecera del avance.

        Con varias visitas por encargo distingue un avance de otro. Si la visita ya no
        existe o no es de este encargo, se escribe igual sin ella: el avance no se pierde.
        """
        try:
            pagina = self._visita(id_visita)
        except EncargoNoEncontrado:
            return None
        if _sin_guiones(encargo["id"]) not in [_sin_guiones(r) for r in prop(pagina, "Encargo") or []]:
            return None
        fecha = prop(pagina, "Fecha_Visita")
        return self._a_local(fecha).strftime("%d/%m/%Y %H:%M") if fecha else None

    @staticmethod
    def _parrafo(texto: str, negrita: bool = False) -> dict:
        # Una línea vacía se manda sin texto (rich_text vacío), que Notion siempre acepta.
        trozos = [texto[i:i + 1900] for i in range(0, len(texto), 1900)]
        return {"object": "block", "type": "paragraph", "paragraph": {"rich_text": [
            {"type": "text", "text": {"content": t}, "annotations": {"bold": negrita}} for t in trozos
        ]}}

    def escribir_avance(self, id_encargo: str, fecha_visita_realizada: str, texto: str,
                        id_envio: str | None = None, id_visita: str | None = None,
                        contactos: list[dict] | None = None) -> str:
        with self._lock:
            if id_envio and self._ya_enviado(id_envio):
                return "ya_registrado"
            encargo = self._resolver(id_encargo, exacto=True)
            # Primero los contactos: si algo falla, la app reintenta todo y los ya creados
            # no se duplican (se comparan con los que ya tiene el encargo).
            if contactos:
                self._crear_contactos(encargo["id"], contactos)
            try:
                visita = self._a_local(fecha_visita_realizada).strftime("%d/%m/%Y %H:%M")
            except ValueError:
                visita = fecha_visita_realizada
            programada = self._fecha_visita_de(id_visita, encargo) if id_visita else None
            if programada:
                visita = f"{programada} (realizada {visita})"
            recibido = self._hoy().strftime("%d/%m/%Y %H:%M")
            # La referencia del envío va en la cabecera para reconocer un avance ya escrito
            # aunque se haya perdido el registro local (p. ej. Notion aplicó la escritura
            # pero la respuesta no llegó).
            ref = f" · ref {id_envio}" if id_envio else ""
            cabecera = f"Avance desde la app · visita {visita} · recibido {recibido}{ref}"
            bloques = [self._parrafo(cabecera, negrita=True)]
            bloques += [self._parrafo(linea) for linea in texto.splitlines()]
            bloques.append({"object": "block", "type": "divider", "divider": {}})

            destino, pestanas = self._buscar_destino(encargo["id"])
            if destino is None:
                # Aún no existe: se crea la pestaña "AVANCE APP" y el avance va dentro.
                destino = (self._crear_pestana(encargo["id"], pestanas), None)
            padre, after = destino
            if id_envio and self._ya_en_notion(padre, ref):
                self._apuntar_envio(id_envio)
                return "ya_registrado"
            # En una pestaña o desplegable se añade al final; bajo un encabezado normal,
            # justo debajo de él.
            self.notion.anadir_hijos(padre, bloques, after=after)
            if id_envio:
                self._apuntar_envio(id_envio)
            return "ok"
