"""Cliente mínimo de Google Drive para guardar las fotos de las visitas.

Las fotos van a las carpetas que ya usa el perito:
  Expedientes_2026/<carpeta del encargo>/Fotografias/Visita_AAAA-MM-DD/<foto>.jpg
La carpeta del encargo se busca por el expediente (MA26043441) dentro de la carpeta
raíz o de una subcarpeta suya (p. ej. "0000 - Cerrados"); si no existe, se crea con el
título del encargo en Notion ("0043 - E26L... - MA26043441 - EUROP").

Credenciales: un refresh token OAuth del usuario (scope drive) que vive solo en el PC
(ver app/autorizar_drive.py). Se usa httpx directamente para no depender de un SDK más.
"""
from __future__ import annotations

import re
import threading
import time
from pathlib import Path

import httpx

TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://www.googleapis.com/drive/v3"
SUBIDA = "https://www.googleapis.com/upload/drive/v3/files"
CARPETA = "application/vnd.google-apps.folder"
SCOPE = "https://www.googleapis.com/auth/drive"

# Trozos de la subida reanudable (múltiplo de 256 KiB, como pide Google).
TROZO = 8 * 256 * 1024


class DriveError(Exception):
    def __init__(self, mensaje: str, status: int | None = None):
        super().__init__(mensaje)
        self.status = status


def _comillas(s: str) -> str:
    """Escapa un texto para una consulta de Drive (q=...)."""
    return s.replace("\\", "\\\\").replace("'", "\\'")


def _contiene_expediente(nombre: str, expediente: str) -> bool:
    """El expediente aparece como palabra entera: "0043 - E26L... - MA26043441 - EUROP"."""
    return expediente.upper() in re.split(r"[\s\-_]+", nombre.upper())


