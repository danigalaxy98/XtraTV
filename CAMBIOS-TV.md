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

Objetivo: que cada versión nueva del original (`crackededed/Xtra`) se pueda absorber con el mínimo trabajo. Hay tres
capas de protección, y cada una la vigila el CI:

1. **Archivos propios.** Todo lo de TV está en archivos que el original no tiene.
2. **Pocas líneas tocadas** en archivos del original (4 puntos de contacto), reaplicables con un script.
3. **Una sola capa de contacto con su código** (`ui/tv/upstream/`): si el original cambia una API interna, solo hay
   que arreglar esos archivos, no la interfaz TV entera.

### Dónde está cada cosa (archivos propios)
| Qué | Dónde |
|---|---|
| Interfaz TV (pantallas, reproductor, ajustes, actualizador) | `app/src/main/java/.../ui/tv/` |
| **Capa de contacto con el código del original** | `ui/tv/upstream/` (`TvUpstreamAccount`, `TvUpstreamData`, `TvUpstreamChannel`, `TvUpstreamPlayer`, `TvUpstreamSettings`) |
| Punto de enganche que llama el original (`MainActivity`) y adaptación de los ajustes clásicos | `ui/tv/TvHooks.kt` |
| Inicialización al arrancar (URL de actualizaciones del fork, ajustes clásicos) | `ui/tv/TvInitProvider.kt` |
| Componentes TV del manifiesto, banner y reglas de copia de seguridad | `app/src/tv/AndroidManifest.xml` (se fusiona sobre el principal), `res/xml/tv_data_extraction_rules.xml` |
| Dependencias de Compose / TV | `app/tv.gradle` (las versiones compartidas se leen del catálogo del original) |
| Textos y plurales | `res/values*/tv_strings.xml`, `tv_plurals.xml` |
| Otros recursos | `tv_arrays.xml`, `ids.xml`, `xml/tv_preferences.xml`, `layout/activity_tv_player.xml`, `drawable-xhdpi/tv_banner.png` |
| Publicación | `.github/workflows/tv-release.yml` |
| Sincronización con el original y comprobación de ramas | `.github/workflows/upstream-sync.yml`, `tv-check.yml`, `.github/scripts/` |

### La capa `ui/tv/upstream/`
Es lo único de la interfaz TV que importa clases del original (`XtraApp`, los `DataSource`, `ExoPlayerService`,
`PlayerViewModel`, `ChatFragment`, `SettingsViewModel`, `AuthRepository`…). El resto de `ui/tv` solo usa su API, que está
escrita en términos de la TV:

| Archivo | Qué ofrece | Lo del original que envuelve |
|---|---|---|
| `TvUpstreamAccount` | sesión, cierre de sesión, login por código, cabeceras, cliente HTTP, formato de cifras | `AuthRepository`, `TwitchApiHelper`, `XtraModule` |
| `TvUpstreamData` | los flujos paginados de las listas (siguiendo, populares, categorías, vídeos, búsqueda) | los 7 `*DataSource` (sus constructores son lo que más cambia) |
| `TvUpstreamChannel` | `TvPlayerModel` y `TvPageModel`: stream, perfil y seguir/dejar de seguir | `PlayerViewModel`, `ChannelPagerViewModel` |
| `TvUpstreamPlayer` | servicio de reproducción (`TvPlaybackService`), calidades, estado a reproducir, chat y raids | `ExoPlayerService`, `BasePlaybackService`, `PlaybackState`, `ChatFragment`, `ChatViewModel`, `MainViewModel` |
| `TvUpstreamSettings` | acciones de ajustes y entrada a la pantalla clásica | `SettingsViewModel`, `SettingsActivity` |

Fuera de esa capa solo se permite importar: el propio paquete TV, recursos (`R`, `BuildConfig`, el binding del
layout TV), los modelos de datos (`Stream`, `Video`, `User`, `Game`) y los ayudantes de preferencias
(`util.C`, `util.prefs`, `util.tokenPrefs`, `util.applyTheme`). Lo comprueba `check-tv-boundary.sh`.

