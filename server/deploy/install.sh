#!/usr/bin/env bash
# Instala el servidor en el PC (Xubuntu). Ejecutar desde la carpeta server/:
#   sudo bash deploy/install.sh
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Ejecuta con sudo"; exit 1; }
ORIGEN="$(cd "$(dirname "$0")/.." && pwd)"

# Paquetes necesarios (si ya están, no hace nada).
apt-get update -qq && apt-get install -y -qq python3-venv openssl >/dev/null

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
  echo ">> Edita /etc/peritaje/peritaje.env y pon tu NOTION_TOKEN, luego: sudo systemctl restart peritaje"
fi
chown root:peritaje /etc/peritaje/peritaje.env
chmod 640 /etc/peritaje/peritaje.env

cp "$ORIGEN/deploy/peritaje.service" /etc/systemd/system/peritaje.service
systemctl daemon-reload
systemctl enable --now peritaje
systemctl --no-pager status peritaje | head -5

if command -v ufw >/dev/null; then
  ufw allow in on tailscale0 to any port 8000 proto tcp >/dev/null
  echo ">> Puerto 8000 abierto solo en la interfaz de Tailscale"
fi
echo ">> Token de la app (cópialo en Ajustes de la app):"
grep '^APP_TOKEN=' /etc/peritaje/peritaje.env | cut -d= -f2
