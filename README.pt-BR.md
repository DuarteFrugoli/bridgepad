# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

> **Conecte qualquer controle. Use qualquer entrada. Jogue em qualquer lugar.**

BridgePad é um projeto Android gratuito e open source que transforma um celular
ou tablet em uma ponte flexível de controles. Ele pode usar a tela ou um controle
físico como entrada, normalizar os comandos em um único estado lógico de gamepad
e enviá-los para um computador.

O projeto está em desenvolvimento ativo. Os caminhos atuais entre Android e PC
funcionam por Bluetooth HID direto ou por Wi-Fi com o BridgePad Desktop no
Windows. Ainda não é uma versão pública finalizada.

## Estado atual

A versão atual de desenvolvimento é a `0.1.0`.

Disponível na build atual:

- Android 9 ou mais recente (`minSdk = 28`);
- gamepad virtual multitouch;
- editor persistente do layout virtual;
- layouts simétrico, assimétrico e mobile;
- entrada de controle físico normalizada pelo Android;
- captura USB HID direta, inclusive em segundo plano ou com a tela apagada;
- mapeamento opcional e salvo para os dois modos de controle físico;
- saída Bluetooth HID de gamepad e mouse relativo;
- touchpad de mouse integrado e touchpad grande para controle físico;
- uso simultâneo das entradas virtual e física durante uma sessão;
- descoberta Wi-Fi automática e sessão autenticada pelo BridgePad Desktop no Windows;
- pareamento guiado, reconexão, avisos e encerramento seguro;
- métricas ao vivo e exportação de diagnóstico com foco em privacidade;
- interface em inglês e português brasileiro;
- janela mínima bilíngue do BridgePad Desktop com status, PIN, revogação de
  celulares confiáveis e funcionamento pela bandeja do sistema;
- testes unitários, lint Android e build independente do APK no CI.

A combinação principal validada é:

```text
GameSir X5 Lite ou touchscreen
               |
               v
Samsung Galaxy A35
Android 16 / API 36
               |
        Bluetooth HID
               |
               v
Windows 11 + Steam Input
```

A estabilização da Fase 7 passou nessa combinação, incluindo sessão de duas
horas, 20 ciclos de início/conexão/encerramento, interrupções do Bluetooth,
remoção do controle físico, recriação da Activity e validação do mouse. Os
resultados estão em [`docs/compatibility.md`](./docs/compatibility.md).

Uma versão distribuível ainda depende dos itens restantes de
[`docs/release-checklist.md`](./docs/release-checklist.md), principalmente a
assinatura de release e evidências em mais equipamentos.

## Como funciona hoje

Atualmente, o BridgePad apresenta o Android ao computador como um dispositivo
Bluetooth HID composto, contendo um gamepad genérico e um mouse. Esse modo não
precisa do BridgePad Desktop instalado no computador.

```text
Touchscreen / controle físico
              |
              v
      BridgePad Android
              |
       Bluetooth HID
              |
              v
   Gamepad + mouse no Windows
              |
              v
       Steam Input / jogo
```

O controle atual é HID genérico, não um dispositivo XInput nativo. Por isso, a
Steam Input é a principal camada de compatibilidade. A Steam reconhece o
BridgePad como controle genérico, mas pode exigir uma configuração inicial dos
botões. Jogos que aceitam somente XInput podem não detectar diretamente o
controle Bluetooth atual.

## Fluxo da sessão

A Home monta a sessão nesta ordem:

1. **Destino** — atualmente um PC Windows ou, futuramente, Linux.
2. **Conexão** — Bluetooth e Wi-Fi estão disponíveis; USB entre celular e PC
   aparece como futuro.
3. **Computador** — no Bluetooth, escolher um PC pareado; no Wi-Fi, descobrir o
   BridgePad Desktop automaticamente e digitar o PIN apenas no primeiro pareamento.

A entrada não é mais uma escolha exclusiva. Os controles virtuais e qualquer
controle físico detectado podem ser usados ao mesmo tempo.

Uma nova configuração começa sem opções selecionadas. **Conectar e jogar** fica
desativado até todas as escolhas e permissões necessárias estarem válidas.
Escolher um computador pareado não torna o celular visível; isso só acontece se
o usuário escolher parear um novo PC.

Durante uma sessão, é possível trocar entre as telas de controle virtual e
touchpad sem reconectar. A tela visível não desativa nenhuma fonte de entrada.