### Los 4 puntos de contacto con el original
| Archivo | Cambio | Para qué |
|---|---|---|
| `ui/main/MainActivity.kt` | 1 línea tras `super.onCreate`: `TvHooks.openTvInterface(this)` | En TV, llevar todo a la interfaz TV |
| `app/build.gradle.kts` | Plugin `kotlin.compose` y `apply(from = "tv.gradle")` al final | Compilar la interfaz TV |
| `gradle/libs.versions.toml` | 1 línea: plugin `kotlin-compose` | Ídem (versión ligada a la de Kotlin del original) |
| `app/src/main/AndroidManifest.xml` | Quitar `LEANBACK_LAUNCHER` de `MainActivity` | Un solo icono en la TV |

Esos cambios están escritos como script: `bash .github/scripts/tv-touchpoints.sh apply` los vuelve a poner sobre la
versión del original de esos archivos (es idempotente), `check` comprueba que están y `files` los lista. Por eso un
conflicto de fusión **solo en esos archivos** lo resuelve solo la sincronización: acepta lo del original y reaplica el
script.

Los tokens ya no se copian entre dispositivos sin tocar un archivo del original: `src/tv/AndroidManifest.xml` sustituye
`data_extraction_rules.xml` por `tv_data_extraction_rules.xml` (igual, más `device-transfer` sin `prefs2.xml`).

Además, el workflow `android.yml` del original se conserva sin cambios pero está **desactivado** en GitHub
(Actions → Build → Disable), porque publicaría por su cuenta en la release `latest`.

### Comprobaciones que no necesitan compilar (`.github/scripts/`)
| Script | Qué detecta |
|---|---|
| `check-tv-contract.mjs` | Lo que el compilador no ve: una preferencia del original que la TV pide por su clave y ya no existe o cambió de archivo, una acción de navegación de los ajustes clásicos que desapareció, o que las reglas de copia TV se han desviado de las del original |
| `check-tv-boundary.sh` | Que nada fuera de `ui/tv/upstream/` (y `TvHooks.kt`) importe el original directamente |
| `tv-touchpoints.sh check` | Que los 4 puntos de contacto siguen aplicados |
| `check-tv-manifest.sh` | Sobre el APK: un único icono de TV que abre `TvActivity` y todos los componentes TV |

### Cosas a tener en cuenta
- `TvHooks` depende de nombres internos del original (`MainActivity.INTENT_*`, `R.id.navHostFragment` de los ajustes) y la
  capa `upstream/` de sus clases. Si el original cambia algo, la compilación o el contrato lo detectan.
- `applicationId` y `versionCode` son los del original: el fork no se puede instalar encima de Xtra de F-Droid (firma
  distinta) y se reemplaza con las builds de upstream (misma clave de depuración). Se mantiene así a propósito.

---

## 3. Publicación y sincronización

### `TV release` (`.github/workflows/tv-release.yml`)
- Se ejecuta con cada push a `master` **que cambie la app** (no con cambios solo en `.github/`, `*.md`, `fastlane/`
  o `LICENSE`) y a mano desde Actions.
- Compila `assembleRelease`, **comprueba el manifiesto** del APK (`.github/scripts/check-tv-manifest.sh`) y publica en la
  release **`latest`**, que es la que usa el actualizador de la TV.
- Antes de publicar guarda el APK anterior en la pre-release **`previous`** (para volver atrás a mano).
- Permisos mínimos: la compilación solo lee; la publicación escribe. Una sola publicación a la vez.

### `TV check` (`.github/workflows/tv-check.yml`)
Para ramas de trabajo y pull requests (todo menos `master`): puntos de contacto, contrato, frontera, compilación y
manifiesto. **No publica nada.**

