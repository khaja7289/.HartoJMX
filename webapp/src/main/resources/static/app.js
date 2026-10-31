// HAR to JMeter Studio - Streamlined AI Client
const form = document.querySelector("#convert-form");
const input = document.querySelector("#har-files");
const dropZone = document.querySelector("#drop-zone");
const fileList = document.querySelector("#file-list");
const queueShell = document.querySelector("#queue-shell");
const queueCount = document.querySelector("#queue-count");
const btnStandardConvert = document.querySelector("#btn-standard-convert");
const btnAiConvert = document.querySelector("#btn-ai-convert");
const statusText = document.querySelector("#status");
const statusWrap = document.querySelector(".status-wrap");
const aiInsightsPanel = document.querySelector("#ai-insights-panel");
const aiInsightsStatus = document.querySelector("#ai-insights-status");
const aiStatsPill = document.querySelector("#ai-stats-pill");
const correlationList = document.querySelector("#correlation-list");

let selectedFiles = [];
let serverApiKey = "";
const naturalCompare = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });

// Load server config (detect if GEMINI_API_KEY is configured in Docker / environment)
async function fetchServerConfig() {
  try {
    const res = await fetch("/api/config");
    if (res.ok) {
      const data = await res.json();
      if (data.geminiKey) {
        serverApiKey = data.geminiKey;
        console.log("Server-configured Gemini API Key detected.");
      }
    }
  } catch (err) {
    console.debug("Config check completed:", err);
  }
}
fetchServerConfig();

function setStatus(message, state = "") {
  statusText.textContent = message;
  statusWrap.classList.remove("is-error", "is-working");
  if (state) statusWrap.classList.add(`is-${state}`);
}

function formatSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function renderQueue() {
  if (aiInsightsPanel) aiInsightsPanel.hidden = true;
  fileList.replaceChildren();
  queueShell.hidden = selectedFiles.length === 0;
  queueCount.textContent = `${String(selectedFiles.length).padStart(2, "0")} TRANSACTION${selectedFiles.length === 1 ? "" : "S"}`;
  
  const hasFiles = selectedFiles.length > 0;
  if (btnStandardConvert) btnStandardConvert.disabled = !hasFiles;
  if (btnAiConvert) btnAiConvert.disabled = !hasFiles;

  selectedFiles.forEach((file, index) => {
    const row = document.createElement("li");
    row.className = "file-row";

    const number = document.createElement("span");
    number.className = "file-number";
    number.textContent = `T${String(index + 1).padStart(2, "0")}`;

    const info = document.createElement("span");
    info.className = "file-info";
    const name = document.createElement("span");
    name.className = "file-name";
    name.textContent = file.name;
    const size = document.createElement("span");
    size.className = "file-size";
    size.textContent = formatSize(file.size);
    info.append(name, size);

    const actions = document.createElement("span");
    actions.className = "row-actions";
    actions.append(makeMoveButton("Up", index, -1), makeMoveButton("Down", index, 1));

    const remove = document.createElement("button");
    remove.className = "remove-file";
    remove.type = "button";
    remove.setAttribute("aria-label", `Remove ${file.name}`);
    remove.title = "Remove file";
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      selectedFiles.splice(index, 1);
      renderQueue();
      setStatus("Ready when you are.");
    });

    row.append(number, info, actions, remove);
    fileList.append(row);
  });
}

function makeMoveButton(label, index, direction) {
  const button = document.createElement("button");
  button.className = "row-action";
  button.type = "button";
  button.textContent = label;
  button.setAttribute("aria-label", `${label} transaction ${index + 1}`);
  button.disabled = index + direction < 0 || index + direction >= selectedFiles.length;
  button.addEventListener("click", () => {
    const destination = index + direction;
    [selectedFiles[index], selectedFiles[destination]] = [selectedFiles[destination], selectedFiles[index]];
    renderQueue();
  });
  return button;
}

