# BridgePad — Plano de Execução até o MVP

## 1. Propósito deste documento

Este documento transforma a visão descrita em [`idea.md`](./idea.md) em uma sequência executável de trabalho.

Ele cobre da preparação do projeto até um MVP Android publicável capaz de usar:

```text
Touchscreen ou gamepad USB/USB-C
                ↓
         BridgePad Android
                ↓
        Bluetooth HID Gamepad
                ↓
       Windows + Steam Input
```

Este plano deve ser atualizado durante o desenvolvimento. Uma fase só termina quando seus critérios de saída forem demonstrados. Funcionalidades posteriores ao MVP permanecem em `idea.md` e não devem entrar no trabalho corrente sem uma decisão explícita de mudança de escopo.

---

## 2. Definição do MVP

O MVP estará concluído quando um usuário conseguir:

1. instalar o BridgePad em um dispositivo Android compatível;
2. conceder as permissões necessárias;
3. escolher entre o controle touchscreen e um gamepad USB/USB-C reconhecido pelo Android;
4. parear o celular com um computador Windows;
5. iniciar uma sessão Bluetooth HID;
6. usar sticks, D-pad, botões, gatilhos e botões de sistema por meio do Steam Input;
7. interromper e retomar a conexão sem deixar botões ou eixos presos;
8. consultar um diagnóstico básico e exportar logs em caso de falha.

O MVP suporta oficialmente:

- Android 9 ou superior (API 28+);
- uma fonte principal de gamepad por vez;
- touchscreen com layout fixo;
- controles USB/USB-C expostos pelo Android como gamepad ou joystick;
- saída Bluetooth HID Classic;
- Windows 10 e Windows 11;
- Steam Input como caminho principal para jogos.

O MVP não promete:

- compatibilidade direta com jogos que aceitam somente XInput;
- rumble ou force feedback;
- gamepad Bluetooth como entrada enquanto o celular atua como Bluetooth HID;
- layouts touchscreen editáveis;
- perfis avançados, macros ou calibração guiada;
- LAN, aplicativo desktop ou USB como saída;
- Linux, macOS, iOS ou consoles;
- funcionamento em todo aparelho Android, pois o comportamento Bluetooth pode variar por fabricante.

---

## 3. Princípios de execução

1. **Validar o risco maior primeiro.** Bluetooth HID deve funcionar no hardware-alvo antes da construção da UI completa.
2. **Entregar fatias verticais.** Cada marco deve atravessar input, estado, encoder e output sempre que possível.
3. **Manter o núcleo independente.** Código de Android, Bluetooth e UI não deve contaminar o modelo lógico.
4. **Não antecipar a versão 2.** Rede, desktop e XInput ficam fora deste plano.
5. **Testar em hardware real cedo.** Emulador não valida Bluetooth HID nem os controles físicos relevantes.
6. **Falhar de modo seguro.** Parada, perda de conexão ou remoção de uma fonte deve produzir estado neutro.
7. **Documentar decisões irreversíveis.** Descriptor HID, formato do estado e política de merge devem ter registros de decisão.

---

## 4. Gates de decisão

### Gate A — Viabilidade de Bluetooth HID

Deve ser decidido ao final da Fase 1.

**GO:** o Android registra o perfil HID, o Windows conecta, recebe reports e a sessão é suficientemente estável para continuar.

**LIMITED GO:** funciona apenas em parte dos aparelhos testados. O projeto continua, mas passa a declarar uma lista explícita de aparelhos compatíveis.

**NO-GO:** o aparelho-alvo não mantém uma sessão utilizável ou o caminho exige APIs privadas/root. Nesse caso, Bluetooth deixa de ser o MVP e deve ser criado um plano separado para LAN + companion desktop.

### Gate B — Compatibilidade funcional

Deve ser decidido ao final da Fase 4.

**GO:** todos os controles obrigatórios aparecem corretamente no Windows e no Steam Input.

**NO-GO:** revisar o descriptor e o formato dos reports antes de construir touchscreen e acabamento.