class Drive:
    def __init__(self, client_id: str, client_secret: str, refresh_token: str,
                 raiz_id: str | None = None, raiz_nombre: str = "Expedientes_2026",
                 timeout: float = 60.0, http: httpx.Client | None = None):
        self._cred = {"client_id": client_id, "client_secret": client_secret,
                      "refresh_token": refresh_token, "grant_type": "refresh_token"}
        self._raiz_id = raiz_id or None
        self._raiz_nombre = raiz_nombre
        self._http = http or httpx.Client(timeout=timeout)
        self._token: str | None = None
        self._caduca = 0.0
        # Crear carpetas de dos peticiones a la vez duplicaría la carpeta: se hace de una en una.
        self._lock = threading.Lock()
        self._carpetas: dict[tuple, str] = {}

    # --- autenticación y peticiones ------------------------------------------

    def _acceso(self, renovar: bool = False) -> str:
        if renovar or not self._token or time.time() > self._caduca - 60:
            try:
                r = self._http.post(TOKEN_URL, data=self._cred)
            except httpx.HTTPError as e:
                raise DriveError(f"Sin conexión con Google ({type(e).__name__})") from e
            if r.status_code != 200:
                # invalid_grant: el permiso se ha retirado o caducado; hay que volver a autorizar.
                raise DriveError(f"Google no acepta la autorización del PC ({r.status_code}): {r.text[:200]}", 401)
            datos = r.json()
            self._token = datos["access_token"]
            self._caduca = time.time() + float(datos.get("expires_in", 3600))
        return self._token

    def _pedir(self, metodo: str, url: str, escritura: bool = False, **kw) -> httpx.Response:
        # Igual que con Notion: las lecturas se reintentan ante fallos de red o 5xx; una
        # escritura solo ante 429 (Google no la ha procesado), para no duplicar carpetas.
        renovado = False
        for intento in range(4):
            cabeceras = {**kw.pop("headers", {}), "Authorization": f"Bearer {self._acceso()}"}
            try:
                r = self._http.request(metodo, url, headers=cabeceras, **kw)
            except httpx.HTTPError as e:
                if escritura or intento == 3:
                    raise DriveError(f"Sin conexión con Drive ({type(e).__name__})") from e
                kw["headers"] = cabeceras
                time.sleep(1 + intento)
                continue
            kw["headers"] = cabeceras
            if r.status_code == 401 and not renovado:
                renovado = True
                self._acceso(renovar=True)
                continue
            if r.status_code == 429 or (r.status_code >= 500 and not escritura):
                time.sleep(min(2 ** intento, 10))
                continue
            if r.status_code >= 400:
                raise DriveError(f"Drive {r.status_code}: {r.text[:300]}", r.status_code)
            return r
        raise DriveError(f"Drive no responde ({r.status_code})", r.status_code)

    def _buscar(self, q: str) -> list[dict]:
        resultados, pagina = [], None
        while True:
            params = {"q": f"({q}) and trashed = false", "pageSize": 100,
                      "fields": "nextPageToken, files(id, name, parents, appProperties)"}
            if pagina:
                params["pageToken"] = pagina
            datos = self._pedir("GET", f"{API}/files", params=params).json()
            resultados.extend(datos.get("files", []))
            pagina = datos.get("nextPageToken")
            if not pagina:
                return resultados

    def _crear_carpeta(self, padre: str, nombre: str) -> str:
        r = self._pedir("POST", f"{API}/files", escritura=True, params={"fields": "id"},
                        json={"name": nombre, "mimeType": CARPETA, "parents": [padre]})
        return r.json()["id"]

    def _subcarpeta(self, padre: str, nombre: str) -> str:
        """Busca la carpeta `nombre` dentro de `padre`; si no está, la crea."""
        clave = ("sub", padre, nombre)
        if clave not in self._carpetas:
            hallada = self._buscar(f"'{_comillas(padre)}' in parents and mimeType = '{CARPETA}' "
                                   f"and name = '{_comillas(nombre)}'")
            self._carpetas[clave] = hallada[0]["id"] if hallada else self._crear_carpeta(padre, nombre)
        return self._carpetas[clave]

    # --- estructura de carpetas ------------------------------------------------

    def raiz(self) -> str:
        if not self._raiz_id:
            halladas = self._buscar(f"mimeType = '{CARPETA}' and name = '{_comillas(self._raiz_nombre)}' "
                                    f"and 'root' in parents")
            if not halladas:
                raise DriveError(f"No encuentro la carpeta {self._raiz_nombre} en tu Drive")
            self._raiz_id = halladas[0]["id"]
        return self._raiz_id

    def carpeta_encargo(self, expediente: str, titulo: str | None) -> str:
        """Carpeta del encargo: la que ya exista (en la raíz o en una subcarpeta) o una nueva."""
        clave = ("encargo", expediente)
        if clave in self._carpetas:
            return self._carpetas[clave]
        raiz = self.raiz()
        candidatas = [c for c in self._buscar(f"mimeType = '{CARPETA}' and name contains '{_comillas(expediente)}'")
                      if _contiene_expediente(c.get("name", ""), expediente)]
        elegida = next((c for c in candidatas if raiz in c.get("parents", [])), None)
        if elegida is None and candidatas:
            # Encargos ya cerrados: Expedientes_2026/0000 - Cerrados/<encargo>.
            subcarpetas = {c["id"] for c in self._buscar(f"'{raiz}' in parents and mimeType = '{CARPETA}'")}
            elegida = next((c for c in candidatas if subcarpetas & set(c.get("parents", []))), None)
        if elegida is not None:
            ident = elegida["id"]
        else:
            nombre = (titulo or "").strip() or expediente
            if not _contiene_expediente(nombre, expediente):
                nombre = f"{nombre} - {expediente}"
            ident = self._crear_carpeta(raiz, nombre)
        self._carpetas[clave] = ident
        return ident

    def carpeta_visita(self, expediente: str, titulo: str | None, dia: str) -> str:
        with self._lock:
            encargo = self.carpeta_encargo(expediente, titulo)
            fotos = self._subcarpeta(encargo, "Fotografias")
            return self._subcarpeta(fotos, f"Visita_{dia}")

    def olvidar_carpetas(self):
        """Tras un error (p. ej. carpeta borrada a mano) se vuelve a buscar todo."""
        with self._lock:
            self._carpetas.clear()

    # --- subida -------------------------------------------------------------

    def ya_subida(self, carpeta: str, sha256: str) -> str | None:
        halladas = self._buscar(f"'{_comillas(carpeta)}' in parents and "
                                f"appProperties has {{ key='sha256' and value='{_comillas(sha256)}' }}")
        return halladas[0]["id"] if halladas else None

    def subir(self, carpeta: str, nombre: str, ruta: Path, sha256: str,
              mime: str = "image/jpeg") -> str:
        """Sube el archivo con una subida reanudable. Si ya estaba (mismo sha256), no lo repite."""
        existente = self.ya_subida(carpeta, sha256)
        if existente:
            return existente
        tamano = ruta.stat().st_size
        inicio = self._pedir(
            "POST", SUBIDA, escritura=True,
            params={"uploadType": "resumable", "fields": "id"},
            headers={"X-Upload-Content-Type": mime, "X-Upload-Content-Length": str(tamano)},
            json={"name": nombre, "parents": [carpeta], "appProperties": {"sha256": sha256}},
        )
        sesion = inicio.headers["Location"]
        enviado, fallos = 0, 0
        with ruta.open("rb") as f:
            while True:
                f.seek(enviado)
                trozo = f.read(TROZO)
                fin = enviado + len(trozo) - 1
                cabeceras = {"Content-Range": f"bytes {enviado}-{fin}/{tamano}" if trozo else f"bytes */{tamano}"}
                try:
                    r = self._http.put(sesion, content=trozo, headers=cabeceras)
                except httpx.HTTPError:
                    r = None
                if r is not None and r.status_code in (200, 201):
                    return r.json()["id"]
                if r is not None and r.status_code == 308:
                    rango = r.headers.get("Range")  # "bytes=0-1234": lo que Google ya tiene
                    enviado = int(rango.rsplit("-", 1)[1]) + 1 if rango else 0
                    continue
                if r is not None and r.status_code < 500 and r.status_code != 429:
                    raise DriveError(f"Drive rechazó la foto {nombre} ({r.status_code}): {r.text[:200]}", r.status_code)
                fallos += 1
                if fallos > 4:
                    raise DriveError(f"No se pudo terminar de subir {nombre} a Drive")
                time.sleep(2 ** fallos)
                # Se pregunta a Google cuánto recibió y se sigue desde ahí.
                try:
                    r = self._http.put(sesion, headers={"Content-Range": f"bytes */{tamano}"})
                except httpx.HTTPError:
                    continue
                if r.status_code in (200, 201):
                    return r.json()["id"]
                if r.status_code == 308:
                    rango = r.headers.get("Range")
                    enviado = int(rango.rsplit("-", 1)[1]) + 1 if rango else 0