function addFiles(fileCollection) {
  const incoming = Array.from(fileCollection).filter(file => file.name.toLowerCase().endsWith(".har"));
  const known = new Set(selectedFiles.map(file => `${file.name}:${file.size}:${file.lastModified}`));
  for (const file of incoming) {
    const key = `${file.name}:${file.size}:${file.lastModified}`;
    if (!known.has(key)) selectedFiles.push(file);
    known.add(key);
  }
  selectedFiles.sort((left, right) => naturalCompare.compare(left.name, right.name));
  renderQueue();
  if (incoming.length !== fileCollection.length) {
    setStatus("Only .har files can be added.", "error");
  } else {
    setStatus(selectedFiles.length ? "Files ready. Choose 'Convert to JMX' or 'AI-Powered JMX'." : "Choose at least one HAR file.");
  }
}

input.addEventListener("change", event => {
  addFiles(event.target.files);
  input.value = "";
});

document.querySelector("#clear-files").addEventListener("click", () => {
  selectedFiles = [];
  renderQueue();
  setStatus("Ready when you are.");
});

for (const eventName of ["dragenter", "dragover"]) {
  dropZone.addEventListener(eventName, event => {
    event.preventDefault();
    dropZone.classList.add("is-over");
  });
}
for (const eventName of ["dragleave", "drop"]) {
  dropZone.addEventListener(eventName, event => {
    event.preventDefault();
    dropZone.classList.remove("is-over");
  });
}
dropZone.addEventListener("drop", event => addFiles(event.dataTransfer.files));

function buildFormData(correlationIds = []) {
  const data = new FormData();
  selectedFiles.forEach(file => data.append("files", file, file.name));
  if (correlationIds && correlationIds.length) {
    correlationIds.forEach(id => data.append("correlationIds", id));
  }
  return data;
}

function validateFiles() {
  if (!selectedFiles.length) {
    setStatus("Please choose at least one .har file to convert.", "error");
    return false;
  }
  const totalBytes = selectedFiles.reduce((total, file) => total + file.size, 0);
  if (selectedFiles.some(file => file.size > 25 * 1024 * 1024) || totalBytes > 100 * 1024 * 1024) {
    setStatus("Files must be under 25 MB each and 100 MB combined.", "error");
    return false;
  }
  return true;
}

// Download JMX blob
async function executeConversion(correlationIds = [], filename = "har-convertor-script.jmx") {
  const response = await fetch("/api/convert", { method: "POST", body: buildFormData(correlationIds) });
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    throw new Error(result.message || `Conversion failed (${response.status}).`);
  }
  const file = await response.blob();
  const downloadUrl = URL.createObjectURL(file);
  const anchor = document.createElement("a");
  anchor.href = downloadUrl;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
}

// 1. STANDARD CONVERSION
btnStandardConvert.addEventListener("click", async () => {
  if (!validateFiles()) return;
  setBusy(true);
  setStatus("Generating standard JMeter script (.jmx)…", "working");
  try {
    await executeConversion([], "har-convertor-script.jmx");
    setStatus("Standard JMX downloaded successfully (har-convertor-script.jmx).");
  } catch (err) {
    setStatus(err.message || "Conversion failed.", "error");
  } finally {
    setBusy(false);
  }
});

// 2. AI-POWERED CONVERSION
btnAiConvert.addEventListener("click", async () => {
  if (!validateFiles()) return;
  setBusy(true);
  setStatus("✨ Step 1/3: Analyzing HAR traffic for dynamic candidate tokens…", "working");

  try {
    // 1. Scan HAR locally via backend (0 tokens)
    const analyzeRes = await fetch("/api/analyze", { method: "POST", body: buildFormData([]) });
    if (!analyzeRes.ok) {
      throw new Error("Could not analyze HAR files for correlation.");
    }
    const candidates = await analyzeRes.json();

    if (!candidates || candidates.length === 0) {
      setStatus("No dynamic tokens found. Generating clean JMX…", "working");
      await executeConversion([], "har-convertor-script.jmx");
      setStatus("No dynamic tokens detected. Downloaded standard JMX successfully.");
      return;
    }

    // 2. Consult Gemini AI with compact micro-payload (~300 tokens)
    setStatus("✨ Step 2/3: Consulting Gemini AI (~300 tokens) to classify tokens and filter noise…", "working");
    const aiResults = await evaluateWithAi(candidates);

    // 3. Extract recommended correlation IDs (CRITICAL + PARAMETER, ignore NOISE)
    const recommendedIds = aiResults
      .filter(r => r.recommend)
      .map(r => r.id);

    // Render AI insights
    renderAiInsights(candidates, aiResults);

    // 4. Generate JMX with auto-correlations
    setStatus(`✨ Step 3/3: Auto-correlating ${recommendedIds.length} tokens into JMeter script…`, "working");
    await executeConversion(recommendedIds, "har-convertor-script-ai.jmx");

    const criticalCount = aiResults.filter(r => r.classification === "CRITICAL").length;
    const paramCount = aiResults.filter(r => r.classification === "PARAMETER").length;
    const noiseCount = aiResults.filter(r => r.classification === "NOISE").length;

    setStatus(`✨ AI Correlation Complete! Downloaded har-convertor-script-ai.jmx with ${criticalCount + paramCount} correlated variables (${noiseCount} noise tokens filtered).`);
  } catch (err) {
    console.error("AI conversion failed:", err);
    setStatus(err.message || "AI conversion failed.", "error");
  } finally {
    setBusy(false);
  }
});

