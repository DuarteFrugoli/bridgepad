const invoke = window.__TAURI__?.core?.invoke || (async (command) => {
  if (command === "desktop_status") {
    return {
      phase: "ready",
      desktopName: "BridgePad Desktop",
      pairingCode: "1842-6950-3714",
      pairingExpiresInSeconds: 527,
      trustedDevices: [],
      controllerSlots: [],
      bluetoothSessions: 0,
      error: null,
      lowBatteryPercentage: null,
    };
  }
  return true;
});

const translations = {
  en: {
    controllerEyebrow: "CONNECTED CONTROLLERS",
    controllerTitle: "BridgePad positions",
    controllerHelp: "Drag one occupied controller onto another, or select both, to exchange who controls each virtual device. Release the buttons after swapping.",
    controllerNote: "These positions are managed by BridgePad, not guaranteed Windows player numbers. Direct HID and Bluetooth Desktop are not rearrangeable here.",
    controllerPosition: "Position {number}",
    controllerEmpty: "Waiting for a phone",
    controllerConnected: "Connected",
    controllerSelected: "Choose another occupied position",
    controllerSwapped: "Controllers swapped without disconnecting the virtual devices.",
    controllerEmptyTarget: "Connect a second phone to swap controllers.",
    controllerNoBluetooth: "{count} Bluetooth Desktop session(s) active outside these positions.",
    languageButton: "PT",
    lowBattery: "Notebook battery at {percent}%. Streaming may slow down; connect the charger for better performance.",
    ready: "Ready to connect",
    connected: "Phone connected",
    playing: "Controller active",
    error: "Could not start",
    readyDetail: "Waiting for your phone on this network.",
    connectedDetail: "Secure connection established. Waiting for a game session.",
    playingDetail: "BridgePad is sending controller input to this computer.",
    activeControllerOne: "1 BridgePad controller active. Windows and the game may assign a different player number.",
    activeControllerMany: "{count} BridgePad controllers active. Windows and the game may assign different player numbers.",
    errorDetail: "BridgePad Desktop is not receiving connections.",
    errorTitle: "BridgePad needs attention",
    pairEyebrow: "PAIR A PHONE",
    pairTitle: "Enter this code in BridgePad",
    copyHint: "Click to copy",
    copied: "Copied",
    pairHelp: "On your phone, choose PC → Wi-Fi → this computer, then enter the code.",
    newCode: "Generate new code",
    devicesEyebrow: "TRUSTED DEVICES",
    devicesTitle: "Your phones",
    emptyTitle: "No paired phones yet",
    emptyText: "The first phone will appear here after pairing.",
    forget: "Forget",
    trayHint: "BridgePad keeps running in the system tray when this window is closed.",
    firewallNote: "Development build: automatic Windows Firewall setup is not available yet.",
    confirmForget: "Forget this phone? It will need to be paired again.",
  },
  pt: {
    controllerEyebrow: "CONTROLES CONECTADOS",
    controllerTitle: "Posições BridgePad",
    controllerHelp: "Arraste um controle ocupado sobre outro, ou selecione os dois, para trocar quem controla cada dispositivo virtual. Solte os botões após a troca.",
    controllerNote: "Estas posições são do BridgePad; o número de jogador no Windows não é garantido. HID direto e Bluetooth Desktop ainda não podem ser rearranjados aqui.",
    controllerPosition: "Posição {number}",
    controllerEmpty: "Aguardando celular",
    controllerConnected: "Conectado",
    controllerSelected: "Escolha outra posição ocupada",
    controllerSwapped: "Controles trocados sem desconectar os dispositivos virtuais.",
    controllerEmptyTarget: "Conecte um segundo celular para trocar os controles.",
    controllerNoBluetooth: "{count} sessão(ões) Bluetooth Desktop ativa(s) fora destas posições.",
    languageButton: "EN",
    lowBattery: "Bateria do notebook em {percent}%. O streaming pode ficar mais lento; conecte o carregador para melhorar o desempenho.",
    ready: "Pronto para conectar",
    connected: "Celular conectado",
    playing: "Controle ativo",
    error: "Não foi possível iniciar",
    readyDetail: "Aguardando seu celular nesta rede.",
    connectedDetail: "Conexão segura estabelecida. Aguardando uma sessão de jogo.",
    playingDetail: "O BridgePad está enviando os comandos para este computador.",
    activeControllerOne: "1 controle BridgePad ativo. O Windows e o jogo podem atribuir outro número de jogador.",
    activeControllerMany: "{count} controles BridgePad ativos. O Windows e o jogo podem atribuir outros números de jogador.",
    errorDetail: "O BridgePad Desktop não está recebendo conexões.",
    errorTitle: "O BridgePad precisa de atenção",
    pairEyebrow: "PAREAR UM CELULAR",
    pairTitle: "Digite este código no BridgePad",
    copyHint: "Clique para copiar",
    copied: "Copiado",
    pairHelp: "No celular, escolha PC → Wi-Fi → este computador e digite o código.",
    newCode: "Gerar novo código",
    devicesEyebrow: "DISPOSITIVOS CONFIÁVEIS",
    devicesTitle: "Seus celulares",
    emptyTitle: "Nenhum celular pareado",
    emptyText: "O primeiro celular aparecerá aqui depois do pareamento.",
    forget: "Esquecer",
    trayHint: "O BridgePad continua ativo na bandeja do sistema quando esta janela é fechada.",
    firewallNote: "Build de desenvolvimento: a configuração automática do Firewall do Windows ainda não está disponível.",
    confirmForget: "Esquecer este celular? Será necessário pareá-lo novamente.",
  },
};

