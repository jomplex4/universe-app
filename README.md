# UNIVERSE by DigitalMinds

Controlador para cintas LED Bluetooth ELK-BLEDOM / ELK-BLEDOB.
Un solo codigo para la web (Chrome) y para el APK de Android (Capacitor 8).

## Estructura
- `src/index.html` y `src/app.js`: la app (interfaz y motor).
- `native/`: copia de respaldo del codigo nativo: Bluetooth propio (Strip), segundo plano (LightService),
  rutinas con la app cerrada (Routines) y carpetas de musica (MusicLibrary).
- `android/`: proyecto Android listo para compilar.
- `.github/workflows/build.yml`: GitHub compila el APK solo en cada `git push`.

## Como sale el APK
1. `git push` a la rama `main`.
2. En GitHub, pestana **Actions**, espera el check verde (5 a 8 minutos).
3. En **Releases** aparece `UNIVERSE.apk` listo para descargar desde el celular.
   `UNIVERSE-backup.apk` es la version sin optimizar, por si la principal fallara.

## Comandos locales (opcional)
- `npm install` y luego `npm run build` genera la carpeta `www/`.
