# Cambios para Android TV (opción B)

Un único APK que funciona en móvil y en TV. Todo lo nuevo solo se activa si la app detecta una TV (`TvUtils.isTv`); en el móvil se comporta igual que antes.

## Qué cambia en TV

| Zona | Comportamiento |
|---|---|
| Listas (streams, vídeos, juegos, canales…) | El elemento seleccionado se resalta con un borde de color y un pequeño zoom |
| Botones del reproductor, ajustes, login | El elemento enfocado lleva un borde de color |
| Reproductor con los controles ocultos | **OK / ↑ / ↓**: muestra los controles y pone el foco en Play/Pausa<br>**← / →**: retrocede o avanza 10 s (solo en vídeos y clips)<br>**Menú**: muestra u oculta el chat<br>**⏪ / ⏩** del mando: retroceder o avanzar |
| Reproductor con los controles visibles | Las flechas se mueven entre los botones; cada pulsación reinicia el temporizador de ocultado |
| Atrás en el reproductor | 1.ª pulsación: oculta los controles. 2.ª: cierra el reproductor |
| Mini-reproductor | No existe en TV, porque no se puede alcanzar con el mando. Al ir a un canal o categoría desde el reproductor, este se cierra. El botón "minimizar" se oculta |
| Foco detrás del reproductor | Mientras el reproductor está a pantalla completa, el foco no puede saltar a las listas de detrás |
| Login | Se usa directamente el código de dispositivo: aparece un código grande y se introduce en twitch.tv/activate desde el móvil |
| Lanzador de Android TV | Banner propio de 320×180 (`drawable-xhdpi/tv_banner.png`) |

## Archivos tocados

- Nuevo: `util/TvUtils.kt`, `res/values/ids.xml`, `res/drawable-xhdpi/tv_banner.png`
- `ui/main/MainActivity.kt`: reenvía las teclas al reproductor y bloquea el foco del fondo
- `ui/player/PlayerFragment.kt`: control con el mando y `minimizeOrClose()`
- `ui/player/ExoPlayerFragment.kt`, `ui/player/MediaPlayerFragment.kt`, `ui/chat/ChatFragment.kt`: usan `minimizeOrClose()`
- `ui/login/LoginActivity.kt` y `layout/activity_login.xml`: login por código en TV
- `ui/settings/SettingsActivity.kt`: resaltado de foco
- 10 layouts `*_list_item*.xml`: `android:focusable="true"` en la tarjeta (necesario en Android TV 7)
- `AndroidManifest.xml`: banner
- `values/strings.xml`, `values-es/strings.xml`: texto de instrucciones del login

## Pendiente o ideas para más adelante

- No está compilado ni probado: falta probarlo en un emulador o en una TV real.
- Hacer automática la comprobación del código de login, para no tener que pulsar "Siguiente".
- Búsqueda por voz.
- Márgenes de seguridad (overscan) en televisores antiguos.
