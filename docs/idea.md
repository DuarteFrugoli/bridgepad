# BridgePad — Visão de Produto e Arquitetura

> Este documento descreve a ideia, os princípios e a direção de longo prazo do BridgePad. O plano executável, os gates técnicos e os critérios de aceite até o MVP estão em [`plan.md`](./plan.md).

## 1. Visão Geral

**BridgePad** será um aplicativo gratuito e open source que transforma um celular ou tablet em uma **ponte universal de controles**.

A ideia principal não é apenas transformar a tela do celular em um controle virtual.

O aplicativo deverá conseguir receber comandos de diferentes fontes de entrada, como:

* touchscreen;
* controles USB;
* controles USB-C encaixados diretamente no celular;
* controles Bluetooth;
* giroscópio;
* acelerômetro;
* futuramente teclado e mouse.

Essas entradas serão convertidas para um **estado interno padronizado de gamepad**.

Depois, esse estado poderá ser enviado para diferentes dispositivos através de diferentes métodos de conexão.

Exemplo:

```text
GameSir X5 Lite
      │
      │ USB-C
      ▼
   Android
      │
   BridgePad
      │
      │ Bluetooth HID
      ▼
   Notebook
      │
      ▼
Steam / Jogos
```

Outro exemplo:

```text
Controle Xbox
      │
   Bluetooth
      ▼
    Celular
      │
   BridgePad
      │
     Wi-Fi
      ▼
BridgePad Desktop
      │
      ▼
Controle Virtual
      │
      ▼
     Jogo
```

---

# 2. Objetivo Final

O objetivo final do BridgePad é permitir que:

> **qualquer entrada de controle suportada pelo celular possa ser convertida e retransmitida para diferentes dispositivos de destino através de diferentes métodos de conexão.**

O celular funciona como um **hub de input**.

A arquitetura deve permitir que a origem dos comandos seja independente do destino.

Por exemplo:

```text
INPUTS

Touchscreen
GameSir USB-C
Controle USB
Controle Bluetooth
Giroscópio
Acelerômetro

        │
        ▼

VirtualGamepadState

        │
        ▼

OUTPUTS

Bluetooth HID
LAN / Wi-Fi
USB + Companion
outros no futuro
```

A aplicação não deve ser programada especificamente para:

```text
GameSir → Windows
```

Ela deve ser programada para:

```text
InputSource
      ↓
VirtualGamepadState
      ↓
OutputTarget
```

Isso permitirá adicionar novos tipos de controle e novas formas de conexão futuramente sem reescrever o núcleo da aplicação.

---

# 3. Filosofia do Projeto

O BridgePad deverá seguir alguns princípios.

## 3.1 Gratuito

O aplicativo será totalmente gratuito.

Não haverá:

* anúncios;
* assinatura;
* recursos essenciais bloqueados;
* versão Pro;
* limite artificial de uso.

Poderá existir apenas uma opção voluntária para:

> Apoiar o projeto / Fazer uma doação.

---

## 3.2 Open Source

O código será disponibilizado publicamente no GitHub.

Isso permitirá:

* contribuições da comunidade;
* correções de compatibilidade;
* suporte a novos dispositivos;
* auditoria do comportamento do aplicativo;
* documentação pública dos protocolos;
* contribuição de desenvolvedores que possuem hardware diferente.

---

## 3.3 Android First

A primeira implementação será exclusivamente Android.

Tecnologias:

```text
Kotlin
Jetpack Compose
Android SDK
Coroutines
Flow / StateFlow
```

Flutter não será utilizado.

O aplicativo depende fortemente de APIs específicas do sistema Android, principalmente:

* Bluetooth;
* Bluetooth HID;
* InputDevice;
* MotionEvent;
* KeyEvent;
* USB;
* sensores;
* serviços Android.

Uma possível versão futura para iPhone deverá ser desenvolvida separadamente em:

```text
Swift
SwiftUI
GameController Framework
CoreBluetooth
```

O protocolo de comunicação e o modelo lógico de gamepad deverão, entretanto, ser reutilizáveis conceitualmente entre as duas plataformas.

---

# 4. Arquitetura Geral

A arquitetura pode ser compreendida em cinco grandes blocos de produto:

```text
┌───────────────────────────────┐
│         Input Layer           │
│                               │
│ Touch                         │
│ USB Gamepad                   │
│ Bluetooth Gamepad             │
│ Gyroscope                     │
│ Accelerometer                 │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│       Input Mapping Layer     │
│                               │
│ Normalize                     │
│ Deadzone                      │
│ Mapping                       │
│ Calibration                   │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│      VirtualGamepadState      │
│                               │
│ Buttons                       │
│ D-Pad                         │
│ Left Stick                    │
│ Right Stick                   │
│ Triggers                      │
│ Optional Sensors              │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│        Output Layer           │
│                               │
│ Bluetooth HID                 │
│ Network                       │
│ USB Companion                 │
│ Future Outputs                │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│          Destination          │
│                               │
│ Windows                       │
│ Linux                         │
│ Android                       │
│ Android TV                    │
│ macOS                         │
│ Future devices                │
└───────────────────────────────┘
```

