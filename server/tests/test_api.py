"""Pruebas con un Notion falso en memoria (no toca el Notion real)."""
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Config
from app.main import crear_app
from app.notion import NotionError
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
            return [p for p in self.paginas.values() if "Exp_Gabinete" in p["properties"]
                    and val in p["properties"]["Exp_Gabinete"]["rich_text"][0]["plain_text"]]
        return []

    def pagina(self, pid):
        if pid not in self.paginas:
            raise NotionError("Notion 404: object_not_found", 404)
        return self.paginas[pid]

    def hijos(self, bid):
        return self.bloques.get(bid, [])

    def crear_pagina(self, db, props):
        assert db == "C"
        pagina = {"id": f"c-nuevo-{len(self.contactos)}", "properties": {
            k: ({"type": "relation", "relation": v["relation"]} if "relation" in v
                else {"type": "select", "select": v["select"]} if "select" in v
                else {"type": "title", "title": rt(v["title"][0]["text"]["content"])} if "title" in v
                else {"type": "rich_text", "rich_text": rt(v["rich_text"][0]["text"]["content"])})
            for k, v in props.items()}}
        self.contactos.append(pagina)
        return pagina

    def anadir_hijos(self, bid, bloques, after=None):
        self.anadidos.append((bid, bloques, after))
        self._guardar(bid, bloques)
        return {"results": []}

    def _guardar(self, bid, bloques):
        # Como Notion: cada bloque recibe id y sus hijos (dentro del tipo) quedan colgando de él.
        for b in bloques:
            datos = b.get(b["type"], {})
            assert "children" not in b, "los hijos van dentro del tipo de bloque"
            b.setdefault("id", f"n{sum(len(v) for v in self.bloques.values())}")
            for t in datos.get("rich_text", []):
                t.setdefault("plain_text", t["text"]["content"])
            hijos = datos.get("children") or []
            b["has_children"] = bool(hijos)
            self.bloques.setdefault(bid, []).append(b)
            self._guardar(b["id"], hijos)


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


def test_avance_crea_pestana_si_la_pagina_no_tiene(entorno):
    cliente, fake, _ = entorno
    fake.bloques["p1"] = []
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json() == {"estado": "ok"}
    (pagina, creado, _), (padre, bloques, _) = fake.anadidos
    assert pagina == "p1" and creado[0]["type"] == "tab"
    pestana = creado[0]["tab"]["children"][0]
    assert pestana["paragraph"]["rich_text"][0]["text"]["content"] == "AVANCE APP"
    assert padre == pestana["id"] and "x" in [b["paragraph"]["rich_text"][0]["text"]["content"]
                                              for b in bloques if b["type"] == "paragraph"]


def pestanas(*nombres, con_avance_antiguo=False):
    bloques = {"p1": [{"id": "b-tabs", "type": "tab", "has_children": True, "tab": {}}],
               "b-tabs": [{"id": f"t-{n}", "type": "paragraph", "has_children": True,
                           "paragraph": {"rich_text": rt(n)}} for n in nombres]}
    if con_avance_antiguo:
        bloques["p1"].append({"id": "b-av", "type": "heading_2", "has_children": True,
                              "heading_2": {"rich_text": rt("AVANCE APP"), "is_toggleable": True}})
    return bloques


def test_avance_va_dentro_de_la_pestana_avance_app(entorno, tmp_path):
    cliente, fake, _ = entorno
    # Gana a un desplegable antiguo "AVANCE APP" de la misma página; el título puede llevar espacios.
    fake.bloques.update(pestanas("AVANCE", "INFORME", "AVANCE APP ", con_avance_antiguo=True))
    cuerpo = {"id_encargo": "MA26043441", "fecha_visita_realizada": "2026-09-28T10:15:00",
              "texto_avance": "x", "id_envio": "e1"}
    assert cliente.post("/avance", headers=H, json=cuerpo).json() == {"estado": "ok"}
    assert [(a[0], a[2]) for a in fake.anadidos] == [("t-AVANCE APP ", None)]
    # Perdido el registro local, la referencia dentro de la pestaña evita duplicarlo.
    (tmp_path / "envios.json").unlink()
    assert cliente.post("/avance", headers=H, json=cuerpo).json() == {"estado": "ok"}
    assert len(fake.anadidos) == 1


