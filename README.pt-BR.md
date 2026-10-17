# BridgePad

[English](./README.md) | [Português (Brasil)](./README.pt-BR.md)

O BridgePad transforma um celular Android em controle, mouse e teclado para o
PC. Ele também oferece streaming experimental de vídeo e áudio do PC para o
celular na rede local. O projeto é gratuito e open source, mas a **v0.1.0 ainda
é uma build de desenvolvimento, não uma versão pública**.

## O que funciona hoje

| Conexão | Precisa do Desktop | Uso atual |
| --- | --- | --- |
| Bluetooth HID direto | Não | Controle, mouse e teclado genéricos. A Steam Input pode exigir configuração; jogos exclusivos de XInput podem não reconhecer o controle. |
| Bluetooth via Desktop | Sim | Comandos por RFCOMM para um controle virtual compatível com Xbox, além de mouse e teclado no Windows. É separado do HID direto. |
| Wi-Fi | Sim | Sessões pareadas de controle, mouse e teclado; streaming local experimental de vídeo H.264 e áudio Opus. |
| Ancoragem USB | Sim | A mesma sessão IP autenticada pela rede criada pela ancoragem USB do celular, não pelo modo de transferência de arquivos. O streaming é experimental. |

O app Android oferece controle virtual multitouch personalizável, layouts
independentes para retrato e paisagem, modos Compatibilidade e captura USB
direta para controle físico, além de touchpad. O controle físico pode funcionar
junto com os botões na tela. Na Home, a pessoa escolhe modo de jogo, conexão,
computador e, no streaming, controle físico ou mouse/teclado. A sobreposição de
controle virtual no vídeo ainda não está pronta.

O streaming local pode usar **QUIC DATAGRAM** (padrão) ou **UDP+AEAD**, escolhido
nas Configurações do Android. Os dois caminhos já são jogáveis em
desenvolvimento, mas ainda não passaram pelos gates finais de latência,
recuperação de perdas e sessões longas. WebRTC permanece como referência
medida, não como transporte local definitivo. O input usa conexão própria,
separada da mídia.

O Desktop testado atualmente é o Windows. O controle virtual XInput ainda usa
o backend de desenvolvimento ViGEmBus; faltam instalador e decisão do backend
de produção. O Linux está planejado para a primeira versão pública, mas ainda
não é jogável. Vários celulares com controles independentes e streaming
simultâneo também não estão prontos para lançamento. Streaming pela internet
fica fora do escopo inicial. Consulte as [evidências de compatibilidade](./docs/compatibility.md)
e os [critérios de lançamento](./docs/release-checklist.md) antes de anunciar
suporte a uma combinação.

## Experimentar a build de desenvolvimento

Você precisa de Android 9+, um PC Windows para desenvolvimento, Android SDK,
JDK 17, Rust e o NDK/target Rust usado pelo módulo nativo de mídia. O
[CI](./.github/workflows/ci.yml) mostra as dependências exatas de build. Para
jogos XInput, instale o driver ViGEmBus separadamente enquanto não houver um
instalador do BridgePad.

Na raiz do repositório, no Windows:

```powershell
.\gradlew.bat installDebug
cargo run --release --manifest-path desktop/Cargo.toml -p bridgepad-desktop
```

Abra o BridgePad no celular, escolha o modo de jogo e a conexão e selecione o
Desktop descoberto. Digite no celular o código temporário exibido no PC. As
próximas sessões usam o pareamento salvo. Já o Bluetooth HID direto é pareado
nas configurações Bluetooth do Android/Windows e dispensa o Desktop. Para USB,
ative a **ancoragem USB** no celular: conectar o cabo ou escolher transferência
de arquivos não cria a rota de rede necessária. Fechar a janela do Desktop
mantém o processo na bandeja; use **Exit** nela para encerrar.

O executável de desenvolvimento não instala regras de Firewall. Se descoberta
ou conexão falhar numa rede pública, verifique a permissão do executável no
Firewall em vez de mudar a rede para privada.

## Compilar e verificar

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :domain:test :protocol:test :streaming-core:test :transport-network:test :transport-bluetooth-hid:testDebugUnitTest :transport-bluetooth-hid:lintDebug :transport-bluetooth-desktop:testDebugUnitTest :transport-bluetooth-desktop:lintDebug :transport-usb-accessory:testDebugUnitTest :transport-usb-accessory:lintDebug :app:testDebugUnitTest :app:lintDebug
cargo test --locked --manifest-path desktop/Cargo.toml
```

O APK de debug fica em `app/build/outputs/apk/debug/app-debug.apk`. As
ferramentas diagnósticas ficam em **Configurações > Testes** no Android; não
fazem parte do fluxo normal de configuração. Veja [BridgePad Desktop](./desktop/README.md)
para comandos do receptor e limitações de desenvolvimento.

## Estrutura do projeto

- `app/`: interface Android, adaptadores de hardware e ciclo de vida da sessão.
- `domain/`, `protocol/`, `streaming-core/`: contratos de input e mídia.
- `transport-*/`: adaptadores de conexão Android.
- `native/`: transporte nativo de mídia no Android.
- `desktop/`: receptor Rust, adaptadores Windows e diagnósticos de transporte.
- `protocol-spec/`: contratos versionados e vetores de teste.

[Arquitetura](./docs/architecture.md) é a referência técnica atual.
[Gamepad core](./docs/gamepad-core.md) descreve a normalização dos comandos;
[streaming baseline](./docs/streaming-baseline.md) registra as medições da
referência. O [CHANGELOG](./CHANGELOG.md) registra as mudanças de desenvolvimento.

## Privacidade, licença e contribuições

Os recursos locais principais do BridgePad não exigem conta, assinatura ou
serviço de nuvem. O projeto usa a [licença Apache 2.0](./LICENSE). Consulte a
[política de privacidade](./PRIVACY.md) e os
[avisos de terceiros](./THIRD_PARTY_NOTICES.md).

Contribuições e relatos de hardware são bem-vindos. Informe versões do Android
e do PC, modelo do controle, método de conexão, versão do BridgePad e passos
para reproduzir. Revise os logs antes de compartilhar: não publique endereços
Bluetooth, segredos de pareamento ou outros identificadores pessoais.
