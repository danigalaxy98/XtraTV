# Xtra para Android TV

Un único APK para móvil y TV:
- En el **móvil** la app es la de siempre (`MainActivity`).
- En la **TV**, el lanzador abre la **interfaz nueva** (`ui/tv/TvActivity`), hecha con Compose for TV.

## Interfaz nueva (opción A)

### Navegación
Un menú lateral que se despliega al recibir el foco: **Siguiendo · Populares · Categorías · Buscar**. Abajo están **Actualizar**, **Iniciar sesión** (si no hay sesión) y **Ajustes**.

| Pantalla | Qué hace |
|---|---|
| Siguiendo | Canales que sigues en directo (cuenta o seguimientos locales) |
| Populares | Directos con más espectadores |
| Categorías → categoría | Cuadrícula de juegos y, dentro, sus directos |
| Buscar | Campo de texto (teclado/voz de la TV), fila de canales y cuadrícula de directos |
| Ficha de canal | Avatar, seguidores, botón Seguir, directo actual y VODs |

- **OK** en una tarjeta de directo: ver el directo.
- **Mantener OK**: abrir la ficha del canal.
- **Atrás**: volver a la pantalla anterior.

### Actualizar (en lugar de deslizar hacia abajo)
- Botón **Actualizar** en el menú lateral.
- Automático:
  - Al volver de ver un directo o al volver a la app.
  - Tras 2 minutos sin tocar el mando.
  - Al volver a una sección cuyos datos tienen más de 2 minutos.
- Indicador "Actualizado hace X min" junto al título.

### Reproductor (`TvPlayerActivity`)
- Vídeo a pantalla completa. El **chat** va en un panel lateral (30 %) y se activa o desactiva con el botón Chat; la elección se recuerda.
- **OK / ↑ / ↓** muestran la capa de información (canal, título, categoría, espectadores, tiempo en directo) y los botones **Chat · Calidad · Seguir · Canal**. Se oculta sola a los 5 s.
- VODs: **← / →** retroceden o avanzan, y hay barra de progreso y Play/Pausa.
- **Atrás**: oculta la capa; una segunda pulsación sale del reproductor.
- Botón Home: se detiene la reproducción.
- Reutiliza el motor de siempre: `ExoPlayerService` (proxys, anuncios, calidades) y `ChatFragment`.

### Archivos
- Nuevos:
  - `ui/tv/TvActivity.kt`, `TvScreens.kt`, `TvComponents.kt`, `TvTheme.kt`, `TvBrowseViewModel.kt`, `TvPlayerActivity.kt`
  - `res/layout/activity_tv_player.xml`
- `gradle/libs.versions.toml`, `app/build.gradle.kts`: Compose BOM 2026.09.00, tv-material 1.1.0, paging-compose, coil-compose, activity-compose, lifecycle-viewmodel-compose y plugin de Compose.
- `AndroidManifest.xml`: `TvActivity` con `LEANBACK_LAUNCHER`; `MainActivity` ya no aparece en el lanzador de la TV.
- Textos nuevos `tv_*` en `values` y `values-es`.

### Limitaciones de esta versión
- Sin chat en VODs.
- No se puede escribir en el chat desde la TV.
- Sin descargas ni clips.

## Cambios anteriores (opción B), que se mantienen
Mejoran la interfaz del móvil cuando se abre en una TV, por ejemplo con un enlace de twitch.tv:
- Resaltado de foco.
- Control con el mando en el reproductor.
- Login por código de dispositivo.
- Banner de 320×180.

Archivos: `util/TvUtils.kt`, `ui/main/MainActivity.kt`, `ui/player/PlayerFragment.kt`, `ui/login/LoginActivity.kt` y los layouts `*_list_item*.xml`.