let language = localStorage.getItem("bridgepad-language") || (navigator.language.startsWith("pt") ? "pt" : "en");
let latestStatus = null;
let selectedSlot = null;
let slotSignature = "";
let swapBusy = false;

const elements = Object.fromEntries(
  [
    "languageButton", "statusDot", "statusLabel", "desktopName", "statusDetail", "errorPanel",
    "errorTitle", "errorText", "pairEyebrow", "pairTitle", "pairTimer", "pairingCode",
    "copyCodeButton", "copyHint", "pairHelp", "newCodeButton", "devicesEyebrow", "devicesTitle",
    "deviceCount", "deviceList", "emptyDevices", "emptyTitle", "emptyText", "trayHint", "firewallNote",
    "batteryPanel", "batteryText", "controllerEyebrow", "controllerTitle", "controllerHelp",
    "controllerCount", "controllerSlots", "controllerNote", "controllerFeedback",
  ].map((id) => [id, document.getElementById(id)]),
);

function t(key) { return translations[language][key]; }

function applyLanguage() {
  document.documentElement.lang = language === "pt" ? "pt-BR" : "en";
  for (const key of ["languageButton", "errorTitle", "pairEyebrow", "pairTitle", "copyHint", "pairHelp", "devicesEyebrow", "devicesTitle", "emptyTitle", "emptyText", "trayHint", "firewallNote", "controllerEyebrow", "controllerTitle", "controllerHelp", "controllerNote"]) {
    elements[key].textContent = t(key);
  }
  elements.newCodeButton.textContent = t("newCode");
  slotSignature = "";
  if (latestStatus) render(latestStatus);
}

function phaseText(phase) {
  return {
    ready: [t("ready"), t("readyDetail")],
    connected: [t("connected"), t("connectedDetail")],
    playing: [t("playing"), t("playingDetail")],
    error: [t("error"), t("errorDetail")],
  }[phase] || [t("ready"), t("readyDetail")];
}

function formatTimer(seconds) {
  const safe = Math.max(0, Number(seconds) || 0);
  return `${String(Math.floor(safe / 60)).padStart(2, "0")}:${String(safe % 60).padStart(2, "0")}`;
}

function renderDevices(devices) {
  elements.deviceList.replaceChildren();
  elements.deviceCount.textContent = String(devices.length);
  elements.emptyDevices.hidden = devices.length > 0;
  for (const device of devices) {
    const row = document.createElement("div");
    row.className = "device";

    const avatar = document.createElement("span");
    avatar.className = "device-avatar";
    avatar.textContent = "▯";

    const info = document.createElement("div");
    const name = document.createElement("div");
    name.className = "device-name";
    name.textContent = device.name;
    const id = document.createElement("div");
    id.className = "device-id";
    id.textContent = device.id.slice(0, 12);
    info.append(name, id);

    const forget = document.createElement("button");
    forget.className = "forget-button";
    forget.type = "button";
    forget.textContent = t("forget");
    forget.addEventListener("click", async () => {
      if (!window.confirm(t("confirmForget"))) return;
      await invoke("forget_device", { id: device.id });
      await refresh();
    });
    row.append(avatar, info, forget);
    elements.deviceList.append(row);
  }
}

function showSlotFeedback(message, error = false) {
  elements.controllerFeedback.textContent = message;
  elements.controllerFeedback.classList.toggle("error", error);
}

async function swapSlots(first, second) {
  if (swapBusy || first === second) return;
  const occupied = new Set((latestStatus?.controllerSlots || []).map((slot) => slot.position));
  selectedSlot = null;
  if (!occupied.has(first) || !occupied.has(second)) {
    showSlotFeedback(t("controllerEmptyTarget"), true);
    renderSlots(latestStatus?.controllerSlots || []);
    return;
  }
  swapBusy = true;
  try {
    await invoke("swap_controller_slots", { first, second });
    showSlotFeedback(t("controllerSwapped"));
    await refresh();
  } catch (error) {
    showSlotFeedback(String(error), true);
  } finally {
    swapBusy = false;
    renderSlots(latestStatus?.controllerSlots || []);
  }
}