Internamente, a ligação entre mapping, estado e output deverá ser mais explícita:

```text
RawInputEvent
      ↓
InputMapper
      ↓
SourceGamepadState (um por origem)
      ↓
InputMerger
      ↓
VirtualGamepadState
      ↓
OutputScheduler
      ↓
OutputTarget
```

Assim, a frequência de uma fonte não determina diretamente a frequência de uma saída, e o estado de uma origem pode ser removido por completo quando seu lifecycle termina.

---

# 5. Núcleo do Sistema

## 5.1 VirtualGamepadState

O componente mais importante da aplicação será o:

```text
VirtualGamepadState
```

Ele representa o controle virtual independentemente de:

* qual dispositivo gerou o comando;
* como o comando chegou;
* para onde o comando será enviado.

Exemplo conceitual simplificado:

```kotlin
data class VirtualGamepadState(
    val faceSouth: Boolean,
    val faceEast: Boolean,
    val faceWest: Boolean,
    val faceNorth: Boolean,

    val leftBumper: Boolean,
    val rightBumper: Boolean,

    val leftTrigger: Float,
    val rightTrigger: Float,

    val leftStickX: Float,
    val leftStickY: Float,

    val rightStickX: Float,
    val rightStickY: Float,

    val dpad: DpadDirection,

    val start: Boolean,
    val select: Boolean,

    val leftStickButton: Boolean,
    val rightStickButton: Boolean
)
```

O snapshot lógico não deverá carregar códigos Android, bytes HID nem nomes específicos de fabricante. Informações opcionais do dispositivo deverão ser descritas separadamente:

```kotlin
data class GamepadCapabilities(
    val hasAnalogTriggers: Boolean,
    val hasRumble: Boolean,
    val hasGyroscope: Boolean,
    val buttonCount: Int
)
```

Cada `InputSource` manterá um `SourceGamepadState`. Um `InputMerger` produzirá o `VirtualGamepadState` final. Essa separação permite remover toda a contribuição de uma origem quando ela for desconectada, evitando botões ou eixos presos.

Os valores analógicos deverão usar uma representação padronizada.

Exemplo:

```text
Sticks:

-1.0 ←──── 0 ────→ +1.0


Triggers:

0.0 ─────────────→ 1.0
```

---

# 6. Sistema de Inputs

Todas as fontes de entrada deverão implementar uma abstração comum.

Exemplo conceitual:

```kotlin
interface InputSource {

    fun start()

    fun stop()

    fun observeInput(): Flow<InputEvent>
}
```

Essa interface é apenas conceitual. A implementação deverá distinguir eventos brutos, estado por fonte e estado final. `Flow`/`StateFlow` poderão transportar snapshots, mas transições curtas de pressionar/soltar não poderão ser perdidas por consolidação de estado.

---

# 7. Inputs Prioritários

## 7.1 Touchscreen

Prioridade:

```text
MUITO ALTA
```

O celular deverá funcionar como controle mesmo sem nenhum periférico conectado.

Elementos possíveis:

```text
Left Stick
Right Stick
D-Pad
A
B
X
Y
L1
R1
L2
R2
Start
Select
L3
R3
```

Posteriormente:

* posição customizável;
* tamanho customizável;
* transparência;
* layouts;
* vibração;
* multi-touch;
* perfis.

---

# 8. Gamepad USB / USB-C

Prioridade:

```text
MUITO ALTA
```

Esse é um dos principais diferenciais do projeto.

Exemplo:

```text
GameSir X5 Lite
      │
      │ USB-C
      ▼
    Android
      │
      ▼
   BridgePad
```

Também deverão funcionar, quando reconhecidos corretamente pelo Android:

```text
Backbone
GameSir
8BitDo
DualSense USB
Xbox Controller USB
controles genéricos
controles OTG
```

Não deverá existir uma implementação específica para cada fabricante sempre que isso puder ser evitado.

O Android deverá identificar o dispositivo como:

```text
SOURCE_GAMEPAD
```

ou:

```text
SOURCE_JOYSTICK
```

---

# 9. Gamepad Bluetooth

Prioridade:

```text
ALTA
```

O aplicativo deverá conseguir receber comandos de controles Bluetooth reconhecidos pelo Android.

Exemplos:

```text
Xbox Wireless Controller
DualSense
DualShock 4
8BitDo
GameSir Bluetooth
controles genéricos
```

Entretanto, esse modo possui uma limitação importante.

Quando o Android estiver funcionando como:

```text
Bluetooth HID Device
```

a utilização simultânea do celular como:

```text
Bluetooth HID Host
```

pode ficar indisponível.

Ao registrar um aplicativo como `BluetoothHidDevice`, o Android desabilita o serviço HID Host enquanto esse registro estiver ativo. O sistema também pode remover automaticamente o registro quando o aplicativo deixa de estar em foreground. Portanto, uma sessão HID deverá possuir lifecycle explícito, foreground service, estado observável e recuperação de desconexão.

Consequentemente:

```text
Controle Bluetooth
      ↓
Celular
      ↓
Bluetooth HID
      ↓
PC
```

não deve ser considerado um fluxo garantido.

