# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

> **Conecte qualquer controle. Use qualquer entrada. Jogue em qualquer lugar.**

BridgePad é um projeto gratuito e open source que transforma celulares e
tablets Android em uma ponte universal de controles.

O aplicativo recebe comandos de diferentes origens, converte-os para um estado
padronizado de gamepad e os transmite para outro dispositivo.

```text
Controle USB-C ou touchscreen
              ↓
       BridgePad Android
              ↓
     Bluetooth HID Gamepad
              ↓
    Windows + Steam Input
```

O projeto está em desenvolvimento inicial.

## Objetivo do MVP

O primeiro MVP permitirá:

- usar touchscreen ou um gamepad USB/USB-C como entrada;
- conectar um aparelho Android 9 ou mais recente a um PC Windows;
- apresentar o celular como um gamepad Bluetooth HID genérico;
- jogar por meio do Steam Input;
- consultar informações básicas de diagnóstico.

O MVP não inclui LAN, aplicativo desktop, XInput nativo, rumble, layouts
editáveis, macros, iOS, consoles ou compatibilidade garantida com todos os
aparelhos Android.

Depois do MVP, o roadmap inclui layouts touchscreen editáveis com presets
personalizados e gatilhos L2/R2 analógicos cuja intensidade pode ser definida
pela posição do toque ou pelo deslocamento do dedo.

## Arquitetura

```text
Android/Touch events
        ↓
RawInputEvent
        ↓
InputMapper
        ↓
SourceGamepadState
        ↓
InputMerger
        ↓
VirtualGamepadState
        ↓
OutputScheduler
        ↓
HidReportEncoder
        ↓
BluetoothHidOutput
```

O núcleo lógico não deve depender de códigos do Android, bytes HID ou nomes de
fabricantes. Isso permitirá adicionar novas entradas e saídas sem reescrever o
estado central do controle.

## Tecnologias

- Kotlin;
- Jetpack Compose;
- Android SDK;
- Coroutines e Flow/StateFlow;
- Gradle.

O application ID é `dev.jonalakas.bridgepad`, a versão inicial é `0.1.0` e o
Android mínimo suportado é o Android 9 (`API 28`).

## Compilar e testar

Requisitos:

- Android SDK configurado em `local.properties`;
- JDK 17 ou mais recente;
- terminal aberto na raiz do repositório.

No Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para instalar em um aparelho conectado por ADB:

```powershell
.\gradlew.bat installDebug
```

As instruções detalhadas estão em [`docs/testing.md`](./docs/testing.md).

## Compatibilidade

O Bluetooth HID pode se comportar de maneira diferente conforme fabricante,
modelo e versão do Android. A compatibilidade será baseada em testes reais e
registrada em [`docs/compatibility.md`](./docs/compatibility.md).

O Samsung Galaxy A35 é o aparelho principal de desenvolvimento, mas não será o
único aparelho suportado.

## Contribuições

Contribuições são bem-vindas, principalmente para:

- testes em diferentes aparelhos Android;
- testes com diferentes controles;
- mappings de controles;
- compatibilidade Bluetooth;
- correções e documentação.

Ao relatar compatibilidade, informe o modelo do aparelho, versão do Android,
controle, tipo de conexão, resultado observado e versão do BridgePad. Não
publique endereços Bluetooth ou outros identificadores pessoais.

## Licença

BridgePad é distribuído sob a Apache License 2.0. Consulte o arquivo
[`LICENSE`](./LICENSE) para os termos completos.
