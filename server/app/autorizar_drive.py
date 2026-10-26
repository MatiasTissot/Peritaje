"""Asistente para dar permiso al PC sobre tu Google Drive (una sola vez).

Uso, en el PC (en el escritorio, por ejemplo por Escritorio remoto, porque se abre el navegador):
    cd /opt/peritaje/server && sudo .venv/bin/python -m app.autorizar_drive

Pide el "ID de cliente" y el "Secreto de cliente" creados en Google Cloud (tipo
"Aplicación de escritorio"), abre la página de permiso de Google y guarda el resultado
en /etc/peritaje/peritaje.env. Después reinicia el servicio.
"""
from __future__ import annotations

import base64
import hashlib
import http.server
import os
import re
import secrets
import subprocess
import sys
import threading
import urllib.parse
import webbrowser
from pathlib import Path

import httpx

from .drive import SCOPE, TOKEN_URL

ENV = Path(os.environ.get("PERITAJE_ENV", "/etc/peritaje/peritaje.env"))
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"


def _leer_env() -> dict:
    valores = {}
    if ENV.exists():
        for linea in ENV.read_text().splitlines():
            if "=" in linea and not linea.lstrip().startswith("#"):
                k, v = linea.split("=", 1)
                valores[k.strip()] = v.strip()
    return valores


def _guardar_env(nuevos: dict):
    """Cambia o añade las líneas indicadas sin tocar el resto ni los permisos del archivo."""
    lineas = ENV.read_text().splitlines() if ENV.exists() else []
    pendientes = dict(nuevos)
    for i, linea in enumerate(lineas):
        clave = linea.split("=", 1)[0].strip()
        if "=" in linea and clave in pendientes:
            lineas[i] = f"{clave}={pendientes.pop(clave)}"
    lineas += [f"{k}={v}" for k, v in pendientes.items()]
    texto = "\n".join(lineas) + "\n"
    if ENV.exists():
        # Reescribir en el mismo archivo conserva propietario y permisos (root:peritaje 640).
        with ENV.open("r+") as f:
            f.seek(0)
            f.write(texto)
            f.truncate()
    else:
        ENV.write_text(texto)
        ENV.chmod(0o600)


def _pedir(texto: str, actual: str) -> str:
    if actual:
        r = input(f"{texto} [Intro = dejar el que ya hay]: ").strip()
        return r or actual
    while True:
        r = input(f"{texto}: ").strip()
        if r:
            return r


class _Receptor(http.server.BaseHTTPRequestHandler):
    resultado: dict = {}
    listo = threading.Event()

    def do_GET(self):
        consulta = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if "code" not in consulta and "error" not in consulta:
            self.send_response(404)
            self.end_headers()
            return
        _Receptor.resultado = {k: v[0] for k, v in consulta.items()}
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        ok = "code" in consulta
        self.wfile.write((
            "<h2>Listo. Ya puedes cerrar esta pestaña y volver al Terminal.</h2>" if ok
            else "<h2>No se dio el permiso. Vuelve al Terminal.</h2>").encode())
        _Receptor.listo.set()

    def log_message(self, *args):
        pass


def main():
    if ENV == Path("/etc/peritaje/peritaje.env") and os.geteuid() != 0:
        sys.exit("Ejecuta con sudo:  cd /opt/peritaje/server && sudo .venv/bin/python -m app.autorizar_drive")
    env = _leer_env()
    print("\n== Permiso del PC sobre Google Drive ==\n")
    client_id = _pedir("Pega el ID de cliente (termina en .apps.googleusercontent.com)",
                       env.get("GOOGLE_CLIENT_ID", ""))
    client_secret = _pedir("Pega el Secreto de cliente", env.get("GOOGLE_CLIENT_SECRET", ""))
    if not re.search(r"\.apps\.googleusercontent\.com$", client_id):
        print("!! Ese ID de cliente no tiene la forma habitual; lo intento igualmente.")

    servidor = http.server.HTTPServer(("127.0.0.1", 0), _Receptor)
    redirect = f"http://127.0.0.1:{servidor.server_port}/"
    verificador = secrets.token_urlsafe(48)
    reto = base64.urlsafe_b64encode(hashlib.sha256(verificador.encode()).digest()).rstrip(b"=").decode()
    estado = secrets.token_urlsafe(16)
    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
        "scope": SCOPE, "access_type": "offline", "prompt": "consent",
        "code_challenge": reto, "code_challenge_method": "S256", "state": estado,
    })
    threading.Thread(target=servidor.serve_forever, daemon=True).start()

    print("\nAbre esta dirección en el navegador de ESTE PC (si no se abre sola):\n")
    print(url + "\n")
    # Con sudo, el navegador se abre como el usuario que inició sesión, no como root.
    usuario = os.environ.get("SUDO_USER")
    try:
        if usuario:
            subprocess.Popen(["sudo", "-u", usuario, "xdg-open", url],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            webbrowser.open(url)
    except OSError:
        pass
    print("Entra con la cuenta de Google de tu Drive y acepta el permiso.")
    print("(Si sale \"Google no ha verificado esta aplicación\": Configuración avanzada > Ir a ... )\n")
    print("Esperando la respuesta de Google...")
    if not _Receptor.listo.wait(timeout=600):
        sys.exit("!! Pasaron 10 minutos sin respuesta. Vuelve a ejecutar el asistente.")
    servidor.shutdown()

    res = _Receptor.resultado
    if res.get("state") != estado or "code" not in res:
        sys.exit(f"!! Google no dio el permiso ({res.get('error', 'respuesta no válida')}). Vuelve a intentarlo.")
    r = httpx.post(TOKEN_URL, data={
        "client_id": client_id, "client_secret": client_secret, "code": res["code"],
        "code_verifier": verificador, "redirect_uri": redirect, "grant_type": "authorization_code",
    }, timeout=30)
    if r.status_code != 200:
        sys.exit(f"!! Google rechazó el intercambio ({r.status_code}): {r.text[:300]}")
    refresh = r.json().get("refresh_token")
    if not refresh:
        sys.exit("!! Google no devolvió el permiso permanente. Quita el acceso en "
                 "https://myaccount.google.com/permissions y vuelve a ejecutar el asistente.")

    # Comprobación: que se vea la carpeta raíz de los expedientes.
    from .drive import Drive, DriveError
    nombre_raiz = env.get("DRIVE_NOMBRE_RAIZ", "") or "Expedientes_2026"
    try:
        Drive(client_id, client_secret, refresh, env.get("DRIVE_CARPETA_RAIZ", ""), nombre_raiz).raiz()
        print(f"\n>> Veo tu carpeta {nombre_raiz} en Drive.")
    except DriveError as e:
        print(f"\n!! Permiso guardado, pero: {e}")

    _guardar_env({"GOOGLE_CLIENT_ID": client_id, "GOOGLE_CLIENT_SECRET": client_secret,
                  "GOOGLE_REFRESH_TOKEN": refresh})
    print(f">> Guardado en {ENV}")
    print(">> Ahora reinicia el servicio:  sudo systemctl restart peritaje\n")


if __name__ == "__main__":
    main()