function setBusy(busy) {
  btnStandardConvert.disabled = busy || selectedFiles.length === 0;
  btnAiConvert.disabled = busy || selectedFiles.length === 0;
}

// Low-token evaluation: Calls Gemini API if key exists, otherwise applies smart local heuristic
async function evaluateWithAi(candidates) {
  const apiKey = serverApiKey || localStorage.getItem("gemini_api_key") || "";

  if (apiKey) {
    try {
      return await callGeminiAdvisor(candidates, apiKey);
    } catch (err) {
      console.warn("Gemini remote call failed, using built-in heuristic:", err.message);
    }
  }

  // Fallback to built-in smart heuristic (0 tokens, instant)
  return candidates.map(classifyCandidateHeuristic);
}

// Compact Gemini Flash Call (~300 tokens total)
async function callGeminiAdvisor(candidates, apiKey) {
  const microCandidates = candidates.map(c => ({
    id: c.id,
    variable: c.variable,
    key: c.key,
    value: (c.value || "").substring(0, 45),
    targetUrl: c.targetUrl
  }));

  const prompt = `You evaluate candidate correlation tokens for JMeter.
Classify each token into: "CRITICAL" (Auth/Session/CSRF), "PARAMETER" (Business IDs), "NOISE" (Analytics/tracking/timestamps).
Return ONLY a valid JSON array of objects with keys: "id" (string), "classification" ("CRITICAL" | "PARAMETER" | "NOISE"), "recommend" (boolean: false for NOISE, true for CRITICAL/PARAMETER), and "reason" (short 1-sentence rationale).
Candidates:
${JSON.stringify(microCandidates)}`;

  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { responseMimeType: "application/json" }
    })
  });

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error?.message || `Gemini API returned ${res.status}`);
  }

  const data = await res.json();
  const text = data.candidates?.[0]?.content?.parts?.[0]?.text;
  const parsed = JSON.parse(text);

  return parsed.map(item => ({
    id: item.id,
    classification: item.classification || "PARAMETER",
    badgeClass: (item.classification || "PARAMETER").toLowerCase(),
    badgeText: item.classification || "PARAMETER",
    recommend: typeof item.recommend === "boolean" ? item.recommend : item.classification !== "NOISE",
    reason: item.reason || "Verified by Gemini AI."
  }));
}