Para esse cenário deverá existir o modo:

```text
Controle Bluetooth
      ↓
Celular
      ↓
LAN / Wi-Fi
      ↓
BridgePad Desktop
      ↓
PC
```

---

# 10. Giroscópio

Prioridade:

```text
MÉDIA
```

O giroscópio poderá funcionar como uma entrada adicional.

Possíveis usos:

```text
Gyro → Mouse
Gyro → Right Stick
Gyro → Steering
```

Isso será implementado depois do funcionamento básico dos gamepads.

---

# 11. Acelerômetro

Prioridade:

```text
BAIXA / MÉDIA
```

Poderá complementar o sistema de motion controls.

Exemplo:

```text
inclinação do celular → direção
```

---

# 12. Teclado e Mouse

Prioridade:

```text
BAIXA
```

Posteriormente poderá ser possível transformar:

```text
teclado + mouse
```

em:

```text
VirtualGamepadState
```

Exemplo:

```text
WASD → Left Stick
Mouse → Right Stick
Space → A
Shift → L3
```

Não deverá fazer parte do MVP.

---

# 13. Outputs

Assim como os inputs, as saídas deverão possuir uma interface comum.

Exemplo:

```kotlin
interface OutputTarget {

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(
        state: VirtualGamepadState
    )
}
```

Essa interface representa a fronteira arquitetural, não necessariamente uma chamada por evento. Na implementação, um `OutputScheduler` deverá controlar a cadência, consumir o snapshot mais recente e preservar transições curtas de botões. Isso desacopla a frequência dos eventos Android da frequência suportada por Bluetooth ou rede.

Possíveis implementações:

```text
BluetoothHidOutput
NetworkOutput
UsbCompanionOutput
```

Futuramente:

```text
LinuxOutput
MacOutput
ConsoleOutput
```

---

# 14. Output 1 — Bluetooth HID

Prioridade:

```text
MUITO ALTA
```

Esse será o primeiro método de saída.

Fluxo:

```text
Touch / USB Gamepad
        │
        ▼
     Android
        │
        ▼
Bluetooth HID
        │
        ▼
    Windows
```

O objetivo é permitir:

> utilizar o celular como controle sem instalar nenhum software no computador.

O celular deverá se anunciar como:

```text
Bluetooth HID Gamepad
```

O dispositivo de destino deverá enxergar o celular como um gamepad Bluetooth.

Esse dispositivo será inicialmente um **gamepad HID genérico**, não um controle Xbox/XInput. No Windows, isso normalmente o coloca no universo HID/DirectInput. O Steam Input será o caminho principal de compatibilidade do MVP, mas jogos que aceitam somente XInput poderão não reconhecer o controle diretamente.

O modo Bluetooth inicial também não promete rumble ou compatibilidade universal entre aparelhos Android. A compatibilidade deverá ser validada e publicada por combinação de aparelho, versão Android e host.

---

# 15. Bluetooth HID — Objetivo Inicial

Primeiro teste funcional:

```text
GameSir X5 Lite
      ↓
Samsung Android
      ↓
BridgePad
      ↓
Bluetooth
      ↓
Windows
      ↓
joy.cpl
```

Ao pressionar:

```text
A
```

no GameSir, deverá aparecer:

```text
Button pressed
```

no painel de controladores do Windows.

Depois:

```text
BridgePad
      ↓
Steam Input
      ↓
Jogo
```

Esse será o primeiro grande marco técnico do projeto.

---

# 16. Output 2 — LAN / Wi-Fi

Prioridade:

```text
ALTA
```

Esse será o segundo método principal de comunicação.

Arquitetura:

```text
BridgePad Android
      │
      │ LAN
      ▼
BridgePad Desktop
      │
      ▼
Virtual Gamepad
      │
      ▼
Windows
```

Não deverá depender de internet.

Deverá funcionar através de:

```text
Wi-Fi local
roteador
hotspot
LAN
```

---

# 17. Por que o modo LAN é importante

O modo LAN não será apenas uma alternativa ao Bluetooth.

Ele permitirá recursos que Bluetooth HID não consegue fornecer adequadamente.

Exemplo:

```text
Xbox Controller Bluetooth
          ↓
       Android
          ↓
         LAN
          ↓
BridgePad Desktop
          ↓
   Virtual Controller
```

Também permitirá que o PC crie diferentes tipos de controle virtual.

Exemplo:

```text
Generic Gamepad
Xbox-compatible Gamepad
outros formatos
```

---

# 18. BridgePad Desktop

O BridgePad Desktop será desenvolvido somente depois que o aplicativo Android e o Bluetooth HID estiverem funcionando.

O objetivo será receber o estado do controle através da rede.

Arquitetura:

```text
Android
   │
   │ Network Protocol
   ▼
BridgePad Desktop
   │
   ▼
Virtual Gamepad Driver/API
   │
   ▼
Windows
```

Inicialmente:

```text
Windows
```

Futuramente:

```text
Linux
macOS
```

---

# 19. Protocolo de Rede

O protocolo deverá ser documentado e independente do Android.

Exemplo conceitual:

```text
BridgePad Protocol
```

