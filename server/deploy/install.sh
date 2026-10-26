#!/usr/bin/env bash
# Instala o actualiza el servidor en el PC (Xubuntu). Ejecutar desde la carpeta server/:
#   sudo bash deploy/install.sh
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Ejecuta con sudo"; exit 1; }
ORIGEN="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_SERVIDOR=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' "$ORIGEN/app/__init__.py")
echo ">> Instalando el servidor versión $VERSION_SERVIDOR"

# Python 3.8 o mayor (Xubuntu 20.04 en adelante).
if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2>/dev/null; then
  echo "!! Hace falta Python 3.8 o mayor y este PC tiene: $(python3 --version 2>&1 || echo 'ninguno')"
  echo "!! Instala Xubuntu 20.04 o posterior (mejor 22.04 o 24.04) y vuelve a ejecutar el instalador."
  exit 1
fi

# Paquetes necesarios (si ya están, no hace nada).
apt-get update -qq && apt-get install -y -qq python3-venv openssl qrencode >/dev/null

id peritaje >/dev/null 2>&1 || useradd --system --home /opt/peritaje --shell /usr/sbin/nologin peritaje
mkdir -p /opt/peritaje /var/lib/peritaje /etc/peritaje
mkdir -p /opt/peritaje/server && rm -rf /opt/peritaje/server/app
cp -r "$ORIGEN/app" "$ORIGEN/requirements.txt" /opt/peritaje/server/
python3 -m venv /opt/peritaje/server/.venv
/opt/peritaje/server/.venv/bin/pip install -q --upgrade pip
/opt/peritaje/server/.venv/bin/pip install -q -r /opt/peritaje/server/requirements.txt
chown -R peritaje:peritaje /opt/peritaje /var/lib/peritaje

if [ ! -f /etc/peritaje/peritaje.env ]; then
  cp "$ORIGEN/deploy/peritaje.env.example" /etc/peritaje/peritaje.env
  sed -i "s/^APP_TOKEN=.*/APP_TOKEN=$(openssl rand -hex 32)/" /etc/peritaje/peritaje.env
fi
chown root:peritaje /etc/peritaje/peritaje.env
chmod 640 /etc/peritaje/peritaje.env

cp "$ORIGEN/deploy/peritaje.service" /etc/systemd/system/peritaje.service
systemctl daemon-reload
systemctl enable peritaje >/dev/null 2>&1
if grep -q '^NOTION_TOKEN=.\+' /etc/peritaje/peritaje.env; then
  # restart (no "enable --now") para que una actualización cargue el código nuevo.
  systemctl restart peritaje || true
  sleep 3
  if systemctl is-active --quiet peritaje; then
    echo ">> Servicio peritaje en marcha (versión $VERSION_SERVIDOR)"
  else
    echo ">> El servicio no arranca. Mira el motivo con: journalctl -u peritaje -n 50"
  fi
else
  # Sin token de Notion no se arranca: solo fallaría y se reiniciaría en bucle.
  systemctl stop peritaje 2>/dev/null || true
  echo ">> Falta el NOTION_TOKEN. Edita /etc/peritaje/peritaje.env y luego: sudo systemctl restart peritaje"
fi

# Cortafuegos: el puerto 8000 solo por Tailscale. Si el SSH está en uso, se deja
# permitido antes de que nadie active ufw, para no perder el acceso remoto.
if command -v ufw >/dev/null; then
  ufw allow in on tailscale0 to any port 8000 proto tcp >/dev/null
  if systemctl is-active --quiet ssh 2>/dev/null; then
    ufw allow OpenSSH >/dev/null
    echo ">> SSH permitido en el cortafuegos"
  fi
  if ufw status | grep -q "Status: active"; then
    echo ">> Cortafuegos activo: el puerto 8000 solo se abre por Tailscale"
  else
    echo ">> Regla del puerto 8000 preparada, pero el cortafuegos está INACTIVO."
    echo ">> Actívalo con: sudo ufw enable   (mientras tanto el puerto 8000 se ve desde la wifi de casa)"
  fi
else
  echo ">> ufw no está instalado: el puerto 8000 queda abierto en todas las redes."
  echo ">> Instálalo con: sudo apt install -y ufw   y vuelve a ejecutar este instalador."
fi

echo
echo ">> Token de la app (cópialo en Ajustes de la app):"
TOKEN_APP=$(grep '^APP_TOKEN=' /etc/peritaje/peritaje.env | cut -d= -f2)
echo "$TOKEN_APP"
if command -v qrencode >/dev/null; then
  echo ">> El mismo token en QR: escanéalo con la cámara del móvil (o Google Lens) y copia el texto."
  qrencode -t ansiutf8 "$TOKEN_APP"
fi
echo ">> Si lo pierdes, se vuelve a ver con: sudo grep APP_TOKEN /etc/peritaje/peritaje.env"
