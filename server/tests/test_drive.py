"""Pruebas de la subida de fotos con un Google Drive falso en memoria (no toca el Drive real)."""
import hashlib
import json
import re
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Config
from app.drive import CARPETA, Drive, DriveError
from app.main import crear_app
from app.servicio import Servicio

from test_api import FakeNotion, encargo

TOKEN = "secreto-de-prueba"
H = {"Authorization": f"Bearer {TOKEN}"}


class FakeGoogle:
    """Lo justo de la API de Drive: token, búsqueda, crear carpeta y subida reanudable."""

    def __init__(self):
        self.archivos = {"root": {"id": "root", "name": "Mi unidad", "parents": []}}
        self.n = 0
        self.sesiones = {}
        self.cortar_subida = 0  # nº de PUT que fallan a mitad (red caída)
        self.tokens_pedidos = 0

    def nuevo(self, nombre, padre, mime=CARPETA, **extra):
        self.n += 1
        fid = f"f{self.n}"
        self.archivos[fid] = {"id": fid, "name": nombre, "parents": [padre], "mimeType": mime, **extra}
        return fid

    def _coincide(self, f, q):
        if f["id"] == "root":
            return False
        for m in re.finditer(r"'([^']*)' in parents", q):
            padre = m.group(1)
            if padre not in f["parents"]:
                return False
        m = re.search(r"name = '([^']*)'", q)
        if m and f["name"] != m.group(1):
            return False
        m = re.search(r"name contains '([^']*)'", q)
        if m and m.group(1) not in f["name"]:
            return False
        if f"mimeType = '{CARPETA}'" in q and f.get("mimeType") != CARPETA:
            return False
        m = re.search(r"value='([0-9a-f]+)'", q)
        if m and f.get("appProperties", {}).get("sha256") != m.group(1):
            return False
        return True

    def __call__(self, req: httpx.Request) -> httpx.Response:
        url = str(req.url)
        if url.startswith("https://oauth2.googleapis.com/token"):
            self.tokens_pedidos += 1
            return httpx.Response(200, json={"access_token": "acc", "expires_in": 3600})
        if req.url.path == "/drive/v3/files" and req.method == "GET":
            q = req.url.params["q"]
            return httpx.Response(200, json={"files": [
                {k: v for k, v in f.items() if k != "contenido"}
                for f in self.archivos.values() if self._coincide(f, q)]})
        if req.url.path == "/drive/v3/files" and req.method == "POST":
            datos = json.loads(req.content)
            return httpx.Response(200, json={"id": self.nuevo(datos["name"], datos["parents"][0])})
        if req.url.path == "/upload/drive/v3/files" and req.method == "POST":
            datos = json.loads(req.content)
            sid = f"s{len(self.sesiones)}"
            self.sesiones[sid] = {"meta": datos, "datos": b"",
                                  "total": int(req.headers["X-Upload-Content-Length"])}
            return httpx.Response(200, headers={"Location": f"https://www.googleapis.com/sesion/{sid}"})
        if req.url.path.startswith("/sesion/") and req.method == "PUT":
            s = self.sesiones[req.url.path.rsplit("/", 1)[1]]
            rango = req.headers["Content-Range"]
            if not rango.startswith("bytes */") and req.content:
                inicio = int(rango.split()[1].split("-")[0])
                assert inicio == len(s["datos"])
                trozo = req.content
                if self.cortar_subida:
                    self.cortar_subida -= 1
                    s["datos"] += trozo[: len(trozo) // 2]
                    raise httpx.ReadError("conexión cortada")
                s["datos"] += trozo
            if len(s["datos"]) == s["total"]:
                m = s["meta"]
                fid = self.nuevo(m["name"], m["parents"][0], mime="image/jpeg",
                                 appProperties=m["appProperties"], contenido=s["datos"])
                return httpx.Response(200, json={"id": fid})
            cab = {"Range": f"bytes=0-{len(s['datos']) - 1}"} if s["datos"] else {}
            return httpx.Response(308, headers=cab)
        return httpx.Response(404, text=f"no simulado: {req.method} {url}")


@pytest.fixture
def google():
    g = FakeGoogle()
    raiz = g.nuevo("Expedientes_2026", "root")
    cerrados = g.nuevo("0000 - Cerrados", raiz)
    g.nuevo("0043 - E26L0267381 - MA26043441 - EUROP", raiz)
    g.nuevo("0036 - E26L0250628 - MA26041031 - EUROP", cerrados)
    # Otra carpeta con un número parecido que no debe confundirse.
    g.nuevo("0099 - MA260434410 - EUROP", raiz)
    g.raiz = raiz
    return g


def drive_falso(g):
    return Drive("cid", "secreto", "refresh", http=httpx.Client(transport=httpx.MockTransport(g)))


def test_usa_la_carpeta_existente_del_encargo(google):
    d = drive_falso(google)
    carpeta = d.carpeta_visita("MA26043441", "titulo que no se usa", "2026-10-26")
    visita = google.archivos[carpeta]
    fotos = google.archivos[visita["parents"][0]]
    enc = google.archivos[fotos["parents"][0]]
    assert visita["name"] == "Visita_2026-10-26"
    assert fotos["name"] == "Fotografias"
    assert enc["name"] == "0043 - E26L0267381 - MA26043441 - EUROP"
    # Segunda vez: misma carpeta, sin crear nada nuevo.
    antes = len(google.archivos)
    assert drive_falso(google).carpeta_visita("MA26043441", None, "2026-10-26") == carpeta
    assert len(google.archivos) == antes


def test_encuentra_encargos_cerrados(google):
    carpeta = drive_falso(google).carpeta_visita("MA26041031", None, "2026-10-01")
    fotos = google.archivos[google.archivos[carpeta]["parents"][0]]
    assert google.archivos[fotos["parents"][0]]["name"] == "0036 - E26L0250628 - MA26041031 - EUROP"


def test_crea_carpeta_con_el_titulo_de_notion(google):
    carpeta = drive_falso(google).carpeta_visita("MA26099999", "0101 - E26L1 - MA26099999 - EUROP", "2026-10-26")
    fotos = google.archivos[google.archivos[carpeta]["parents"][0]]
    enc = google.archivos[fotos["parents"][0]]
    assert enc["name"] == "0101 - E26L1 - MA26099999 - EUROP"
    assert enc["parents"] == [google.raiz]


def test_subida_reanuda_tras_corte_y_no_duplica(google, tmp_path):
    d = drive_falso(google)
    carpeta = d.carpeta_visita("MA26043441", None, "2026-10-26")
    datos = bytes(range(256)) * 20000  # ~5 MB, varios trozos
    ruta = tmp_path / "f.jpg"
    ruta.write_bytes(datos)
    sha = hashlib.sha256(datos).hexdigest()
    google.cortar_subida = 1
    fid = d.subir(carpeta, "2026-10-26_10-00-00.jpg", ruta, sha)
    assert google.archivos[fid]["contenido"] == datos
    assert d.subir(carpeta, "2026-10-26_10-00-00.jpg", ruta, sha) == fid
    assert sum(1 for f in google.archivos.values() if f.get("mimeType") == "image/jpeg") == 1


def test_sin_carpeta_raiz_da_error():
    g = FakeGoogle()
    with pytest.raises(DriveError):
        drive_falso(g).raiz()


@pytest.fixture
def cliente(google, tmp_path: Path):
    fake = FakeNotion({"p1": encargo("p1", "MA26043441")}, [], [], {})
    cfg = Config(notion_token="x", app_token=TOKEN, db_encargos="E", db_visitas="V",
                 db_contactos="C", dir_datos=tmp_path)
    return TestClient(crear_app(Servicio(cfg, fake), TOKEN, drive_falso(google)))


def _put(cliente, datos, sha=None, **params):
    q = {"id_encargo": "MA26043441", "fecha_visita": "2026-10-26", "nombre": "2026-10-26_10-00-00.jpg", **params}
    return cliente.put("/fotos", params=q, content=datos,
                       headers={**H, "X-Sha256": sha or hashlib.sha256(datos).hexdigest()})


def test_endpoint_sube_y_es_idempotente(cliente, google, tmp_path):
    datos = b"\xff\xd8 foto de prueba \xff\xd9"
    r = _put(cliente, datos)
    assert r.json()["estado"] == "ok"
    fid = r.json()["id_drive"]
    assert google.archivos[fid]["contenido"] == datos
    assert _put(cliente, datos).json() == {"estado": "ok", "id_drive": fid}
    # No quedan copias temporales en el PC.
    assert list((tmp_path / "fotos_entrantes").iterdir()) == []


def test_endpoint_rechaza_foto_incompleta(cliente, google):
    r = _put(cliente, b"mitad", sha=hashlib.sha256(b"entera").hexdigest())
    assert r.status_code == 400
    assert not any(f.get("mimeType") == "image/jpeg" for f in google.archivos.values())


def test_endpoint_valida_nombre(cliente):
    assert _put(cliente, b"x", nombre="../../etc/passwd").status_code == 400
    assert _put(cliente, b"x", fecha_visita="26/10/2026").status_code == 400


def test_endpoint_sin_drive_configurado(tmp_path):
    cfg = Config(notion_token="x", app_token=TOKEN, db_encargos="E", db_visitas="V",
                 db_contactos="C", dir_datos=tmp_path)
    c = TestClient(crear_app(Servicio(cfg, FakeNotion({}, [], [], {})), TOKEN))
    assert _put(c, b"x").status_code == 503


def test_endpoint_pide_token(cliente):
    r = cliente.put("/fotos", params={"id_encargo": "MA1", "fecha_visita": "2026-10-26", "nombre": "a.jpg"},
                    content=b"x", headers={"X-Sha256": "0" * 64})
    assert r.status_code == 401
