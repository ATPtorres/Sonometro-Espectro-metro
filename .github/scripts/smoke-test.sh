#!/usr/bin/env bash
set -euo pipefail
package=com.atptorres.sonarlab
mkdir -p smoke-results
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$package" android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -W -n "$package/.MainActivity"
sleep 4
adb shell pidof "$package" >/dev/null || { echo 'APP_CLOSED_AT_LAUNCH'; exit 1; }
adb exec-out screencap -p > smoke-results/initial.png
# Buscar el botón por su etiqueta, no por coordenadas fijas.
dump_ui() {
  adb shell uiautomator dump /sdcard/sonarlab.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/sonarlab.xml > smoke-results/hierarchy.xml
}
dump_ui
coords=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('smoke-results/hierarchy.xml').getroot()
for node in root.iter('node'):
    if 'INICIAR MEDICIÓN' in node.attrib.get('text',''):
        import re
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
        print((x1+x2)//2,(y1+y2)//2)
        break
else:
    raise SystemExit('START_BUTTON_NOT_FOUND')
PY
)
echo "Start button at: $coords"
adb shell input tap $coords
# El volcado uiautomator puede reflejar un fotograma anterior al último clic.
# Reintentar y sincronizar con la UI evita falsos negativos en un emulador lento.
ui_ok=0
for attempt in 1 2 3 4; do
  sleep 5
  adb shell pidof "$package" >/dev/null || { echo 'APP_CLOSED_ON_START'; adb logcat -d -s AndroidRuntime:E; exit 1; }
  dump_ui
  if python3 - <<'PYUI'
import xml.etree.ElementTree as ET
root = ET.parse('smoke-results/hierarchy.xml').getroot()
labels = [n.attrib.get('text','') for n in root.iter('node')]
if not any('DETENER MEDICIÓN' in text for text in labels):
    raise SystemExit(1)
if not any('RECIBIENDO PCM' in text for text in labels):
    raise SystemExit(2)
if any('ERROR DE CAPTURA' in text for text in labels):
    raise SystemExit(3)
print('UI + AUDIO EMULATOR SMOKE PASS')
PYUI
  then
    ui_ok=1
    break
  fi
done
adb exec-out screencap -p > smoke-results/after-start.png
if [ "$ui_ok" -ne 1 ]; then
  echo 'CAPTURE_UI_DID_NOT_REACH_RUNNING_STATE'
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi
adb logcat -d -s AndroidRuntime:E > smoke-results/crashes.log
if grep -q 'FATAL EXCEPTION' smoke-results/crashes.log; then
  cat smoke-results/crashes.log
  exit 1
fi