### `Upstream sync` (`.github/workflows/upstream-sync.yml`)
- **Cada día a las 06:00 UTC** busca en `crackededed/Xtra` el commit más reciente que tenga **al menos 3 días** y cuyo CI
  no haya fallado. El original no usa versiones etiquetadas (su release `latest` se mueve con cada commit), así que
  esos días de espera dejan que sus arreglos y reversiones lleguen antes que nosotros. Si no hay nada nuevo, no hace
  nada.
- Mezcla ese commit (con `git rerere` activo, que recuerda cómo se resolvió un conflicto). Si el conflicto es solo en los
  4 puntos de contacto, lo resuelve solo; si hay otros archivos, se detiene.
- Comprueba puntos de contacto, contrato y frontera → compila → comprueba el manifiesto → **prueba de humo en un emulador
  de Android TV** (`tv-smoke-test.sh`: abre la app, reproduce el primer directo, sale y abre un enlace; falla con
  cualquier cierre, bloqueo o pantalla equivocada). Las capturas quedan como artefacto `smoke-screens`.
- Solo si todo pasa sube la mezcla a `master` y lanza `TV release`.
- **Si algo falla no toca `master`.** La mezcla ya hecha se guarda en la rama `sync/upstream-<sha>`, y se abre (o
  actualiza) un issue con el motivo, los errores del compilador y la lista de commits nuevos; además llega el correo de
  GitHub. Ahí se arregla (casi siempre solo `ui/tv/upstream/`) y se mezcla a mano.

### Operación
| Para… | Hacer |
|---|---|
| Probar la sincronización sin esperar | Actions → Upstream sync → Run workflow (`force_test` compila y prueba la rama elegida sin mezclar; `soak_days` = 0 toma el commit más reciente) |
| Verificar un cambio antes de llevarlo a master | Subirlo a una rama: `TV check` se ejecuta solo (o Run workflow de `Upstream sync` con `force_test` sobre la rama, que además pasa el emulador) |
| Publicar a mano | Actions → TV release → Run workflow |
| Volver a la versión anterior | Descargar el APK de la release `previous` e instalarlo a mano |
| Que la sincronización funcione también cuando el original cambie sus workflows | Crear un token de grano fino (Contents y Workflows: escritura) y guardarlo como secret `SYNC_TOKEN` |
| Reactivar la tarea diaria | GitHub la desactiva tras 60 días sin actividad en el repositorio: Actions → Upstream sync → Enable |

Lo que la prueba de humo **no** cubre: cambios de comportamiento del original que no provoquen un cierre (por ejemplo,
una opción que deja de funcionar). Si algo raro aparece tras una sincronización, `previous` permite volver atrás.

---

## 4. Si la sincronización falla

1. Abrir el issue «Sincronización con upstream detenida» (o el resumen de la ejecución): indica el motivo y trae los
   errores del compilador.
2. **Compilación o contrato rotos** (lo normal): el original ha cambiado una API que usa la interfaz TV.
   ```bash
   git fetch origin
   git checkout sync/upstream-<sha>
   # adaptar ui/tv/upstream/ (la frontera garantiza que el resto de ui/tv no necesita cambios)
   git push origin sync/upstream-<sha>     # TV check lo compila
   # cuando esté en verde, llevarlo a master:
   git checkout master && git merge --ff-only sync/upstream-<sha> && git push origin master
   ```
3. **Conflictos fuera de los puntos de contacto**: no hay rama (la mezcla no se pudo hacer). En local:
   ```bash
   git remote add upstream https://github.com/crackededed/Xtra.git   # solo la primera vez
   git fetch upstream
   git merge <sha-de-upstream>
   # resolver: aceptar lo del original; los puntos de contacto se reaplican con
   bash .github/scripts/tv-touchpoints.sh apply
   git commit && git push origin master    # lanza TV release
   ```
4. **Falla la prueba del emulador**: descargar el artefacto `smoke-screens` y el log de la ejecución para ver dónde se
   cerró la app.
5. **Upstream cambió sus workflows y no hay `SYNC_TOKEN`**: hacer la mezcla a mano como en el paso 3 (desde local con
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
