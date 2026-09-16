const invoke = window.__TAURI__?.core?.invoke || (async (command) => {
  if (command === "desktop_status") {
    return {
      phase: "ready",
      desktopName: "BridgePad Desktop",
      pairingCode: "1842-6950-3714",
      pairingExpiresInSeconds: 527,
      trustedDevices: [],
      error: null,
    };
  }
  return true;
});

const translations = {
  en: {
    languageButton: "PT",
    ready: "Ready to connect",
    connected: "Phone connected",
    playing: "Controller active",
    error: "Could not start",
    readyDetail: "Waiting for your phone on this network.",
    connectedDetail: "Secure connection established. Waiting for a game session.",
    playingDetail: "BridgePad is sending controller input to this computer.",
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
    languageButton: "EN",
    ready: "Pronto para conectar",
    connected: "Celular conectado",
    playing: "Controle ativo",
    error: "Não foi possível iniciar",
    readyDetail: "Aguardando seu celular nesta rede.",
    connectedDetail: "Conexão segura estabelecida. Aguardando uma sessão de jogo.",
    playingDetail: "O BridgePad está enviando os comandos para este computador.",
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

const elements = Object.fromEntries(
  [
    "languageButton", "statusDot", "statusLabel", "desktopName", "statusDetail", "errorPanel",
    "errorTitle", "errorText", "pairEyebrow", "pairTitle", "pairTimer", "pairingCode",
    "copyCodeButton", "copyHint", "pairHelp", "newCodeButton", "devicesEyebrow", "devicesTitle",
    "deviceCount", "deviceList", "emptyDevices", "emptyTitle", "emptyText", "trayHint", "firewallNote",
  ].map((id) => [id, document.getElementById(id)]),
);

function t(key) { return translations[language][key]; }

function applyLanguage() {
  document.documentElement.lang = language === "pt" ? "pt-BR" : "en";
  for (const key of ["languageButton", "errorTitle", "pairEyebrow", "pairTitle", "copyHint", "pairHelp", "devicesEyebrow", "devicesTitle", "emptyTitle", "emptyText", "trayHint", "firewallNote"]) {
    elements[key].textContent = t(key);
  }
  elements.newCodeButton.textContent = t("newCode");
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

function render(status) {
  latestStatus = status;
  const [label, detail] = phaseText(status.phase);
  elements.statusLabel.textContent = label;
  elements.statusDetail.textContent = detail;
  elements.desktopName.textContent = status.desktopName;
  elements.statusDot.className = `status-dot ${status.phase}`;
  elements.pairingCode.textContent = status.pairingCode || "•••• — •••• — ••••";
  elements.pairTimer.textContent = formatTimer(status.pairingExpiresInSeconds);
  elements.errorPanel.hidden = !status.error;
  elements.errorText.textContent = status.error || "";
  renderDevices(status.trustedDevices || []);
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