### Gate C — Candidato a MVP

Deve ser decidido ao final da Fase 7.

**GO:** os critérios funcionais, de estabilidade e documentação da seção 15 foram satisfeitos.

---

## 5. Arquitetura mínima do MVP

```text
Android events / Touch events
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

### 5.1 Responsabilidades

- `InputSource`: inicia, encerra e publica eventos de uma origem.
- `RawInputEvent`: representa um evento Android ainda não normalizado, com origem e timestamp monotônico.
- `InputMapper`: converte códigos/eixos de uma origem em controles lógicos.
- `SourceGamepadState`: mantém a contribuição atual de uma única fonte.
- `InputMerger`: combina contribuições e remove o estado de fontes encerradas.
- `VirtualGamepadState`: snapshot imutável e independente de plataforma.
- `OutputScheduler`: controla a cadência de envio e sempre usa o estado mais recente.
- `HidReportEncoder`: transformação pura de estado lógico em bytes.
- `BluetoothHidOutput`: lifecycle, registro HID, conexão e envio ao host.

### 5.2 Regras iniciais de estado

- sticks usam `Float` no intervalo `[-1, 1]`;
- gatilhos usam `Float` no intervalo `[0, 1]`;
- valores fora do intervalo são limitados;
- deadzone de sticks é radial e aplicada antes do merge;
- D-pad interno usa uma enumeração de nove posições, incluindo neutro;
- controles lógicos usam posição (`FACE_SOUTH`) e não rótulo (`A`);
- cada fonte possui seu próprio estado;
- botões de múltiplas fontes usam união lógica;
- no MVP, uma única fonte possui cada eixo analógico;
- ao parar/desconectar, uma fonte perde toda a sua contribuição;
- ao parar a sessão, um report neutro deve ser enviado quando a conexão permitir;
- atualizações de alta frequência podem ser consolidadas, mas transições curtas de botões não podem ser perdidas.

### 5.3 Lifecycle obrigatório

Uma sessão ativa deve ser mantida por um foreground service com notificação persistente. O código deve tratar:

- Activity recriada ou enviada ao background;
- tela apagada;
- Bluetooth desligado;
- permissão removida;
- host desconectado;
- controle USB removido;
- processo encerrado pelo sistema;
- nova tentativa de conexão;
- encerramento solicitado pelo usuário.

---

## 6. Organização inicial do projeto

Manter um único repositório até existir um companion desktop real.

Estrutura sugerida:

```text
app/
  src/main/java/.../bridgepad/
    core/gamepad/
    core/mapping/
    input/android/
    input/touch/
    output/hid/
    session/
    ui/home/
    ui/controller/
    ui/debug/
    diagnostics/
  src/test/
  src/androidTest/

docs/
  idea.md
  plan.md
  decisions/
  compatibility.md
  testing.md