// Built-in rule classifier for tokens (0 tokens consumed)
function classifyCandidateHeuristic(candidate) {
  const key = (candidate.key || "").toLowerCase();
  const varName = (candidate.variable || "").toLowerCase();
  const val = candidate.value || "";
  const target = (candidate.targetUrl || "").toLowerCase();

  // Noise / Analytics / Pixels
  const noiseRegex = /(_ga|_gid|analytics|telemetry|track|pixel|utm_|timestamp|_time|cache|bust|_nonce|doubleclick|clarity|hotjar|newrelic|datadog|sentry)/i;
  const isAnalyticsHost = /google-analytics\.com|googletagmanager\.com|doubleclick\.net|clarity\.ms|hotjar\.com|sentry\.io/i.test(target);
  if (noiseRegex.test(key) || noiseRegex.test(varName) || isAnalyticsHost) {
    return {
      id: candidate.id,
      classification: "NOISE",
      badgeClass: "noise",
      badgeText: "NOISE",
      recommend: false,
      reason: "Analytics/telemetry tracking token. Filtered out to keep load test script clean."
    };
  }

  // Critical: Auth / Session / CSRF / Tokens
  const authRegex = /(token|jwt|bearer|auth|session|jsession|sid|csrf|xsrf|accesstoken|refreshtoken|id_token|ticket|saml|oauth)/i;
  const isJwt = val.startsWith("ey") && val.includes(".");
  if (authRegex.test(key) || authRegex.test(varName) || isJwt) {
    return {
      id: candidate.id,
      classification: "CRITICAL",
      badgeClass: "critical",
      badgeText: "CRITICAL",
      recommend: true,
      reason: "Essential authentication, session, or security token. Downstream requests require this."
    };
  }

  // Business Parameters (Order ID, Cart ID, User ID)
  return {
    id: candidate.id,
    classification: "PARAMETER",
    badgeClass: "parameter",
    badgeText: "PARAMETER",
    recommend: true,
    reason: "Dynamic business identifier reused across requests."
  };
}

// Display AI Insights list
function renderAiInsights(candidates, aiResults) {
  if (!aiInsightsPanel) return;
  aiInsightsPanel.hidden = false;
  correlationList.replaceChildren();

  const resultsMap = new Map(aiResults.map(r => [r.id, r]));
  let criticalCount = 0;
  let paramCount = 0;
  let noiseCount = 0;

  candidates.forEach(candidate => {
    const aiRes = resultsMap.get(candidate.id) || {
      classification: "PARAMETER",
      badgeClass: "parameter",
      badgeText: "PARAMETER",
      recommend: true,
      reason: "Evaluated by AI."
    };

    if (aiRes.classification === "CRITICAL") criticalCount++;
    else if (aiRes.classification === "PARAMETER") paramCount++;
    else if (aiRes.classification === "NOISE") noiseCount++;

    const item = document.createElement("li");
    item.className = "correlation-item";

    const indicator = document.createElement("input");
    indicator.type = "checkbox";
    indicator.className = "candidate-choice";
    indicator.checked = aiRes.recommend;
    indicator.disabled = true;

    const infoWrap = document.createElement("div");
    infoWrap.className = "correlation-info";

    const nameRow = document.createElement("div");
    nameRow.className = "correlation-name";
    nameRow.textContent = candidate.variable;

    const badge = document.createElement("span");
    badge.className = `ai-badge ${aiRes.badgeClass}`;
    badge.textContent = aiRes.badgeText;
    nameRow.append(badge);

    const isJson = candidate.extractorType === "JSON";
    const extractorBadge = document.createElement("span");
    extractorBadge.className = `extractor-badge ${isJson ? "json" : "regex"}`;
    extractorBadge.textContent = isJson && candidate.jsonPath ? `JSON: ${candidate.jsonPath}` : "Regex";
    nameRow.append(extractorBadge);

    const reasonEl = document.createElement("span");
    reasonEl.className = "ai-reason";
    reasonEl.textContent = aiRes.reason;

    const route = document.createElement("span");
    route.className = "correlation-route";
    route.textContent = `${candidate.sourceUrl} → ${candidate.targetUrl}`;

    infoWrap.append(nameRow, reasonEl, route);

    const valueEl = document.createElement("span");
    valueEl.className = "correlation-value";
    valueEl.textContent = `Value: ${candidate.value}`;

    item.append(indicator, infoWrap, valueEl);
    correlationList.append(item);
  });

  if (aiStatsPill) {
    aiStatsPill.textContent = `${criticalCount} Critical · ${paramCount} Parameter · ${noiseCount} Noise Filtered`;
  }
  if (aiInsightsStatus) {
    aiInsightsStatus.innerHTML = `✨ <strong>AI Analysis Complete:</strong> Automatically correlated <strong>${criticalCount + paramCount} dynamic variables</strong> and ignored <strong>${noiseCount} analytics/tracking tokens</strong>.`;
  }
}