function renderSlots(slots) {
  const byPosition = new Map(slots.map((slot) => [slot.position, slot]));
  if (selectedSlot !== null && !byPosition.has(selectedSlot)) selectedSlot = null;
  const signature = JSON.stringify([slots, selectedSlot, language]);
  elements.controllerCount.textContent = `${slots.length}/4`;
  if (signature === slotSignature) return;
  slotSignature = signature;
  elements.controllerSlots.replaceChildren();
  for (let position = 0; position < 4; position++) {
    const slot = byPosition.get(position);
    const card = document.createElement("button");
    card.type = "button";
    card.className = `controller-slot${slot ? " occupied" : ""}${selectedSlot === position ? " selected" : ""}`;
    card.draggable = Boolean(slot);
    card.setAttribute("aria-pressed", String(selectedSlot === position));
    card.setAttribute("aria-label", `${t("controllerPosition").replace("{number}", position + 1)}: ${slot?.deviceName || t("controllerEmpty")}`);
    const number = document.createElement("span");
    number.className = "slot-number";
    number.textContent = t("controllerPosition").replace("{number}", position + 1);
    const name = document.createElement("span");
    name.className = "slot-name";
    name.textContent = slot?.deviceName || t("controllerEmpty");
    const detail = document.createElement("span");
    detail.className = "slot-detail";
    detail.textContent = slot ? t("controllerConnected") : "—";
    card.append(number, name, detail);
    card.addEventListener("click", () => {
      if (!slot) {
        showSlotFeedback(t("controllerEmptyTarget"), true);
        return;
      }
      if (selectedSlot === null || selectedSlot === position) {
        selectedSlot = selectedSlot === position ? null : position;
        showSlotFeedback(selectedSlot === null ? "" : t("controllerSelected"));
        renderSlots(latestStatus?.controllerSlots || []);
      } else {
        void swapSlots(selectedSlot, position);
      }
    });
    card.addEventListener("dragstart", (event) => {
      if (!slot) return event.preventDefault();
      event.dataTransfer.setData("text/plain", String(position));
      event.dataTransfer.effectAllowed = "move";
      card.classList.add("dragging");
    });
    card.addEventListener("dragend", () => card.classList.remove("dragging"));
    card.addEventListener("dragover", (event) => {
      event.preventDefault();
      event.dataTransfer.dropEffect = slot ? "move" : "none";
      if (slot) card.classList.add("drop-target");
    });
    card.addEventListener("dragleave", () => card.classList.remove("drop-target"));
    card.addEventListener("drop", (event) => {
      event.preventDefault();
      card.classList.remove("drop-target");
      const first = Number(event.dataTransfer.getData("text/plain"));
      if (Number.isInteger(first) && first >= 0 && first < 4) void swapSlots(first, position);
    });
    elements.controllerSlots.append(card);
  }
}

function render(status) {
  latestStatus = status;
  const [label, detail] = phaseText(status.phase);
  elements.statusLabel.textContent = label;
  const activeCount = Number(status.activeSessions) || 0;
  elements.statusDetail.textContent = status.phase === "playing"
    ? t(activeCount === 1 ? "activeControllerOne" : "activeControllerMany")
        .replace("{count}", String(activeCount))
    : detail;
  elements.desktopName.textContent = status.desktopName;
  elements.statusDot.className = `status-dot ${status.phase}`;
  elements.pairingCode.textContent = status.pairingCode || "•••• — •••• — ••••";
  elements.pairTimer.textContent = formatTimer(status.pairingExpiresInSeconds);
  elements.errorPanel.hidden = !status.error;
  elements.errorText.textContent = status.error || "";
  const lowBattery = Number(status.lowBatteryPercentage);
  elements.batteryPanel.hidden = status.lowBatteryPercentage == null || !Number.isFinite(lowBattery);
  if (!elements.batteryPanel.hidden) {
    const warning = t("lowBattery").replace("{percent}", String(lowBattery));
    if (elements.batteryText.textContent !== warning) elements.batteryText.textContent = warning;
  }
  renderDevices(status.trustedDevices || []);
  renderSlots(status.controllerSlots || []);
  if (status.bluetoothSessions > 0) {
    elements.controllerNote.textContent = `${t("controllerNote")} ${t("controllerNoBluetooth").replace("{count}", status.bluetoothSessions)}`;
  } else {
    elements.controllerNote.textContent = t("controllerNote");
  }
}

async function refresh() {
  try {
    render(await invoke("desktop_status"));
  } catch (error) {
    render({ phase: "error", desktopName: "BridgePad Desktop", pairingCode: "", pairingExpiresInSeconds: 0, trustedDevices: [], error: String(error) });
  }
}

elements.languageButton.addEventListener("click", () => {
  language = language === "pt" ? "en" : "pt";
  localStorage.setItem("bridgepad-language", language);
  applyLanguage();
});

elements.newCodeButton.addEventListener("click", async () => {
  elements.newCodeButton.disabled = true;
  try {
    await invoke("new_pairing_code");
    await refresh();
  } finally {
    elements.newCodeButton.disabled = false;
  }
});

elements.copyCodeButton.addEventListener("click", async () => {
  if (!latestStatus?.pairingCode) return;
  try {
    await navigator.clipboard.writeText(latestStatus.pairingCode.replaceAll("-", ""));
    elements.copyHint.textContent = t("copied");
    setTimeout(() => { elements.copyHint.textContent = t("copyHint"); }, 1300);
  } catch (_) {
    // The code remains selectable and visible when clipboard access is unavailable.
  }
});

applyLanguage();
refresh();
setInterval(refresh, 1000);