## Modos do controle físico

### Compatibilidade

Usa as APIs normalizadas `InputDevice`, `KeyEvent` e `MotionEvent` do Android. É
o caminho que aceita mais controles, mas o BridgePad precisa permanecer visível
e a tela deve ficar ligada.

### USB em segundo plano

Captura diretamente um controle USB HID padrão compatível. Os comandos podem
continuar com o BridgePad em outra janela ou com a tela do Android apagada.

O assistente de mapeamento é opcional nos dois modos. Os perfis são associados à
identidade e ao descritor HID do controle quando essas informações estão
disponíveis. Remover uma entrada ou trocar de modo neutraliza seu estado para
evitar comandos presos ou duplicados.

Nesse nome, USB é a ligação entre o controle físico e o celular. Ainda não é uma
saída USB do celular para o computador.

## Controle virtual e mouse

O controle virtual aceita toques simultâneos independentes para:

- D-pad;
- analógicos esquerdo e direito;
- A, B, X e Y;
- L1/R1 e gatilhos digitais;
- Start, Select, L3 e R3;
- touchpad de mouse relativo.

O editor usa a mesma área e geometria da tela de jogo. É possível mover todos os
componentes, redimensionar largura e altura separadamente quando aplicável,
começar por um dos três layouts incluídos, cancelar as alterações, restaurar o
padrão ou salvar um único layout ativo persistente.

Presets personalizados com nome, gatilhos analógicos na tela, giroscópio e gestos
avançados de mouse ainda não foram implementados.

Com um controle físico ativo, o BridgePad pode mostrar um touchpad grande. O
botão ou gesto Voltar do Android retorna à Home sem encerrar a sessão Bluetooth.

## Arquitetura

Entrada, estado lógico, saída e interface são separados:

```text
touch / Android InputDevice / USB direto
                  |
             InputRouter
                  |
        VirtualGamepadState
                  |
          OutputScheduler
                  |
        GamepadOutputTransport
                  |
          Bluetooth HID hoje
```

Os módulos Gradle atuais são:

- `:domain` — estado, mapeamento, combinação, agendamento, sessão e portas em
  Kotlin puro;
- `:protocol` — mensagens versionadas e independentes de plataforma para o
  receptor desktop;
- `:transport-network` — descoberta, pareamento seguro, autenticação e sessão Wi-Fi;
- `:transport-bluetooth-hid` — perfis Bluetooth HID, descritores e encoders;
- `:app` — UI Android, permissões, ciclo de vida, entradas físicas, persistência
  e composição das dependências.

As dependências apontam para `:domain`. Uma entrada nova não deve conhecer o
transporte, e uma saída deve consumir apenas estados normalizados. Consulte
[`docs/architecture.md`](./docs/architecture.md) e os ADRs em
[`docs/decisions`](./docs/decisions/).

## Próxima direção

O trabalho principal agora é transformar o primeiro caminho jogável do
BridgePad Desktop em um produto, primeiro para Windows e depois Linux.

```text
BridgePad Android
       |
  Wi-Fi ou USB
       |
       v
BridgePad Desktop
       |
controle virtual do sistema
       |
       v
      Jogo
```

A ordem pretendida é:

1. consolidar e testar o protocolo versionado do desktop;
2. substituir o backend experimental por um backend sustentável no Windows;
3. validar e endurecer descoberta, pareamento seguro e sessões por Wi-Fi;
4. automatizar instalação, Firewall, credenciais e atualização no Windows;
5. implementar USB entre celular e PC sem root ou ADB no uso normal;
6. adicionar backend de controle virtual e empacotamento para Linux;
7. adicionar streaming opcional e de baixa latência do PC para o celular,
   começando por vídeo e depois áudio.

Wi-Fi e USB precisarão do BridgePad Desktop porque o computador deverá receber o
estado normalizado e criar um controle virtual nativo. Bluetooth HID continuará
como caminho direto, sem exigir o aplicativo complementar.

## Ainda não implementado

- saída USB entre celular e computador;
- backend e instalador de produção do controle virtual no Windows;
- receptor Linux;
- retorno de vibração/force feedback;
- controles por giroscópio ou acelerômetro;
- presets pessoais de layout com nome;
- gatilhos analógicos na tela;
- streaming de vídeo ou áudio do PC;
- modo cooperativo local em vários celulares, com um slot de controle virtual
  independente para cada pessoa;