O celular enviará algo equivalente a:

```json
{
    "sequence": 18452,
    "timestamp": 39104482,

    "buttons": 4129,

    "leftStickX": -0.27,
    "leftStickY": 0.81,

    "rightStickX": 0.02,
    "rightStickY": -0.10,

    "leftTrigger": 0.0,
    "rightTrigger": 0.73
}
```

Na implementação real deverá ser utilizado um formato binário compacto.

---

# 20. UDP para Input

Para a transmissão do estado durante o jogo, deverá ser avaliado o uso de:

```text
UDP
```

Motivo:

```text
baixa latência
```

Não é importante retransmitir um pacote antigo perdido.

É mais importante receber rapidamente o próximo estado.

Por isso o celular poderá enviar:

```text
estado completo do gamepad
```

em vez de apenas eventos isolados.

Exemplo:

```text
Packet 100
A = pressed

Packet 101
A = pressed

Packet 102
A = released
```

Se o pacote 101 for perdido, o estado continuará correto.

---

# 21. TCP ou Canal Confiável

Algumas operações não deverão usar UDP.

Exemplo:

```text
pairing
configuração
negociação
nome do dispositivo
seleção do tipo de controle
informações de versão
perfil
capabilities
```

Essas informações poderão utilizar:

```text
TCP
```

ou outro canal confiável.

---

# 22. Descoberta Automática

O usuário não deverá precisar digitar IP sempre que utilizar o BridgePad.

Fluxo desejado:

```text
BridgePad Desktop iniciado
        ↓
BridgePad Android procura na rede
        ↓
PC encontrado
        ↓
"Pedro-PC"
        ↓
Conectar
```

Poderão ser estudados:

```text
mDNS
DNS-SD
UDP Discovery
```

---

# 23. Pareamento

Para impedir que qualquer pessoa da rede envie comandos ao PC, deverá existir pareamento.

Exemplo:

```text
Celular encontra:

PEDRO-NOTEBOOK

[Conectar]
```

PC:

```text
BridgePad deseja conectar.

Código:

527184
```

Celular:

```text
Digite o código:

527184
```

Depois o dispositivo poderá ser salvo como confiável.

---

# 24. Output 3 — USB + Companion

Prioridade:

```text
FUTURA
```

Posteriormente poderá ser criado:

```text
Android
   │
   │ USB
   ▼
BridgePad Desktop
```

Essa comunicação poderá fornecer:

```text
latência muito baixa
sem depender de Wi-Fi
```

Entretanto, não será prioridade inicial.

---

# 25. USB HID Direto

Não será objetivo inicial fazer o Android se apresentar diretamente como:

```text
USB Gamepad HID
```

Isso possui limitações maiores do sistema Android e normalmente exige acesso mais profundo ao USB Gadget do dispositivo.

Portanto, o USB deverá inicialmente ser tratado como:

```text
USB → BridgePad Desktop
```

e não:

```text
USB → HID direto
```

---

# 26. Consoles

## Xbox

Não deverá ser considerado compatível inicialmente.

O Xbox utiliza protocolos e autenticação específicos para controles.

Não basta enviar:

```text
Bluetooth HID genérico
```

---

# 27. PlayStation

Da mesma forma, PS4 e PS5 não deverão ser tratados como destinos genéricos inicialmente.

Compatibilidade com consoles deverá ser considerada uma área de pesquisa futura.

---

# 28. Filosofia para Consoles

O código não deverá possuir algo como:

```text
if Xbox
if PlayStation
```

espalhado pelo projeto.

Caso sejam implementados no futuro:

```text
OutputTarget

BluetoothHidOutput
NetworkOutput
XboxOutput
PlayStationOutput
```

---

# 29. Possível Dongle Futuro

Uma possível expansão de longo prazo seria desenvolver hardware próprio.

Exemplo:

```text
Celular
   ↓
BridgePad
   ↓
Wireless
   ↓
BridgePad Dongle
   ↓
Console / PC
```

O dongle poderia cuidar dos protocolos específicos do dispositivo de destino.

Isso NÃO pertence às versões iniciais.

---

# 30. Interface do Aplicativo

Tela principal:

```text
BridgePad

Input
────────────────

GameSir X5 Lite
USB
Connected


Output
────────────────

Pedro-PC
Bluetooth HID
Connected


[ Start Controller ]
```

---

# 31. Tela de Inputs

Exemplo:

```text
Input Sources

✓ Touchscreen

✓ GameSir X5 Lite
  USB Gamepad

○ Gyroscope

○ Accelerometer
```

---

# 32. Visualizador de Input

O aplicativo deverá possuir uma tela que mostre os inputs detectados.

Exemplo:

```text
LEFT STICK

X:  0.432
Y: -0.218


RIGHT STICK

X: -0.012
Y:  0.823


BUTTONS

A     ●
B     ○
X     ○
Y     ○

L1    ○
R1    ●
```

Isso será extremamente útil para:

```text
debug
testes
compatibilidade
calibração
issues no GitHub
```

---

# 33. Sistema de Mapeamento

Nem todos os controles possuem os mesmos códigos.

Será necessário criar uma camada:

