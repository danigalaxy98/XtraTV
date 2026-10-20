# Xtra para Android TV (XtraTV)

Fork de [crackededed/Xtra](https://github.com/crackededed/Xtra) con una interfaz propia para Android TV.
Un único APK:
- En el **móvil** la app es la del original (`MainActivity`), sin cambios.
- En la **TV**, el lanzador abre la **interfaz TV** (`ui/tv/TvActivity`, Compose for TV). La interfaz del móvil
  no se muestra nunca en la TV: enlaces, notificaciones y reinicios que llegan a `MainActivity` se redirigen a la
  interfaz TV.

Índice: [1. Qué hace](#1-qué-hace-la-versión-tv) · [2. Separación del original](#2-cómo-está-separada-del-original) ·
[3. Publicación y sincronización](#3-publicación-y-sincronización) · [4. Si la sincronización falla](#4-si-la-sincronización-falla) ·
[5. Estado de la revisión](#5-estado-de-la-revisión)

---

## 1. Qué hace la versión TV

### Navegación
Menú lateral que se despliega al recibir el foco: **Siguiendo · Populares · Categorías · Buscar**, y abajo
**Actualizar**, **Iniciar sesión** (sin sesión) y **Ajustes**.

| Pantalla | Qué hace |
|---|---|
| Siguiendo | Canales seguidos en directo (cuenta o seguimientos locales) |
| Populares | Directos con más espectadores |
| Categorías → categoría | Cuadrícula de juegos y, dentro, sus directos |
| Buscar | Campo de texto (teclado o voz del teclado de la TV), fila de canales y cuadrícula de directos |
| Ficha de canal | Avatar, seguidores, botón Seguir, directo actual y VODs |

- **OK** en un directo: verlo. **Mantener OK**: abrir la ficha del canal. **Atrás**: pantalla anterior.
- **←** desde la primera columna de tarjetas vuelve a la entrada del menú de la sección (`LocalRailFocus`).
- **Atrás** en una sección principal pregunta "¿Quieres salir de Xtra?" (foco en No).
- Al volver de Ajustes sin cambiar nada se mantiene la pantalla en la que estabas; si cambias algo que lo requiere,
  la pantalla se reconstruye conservando la navegación.
- Enlaces `twitch.tv/<canal>` y notificaciones de directos abren la ficha del canal en la interfaz TV.

### Listas y actualización
- **Actualizar** en el menú, y automático: al volver de un directo o a la app, tras el tiempo configurado sin tocar
  el mando (2 min por defecto) y al volver a una sección con datos más antiguos que ese tiempo.
- Mientras recarga: "Actualizando…" con una barra; al terminar, "✓ Actualizado a las HH:mm". Solo cuenta como
  actualización una recarga real (volver a mostrar datos guardados no cambia la hora).
- Al cambiar de cuenta se recargan todas las listas.
- Las tarjetas tienen claves estables: tras una recarga el foco sigue en el mismo canal.
- Miniaturas de directos renovadas cada 5 minutos (como en el móvil).
- Al final de cada lista se ve "Cargando" o un error con **Reintentar**.

### Reproductor (`TvPlayerActivity`)
- Vídeo a pantalla completa y **chat** en panel lateral (ancho configurable, 15–50 %); se activa con el botón Chat y
  la elección se recuerda.
- **OK / ↑ / ↓** muestran la capa (canal, título, categoría, espectadores, tiempo) y los botones **Chat · Calidad ·
  Seguir · Canal**. Se oculta sola (3, 5 o 10 s).
- **VODs**: una pulsación de **← / →** salta el paso configurado (10 s por defecto). **Mantener pulsado** desplaza la
  barra cada vez más rápido (×30, ×60, ×120, ×300, ×600 según el tiempo pulsado; se ve `⏩ ×60`) y el salto real se
  hace al soltar. También con las teclas de avance/retroceso del mando.
- Teclas multimedia: Play, Pausa y Play/Pausa hacen lo que dicen; **Stop** cierra el reproductor.
- **Atrás** oculta la capa y una segunda pulsación sale; **Home** detiene la reproducción.
- Si la red se corta, el directo (y el chat) se recupera solo al volver la conexión.
- **Raids**: con el cambio automático activado, el reproductor sigue al canal del raid.
- Reutiliza el motor del original: `ExoPlayerService` (proxys, anuncios, calidades) y `ChatFragment`.

### Iniciar sesión (`TvLoginActivity`)
Muestra un código; se introduce en twitch.tv/activate desde el móvil u ordenador y la TV inicia sesión **sola** (comprueba
cada 5 s). **Nuevo código** si caduca o falla. Usa las mismas peticiones y guarda los mismos valores que el login por
código del original.

### Ajustes (`TvSettingsActivity`)
- Secciones a la izquierda y opciones a la derecha: **General · Cuenta · Reproductor · Chat · Anuncios y proxys ·
  Actualizaciones · Avanzado** (lista en `TvSettingsModel.sections()`). Se generan a partir de los XML de ajustes del
  móvil y guardan en las mismas preferencias.
- Ajustes propios de TV (`res/xml/tv_preferences.xml`, `TvPrefs.kt`): pantalla de inicio, tarjetas por fila,
  actualización automática (desactivada / 1 / 2 / 5 min), tiempo para ocultar los controles, mostrar el chat al abrir
  un directo, ancho del chat y cuenta (iniciar o cerrar sesión).
- Interruptores con OK, deslizadores con ← / →, textos numéricos con teclado numérico; los tokens se muestran ocultos.
- Los editores complejos (proxys, video swap, token de integridad) y **"Todos los ajustes (pantalla clásica)"** abren
  la pantalla de ajustes del móvil en la sección correcta y con el foco resaltado.

### Actualizaciones de la app (`TvUpdater`)
- Versión instalada, "Buscar actualizaciones ahora", búsqueda automática (sí/no) y frecuencia (1, 3 o 7 días).
- Panel con fecha, tamaño y novedades; descarga con progreso (**Atrás la cancela**) e instalador de Android.
- Solo consulta la API de GitHub (`https://api.github.com/repos/...`, por defecto la release `latest` del fork) y
  solo descarga de hosts de GitHub; comprueba el tamaño del APK.
- El APK se descarga a la caché privada de la app y se borra en cuanto se entrega al instalador.
- El resultado de la instalación llega a un receptor interno no exportado (`TvInstallReceiver`).
- "Ahora no" descarta ese build concreto: solo se ofrecerán builds subidos después.

### Registro de errores (`TvCrashLog`)
Si la app se cierra por un error, al volver a abrirla se muestra el error en pantalla para poder fotografiarlo
(las URLs aparecen sin parámetros, que pueden contener tokens).

### Limitaciones de esta versión
Sin chat en VODs; no se puede escribir en el chat; sin descargas ni clips.

---

## 2. Cómo está separada del original

Objetivo: que las actualizaciones del original se mezclen solas con el mínimo riesgo. Todo lo de TV está en archivos
que el original no tiene; los archivos del original solo se tocan en **5 puntos**.

### Dónde está cada cosa (archivos propios)
| Qué | Dónde |
|---|---|
| Código de la interfaz TV | `app/src/main/java/.../ui/tv/` |
| Punto único de enganche con el código del original | `ui/tv/TvHooks.kt` |
| Inicialización al arrancar (URL de actualizaciones del fork, ajustes clásicos) | `ui/tv/TvInitProvider.kt` |
| Componentes TV del manifiesto y banner | `app/src/tv/AndroidManifest.xml` (se fusiona sobre el principal) |
| Dependencias de Compose / TV | `app/tv.gradle` (las versiones compartidas se leen del catálogo del original) |
| Textos y plurales | `res/values*/tv_strings.xml`, `tv_plurals.xml` |
| Otros recursos | `tv_arrays.xml`, `ids.xml`, `xml/tv_preferences.xml`, `layout/activity_tv_player.xml`, `drawable-xhdpi/tv_banner.png` |
| Publicación | `.github/workflows/tv-release.yml` |
| Sincronización con el original | `.github/workflows/upstream-sync.yml` y `.github/scripts/` |

### Los 5 puntos de contacto con el original
| Archivo | Cambio | Para qué |
|---|---|---|
| `ui/main/MainActivity.kt` | 1 línea tras `super.onCreate`: `TvHooks.openTvInterface(this)` | En TV, llevar todo a la interfaz TV |
| `app/build.gradle.kts` | Plugin `kotlin.compose` y `apply(from = "tv.gradle")` al final | Compilar la interfaz TV |
| `gradle/libs.versions.toml` | 1 línea: plugin `kotlin-compose` | Ídem (versión ligada a la de Kotlin del original) |
| `app/src/main/AndroidManifest.xml` | Quitar `LEANBACK_LAUNCHER` de `MainActivity` | Un solo icono en la TV |
| `res/xml/data_extraction_rules.xml` | Sección `<device-transfer>` que excluye `prefs2.xml` | Que los tokens no se copien entre dispositivos (corrección de seguridad que podría proponerse al original) |

Además, el workflow `android.yml` del original se conserva sin cambios pero está **desactivado** en GitHub
(Actions → Build → Disable), porque publicaría por su cuenta en la release `latest`.

### Cosas a tener en cuenta
- `TvHooks` y `TvLoginActivity` dependen de nombres internos del original (`MainActivity.INTENT_*`, `R.id.navHostFragment`
  de los ajustes, `AuthRepository`, los client id de `LoginActivity`). Si el original los cambia, la compilación o la
  prueba de la sincronización lo detectan; los fallos en los ajustes clásicos son silenciosos (se abre la pantalla
  principal de ajustes).
- `applicationId` y `versionCode` son los del original: el fork no se puede instalar encima de Xtra de F-Droid (firma
  distinta) y se reemplaza con las builds de upstream (misma clave de depuración).

---

## 3. Publicación y sincronización

### `TV release` (`.github/workflows/tv-release.yml`)
- Se ejecuta con cada push a `master` **que cambie la app** (no con cambios solo en `.github/`, `*.md`, `fastlane/`
  o `LICENSE`) y a mano desde Actions.
- Compila `assembleRelease`, **comprueba el manifiesto** del APK (`.github/scripts/check-tv-manifest.sh`: un único
  icono de TV que abre `TvActivity` y todos los componentes TV) y publica en la release **`latest`**, que es la que
  usa el actualizador de la TV.
- Antes de publicar guarda el APK anterior en la pre-release **`previous`** (para volver atrás a mano).
- Permisos mínimos: la compilación solo lee; la publicación escribe. Una sola publicación a la vez.

### `Upstream sync` (`.github/workflows/upstream-sync.yml`)
- **Cada lunes a las 06:00 UTC** comprueba si `crackededed/Xtra` tiene commits nuevos; si no, no hace nada.
- Si los hay: mezcla → compila → comprueba el manifiesto → **prueba de humo en un emulador de Android TV**
  (`.github/scripts/tv-smoke-test.sh`: abre la app, reproduce el primer directo, sale y abre un enlace; falla con
  cualquier cierre, bloqueo o pantalla equivocada). Las capturas quedan como artefacto `smoke-screens`.
- Solo si todo pasa sube la mezcla a `master` y lanza `TV release`.
- Si algo falla **no sube nada**: GitHub manda un correo y el resumen de la ejecución explica el motivo (conflictos con
  la lista de archivos, compilación, manifiesto o emulador). Si los issues del repositorio están activados, también
  abre uno.

### Operación
| Para… | Hacer |
|---|---|
| Probar la sincronización sin esperar al lunes | Actions → Upstream sync → Run workflow (con `force_test` solo compila y prueba, sin mezclar) |
| Publicar a mano | Actions → TV release → Run workflow |
| Volver a la versión anterior | Descargar el APK de la release `previous` e instalarlo a mano |
| Que la sincronización funcione también cuando el original cambie sus workflows | Crear un token de grano fino (Contents y Workflows: escritura) y guardarlo como secret `SYNC_TOKEN` |
| Reactivar la tarea semanal | GitHub la desactiva tras 60 días sin actividad en el repositorio: Actions → Upstream sync → Enable |

Lo que la prueba de humo **no** cubre: cambios de comportamiento del original que no provoquen un cierre (por ejemplo,
una opción que deja de funcionar). Si algo raro aparece tras una actualización semanal, `previous` permite volver atrás.

---

## 4. Si la sincronización falla

1. Mirar el resumen de la ejecución fallida (Actions → Upstream sync). Indica el motivo y, si hay conflictos, los archivos.
2. **Conflictos**: solo deberían darse en los 5 puntos de contacto. En local:
   ```bash
   git remote add upstream https://github.com/crackededed/Xtra.git   # solo la primera vez
   git fetch upstream
   git merge upstream/master
   # resolver: aceptar lo del original y volver a aplicar el cambio de la tabla de la sección 2
   git commit
   git push origin master    # lanza TV release
   ```
3. **No compila**: el original ha cambiado algo que usa el código de `ui/tv` (por ejemplo, una función de
   `ExoPlayerService`). Hay que adaptar `ui/tv`, no el código del original.
4. **Falla la prueba del emulador**: descargar el artefacto `smoke-screens` y el log de la ejecución para ver dónde se
   cerró la app.
5. **Upstream cambió sus workflows y no hay `SYNC_TOKEN`**: hacer la mezcla a mano como en el paso 2 (desde local con
   tu usuario sí se pueden subir esos archivos) o crear el secret.

---

## 5. Estado de la revisión

Revisión técnica del port del 20/10/2026 (commit `3b003969`). Los informes completos (`audit-ruflo/`) están solo en
local, sin subir al repositorio; los identificadores H.. remiten a ellos.

### Corregido
- **Seguridad**: redirección de intents a través de `TvActivity` (H01); URL de actualización sin validar (H21);
  tokens en la transferencia entre dispositivos (H24); tokens en las trazas de error (H25); permisos y concurrencia
  del workflow de publicación (H09); URL de actualización del fork desde el arranque (H10, en parte).
- **Reproductor**: recuperación tras corte de red (H03); cierre robusto del servicio (H05); pantalla negra al
  recrear (H06); seek repetido en VOD (H07); teclas multimedia (H19, H20); scroll en el panel de calidad (H28).
- **Actualizador**: descarga cancelable (H08); descarga a archivo y comprobación de tamaño (H23); "Ahora no" por build
  y no por hora (H22, en parte).
- **Navegación y listas**: recarga al cambiar de cuenta (H04); navegación conservada al volver de Ajustes (H11);
  claves estables (H13); hora de "Actualizado" solo tras recargas reales (H15); miniaturas renovadas (H30); estado al
  final de las listas (H31); enlaces y notificaciones en la interfaz TV (H26, H29 en parte); login por código con
  errores visibles y renovación (H36).
- **Separación del original** (sección 2) y **sincronización automática** (sección 3).

### Pendiente
| Prioridad | Qué | Hallazgo |
|---|---|---|
| Alta | Clave de firma de release propia (hoy se firma con la clave de depuración pública del repositorio). Cambiarla obliga a desinstalar una vez o a rotar la clave | H02, T6 |
| Media | Restaurar foco y scroll al volver de una ficha; foco inicial al abrir la app (se nota también en la prueba del emulador) | H12, H14 |
| Media | Que diálogos y paneles retengan el foco; foco al volver de una subpágina de Ajustes | H16, H17 |
| Baja | Campo de búsqueda: no abrir el teclado solo con pasar el foco | H18 |
| Baja | Ajustes: releer valores al volver de la pantalla clásica, ← desde los deslizadores, validar números | H33–H35 |
| Baja | Cachés de canales/juegos sin límite; avisos del token de integridad; estado de reproducción residual | H32, H27, H29 |
| Baja | Criterio de "versión nueva" por fecha (cada publicación con cambios en la app se ofrece como nueva) | H22 |
| Baja | Fijar las actions de GitHub por SHA | H09 |
