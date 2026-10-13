# App de Peritaciones · v0.1

Primera versión: la app trae las visitas desde Notion (a través del PC), permite rellenar el checklist y las observaciones, hacer fotos que se guardan en el móvil, y al pulsar **Finalizar visita** envía el avance al PC, que lo escribe en Notion. Google Drive e incidencias quedan para el siguiente módulo.

```
android/   App Android (Kotlin, Compose + Material 3, Room, WorkManager, Retrofit)
server/    Servidor del PC (FastAPI + Uvicorn), único que habla con Notion
.github/   Compilación automática del APK en GitHub (opcional)
```

## Cómo funciona

1. El PC consulta la base **Visitas** de Notion (ventana: ayer + 14 días) y manda al móvil el índice de citas. El identificador de cada cita es el **Exp_Gabinete** del encargo (p. ej. `MA26043441`).
2. El móvil compara con lo que tiene y pide el detalle de lo que le falta: dirección, asegurado y teléfono (base **Contactos**, rol Asegurado), tipo, descripción, notas de la visita y resto de contactos.
3. En la visita todo se guarda en el móvil al momento, sin cobertura. Las fotos van a la galería en `Pictures/PeritajeApp/AAAA-MM/` con nombre `<id>_NN.jpg`.
4. **Finalizar visita** bloquea el checklist y deja el avance en cola. WorkManager lo manda en cuanto hay red (aunque la app esté cerrada o el móvil se reinicie) y solo lo marca como ENVIADA cuando el PC responde `ok`. Cada envío lleva un identificador, así que un reintento no duplica el texto en Notion.
5. El PC escribe el avance en el bloque **AVANCE APP** de la página del encargo. Busca un bloque (pestaña, desplegable o encabezado) cuyo texto sea exactamente `AVANCE APP`; si no lo encuentra, crea un encabezado desplegable `AVANCE APP` al final de la página.

Estados en el móvil: **PENDIENTE** → **VISITADA** (finalizada, en cola) → **ENVIADA**. Si una cita cambia de fecha en Notion y ya tiene datos, la app pregunta si eliminarla o conservarla. Las citas enviadas desaparecen de la lista (filtro "Enviadas") y se borran de la base local a los 7 días; las fotos nunca se borran solas.

## 1. Servidor en el PC (Xubuntu)

Requisitos: Python 3.10 o superior, Tailscale instalado y con sesión iniciada en el PC y en el móvil.

1. **Integración de Notion**: en https://www.notion.so/my-integrations crea una integración interna, copia su token y, en Notion, abre la página *Gestión de Encargos - Peritaciones* → `···` → *Conexiones* → añade la integración (así puede leer Encargos, Visitas y Contactos y escribir el avance).
2. Copia la carpeta `server/` al PC y ejecuta:
   ```bash
   cd server
   sudo bash deploy/install.sh
   ```
   El instalador crea el servicio `peritaje`, genera un token para la app y te lo muestra al final.
3. Pon el token de Notion en `/etc/peritaje/peritaje.env` (`NOTION_TOKEN=`) y reinicia: `sudo systemctl restart peritaje`.
4. Comprueba desde el propio PC:
   ```bash
   TOKEN=$(sudo grep APP_TOKEN /etc/peritaje/peritaje.env | cut -d= -f2)
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/citas-index
   ```
5. La IP de Tailscale del PC se ve con `tailscale ip -4` (empieza por 100.).

Registros: `journalctl -u peritaje -f`. Las bases de datos ya están configuradas en `peritaje.env` con las de tu workspace.

## 2. Compilar el APK

**Opción A, Android Studio** (en cualquier ordenador con 8 GB de RAM o más; el PC viejo no llega):
abrir la carpeta `android/`, esperar a que sincronice Gradle y pulsar *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. Por consola: `./gradlew assembleDebug`. El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`.

**Opción B, GitHub**: subir esta carpeta a un repositorio privado. Cada cambio en `android/` compila el APK solo y lo deja para descargar en la pestaña *Actions* (artefacto `peritaje-debug-apk`). También pasa las pruebas del servidor.

## 3. Instalar y configurar el móvil

1. Instalar el APK (permitir "instalar apps de origen desconocido" para el navegador o el gestor de archivos).
2. Abrir la app → engranaje → poner `http://<IP Tailscale del PC>:8000` y el token → **Probar conexión** → **Guardar**.
3. Pulsar **Sincronizar**. A partir de ahí se sincroniza sola cada hora con red.

## Seguridad

- El token de Notion solo vive en el PC (`/etc/peritaje/peritaje.env`, permisos 640, usuario de servicio sin shell).
- El token de la app se guarda cifrado en el móvil con Android Keystore (EncryptedSharedPreferences), nunca en la base de datos.
- El tráfico va por Tailscale (cifrado de extremo a extremo), y además el PC exige el token en cada petición. El instalador abre el puerto 8000 solo en la interfaz de Tailscale si `ufw` está activo.
- Deuda técnica anotada: un único token compartido. Para varios peritos hará falta un token por instalación.

## Pendiente para los siguientes módulos

- **Google Drive**: subir fotos a `Expedientes_2026/<ID_Encargo>/Fotografias`. El enganche ya existe (`SubidorFotos`); la tabla de fotos ya lleva estado de subida.
- **Incidencias**.
- Lógica condicional del checklist y campos obligatorios.