```text
RawInput
    ↓
Mapping
    ↓
VirtualGamepadState
```

Exemplo:

```text
KEYCODE_BUTTON_A

        ↓

VirtualButton.SOUTH
```

O código interno não deverá depender diretamente de:

```text
A
B
X
Y
```

porque diferentes layouts utilizam nomes diferentes.

Uma representação mais genérica poderá ser:

```text
FACE_SOUTH
FACE_EAST
FACE_WEST
FACE_NORTH
```

---

# 34. Deadzone

Analógicos deverão possuir suporte a deadzone.

Exemplo:

```text
Raw:

X = 0.032
Y = -0.021

Deadzone = 0.08

Result:

X = 0
Y = 0
```

Posteriormente o usuário poderá configurar:

```text
Left Stick Deadzone
Right Stick Deadzone
Trigger Deadzone
```

---

# 35. Calibration

Será criado futuramente um sistema para calibrar controles.

Exemplo:

```text
Move Left Stick in circles.

[ Calibration progress ]
```

O sistema poderá identificar:

```text
minimum
maximum
center
drift
range
```

---

# 36. Input Merge

Uma das principais características do BridgePad será permitir combinar diferentes entradas.

Exemplo:

```text
GameSir
+
Touchscreen
+
Gyroscope
```

Todos modificando o mesmo:

```text
VirtualGamepadState
```

Exemplo:

```text
GameSir:

Right Stick
A
B
X
Y


Touchscreen:

Custom Button
Start
Select


Gyroscope:

Right Stick Fine Movement
```

---

# 37. Conflitos de Input

As regras deverão ser definidas por controle, e não escolhendo uma fonte vencedora para o gamepad inteiro.

Exemplo:

```text
GameSir Left Stick = 0.6

Touch Left Stick = -1.0
```

No comportamento básico:

* cada fonte mantém seu próprio estado;
* botões usam união lógica entre fontes ativas;
* um eixo analógico possui uma fonte dona enquanto estiver em uso;
* combinações intencionais, como gyro somado ao stick, usam uma política específica;
* desconectar ou parar uma fonte remove imediatamente toda a contribuição dela;
* encerrar a sessão produz um estado neutro.

Políticas como maior magnitude ou última fonte ativa poderão existir como opções, mas não serão uma regra global implícita.

---

# 38. Estrutura Inicial do Projeto Android

Sugestão:

```text
bridgepad-android/

app/
│
├── ui/
│   ├── home/
│   ├── controller/
│   ├── devices/
│   ├── connections/
│   ├── settings/
│   └── debug/
│
├── input/
│   ├── InputSource.kt
│   ├── android/
│   │   ├── AndroidGamepadInput.kt
│   │   └── InputDeviceManager.kt
│   │
│   ├── touch/
│   │   └── TouchInput.kt
│   │
│   └── sensors/
│       ├── GyroscopeInput.kt
│       └── AccelerometerInput.kt
│
├── gamepad/
│   ├── VirtualGamepadState.kt
│   ├── VirtualButton.kt
│   ├── GamepadMapper.kt
│   ├── DeadzoneProcessor.kt
│   └── GamepadStateManager.kt
│
├── output/
│   ├── OutputTarget.kt
│   │
│   ├── bluetooth/
│   │   ├── BluetoothHidOutput.kt
│   │   ├── HidDescriptor.kt
│   │   └── HidReportEncoder.kt
│   │
│   └── network/
│       ├── NetworkOutput.kt
│       ├── DiscoveryManager.kt
│       └── PacketEncoder.kt
│
├── connection/
│   ├── ConnectionManager.kt
│   └── ConnectionState.kt
│
├── profile/
│   ├── ControllerProfile.kt
│   └── ProfileRepository.kt
│
└── settings/
    └── SettingsRepository.kt
```

---

# 39. Repositórios

Inicialmente:

```text
bridgepad-android
```

Depois:

```text
bridgepad-android
bridgepad-desktop
bridgepad-protocol
```

Possivelmente futuramente:

```text
bridgepad-ios
```

---

# 40. BridgePad Protocol

Quando o modo de rede for criado, o protocolo deverá possuir documentação própria.

Exemplo:

```text
bridgepad-protocol/

README.md
PROTOCOL.md

src/
spec/
examples/
```

A especificação deverá definir:

```text
packet format
version
handshake
input state
capabilities
device information
authentication
```

---

# 41. Etapa 0 — Pesquisa Técnica

> As etapas 41 a 76 registram a evolução conceitual imaginada para o produto. Elas não definem mais a ordem de implementação. O plano vigente, inclusive a validação de Bluetooth HID antes do núcleo definitivo, está em [`plan.md`](./plan.md).

Objetivo:

> validar os recursos fundamentais antes de construir o aplicativo inteiro.

Testes:

```text
Android detecta GameSir X5 Lite?
Android recebe todos os botões?
Android recebe os dois analógicos?
Android recebe gatilhos analógicos?
BluetoothHidDevice funciona no aparelho?
Windows reconhece HID enviado pelo Android?
Steam reconhece?
```

Entrega:

```text
Proof of Concept
```

---

# 42. Etapa 1 — Gamepad Input

