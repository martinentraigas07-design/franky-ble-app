# FRANKY 4.0 BLE — App Android

App Android para controlar el robot **FRANKY 4.0** por Bluetooth BLE.
Misma interfaz visual que el servidor web SPIFFS del ESP32.

![Build](https://github.com/TU_USUARIO/FrankyBLE/actions/workflows/build.yml/badge.svg)

---

## Pantallas

| Scanner | Dashboard | Gamepad | Bloques | Panel |
|---------|-----------|---------|---------|-------|
| Busca dispositivos BLE con nombre `FRANKY*` | Estado del sistema, sensores, navegación | D-PAD multitoque + Joystick virtual | Editor Blockly con bloques personalizados | Panel industrial con ADC, DHT22, velocidad |

---

## Compilar el APK

### Con GitHub Actions (recomendado)
1. Subí el proyecto a un repositorio GitHub
2. El workflow `.github/workflows/build.yml` compila automáticamente en cada push
3. Descargá el APK desde **Actions → último workflow → Artifacts**

Para generar una release con el APK adjunto:
```bash
git tag v1.0.0
git push origin v1.0.0
```

### Localmente (Android Studio)
1. Instalá Android Studio Hedgehog o superior
2. Abrí la carpeta `FrankyBLE` como proyecto existente
3. Agregá los archivos JS de Blockly en `app/src/main/assets/blockly/` (ver sección Blockly)
4. Build → Generate Signed Bundle / APK → APK → debug

---

## Configurar Blockly (archivos JS requeridos)

El editor de bloques requiere los archivos JS de Blockly en:
```
app/src/main/assets/blockly/
  ├── index.html       ✅ incluido
  ├── bly_core_1.js    ← descargar
  ├── bly_core_2.js    ← descargar
  ├── bly_core_3.js    ← descargar
  ├── bly_core_4.js    ← descargar
  ├── bly_core_5.js    ← descargar
  ├── bly_blocks.js    ← descargar
  ├── bly_js.js        ← descargar
  └── bly_msg.js       ← descargar
```

Estos son exactamente los mismos archivos del proyecto SPIFFS del ESP32 (`data/bly_*.js`).
Copiálos desde tu proyecto ESP32 a esta carpeta.

---

## UUIDs BLE — deben coincidir con el firmware ESP32

```
Service:  12345678-1234-1234-1234-123456789abc
CMD:      abcd1234-5678-1234-5678-abcdef123456   (write)
STATE:    abcd1234-5678-1234-5678-abcdef123457   (notify)
SENSOR:   abcd1234-5678-1234-5678-abcdef123458   (notify)
```

---

## Comandos BLE enviados al robot

| Comando | Acción |
|---------|--------|
| `F` | Avanzar |
| `B` | Retroceder |
| `L` | Girar izquierda |
| `R` | Girar derecha |
| `FL` `FR` `BL` `BR` | Diagonales |
| `S` | Stop motores |
| `X` | PARAR TODO (emergency) |
| `M:left,right` | Control joystick diferencial (-255 a 255) |
| `T:1` / `T:0` | Turbo on/off |
| `SPD:N` | Velocidad PWM (0–255) |
| `XML_START` | Inicio transferencia Blockly |
| `XML:chunk` | Fragmento XML (400 bytes) |
| `XML_END` | Fin transferencia Blockly |

---

## Notificaciones BLE recibidas del robot

```
STATE:IDLE          → modo activo
ADC0:1234           → valor ADC0 (0–4095)
BTN:0               → pulsador Start
TEMP:23.5           → temperatura DHT22
HUM:65.2            → humedad DHT22
```

---

## Estructura del proyecto

```
FrankyBLE/
├── .github/workflows/build.yml          ← GitHub Actions CI/CD
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/blockly/index.html        ← Editor Blockly
│   ├── java/com/franky/robot/
│   │   ├── MainActivity.kt
│   │   ├── data/ble/BleManager.kt
│   │   ├── domain/Models.kt
│   │   └── ui/
│   │       ├── components/JoystickView.kt
│   │       ├── viewmodel/RobotViewModel.kt
│   │       └── fragments/
│   │           ├── ScannerFragment.kt
│   │           ├── DashboardFragment.kt
│   │           ├── GamepadFragment.kt
│   │           ├── BlocklyFragment.kt
│   │           └── PanelFragment.kt
│   └── res/
│       ├── drawable/         ← estilos visuales industriales
│       ├── layout/           ← 6 layouts XML
│       ├── navigation/nav_graph.xml
│       └── values/           ← colores, temas, strings
└── build.gradle.kts
```

---

## Requisitos

- Android 6.0+ (API 23)
- Bluetooth LE activado
- Robot FRANKY 4.0 con firmware BLE encendido y cerca