```

O núcleo pode começar como pacote dentro do módulo `app`. Criar múltiplos módulos Gradle só quando houver uma necessidade demonstrada de isolamento ou reuso.

---

## 7. Fase 0 — Preparação e baseline

### Objetivo

Criar uma base pequena, reproduzível e verificável para os experimentos.

### Trabalho

- [ ] Inicializar Git e definir licença open source.
- [ ] Criar projeto Android em Kotlin com Jetpack Compose.
- [ ] Definir `minSdk = 28` e documentar a escolha.
- [ ] Definir application ID, namespace e estratégia de versionamento.
- [ ] Adicionar lint, testes unitários e build de debug.
- [ ] Criar CI para build, lint e unit tests.
- [ ] Criar uma tela inicial mínima com versão e informações do aparelho.
- [ ] Registrar dados de teste: fabricante, modelo, versão Android e versão do app.
- [ ] Criar `docs/decisions/` e o primeiro ADR sobre Android nativo/API mínima.

### Entregáveis

- APK de debug instalável;
- build reproduzível por comando documentado;
- CI verde;
- modelo inicial de registro de testes em hardware.

### Critério de saída

Um checkout limpo compila, testa e gera APK usando as instruções do README.

---

## 8. Fase 1 — Spike Bluetooth HID

### Objetivo

Eliminar o maior risco do produto com o menor código possível.

### Escopo

Nesta fase não implementar arquitetura completa, gamepad físico nem UI final. Usar botões simples de teste.

### Trabalho

- [ ] Solicitar permissões Bluetooth conforme a versão do Android.
- [ ] Obter o proxy `BluetoothProfile.HID_DEVICE`.
- [ ] Implementar registro e desregistro por `BluetoothHidDevice.registerApp`.
- [ ] Criar um descriptor HID mínimo de gamepad.
- [ ] Implementar callbacks de registro e conexão.
- [ ] Permitir selecionar/conectar um host já pareado ou orientar o pareamento.
- [ ] Enviar reports de pressionar e soltar um botão de teste.
- [ ] Enviar valores de um eixo de teste.
- [ ] Executar a sessão em foreground service.
- [ ] Exibir estado: indisponível, registrando, pronto, conectando, conectado e erro.
- [ ] Testar foreground/background, tela apagada, disconnect e reconnect.
- [ ] Registrar resultado por aparelho Android e computador Windows.
- [ ] Escrever ADR do descriptor inicial e guardar bytes/campos esperados.

### Testes manuais obrigatórios

- Windows lista o dispositivo em `joy.cpl`;
- pressionar e soltar nunca deixa o botão preso;
- eixo percorre mínimo, centro e máximo;
- vinte ciclos de conexão/desconexão sem reiniciar o aparelho;
- sessão de pelo menos 30 minutos sem desconexão inesperada;
- comportamento conhecido ao colocar o app em background e apagar a tela;
- Steam Input detecta o dispositivo ou a limitação fica registrada.

### Entregáveis

- APK do spike;
- descriptor e layout do report documentados;
- tabela de testes por hardware;
- decisão Gate A registrada.

### Critério de saída

Gate A decidido com evidências. Nenhuma fase posterior começa em caso de `NO-GO`.

---

## 9. Fase 2 — Núcleo de gamepad

### Objetivo

Construir o pipeline lógico testável e independente de Android/Bluetooth.

### Trabalho

- [ ] Definir `VirtualControl`, `DpadDirection` e `VirtualGamepadState`.
- [ ] Definir `SourceId`, `RawInputEvent` e `SourceGamepadState`.
- [ ] Implementar normalização e clamp de eixos.
- [ ] Implementar deadzone radial com reescala opcional da faixa restante.
- [ ] Implementar reducer de eventos por fonte.
- [ ] Implementar remoção/neutralização de uma fonte.
- [ ] Implementar `InputMerger` com as regras do MVP.
- [ ] Implementar `HidReportEncoder` como função pura.
- [ ] Implementar scheduler com taxa configurável para diagnóstico.
- [ ] Definir estado da sessão separadamente do estado do gamepad.

### Testes unitários obrigatórios

- normalização nos extremos e fora da faixa;
- centro, borda e continuidade da deadzone;
- pressionar e soltar todos os botões;
- conversão de D-pad para hat switch, incluindo neutro;
- encoding de sticks e gatilhos nos extremos;
- tamanho e bytes conhecidos de reports HID;
- merge de botões;
- ownership de eixos;
- remoção de fonte com controles ativos;
- parada global resulta em estado neutro;
- sequência rápida press/release é preservada para o output.

### Entregáveis

- núcleo coberto por testes;
- especificação do estado lógico;
- fixtures de reports HID conhecidos.

### Critério de saída

Todo comportamento lógico necessário ao MVP pode ser testado na JVM, sem aparelho Android.

---

## 10. Fase 3 — Gamepad USB/USB-C

### Objetivo

Transformar eventos de um controle físico reconhecido pelo Android no estado lógico do BridgePad.

### Trabalho

- [ ] Detectar dispositivos `SOURCE_GAMEPAD` e `SOURCE_JOYSTICK`.
- [ ] Reagir a conexão, alteração e remoção de `InputDevice`.
- [ ] Capturar `KeyEvent` e `MotionEvent` no ponto correto da UI Android.
- [ ] Mapear face buttons, bumpers, sticks, D-pad, Start, Select, L3 e R3.
- [ ] Inspecionar `MotionRange` por dispositivo em vez de presumir eixos fixos.
- [ ] Mapear gatilhos separados quando disponíveis.
- [ ] Aplicar flatten/deadzone informada pelo Android como baseline, com fallback próprio.
- [ ] Criar identificação estável para selecionar mappings por descriptor/vendor/product quando disponível.
- [ ] Neutralizar o estado ao remover o dispositivo.
- [ ] Criar tela de diagnóstico com códigos brutos e valores normalizados.

### Hardware obrigatório

- GameSir X5 Lite no aparelho Android principal;
- pelo menos um segundo gamepad USB, se disponível;
- teste de hot-plug durante uma sessão.

### Critério de saída

Todos os controles obrigatórios do GameSir aparecem corretamente na tela de diagnóstico, sem drift acima da deadzone e sem estado preso após remoção.

---

## 11. Fase 4 — Primeira fatia vertical

### Objetivo

Completar o fluxo que representa o diferencial inicial do BridgePad.

```text
GameSir → Android input → estado lógico → HID report → Windows → Steam Input
```

### Trabalho

- [ ] Substituir os botões artificiais do spike pelo pipeline real.
- [ ] Integrar scheduler e `BluetoothHidOutput`.
- [ ] Confirmar a correspondência de todos os campos no `joy.cpl`.
- [ ] Criar configuração no Steam Input.
- [ ] Testar ao menos dois jogos: um via Steam Input e um que aceite HID/DirectInput quando disponível.
- [ ] Medir input rate, output rate e tempo aproximado entre evento e envio.
- [ ] Testar movimentos simultâneos, diagonais, gatilhos e múltiplos botões.
- [ ] Testar remoção do gamepad e desconexão Bluetooth durante input ativo.
- [ ] Corrigir descriptor antes de estabilizar formatos públicos.

### Critério de saída

Gate B aprovado: o GameSir controla corretamente um jogo pelo Steam Input durante uma sessão contínua de pelo menos uma hora, sem inputs presos ou desconexões atribuíveis ao app.

---

## 12. Fase 5 — Controle touchscreen fixo

### Objetivo

Permitir o uso do BridgePad sem periférico físico.

### Escopo do layout

- dois sticks;
- D-pad;
- quatro face buttons;
- L1/R1 e L2/R2;
- Start/Select;
- L3/R3 por botões dedicados, caso o gesto nos sticks não seja confiável.

### Trabalho

- [ ] Criar layout landscape fixo e responsivo a diferentes proporções.
- [ ] Implementar multitouch por pointer ID.
- [ ] Impedir que mover/remover um dedo altere o controle de outro dedo.
- [ ] Implementar cancelamento de gestos e neutralização em perda de foco.
- [ ] Normalizar sticks e limitar o knob ao raio visual.
- [ ] Permitir gatilhos analógicos somente se a interação for clara; caso contrário usar 0/1 no MVP.
- [ ] Adicionar feedback visual de controles ativos.
- [ ] Integrar ao mesmo `SourceGamepadState`/merger do gamepad físico.
- [ ] Manter controles importantes longe de recortes e gestos do sistema.

### Testes obrigatórios

- cinco ou mais toques simultâneos, conforme suporte do aparelho;
- stick mantido enquanto botões são alternados;
- troca rápida de dedos;
- `ACTION_CANCEL`, app em background e rotação não deixam estado preso;
- layout utilizável em pelo menos dois tamanhos/proporções de tela.

### Critério de saída

O usuário joga pelo Steam Input usando somente a tela por 30 minutos, sem perda de pointers, controles presos ou elementos inacessíveis.

---

## 13. Fase 6 — Produto mínimo e resiliência

### Objetivo

Transformar a prova técnica em um aplicativo compreensível e recuperável.

### Fluxo mínimo de UI

```text
Onboarding/permissões
        ↓