Objetivo:

> receber corretamente os comandos de um controle físico conectado ao Android.

Implementar:

```text
InputDevice detection
KeyEvent
MotionEvent
sticks
buttons
triggers
dpad
```

Criar:

```text
Input Debug Screen
```

Critério de conclusão:

> Todos os controles do GameSir X5 Lite aparecem corretamente dentro do aplicativo.

---

# 43. Etapa 2 — VirtualGamepadState

Objetivo:

> desacoplar completamente o controle físico do restante do sistema.

Criar:

```text
VirtualGamepadState
GamepadStateManager
VirtualButton
GamepadMapper
```

Fluxo:

```text
GameSir
   ↓
Android Input Events
   ↓
GamepadMapper
   ↓
VirtualGamepadState
```

Critério:

> O restante do app consegue funcionar sem conhecer o GameSir.

---

# 44. Etapa 3 — Touchscreen Controller

Objetivo:

> permitir utilizar o celular como controle sem periférico físico.

Criar:

```text
Left Stick
Right Stick
D-Pad
Face Buttons
Shoulders
Triggers
Start
Select
```

Critério:

> Touchscreen e GameSir produzem exatamente o mesmo VirtualGamepadState.

---

# 45. Etapa 4 — Bluetooth HID Proof of Concept

Objetivo:

> fazer o computador reconhecer o Android como um gamepad Bluetooth.

Implementar:

```text
BluetoothHidDevice
HID Descriptor
HID Reports
Connection Handling
```

Primeiro:

```text
Touchscreen
      ↓
Bluetooth HID
      ↓
Windows
```

Depois:

```text
GameSir
      ↓
Android
      ↓
Bluetooth HID
      ↓
Windows
```

Critério:

> Windows reconhece o celular como gamepad e responde corretamente aos inputs.

---

# 46. Etapa 5 — Steam Compatibility

Objetivo:

> validar a utilização em jogos reais.

Testar:

```text
Steam Input
Steam Big Picture
jogos com suporte a controle
jogos compatíveis com HID/DirectInput sem Steam Input (teste exploratório)
```

Falhar no último caso não invalida o MVP: jogos exclusivamente XInput exigirão o futuro modo LAN/desktop ou outra camada de compatibilidade.

Critérios:

```text
sticks corretos
buttons corretos
triggers corretos
dpad correto
sem inputs presos
latência aceitável
```

---

# 47. Etapa 6 — Bluetooth HID Completo

Implementar:

```text
pairing
reconnect
connection status
disconnect
error handling
permissions
device selection
```

Criar UI:

```text
Available Devices

Pedro-PC
Windows PC

[ Connect ]
```

---

# 48. Etapa 7 — MVP

Essa será a primeira versão publicável.

## Inputs

```text
Touchscreen
USB Gamepad
USB-C Gamepad
```

## Output

```text
Bluetooth HID
```

## Destino principal

```text
Windows
```

## Recursos

```text
input visualizer
basic mapping
basic deadzone
connection status
basic settings
```

O MVP deverá cumprir:

> GameSir X5 Lite → Android → Bluetooth → Notebook → Steam.

---

# 49. Etapa 8 — Testes com Outros Controles

Testar:

```text
DualSense
DualShock
Xbox Controller
8BitDo
GameSir
controles genéricos
```

Criar tabela pública:

```text
Device Compatibility
```

Exemplo:

| Controle        | USB | Bluetooth Input | Funciona |
| --------------- | --: | --------------: | -------: |
| GameSir X5 Lite |   ✅ |             N/A |        ✅ |
| Xbox Controller |   ✅ |               ✅ |        ? |
| DualSense       |   ✅ |               ✅ |        ? |
| 8BitDo          |   ✅ |               ✅ |        ? |

---

# 50. Etapa 9 — Perfis de Controle

Criar:

```text
ControllerProfile
```

Cada perfil poderá armazenar:

```text
mapping
deadzone
axis inversion
trigger calibration
button remapping
```

---

# 51. Etapa 10 — Layouts Touchscreen

Permitir:

```text
mover botões
redimensionar
alterar transparência
adicionar/remover elementos
salvar layouts
```

Layouts:

```text
Xbox Style
PlayStation Style
Minimal
Custom
```

---

# 52. Etapa 11 — Network Protocol

Objetivo:

> criar a segunda grande forma de saída.

Criar:

```text
BridgePad Protocol v1
```

Implementar:

```text
handshake
device discovery
pairing
state packets
capabilities
versioning
```

---

# 53. Etapa 12 — BridgePad Desktop

Primeiro destino:

```text
Windows
```

Objetivo:

```text
receber VirtualGamepadState
          ↓
criar controle virtual
          ↓
jogo
```

Recursos:

```text
system tray
device list
connection status
controller type
logs
```

---

# 54. Etapa 13 — LAN / Wi-Fi

Integrar:

```text
BridgePad Android
      ↕
BridgePad Desktop
```

Testar:

```text
mesmo roteador
hotspot
Wi-Fi 2.4 GHz
Wi-Fi 5 GHz
Wi-Fi 6
```

Medir:

```text
latência
jitter
packet loss
stability
```

