"""Pruebas con un Notion falso en memoria (no toca el Notion real)."""
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Config
from app.main import crear_app
from app.servicio import Servicio

TOKEN = "secreto-de-prueba"


def rt(s):
    return [{"plain_text": s, "text": {"content": s}}]


def encargo(pid, exp, estado="PNDT. VISITA"):
    return {"id": pid, "properties": {
        "ID_Encargo": {"type": "title", "title": rt(f"0099 - E26X - {exp} - EUROP")},
        "Exp_Gabinete": {"type": "rich_text", "rich_text": rt(exp)},
        "Exp_Compania": {"type": "rich_text", "rich_text": rt("E26L0000001")},
        "Direccion Calle": {"type": "rich_text", "rich_text": rt("Calle Falsa 123, Valencia")},
        "Piso, Puerta": {"type": "rich_text", "rich_text": rt("1ºC")},
        "Descripcion": {"type": "rich_text", "rich_text": rt("Filtración en comunidad")},
        "Tipo_Siniestro": {"type": "select", "select": {"name": "Poliza seguro"}},
        "Estado": {"type": "status", "status": {"name": estado}},
        "Ubicacion_Maps": {"type": "url", "url": "https://maps.example/x"},
    }}


def visita(vid, encargo_id, fecha):
    return {"id": vid, "properties": {
        "Fecha_Visita": {"type": "date", "date": {"start": fecha}},
        "Encargo": {"type": "relation", "relation": [{"id": encargo_id}]},
        "Notas_Campo": {"type": "rich_text", "rich_text": rt("Avisar antes de subir")},
    }}


def contacto(encargo_id, rol, nombre, tel):
    return {"id": f"c-{nombre}", "properties": {
        "Contacto": {"type": "title", "title": rt(f"{rol} - {nombre}")},
        "Nombre": {"type": "rich_text", "rich_text": rt(nombre)},
        "Rol": {"type": "select", "select": {"name": rol}},
        "Telefono": {"type": "rich_text", "rich_text": rt(tel)},
        "Encargo": {"type": "relation", "relation": [{"id": encargo_id}]},
    }}


class FakeNotion:
    def __init__(self, paginas, visitas, contactos, bloques):
        self.paginas, self.visitas, self.contactos, self.bloques = paginas, visitas, contactos, bloques
        self.anadidos = []

    def consultar(self, db, filtro=None, orden=None):
        if db == "V":
            if filtro and "relation" in str(filtro):
                pid = filtro["relation"]["contains"]
                return [v for v in self.visitas if v["properties"]["Encargo"]["relation"][0]["id"] == pid]
            return self.visitas
        if db == "C":
            pid = filtro["relation"]["contains"]
            return [c for c in self.contactos if c["properties"]["Encargo"]["relation"][0]["id"] == pid]
        if db == "E":
            val = list(filtro.values())[1]
            val = list(val.values())[0]
            return [p for p in self.paginas.values()
                    if val in p["properties"]["Exp_Gabinete"]["rich_text"][0]["plain_text"]]
        return []

    def pagina(self, pid):
        return self.paginas[pid]

    def hijos(self, bid):
        return self.bloques.get(bid, [])

    def anadir_hijos(self, bid, bloques, after=None):
        self.anadidos.append((bid, bloques, after))
        return {"results": []}


@pytest.fixture
def entorno(tmp_path: Path):
    manana = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d")
    paginas = {"p1": encargo("p1", "MA26043441"), "p2": encargo("p2", "MA26000002", estado="ANULADO")}
    visitas = [visita("v1", "p1", f"{manana}T08:00:00.000Z"), visita("v2", "p2", f"{manana}T09:00:00.000Z")]
    contactos = [contacto("p1", "Comunidad", "Presidente", "611111111"),
                 contacto("p1", "Asegurado", "Juan Pérez", "600123456")]
    bloques = {"p1": [
        {"id": "b-col", "type": "column_list", "has_children": True, "column_list": {}},
        {"id": "b-av", "type": "heading_2", "has_children": False,
         "heading_2": {"rich_text": rt("AVANCE APP"), "is_toggleable": True}},
    ]}
    fake = FakeNotion(paginas, visitas, contactos, bloques)
    cfg = Config(notion_token="x", app_token=TOKEN, db_encargos="E", db_visitas="V",
                 db_contactos="C", dir_datos=tmp_path)
    cliente = TestClient(crear_app(Servicio(cfg, fake), TOKEN))
    return cliente, fake, manana


H = {"Authorization": f"Bearer {TOKEN}"}


def test_sin_token_401(entorno):
    cliente, _, _ = entorno
    assert cliente.get("/citas-index").status_code == 401
    assert cliente.get("/citas-index", headers={"Authorization": "Bearer otro"}).status_code == 401


def test_indice_excluye_anulados_y_pasa_a_hora_local(entorno):
    cliente, _, manana = entorno
    r = cliente.get("/citas-index", headers=H)
    assert r.status_code == 200
    # 08:00 UTC = 10:00 en Madrid en horario de verano / 09:00 en invierno
    citas = r.json()["citas"]
    assert [c["id_encargo"] for c in citas] == ["MA26043441"]
    assert citas[0]["fecha_hora"].startswith(manana + "T")
    assert citas[0]["fecha_hora"][11:13] in ("09", "10")


def test_detalle(entorno):
    cliente, _, _ = entorno
    cliente.get("/citas-index", headers=H)
    d = cliente.get("/citas/MA26043441", headers=H).json()
    assert d["asegurado"] == "Juan Pérez"
    assert d["telefono_contacto"] == "600123456"
    assert d["direccion"] == "Calle Falsa 123, Valencia, 1ºC"
    assert d["notas_gabinete"] == "Filtración en comunidad"
    assert len(d["contactos"]) == 2


def test_detalle_sin_indice_busca_por_expediente(entorno):
    cliente, _, _ = entorno
    assert cliente.get("/citas/MA26043441", headers=H).status_code == 200
    assert cliente.get("/citas/NOEXISTE", headers=H).status_code == 404


def test_avance_va_al_bloque_y_no_se_duplica(entorno):
    cliente, fake, _ = entorno
    cuerpo = {"id_encargo": "MA26043441", "fecha_visita_realizada": "2026-09-28T10:15:00",
              "texto_avance": "Causa: rotura\nObservaciones: humedad en techo", "id_envio": "abc"}
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    assert len(fake.anadidos) == 1
    destino, bloques, after = fake.anadidos[0]
    assert destino == "b-av" and after is None
    textos = [b["paragraph"]["rich_text"][0]["text"]["content"] for b in bloques if b["type"] == "paragraph"]
    assert "Causa: rotura" in textos and "28/09/2026 10:15" in textos[0]


def test_avance_crea_desplegable_si_no_existe(entorno):
    cliente, fake, _ = entorno
    fake.bloques["p1"] = []
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json() == {"estado": "ok"}
    destino, bloques, _ = fake.anadidos[0]
    assert destino == "p1" and bloques[0]["heading_2"]["is_toggleable"]


def test_avance_encargo_inexistente(entorno):
    cliente, _, _ = entorno
    r = cliente.post("/avance", headers=H, json={"id_encargo": "NOEXISTE",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json()["estado"] == "error" and "NOEXISTE" in r.json()["detalle"]