Escolher input
        ↓
Escolher ou parear PC
        ↓
Iniciar sessão
        ↓
Controle + status
        ↓
Encerrar sessão
```

### Trabalho

- [ ] Criar onboarding curto com requisitos e limitações.
- [ ] Implementar fluxo de permissões, negação e tentativa posterior.
- [ ] Exibir compatibilidade Bluetooth HID do aparelho antes de iniciar.
- [ ] Criar home com input, host, estado e ação principal.
- [ ] Criar seleção entre touchscreen e gamepad físico.
- [ ] Implementar mensagens acionáveis para falhas conhecidas.
- [ ] Implementar reconnect controlado, sem loop agressivo.
- [ ] Enviar estado neutro ao encerrar quando possível.
- [ ] Implementar logs estruturados por categoria, sem dados sensíveis.
- [ ] Permitir copiar/exportar relatório de diagnóstico.
- [ ] Adicionar versão, modelo do aparelho, Android e dados do controle ao relatório.
- [ ] Impedir duas sessões ou múltiplas chamadas concorrentes de conexão.
- [ ] Revisar acessibilidade básica, contraste e áreas de toque.

### Critério de saída

Uma pessoa que não participou do desenvolvimento consegue instalar, conceder permissões, parear, iniciar, jogar, desconectar e repetir o fluxo usando apenas as instruções do app/README.

---

## 14. Fase 7 — Estabilização e candidato a MVP

### Objetivo

Validar o aplicativo contra uma matriz mínima e eliminar falhas bloqueadoras.

### Matriz mínima

Android:

- aparelho principal Samsung;
- um aparelho de outro fabricante, se disponível;
- pelo menos duas versões Android suportadas, diretamente ou por contribuição externa.

Windows:

- Windows 10, se disponível;
- Windows 11;
- Steam estável atual.

Inputs:

- touchscreen;
- GameSir X5 Lite;
- um segundo gamepad USB/USB-C, se disponível.

### Cenários de estabilidade

- [ ] instalação limpa;
- [ ] permissão aceita, negada e posteriormente concedida;
- [ ] primeiro pareamento;
- [ ] reconexão com host já pareado;
- [ ] 20 ciclos iniciar/encerrar;
- [ ] sessão contínua de 2 horas;
- [ ] tela apagada e reaberta;
- [ ] Activity recriada;
- [ ] Bluetooth desligado e religado;
- [ ] cabo/controle removido com botões pressionados;
- [ ] host desligado durante a sessão;
- [ ] retorno ao app depois de interrupção;
- [ ] exportação de diagnóstico após erro.

### Política de defeitos

Bloqueiam o MVP:

- crash no fluxo principal;
- input preso;
- pareamento ou reconnect não recuperável sem limpar dados;
- eixo/botão obrigatório incorreto no hardware oficialmente suportado;
- sessão encerrada ao simples background permitido pelo fluxo;
- falta de indicação de erro que impeça o usuário de prosseguir.

Podem ser conhecidos e documentados:

- incompatibilidade com aparelho não listado;
- diferenças de nomes de botões no Windows;
- ausência de rumble;
- incompatibilidade direta com jogos exclusivamente XInput;
- pequenos problemas visuais que não afetem o controle.

### Critério de saída

Zero defeitos bloqueadores abertos, matriz publicada, checklist de release completo e Gate C aprovado.

---

## 15. Critérios globais de aceite do MVP

### Funcionais

- [ ] touchscreen produz todos os controles obrigatórios;
- [ ] GameSir X5 Lite produz todos os controles obrigatórios;
- [ ] estado interno não depende de códigos específicos do GameSir;
- [ ] Windows reconhece o celular como gamepad HID;
- [ ] Steam Input recebe e permite mapear o controle;
- [ ] controles simultâneos funcionam;
- [ ] nenhum cenário testado deixa input preso;
- [ ] início, parada e reconnect são acessíveis pela UI.

### Qualidade

- [ ] lógica de mapping, deadzone, merge e encoding possui testes unitários;
- [ ] build, lint e testes rodam na CI;
- [ ] crashes conhecidos do fluxo principal foram resolvidos;
- [ ] logs permitem identificar aparelho, controle e etapa da falha;
- [ ] nenhum segredo ou identificador pessoal desnecessário aparece nos logs.

### Compatibilidade e desempenho

- [ ] matriz real de hardware publicada;
- [ ] taxa de reports permanece estável durante uma sessão de 2 horas;
- [ ] não há crescimento contínuo de memória observado durante o teste longo;
- [ ] latência é considerada jogável em avaliação prática e os números medidos são registrados;
- [ ] limitações HID/DirectInput/XInput estão explicadas sem prometer compatibilidade universal.

### Distribuição e documentação

- [ ] README explica instalação, uso, suporte e limitações;
- [ ] licença e avisos de terceiros estão presentes;
- [ ] `compatibility.md` lista combinações testadas;
- [ ] `testing.md` permite repetir os testes manuais;
- [ ] changelog da primeira versão está pronto;
- [ ] APK release assinado pode ser gerado de forma reproduzível;
- [ ] política de privacidade simples informa que não há conta, nuvem ou telemetria, se isso continuar verdadeiro.

---

## 16. Ordem de issues/épicos

Criar os épicos nesta ordem, sem abrir antecipadamente todas as tarefas futuras:

1. `E0 Project baseline`
2. `E1 Bluetooth HID viability spike`
3. `E2 Core gamepad model and reducer`
4. `E3 Android USB gamepad input`
5. `E4 GameSir-to-Steam vertical slice`
6. `E5 Fixed touchscreen controller`
7. `E6 Session UX and resilience`
8. `E7 MVP stabilization and release`

Cada issue deve conter:

- problema e resultado esperado;
- escopo e itens explicitamente fora do escopo;
- dependências;
- forma de teste;
- critério de aceite observável;
- hardware necessário, quando aplicável.

---

## 17. Registro de riscos

| Risco | Impacto | Mitigação antes do MVP |
| --- | --- | --- |
| Implementação HID varia entre fabricantes Android | Alto | Spike cedo e lista explícita de compatibilidade |
| App HID é desregistrado fora de foreground | Alto | Foreground service e testes de lifecycle |
| HID genérico não funciona em jogos XInput-only | Alto | Posicionar Steam Input como caminho oficial e documentar a limitação |
| Descriptor produz eixos/gatilhos inconsistentes | Alto | Fixtures, `joy.cpl`, Steam e congelamento do descriptor após a Fase 4 |
| Eventos de release são perdidos | Alto | Estado por fonte, fila de transições e neutralização defensiva |
| Controle USB usa eixos/códigos diferentes | Médio | Inspecionar `MotionRange` e manter mappings por dispositivo |
| Touchscreen perde pointer em multitouch | Alto | Controle por pointer ID e testes de cancelamento/foco |
| Economia de bateria interrompe a sessão | Alto | Foreground service, diagnóstico e matriz por fabricante |
| Escopo cresce para rede/perfis/layout editor | Médio | Manter esses itens fora dos épicos do MVP |

O registro deve ganhar responsável, estado e evidência assim que o desenvolvimento começar.

---

## 18. Evidências de conclusão por fase

Uma fase não é concluída apenas porque o código foi escrito. Anexar à issue/PR correspondente:

- resultado dos testes automatizados;
- vídeo curto ou captura do teste em hardware, quando útil;
- aparelho, versão Android, host Windows e controle usados;
- logs relevantes;
- limitações encontradas;
- decisão tomada no gate, quando houver.

---

## 19. Próximo passo imediato

Executar somente a Fase 0 e, em seguida, a Fase 1.

O primeiro objetivo técnico não é criar a tela final nem suportar todos os botões. É demonstrar de forma repetível:

```text
Botão de teste no Android
          ↓
Bluetooth HID report
          ↓
Botão pressionado e solto no joy.cpl
```

Somente após o Gate A deve começar a implementação do núcleo definitivo.