- perfis avançados por jogo;
- macros ou calibração guiada.

## Tecnologias

O aplicativo Android usa:

- Kotlin;
- Jetpack Compose;
- Android SDK;
- Coroutines e `Flow`/`StateFlow`;
- Gradle.

O application ID é `dev.jonalakas.bridgepad`. Android, Windows e Linux são as
plataformas atualmente planejadas.

## Compilar e testar

Requisitos:

- Android SDK configurado em `local.properties`;
- JDK 17 ou mais recente;
- terminal aberto na raiz do repositório.

No Windows, execute todos os testes unitários e lints usados pelo CI:

```powershell
.\gradlew.bat :domain:test :protocol:test :transport-bluetooth-hid:testDebugUnitTest :transport-bluetooth-hid:lintDebug :app:testDebugUnitTest :app:lintDebug
```

Gere o APK de debug separadamente:

```powershell
.\gradlew.bat assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para instalar em um aparelho online no ADB:

```powershell
.\gradlew.bat installDebug
```

Os procedimentos detalhados estão em [`docs/testing.md`](./docs/testing.md).

## Estrutura do repositório

```text
app/                       aplicativo Android e composição
domain/                    domínio de gamepad independente
protocol/                  futuras mensagens do protocolo desktop
transport-bluetooth-hid/   adaptador Bluetooth HID para Android
docs/                      documentação pública em inglês e ADRs
README.md                  apresentação em inglês
README.pt-BR.md            apresentação em português brasileiro
```

Os documentos pessoais de planejamento são privados e ignorados pelo Git. Código
e documentação pública usam inglês; as duas versões do README são mantidas em
paralelo.

## Documentação

- [`docs/architecture.md`](./docs/architecture.md) — módulos e limites atuais;
- [`docs/gamepad-core.md`](./docs/gamepad-core.md) — pipeline lógico e contrato
  dos relatórios HID;
- [`docs/ui-flow.md`](./docs/ui-flow.md) — configuração da sessão, Settings e
  localização;
- [`docs/testing.md`](./docs/testing.md) — procedimentos de build e hardware;
- [`docs/compatibility.md`](./docs/compatibility.md) — resultados reais;
- [`docs/release-checklist.md`](./docs/release-checklist.md) — gates restantes;
- [`docs/decisions`](./docs/decisions/) — decisões de arquitetura.

## Compatibilidade

Bluetooth HID e as entradas Android podem variar conforme versão do Android,
fabricante, controle, implementação Bluetooth e sistema do computador. O suporte
é baseado em testes reais, não apenas na existência da API.

O Samsung Galaxy A35 é o aparelho Android principal, e o GameSir X5 Lite é o
controle físico principal. Eles são referências de validação, não requisitos
fixos do código. Windows 11 foi validado; Windows 10 e Linux ainda não passaram
pelo gate de compatibilidade do projeto.

## Open source e privacidade

O BridgePad pretende permanecer:

- gratuito e open source;
- sem anúncios;
- sem assinatura;
- sem conta obrigatória;
- sem nuvem para as funções principais.

Não haverá uma versão paga necessária para liberar funções do controle. Uma
opção de doação voluntária poderá existir no futuro.

O BridgePad usa a licença Apache License 2.0. Consulte [`LICENSE`](./LICENSE),
[`PRIVACY.md`](./PRIVACY.md) e
[`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md).

## Contribuições

Contribuições e relatórios de hardware são bem-vindos, especialmente sobre:

- aparelhos Android e controles diferentes;
- comportamento Bluetooth;
- mapeamento de controles;
- bugs de input, reconexão e ciclo de vida;
- desenvolvimento do receptor desktop para Windows/Linux;
- testes, traduções e documentação.

Ao relatar compatibilidade, informe aparelho e versão do Android, controle, tipo
de conexão, comandos testados e versão do BridgePad. Não publique endereços
Bluetooth ou identificadores pessoais. Revise relatórios de diagnóstico antes
de compartilhá-los.

## Integração contínua

Todo push executa dois jobs independentes no GitHub Actions:

- testes unitários e lint Android;
- build do APK de debug.

A build não depende do job de testes, então ambos executam mesmo que um falhe. O
envio de e-mails é controlado pelas configurações de notificações do GitHub de
cada colaborador.
