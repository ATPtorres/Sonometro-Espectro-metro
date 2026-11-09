# SonarLab · Sonómetro y espectrómetro para Android

Aplicación Android nativa, ligera, privada y sin anuncios. Usa el **micrófono integrado** cuando Android permite seleccionarlo y muestra el nivel sonoro estimado y el espectro en directo.

## Funciones

- Captura mono **PCM 16-bit / 48 kHz** usando `AudioRecord` y fuente `UNPROCESSED` en móviles compatibles.
- En teléfonos sin soporte `UNPROCESSED`, usa `MIC`, intenta desactivar AGC/supresión de ruido/eco y **muestra la advertencia de posible DSP**.
- Nivel RMS en **dBFS**, convertido a **dB SPL estimados** mediante offset manual configurable (valor inicial +130 dB, *no calibración certificada*).
- Promedio **Leq energético** de la sesión, mínimo y máximo de los niveles RMS medidos.
- Historial temporal con segmentos verdes (<70 dB), amarillos (70–84,9 dB) y rojos (≥85 dB).
- Analizador FFT de 4096 puntos, bandas logarítmicas entre ~40 Hz y 16 kHz y frecuencia dominante.
- Referencias orientativas de biblioteca, conversación, tráfico, moto, concierto y sirena, según **nivel**, sin pretender reconocer sonidos.
- Funciona totalmente **offline**, sin grabar ni transmitir audio. La medición se detiene al salir de la pantalla.

## Precisión y privacidad

El micrófono de un teléfono **no reemplaza a un sonómetro calibrado**. En especial:

1. **dBFS no equivale a dB SPL.** Se estima `dB_SPL ≈ 20·log10(RMS_PCM/32768) + offset`. El offset inicial de +130 dB sigue una referencia nominal asociada a rutas UNPROCESSED conformes, pero **puede ser incorrecto en tu equipo**; úsalo solo como valor de partida. Calibra comparando con un sonómetro físico en un sonido estable.
2. Las lecturas son **sin ponderación A** y por eso no deben compararse directamente con límites oficiales de exposición expresados en dB(A). Los umbrales visuales son orientativos y conservadores, no certificación ni consejo clínico.
3. `UNPROCESSED` es una **solicitud al sistema**; algunos modelos aplican procesamiento en firmware, seleccionan otro micrófono físico, o limitan/recortan niveles altos. Se muestra la ruta en la pantalla. La app prioriza el micrófono incorporado, pero **no puede garantizar cuál de varios micrófonos internos es el principal**.
4. FFT y RMS son *análisis matemático* para visualizar la señal, no efectos aplicados al audio de entrada. La ventana de Hann solo afecta a una copia en memoria para el espectro. No se guarda audio.

## Instalar APK desde GitHub

1. Abre la pestaña [Actions](https://github.com/ATPtorres/Sonometro-Espectro-metro/actions) del repositorio.
2. Selecciona la ejecución exitosa de **Compilar APK Android**.
3. En **Artifacts**, descarga `SonarLab-APK-instalable` (ZIP con `app-debug.apk`).
4. Extrae `app-debug.apk` e instálalo en el celular autorizando temporalmente la instalación desde esa fuente. Concede permiso de micrófono al abrir.

> El APK de Actions es una **compilación debug firmada para pruebas**; no es un lanzamiento publicado en Play Store. Cada nueva compilación debug usa la clave generada por ese entorno y podría requerir desinstalar una build debug anterior antes de actualizar.

## Construir localmente

Abre la carpeta en **Android Studio**, sincroniza Gradle y ejecuta **Build > Build APK(s)**. Para línea de comandos, usa Gradle 8.13 / JDK 17:

```bash
gradle assembleDebug
```

Salida: `app/build/outputs/apk/debug/app-debug.apk`.

## Stack

- Java 17, Android SDK (min 26, target 35), sin librerías de UI ni servicios en la nube.
- AGP 8.13.2 / Gradle 8.13, compilación reproducible en GitHub Actions.
- Código abierto. Ninguna petición de red ni permiso de Internet en el manifiesto.
