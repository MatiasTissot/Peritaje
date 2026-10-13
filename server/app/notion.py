"""Cliente mínimo de la API REST de Notion.

Solo lo que usa el servidor: consultar bases de datos, leer páginas, listar y
añadir bloques. Se usa httpx directamente para no depender de un SDK más.
"""
import time

import httpx

API = "https://api.notion.com/v1"
VERSION = "2022-06-28"


class NotionError(Exception):
    pass


class Notion:
    def __init__(self, token: str, timeout: float = 20.0):
        self._http = httpx.Client(
            base_url=API,
            timeout=timeout,
            headers={
                "Authorization": f"Bearer {token}",
                "Notion-Version": VERSION,
                "Content-Type": "application/json",
            },
        )

    def _pedir(self, metodo: str, ruta: str, **kw) -> dict:
        # Notion limita a ~3 peticiones/s; ante 429 o 5xx se reintenta con espera.
        for intento in range(4):
            r = self._http.request(metodo, ruta, **kw)
            if r.status_code == 429 or r.status_code >= 500:
                espera = float(r.headers.get("Retry-After", 1 + intento))
                time.sleep(min(espera, 10))
                continue
            if r.status_code >= 400:
                raise NotionError(f"Notion {r.status_code}: {r.text[:300]}")
            return r.json()
        raise NotionError(f"Notion no responde ({r.status_code}) en {ruta}")

    def consultar(self, db_id: str, filtro: dict | None = None, orden: list | None = None) -> list[dict]:
        cuerpo: dict = {"page_size": 100}
        if filtro:
            cuerpo["filter"] = filtro
        if orden:
            cuerpo["sorts"] = orden
        resultados = []
        while True:
            datos = self._pedir("POST", f"/databases/{db_id}/query", json=cuerpo)
            resultados.extend(datos.get("results", []))
            if not datos.get("has_more"):
                return resultados
            cuerpo["start_cursor"] = datos["next_cursor"]

    def pagina(self, page_id: str) -> dict:
        return self._pedir("GET", f"/pages/{page_id}")

    def hijos(self, block_id: str) -> list[dict]:
        resultados, cursor = [], None
        while True:
            params = {"page_size": 100}
            if cursor:
                params["start_cursor"] = cursor
            datos = self._pedir("GET", f"/blocks/{block_id}/children", params=params)
            resultados.extend(datos.get("results", []))
            if not datos.get("has_more"):
                return resultados
            cursor = datos["next_cursor"]

    def anadir_hijos(self, block_id: str, bloques: list[dict], after: str | None = None) -> dict:
        """Añade bloques al final de block_id, o justo debajo del bloque `after`."""
        # La API admite como máximo 100 bloques por llamada.
        ultimo = {}
        for i in range(0, len(bloques), 100):
            cuerpo = {"children": bloques[i:i + 100]}
            if after:
                cuerpo["after"] = after
            ultimo = self._pedir("PATCH", f"/blocks/{block_id}/children", json=cuerpo)
            if after and ultimo.get("results"):
                after = ultimo["results"][-1]["id"]
        return ultimo