---

# 55. Etapa 14 — Controle Bluetooth → PC

Depois do modo LAN:

```text
Xbox Controller
      ↓
Bluetooth
      ↓
Android
      ↓
LAN
      ↓
PC
```

Isso contorna a limitação de utilizar Bluetooth HID simultaneamente nos dois lados.

---

# 56. Etapa 15 — Gyroscope

Adicionar:

```text
Gyro Input
```

Modos:

```text
Gyro → Right Stick
Gyro → Mouse
Gyro → Steering
```

Permitir:

```text
sensitivity
deadzone
invert
activation button
```

---

# 57. Etapa 16 — Input Mixing

Permitir combinações avançadas.

Exemplo:

```text
GameSir:
movement + buttons

Touch:
extra buttons

Gyro:
aim
```

---

# 58. Etapa 17 — USB Companion

Adicionar conexão:

```text
Android
   ↓
USB
   ↓
BridgePad Desktop
```

Objetivo:

```text
menor latência
independência de Wi-Fi
```

---

# 59. Etapa 18 — Linux

Depois de estabilizar Windows:

```text
BridgePad Desktop Linux
```

Reutilizar:

```text
BridgePad Protocol
```

Implementar apenas a camada específica de virtualização do controle.

---

# 60. Etapa 19 — macOS

Mesmo princípio:

```text
Android
   ↓
BridgePad Protocol
   ↓
BridgePad Desktop macOS
```

---

# 61. Etapa 20 — iOS

Somente depois do projeto Android estar maduro.

Criar:

```text
bridgepad-ios
```

Tecnologias:

```text
Swift
SwiftUI
GameController
CoreMotion
Network Framework
```

A principal compatibilidade compartilhada será:

```text
BridgePad Protocol
```

Portanto:

```text
iPhone
   ↓
Wi-Fi
   ↓
BridgePad Desktop
```

poderá ser implementado sem alterar o servidor principal.

---

# 62. Etapa 21 — Pesquisa sobre Consoles

Somente após PC/mobile estarem maduros.

Pesquisar:

```text
Xbox
PlayStation
Nintendo
```

Avaliar:

```text
protocolos
autenticação
licenciamento
hardware
restrições legais
restrições técnicas
```

Não prometer compatibilidade antes de validar tecnicamente.

---

# 63. Testes

O projeto deverá possuir testes em diferentes níveis.

## Unit Tests

```text
mapping
deadzone
state normalization
packet encoding
packet decoding
calibration
```

---

# 64. Integration Tests

```text
InputSource → VirtualGamepadState

VirtualGamepadState → HID Report

VirtualGamepadState → Network Packet
```

---

# 65. Hardware Tests

Será necessária uma matriz real de hardware.

Testar:

```text
Samsung
Pixel
Xiaomi
Motorola
outros
```

E:

```text
GameSir
Xbox
PlayStation
8BitDo
genéricos
```

A comunidade open source será importante para ampliar essa matriz.

---

# 66. Métricas Técnicas

O aplicativo deverá possuir ferramentas para medir:

```text
Input latency
Output latency
Packet rate
Packet loss
Jitter
Bluetooth connection state
Input event rate
```

Exemplo debug:

```text
Input Rate:       120 Hz
Output Rate:      120 Hz
Network RTT:      3.2 ms
Packet Loss:      0.03 %
```

---

# 67. Logs

Criar sistema de logs.

Categorias:

```text
INPUT
OUTPUT
BLUETOOTH
NETWORK
HID
DEVICE
ERROR
```

O usuário poderá exportar logs para abrir issues no GitHub.

---

# 68. Compatibilidade

Criar documentação:

```text
docs/

COMPATIBILITY.md
BLUETOOTH.md
NETWORK.md
CONTROLLERS.md
TROUBLESHOOTING.md
PROTOCOL.md
```

---

# 69. README Inicial

O README deverá explicar claramente:

```text
What is BridgePad?
Why does it exist?
What devices are supported?
How does Bluetooth mode work?
How does LAN mode work?
How can I contribute?
```

---

# 70. GitHub Issues

Criar templates:

```text
Bug Report
Controller Compatibility
Feature Request
Device Compatibility
Connection Problem
```

Controller Compatibility deverá pedir:

```text
Controller:
Connection type:
Android device:
Android version:
Buttons working:
Axes working:
Triggers working:
Logs:
```

---

# 71. GitHub Discussions

Usar para:

```text
ideias
hardware reports
roadmap
feature discussion
community support
```

---

# 72. Releases

Possível evolução de versões, sujeita aos gates e critérios definidos em `plan.md`:

```text
0.1.0
Input Proof of Concept

0.2.0
Touch Controller

0.3.0
Bluetooth HID

0.4.0
Steam Compatibility

0.5.0
Profiles + Mapping

0.9.0
Beta

1.0.0
Bluetooth MVP Stable
```

Depois:

```text
1.x
melhorias Bluetooth

2.0
BridgePad Desktop + LAN

3.x
advanced input / gyro / customization
```

---

# 73. Roadmap Resumido

Este roadmap representa dependências conceituais de longo prazo, não a ordem operacional do trabalho até o MVP.

