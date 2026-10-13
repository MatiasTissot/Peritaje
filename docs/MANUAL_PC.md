# Manual: configurar el PC para la app de peritaciones

Este manual deja el PC viejo funcionando como "asistente" de la app: es quien habla con Notion, guarda el token de Notion y recibe los avances desde el móvil. Al acabar tendrás la app del móvil conectada al PC y probada.

No hace falta saber programar. Solo hay que copiar y pegar órdenes en el **Terminal**. Si algo falla, mira la sección [Si algo falla](#si-algo-falla) al final.

**Tiempo aproximado:** 30-45 minutos.

---

## Antes de empezar

Necesitas:

- El **PC con Xubuntu** encendido y con Internet.
- Tu **móvil Android** con la app instalada ([descargar peritaje.apk](https://github.com/MatiasTissot/Peritaje/releases/download/ultima/peritaje.apk), con tu sesión de GitHub iniciada).
- Tu **cuenta de Notion** (la del workspace *Gestión de Encargos - Peritaciones*).
- Tu **cuenta de GitHub**.
- Una cuenta para **Tailscale**. Puedes entrar con tu cuenta de Google; es gratis para uso personal.

### Cómo usar el Terminal

- Ábrelo con **Ctrl + Alt + T** (o menú → *Emulador de terminal*).
- Para pegar una orden en el Terminal: **Ctrl + Shift + V** (no Ctrl + V).
- Pulsa **Enter** después de cada orden y espera a que vuelva a aparecer el cursor.
- Cuando una orden empieza por `sudo`, te pedirá **tu contraseña** del PC. Al escribirla no se ve nada en pantalla: es normal. Escríbela y pulsa Enter.

---

## Paso 1. Poner el sistema al día

Pega esto en el Terminal:

```
sudo apt update && sudo apt upgrade -y && sudo apt install -y curl unzip
```

Puede tardar varios minutos. Si al final pide reiniciar, reinicia y vuelve a abrir el Terminal.

---

## Paso 2. Que el PC no se duerma

El PC tiene que estar siempre encendido, porque la app le habla a cualquier hora.

1. Menú → *Configuración* → **Gestor de energía**.
2. Pestaña *Sistema*: en "Cuando esté inactivo" pon **Nunca** (en *Suspender* y en *Hibernar*).
3. Si es un portátil, en "Al cerrar la tapa" elige **Bloquear pantalla** o **No hacer nada** (no "Suspender").
4. Pestaña *Pantalla*: puedes dejar que la pantalla se apague, eso no molesta.

---

## Paso 3. Crear la integración de Notion

Es la "llave" con la que el PC entra en tu Notion. Se hace desde cualquier ordenador o desde el móvil.

1. Entra en <https://www.notion.so/my-integrations> con tu cuenta.
2. Pulsa **Nueva integración** (*New integration*).
3. Nombre: `Peritaje PC`. Workspace: el de *Gestión de Encargos - Peritaciones*. Tipo: **Interna**.
4. Guarda y copia el **token secreto** (empieza por `ntn_` o `secret_`). Guárdalo a mano un momento; lo usarás en el paso 6.
5. Ahora dale acceso a tus encargos: en Notion abre la página **Gestión de Encargos - Peritaciones** → botón `···` (arriba a la derecha) → **Conexiones** → busca `Peritaje PC` → **Confirmar**.

> Esto le da acceso a Encargos, Visitas y Contactos, que están dentro de esa página. Sin este paso, el PC no verá ninguna cita.

---

## Paso 4. Bajar el programa al PC

1. En el PC abre el navegador y entra en <https://github.com/MatiasTissot/Peritaje> (inicia sesión en GitHub si te lo pide).
2. Botón verde **Code** → **Download ZIP**.
3. En el Terminal, descomprímelo en tu carpeta personal:

```
cd ~ && unzip -o ~/Descargas/Peritaje-main.zip
```

> Si tu carpeta de descargas se llama `Downloads` en vez de `Descargas`, cambia esa palabra en la orden.

---

## Paso 5. Instalar el servidor

```
cd ~/Peritaje-main/server && sudo bash deploy/install.sh
```

El instalador hace todo solo: instala lo necesario, crea el servicio `peritaje` que arranca solo al encender el PC, y genera un **token para la app**.

Al final verás algo así:

```
>> Edita /etc/peritaje/peritaje.env y pon tu NOTION_TOKEN, luego: sudo systemctl restart peritaje
>> Token de la app (cópialo en Ajustes de la app):
3f9a1c...(64 letras y números)
```

**Apunta ese token de la app**; lo necesitas en el paso 9. Si lo pierdes, se puede volver a ver con:

```
sudo grep APP_TOKEN /etc/peritaje/peritaje.env
```

---

## Paso 6. Poner el token de Notion

1. Abre el archivo de configuración:

```
sudo nano /etc/peritaje/peritaje.env
```

2. Busca la línea `NOTION_TOKEN=` y pega tu token de Notion justo detrás del `=`, sin espacios. Debe quedar así: `NOTION_TOKEN=ntn_xxxxxxxx`.
3. Guarda: **Ctrl + O** y luego **Enter**. Sal: **Ctrl + X**.
4. Reinicia el servicio para que lo lea:

```
sudo systemctl restart peritaje
```

---

## Paso 7. Comprobar que el PC habla con Notion

```
TOKEN=$(sudo grep '^APP_TOKEN=' /etc/peritaje/peritaje.env | cut -d= -f2) && curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8000/citas-index
```

- **Bien:** sale algo como `{"citas":[{"id_encargo":"MA26043441","fecha_hora":"2026-10-14T10:00:00"}]}`. Son tus próximas visitas (de ayer a dentro de 14 días). Si no tienes visitas en esas fechas sale `{"citas":[]}`, y también está bien.
- **Mal:** si sale un error, ve a [Si algo falla](#si-algo-falla).

---

## Paso 8. Tailscale: unir el PC y el móvil

Tailscale crea una red privada entre tu PC y tu móvil, cifrada, que funciona igual con 4G que con wifi y sin abrir nada a Internet.

**En el PC:**

```
curl -fsSL https://tailscale.com/install.sh | sh
```

```
sudo tailscale up
```

La segunda orden muestra un enlace (`https://login.tailscale.com/...`). Ábrelo (Ctrl + clic) e inicia sesión. Después mira la dirección del PC:

```
tailscale ip -4
```

Saldrá algo como `100.101.102.103`. **Apúntala.**

**En el móvil:**

1. Instala **Tailscale** desde Play Store.
2. Ábrela e inicia sesión **con la misma cuenta** que en el PC.
3. Activa el interruptor de conexión. Acepta el aviso de "VPN" de Android; es normal.
4. En la lista de dispositivos de la app de Tailscale debe aparecer tu PC.

**Recomendado: que el PC no caduque.** Tailscale pide volver a iniciar sesión en cada equipo cada pocos meses. Para que el PC no se quede desconectado sin avisar: entra en <https://login.tailscale.com/admin/machines>, en la fila del PC pulsa `···` → **Disable key expiry**.

---

## Paso 9. Conectar la app

En el móvil, con Tailscale activado:

1. Abre la app **Peritaje** → icono del **engranaje** (arriba a la derecha).
2. **Dirección del PC:** `http://` + la IP del paso 8 + `:8000`. Ejemplo: `http://100.101.102.103:8000`.
3. **Token:** el token de la app del paso 5. Lo más cómodo es enviártelo por un medio privado (por ejemplo, a ti mismo por correo) y copiarlo y pegarlo. No lo compartas con nadie.
4. Pulsa **Probar conexión**. Debe decir **"Conexión correcta con el PC"**.
5. Pulsa **Guardar**. Vuelve atrás y pulsa **Sincronizar**: aparecerán tus visitas.

El token se guarda **cifrado** en el móvil.

---

## Paso 10. Prueba completa

1. Abre una visita → pestaña **Checklist** → escribe algo de prueba en *Observaciones*.
2. Pulsa **Finalizar visita** → **Finalizar y enviar**.
3. En unos segundos el icono de la visita pasa a verde (✓ Enviada) y desaparece de "Activas" (la verás en el filtro "Enviadas").
4. En Notion, abre ese encargo: el texto aparece bajo **AVANCE APP**. Si la página no tenía ese bloque, el PC crea un desplegable "AVANCE APP" al final de la página.

> Para la prueba usa un encargo que no te importe, o borra después el texto de prueba en Notion.

Listo. A partir de aquí el PC funciona solo: arranca al encenderlo, y la app sincroniza cada hora (o al pulsar Sincronizar). Las visitas finalizadas sin cobertura se envían solas cuando vuelve la red.

---

## Si algo falla

### Órdenes útiles

| Para… | Orden |
|---|---|
| Ver si el servicio está en marcha | `systemctl status peritaje` (debe decir *active (running)*; sal con `q`) |
| Ver los mensajes del servicio en directo | `journalctl -u peritaje -f` (sal con Ctrl + C) |
| Reiniciar el servicio | `sudo systemctl restart peritaje` |
| Ver el token de la app | `sudo grep APP_TOKEN /etc/peritaje/peritaje.env` |
| Ver la IP de Tailscale | `tailscale ip -4` |
| Ver si Tailscale está conectado | `tailscale status` |

### Problemas típicos

**En el paso 7 sale `Notion 401` o "API token is invalid".**
El token de Notion está mal copiado. Repite el paso 6 y fíjate en que no haya espacios ni falte ningún carácter.

**En el paso 7 sale `Notion 404` o "Could not find database".**
La integración no tiene acceso a la página. Repite el punto 5 del paso 3 (Conexiones → `Peritaje PC`).

**En el paso 7 sale `Failed to connect` o nada.**
El servicio no está en marcha. Mira `systemctl status peritaje`. Si dice *failed*, mira `journalctl -u peritaje -n 50` y pégame lo que sale. Lo más habitual es que falte el `NOTION_TOKEN` (paso 6).

**La app dice "No se puede conectar … ¿Está Tailscale activo?".**
- Comprueba que Tailscale está **activado en el móvil** (el interruptor de la app de Tailscale).
- Comprueba que el PC está encendido y que `tailscale status` en el PC no dice *Logged out*. Si lo dice, repite `sudo tailscale up`.
- Revisa la dirección en Ajustes: tiene que empezar por `http://` (no `https://`) y acabar en `:8000`.

**La app dice "El PC rechaza el token".**
El token de la app no coincide. Vuelve a verlo con `sudo grep APP_TOKEN /etc/peritaje/peritaje.env` y pégalo de nuevo en Ajustes.

**La conexión es correcta, pero no aparece ninguna visita.**
El PC solo manda visitas que cumplen todo esto:
- Están en la base **Visitas** con **Fecha_Visita** entre ayer y dentro de 14 días.
- Tienen el **Encargo** relacionado.
- El encargo no está en estado ANULADO ni PNDT ANULADO.

Revisa esos campos en Notion y pulsa Sincronizar otra vez.

**La nube de arriba sale en rojo, "Error de sincronización".**
Púlsala para ver el error concreto. Si es de Notion (401/404), mira los problemas anteriores.

**Una visita finalizada se queda en ámbar ("Finalizada · enviando").**
Está esperando red o el PC no responde. Se envía sola en cuanto pueda, aunque cierres la app. Si sale en rojo con un mensaje, ese mensaje dice el motivo.

**He reiniciado el PC y ya no conecta.**
El servicio y Tailscale arrancan solos. Si no conecta, mira `systemctl status peritaje` y `tailscale status`. Si el PC pide iniciar sesión en Tailscale, haz el apartado "que el PC no caduque" del paso 8.

---

## Actualizar el programa del PC

Cuando haya una versión nueva del servidor:

1. Repite el **paso 4** (bajar el ZIP y descomprimirlo; sobrescribe la carpeta).
2. Ejecuta otra vez el **paso 5**.

Tu configuración (tokens) se conserva: el instalador no toca `/etc/peritaje/peritaje.env` si ya existe.

## Seguridad, en corto

- El **token de Notion** solo está en el PC, en un archivo que solo puede leer el administrador y el servicio.
- El **token de la app** está en el PC y, cifrado, en tu móvil. Si pierdes el móvil, cambia el token: borra la línea `APP_TOKEN=...` del archivo, pon `APP_TOKEN=` seguido de un token nuevo (lo genera `openssl rand -hex 32`), reinicia el servicio y ponlo en la app del móvil nuevo.
- El PC no queda abierto a Internet: solo se entra a través de Tailscale.