def test_avance_anade_pestana_si_falta(entorno):
    cliente, fake, _ = entorno
    fake.bloques.update(pestanas("AVANCE", "INFORME", con_avance_antiguo=True))
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json() == {"estado": "ok"}
    (contenedor, creado, _), (padre, _, _) = fake.anadidos
    # Se añade como una pestaña más del bloque existente, no como bloque suelto.
    assert contenedor == "b-tabs" and creado[0]["type"] == "paragraph"
    assert padre == creado[0]["id"]
    assert [p["paragraph"]["rich_text"][0]["plain_text"] for p in fake.bloques["b-tabs"]] == \
        ["AVANCE", "INFORME", "AVANCE APP"]


def test_avance_encargo_inexistente(entorno):
    cliente, _, _ = entorno
    r = cliente.post("/avance", headers=H, json={"id_encargo": "NOEXISTE",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json()["estado"] == "error" and "NOEXISTE" in r.json()["detalle"]


def test_avance_no_se_duplica_si_se_pierde_el_registro(entorno, tmp_path):
    cliente, fake, _ = entorno
    cuerpo = {"id_encargo": "MA26043441", "fecha_visita_realizada": "2026-09-28T10:15:00",
              "texto_avance": "x", "id_envio": "env-1"}
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    (tmp_path / "envios.json").unlink()
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    assert len(fake.anadidos) == 1


def test_avance_no_escribe_si_el_expediente_esta_repetido(entorno):
    cliente, fake, _ = entorno
    fake.paginas["p3"] = encargo("p3", "MA26043441")
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json()["estado"] == "error" and not fake.anadidos


def test_avance_no_usa_coincidencia_parcial(entorno):
    cliente, fake, _ = entorno
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA2604344",
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json()["estado"] == "error" and not fake.anadidos


def test_sin_conexion_con_notion_da_error_claro():
    import httpx
    from app.notion import Notion

    def caido(request):
        raise httpx.ConnectError("sin red")

    n = Notion("x")
    n._http = httpx.Client(base_url="https://api.notion.com/v1", transport=httpx.MockTransport(caido))
    with pytest.raises(NotionError, match="Sin conexión"):
        n.anadir_hijos("b", [{"type": "divider", "divider": {}}])


def test_escritura_no_se_reintenta_ante_5xx():
    import httpx
    from app.notion import Notion
    llamadas = []

    def falla(request):
        llamadas.append(request.method)
        return httpx.Response(502)

    n = Notion("x")
    n._http = httpx.Client(base_url="https://api.notion.com/v1", transport=httpx.MockTransport(falla))
    with pytest.raises(NotionError):
        n.anadir_hijos("b", [{"type": "divider", "divider": {}}])
    assert llamadas == ["PATCH"]


def test_detalle_de_encargo_borrado_da_404(entorno):
    cliente, fake, _ = entorno
    cliente.get("/citas-index", headers=H)  # deja el id en caché
    assert cliente.get("/citas/MA26043441", headers=H).status_code == 200
    fake.paginas["p1"]["in_trash"] = True
    assert cliente.get("/citas/MA26043441", headers=H).status_code == 404
    # Sale de la caché: si vuelve a la vida en Notion, se encuentra de nuevo.
    fake.paginas["p1"].pop("in_trash")
    assert cliente.get("/citas/MA26043441", headers=H).status_code == 200
    fake.paginas["p1"]["archived"] = True
    assert cliente.get("/citas/MA26043441", headers=H).status_code == 404


def test_detalle_de_encargo_anulado_sigue_respondiendo(entorno):
    cliente, _, _ = entorno
    d = cliente.get("/citas/MA26000002", headers=H)
    assert d.status_code == 200 and d.json()["estado_notion"] == "ANULADO"


VA = "a" * 32
VB = "b" * 32


def dos_visitas(fake, manana):
    va, vb = visita(VA, "p1", f"{manana}T08:00:00.000Z"), visita(VB, "p1", f"{manana}T15:00:00.000Z")
    vb["properties"]["Notas_Campo"]["rich_text"] = rt("Segunda visita: llevar escalera")
    fake.visitas = [va, vb]
    fake.paginas[VA], fake.paginas[VB] = va, vb


def test_indice_una_entrada_por_visita(entorno):
    cliente, fake, manana = entorno
    dos_visitas(fake, manana)
    citas = cliente.get("/citas-index", headers=H).json()["citas"]
    assert [(c["id_encargo"], c["id_visita"]) for c in citas] == [("MA26043441", VA), ("MA26043441", VB)]


def test_detalle_de_una_visita(entorno):
    cliente, fake, manana = entorno
    dos_visitas(fake, manana)
    d = cliente.get(f"/visitas/{VB}", headers=H).json()
    assert d["id_visita"] == VB and d["id_encargo"] == "MA26043441"
    assert d["notas_visita"] == "Segunda visita: llevar escalera"
    assert d["fecha_visita"].startswith(manana) and d["fecha_visita"][11:13] in ("16", "17")
    assert d["asegurado"] == "Juan Pérez" and len(d["contactos"]) == 2


def test_visita_inexistente_o_borrada_da_404(entorno):
    cliente, fake, manana = entorno
    dos_visitas(fake, manana)
    assert cliente.get("/visitas/" + "c" * 32, headers=H).status_code == 404
    assert cliente.get("/visitas/no-es-un-id", headers=H).status_code == 404
    fake.paginas[VA]["in_trash"] = True
    assert cliente.get(f"/visitas/{VA}", headers=H).status_code == 404
    fake.paginas["p1"]["archived"] = True
    assert cliente.get(f"/visitas/{VB}", headers=H).status_code == 404


def test_visita_sin_fecha_devuelve_fecha_nula(entorno):
    cliente, fake, manana = entorno
    dos_visitas(fake, manana)
    fake.paginas[VA]["properties"]["Fecha_Visita"]["date"] = None
    d = cliente.get(f"/visitas/{VA}", headers=H).json()
    assert d["fecha_visita"] is None and d["notas_visita"] == "Avisar antes de subir"


def test_avance_con_visita_lleva_su_fecha(entorno):
    cliente, fake, manana = entorno
    dos_visitas(fake, manana)
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441", "id_visita": VB,
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json() == {"estado": "ok"}
    cabecera = fake.anadidos[0][1][0]["paragraph"]["rich_text"][0]["text"]["content"]
    assert "(realizada 28/09/2026 10:15)" in cabecera
    assert datetime.strptime(manana, "%Y-%m-%d").strftime("%d/%m/%Y") in cabecera


def test_avance_con_visita_borrada_se_escribe_igual(entorno):
    cliente, fake, _ = entorno
    r = cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441", "id_visita": "c" * 32,
                     "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert r.json() == {"estado": "ok"} and len(fake.anadidos) == 1


def test_avance_crea_contactos_nuevos_sin_duplicar(entorno):
    cliente, fake, _ = entorno
    cuerpo = {"id_encargo": "MA26043441", "fecha_visita_realizada": "2026-09-28T10:15:00",
              "texto_avance": "x", "contactos": [
                  {"rol": "Inquilino", "nombre": "Ana Ruiz", "telefono": "699 111 222"},
                  {"rol": "Asegurado", "nombre": "Juan Pérez", "telefono": "600123456"},  # ya existe
                  {"rol": "Causante", "nombre": "  "}]}  # sin nombre: se ignora
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    nuevos = [c for c in fake.contactos if c["id"].startswith("c-nuevo")]
    assert len(nuevos) == 1
    p = nuevos[0]["properties"]
    assert p["Rol"]["select"]["name"] == "Inquilino" and p["Encargo"]["relation"] == [{"id": "p1"}]
    assert p["Contacto"]["title"][0]["plain_text"] == "Inquilino - Ana Ruiz"
    # Un reintento no los vuelve a crear.
    cuerpo["contactos"][0]["telefono"] = "699111222"
    assert cliente.post("/avance", json=cuerpo, headers=H).json() == {"estado": "ok"}
    assert len([c for c in fake.contactos if c["id"].startswith("c-nuevo")]) == 1


def test_avance_encuentra_bloque_avance_apk(entorno):
    cliente, fake, _ = entorno
    fake.bloques["p1"] = [{"id": "b-apk", "type": "toggle", "has_children": False,
                           "toggle": {"rich_text": rt("Avance APK")}}]
    cliente.post("/avance", headers=H, json={"id_encargo": "MA26043441",
                 "fecha_visita_realizada": "2026-09-28T10:15:00", "texto_avance": "x"})
    assert fake.anadidos[0][0] == "b-apk"