```text
PHASE 1
Android Input
    ↓
GameSir funcionando


PHASE 2
VirtualGamepadState
    ↓
arquitetura desacoplada


PHASE 3
Touchscreen
    ↓
celular funciona sozinho


PHASE 4
Bluetooth HID
    ↓
Windows reconhece celular


PHASE 5
Steam
    ↓
jogos funcionando


PHASE 6
MVP
    ↓
primeira versão pública


PHASE 7
Profiles / Mapping
    ↓
compatibilidade


PHASE 8
BridgePad Protocol
    ↓
protocolo independente


PHASE 9
BridgePad Desktop
    ↓
controle virtual no PC


PHASE 10
LAN / Wi-Fi
    ↓
modo avançado


PHASE 11
Bluetooth Gamepad Passthrough
    ↓
controle BT → celular → PC


PHASE 12
Gyro / Custom Layouts
    ↓
recursos avançados


PHASE 13
USB Companion
    ↓
baixa latência


PHASE 14
Linux / macOS
    ↓
mais plataformas


PHASE 15
iOS
    ↓
BridgePad Protocol reutilizado


PHASE 16
Console Research
```

---

# 74. Escopo da Primeira Versão

É importante impedir que o projeto cresça demais antes da validação.

A versão inicial NÃO deverá tentar suportar:

```text
Xbox console
PlayStation
Nintendo Switch
iPhone
Linux
macOS
USB output
mouse
keyboard
complex macros
cloud
accounts
multiplayer
multiple controllers
```

O foco será:

```text
Android
+
Touchscreen
+
Gamepad USB
+
Bluetooth HID
+
Windows
+
Steam
```

---

# 75. Primeiro Objetivo Real

Antes de criar:

```text
layouts
settings
profiles
themes
desktop app
network
```

o projeto precisa primeiro validar o maior risco técnico com um caminho mínimo:

```text
Botão de teste no Android
       ↓
Bluetooth HID
       ↓
Windows / joy.cpl
```

Depois desse gate, a primeira fatia completa deverá provar:

```text
GameSir X5 Lite
       ↓
Android
       ↓
BridgePad
       ↓
Bluetooth HID
       ↓
Windows
       ↓
Steam
```

Se ambos funcionarem corretamente, o núcleo tecnológico do produto estará validado no hardware testado. Isso não implica compatibilidade com todo aparelho Android nem com jogos exclusivamente XInput.

---

# 76. Objetivo do MVP

O MVP será considerado concluído quando um usuário conseguir:

1. instalar BridgePad;
2. conectar um controle USB ao Android ou usar a tela;
3. selecionar Bluetooth HID;
4. parear o celular com o Windows;
5. abrir Steam;
6. utilizar o celular/controle em um jogo por meio do Steam Input.

O suporte direto será limitado a jogos e APIs que reconheçam o gamepad HID genérico. Compatibilidade universal com jogos XInput-only não faz parte desse MVP.

Sem:

```text
servidor
conta
internet
assinatura
anúncios
```

---

# 77. Objetivo da Versão 2

Depois da validação do MVP:

```text
Input:
Touch
USB
Bluetooth Controllers

        ↓

BridgePad Android

        ↓

LAN / Wi-Fi

        ↓

BridgePad Desktop

        ↓

Virtual Gamepad
```

Isso transformará BridgePad de:

> celular como gamepad Bluetooth

em:

> plataforma universal de roteamento de controles.

---

# 78. Visão de Longo Prazo

A arquitetura final ideal será:

```text
                      INPUTS

        Touchscreen
             │
        USB Gamepad
             │
      Bluetooth Gamepad
             │
          Keyboard
             │
           Mouse
             │
         Gyroscope
             │
       Accelerometer
             │
             ▼

      ┌───────────────────┐
      │     BridgePad     │
      │                   │
      │ VirtualGamepad    │
      │      State        │
      └─────────┬─────────┘
                │
                │
      ┌─────────┼─────────┐
      │         │         │
      ▼         ▼         ▼

 Bluetooth    Network    USB
    HID       Protocol Companion

      │         │         │
      ▼         ▼         ▼

     PC        PC        PC
   Android    Linux     macOS
     TV       macOS     outros

                │
                ▼

        Futuras plataformas
```

---

# 79. Definição do Projeto

Uma boa definição curta para o BridgePad seria:

> **BridgePad is an open-source universal gamepad bridge that turns Android devices into a hub between physical controllers, touchscreen controls and multiple output devices.**

Ou de forma mais simples:

> **Connect any controller. Use any input. Play anywhere.**

---

# 80. Regra Principal de Arquitetura

Durante todo o desenvolvimento, fazer sempre esta pergunta:

> Esta funcionalidade está acoplada a um controle específico, conexão específica ou plataforma específica?

Se a resposta for sim, verificar se ela pode ser transformada em:

```text
InputSource
OutputTarget
GamepadMapper
ProtocolAdapter
```

O núcleo do BridgePad deverá permanecer independente do hardware.

Essa será a principal característica que permitirá ao projeto realmente se aproximar da ideia de um:

> **Universal Gamepad Bridge.**
