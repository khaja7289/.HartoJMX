const form = document.querySelector("#convert-form");
const input = document.querySelector("#har-files");
const dropZone = document.querySelector("#drop-zone");
const fileList = document.querySelector("#file-list");
const queueShell = document.querySelector("#queue-shell");
const queueCount = document.querySelector("#queue-count");
const convertButton = document.querySelector("#convert-button");
const statusText = document.querySelector("#status");
const statusWrap = document.querySelector(".status-wrap");
const correlationResults = document.querySelector("#correlation-results");
const correlationList = document.querySelector("#correlation-list");
const selectAllCheckbox = document.querySelector("#select-all-correlations");
const scenarioSelect = document.querySelector("#scenario");
const scenarioDescription = document.querySelector("#scenario-description");
const scenarioEnabled = document.querySelector("#scenario-enabled");
const scenarioGrid = document.querySelector(".scenario-grid");
const pluginNote = document.querySelector("#plugin-note");
const correlationActions = document.querySelector("#correlation-actions");
let selectedFiles = [];
let analyzedCandidates = null;

const naturalCompare = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });
const scenarioDescriptions = {
  load: "Ramp to peak users, hold, then ramp down. Execute time includes both ramps.",
  scalability: "Add users in four gradual cohorts across ramp-up, hold the full peak, then ramp down.",
  spike: "Hold a 10% baseline, add the remaining users for a short spike near the end, then ramp down.",
  endurance: "Hold the selected peak for the full execute time; ramp-up and ramp-down are added before and after the hold.",
  stress: "Add users in up to four abrupt steps from the start through the end of ramp-up, hold peak, then ramp down."
};

scenarioSelect.addEventListener("change", () => {
  scenarioDescription.textContent = scenarioDescriptions[scenarioSelect.value];
});

scenarioEnabled.addEventListener("change", () => {
  scenarioGrid.classList.toggle("is-disabled", !scenarioEnabled.checked);
  scenarioGrid.querySelectorAll("input, select").forEach(control => { control.disabled = !scenarioEnabled.checked; });
  scenarioDescription.hidden = !scenarioEnabled.checked;
  pluginNote.hidden = !scenarioEnabled.checked;
});
scenarioEnabled.dispatchEvent(new Event("change"));

if (selectAllCheckbox) {
  selectAllCheckbox.addEventListener("change", () => {
    const checkboxes = correlationList.querySelectorAll(".candidate-choice");
    checkboxes.forEach(cb => { cb.checked = selectAllCheckbox.checked; });
  });
}

function setStatus(message, state = "") {
  statusText.textContent = message;
  statusWrap.classList.remove("is-error", "is-working");
  if (state) statusWrap.classList.add(`is-${state}`);
}

function showCorrelationCandidates(candidates) {
  correlationList.replaceChildren();
  correlationResults.hidden = false;
  correlationActions.hidden = candidates.length === 0;
  analyzedCandidates = candidates;
  if (!candidates.length) {
    const empty = document.createElement("li");
    empty.className = "correlation-empty";
    empty.textContent = "No auto-correlation token candidates detected. Converting JMX directly without correlation changes.";
    correlationList.append(empty);
    return;
  }
  if (selectAllCheckbox) {
    selectAllCheckbox.checked = true;
  }
  for (const candidate of candidates) {
    const item = document.createElement("li");
    item.className = "correlation-item";

    const choice = document.createElement("input");
    choice.type = "checkbox";
    choice.className = "candidate-choice";
    choice.value = candidate.id;
    choice.checked = true;
    choice.setAttribute("aria-label", `Apply auto-correlation ${candidate.variable}`);
    choice.addEventListener("change", () => {
      const all = correlationList.querySelectorAll(".candidate-choice");
      const checked = correlationList.querySelectorAll(".candidate-choice:checked");
      if (selectAllCheckbox) {
        selectAllCheckbox.checked = all.length === checked.length;
      }
    });

    const infoWrap = document.createElement("div");
    infoWrap.className = "correlation-info";

    const variable = document.createElement("span");
    variable.className = "correlation-name";
    variable.textContent = candidate.variable;

    const badge = document.createElement("span");
    const isJson = candidate.extractorType === "JSON";
    badge.className = `extractor-badge ${isJson ? "json" : "regex"}`;
    badge.textContent = isJson && candidate.jsonPath ? `JSON Extractor: ${candidate.jsonPath}` : `Regex Extractor`;
    variable.append(badge);

    const route = document.createElement("span");
    route.className = "correlation-route";
    route.textContent = `${candidate.sourceFile}: ${candidate.sourceUrl} → ${candidate.targetUrl}`;

    infoWrap.append(variable, route);

    const value = document.createElement("span");
    value.className = "correlation-value";
    value.textContent = `Value: ${candidate.value}`;

    item.append(choice, infoWrap, value);
    correlationList.append(item);
  }
}

function formatSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function renderQueue() {
  analyzedCandidates = null;
  correlationResults.hidden = true;
  correlationActions.hidden = true;
  fileList.replaceChildren();
  queueShell.hidden = selectedFiles.length === 0;
  queueCount.textContent = `${String(selectedFiles.length).padStart(2, "0")} TRANSACTION${selectedFiles.length === 1 ? "" : "S"}`;
  convertButton.disabled = selectedFiles.length === 0;

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
    setStatus(selectedFiles.length ? "Transaction order is ready. Click Analyze HARs." : "Choose at least one HAR file.");
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

function buildRequestData(correlationIds = []) {
  const data = new FormData();
  selectedFiles.forEach(file => data.append("files", file, file.name));
  if (correlationIds !== null) {
    data.append("filterInclude", document.querySelector("#filter-include").value);
    data.append("filterExclude", document.querySelector("#filter-exclude").value);
    data.append("removeHeaders", document.querySelector("#remove-headers").value);
    data.append("scenarioEnabled", scenarioEnabled.checked);
    data.append("scenario", scenarioSelect.value);
    data.append("users", document.querySelector("#users").value);
    data.append("executeTime", document.querySelector("#execute-time").value);
    data.append("rampUp", document.querySelector("#ramp-up").value);
    data.append("rampDown", document.querySelector("#ramp-down").value);
    data.append("responseMessageOne", document.querySelector("#response-message-one").value);
    data.append("responseMessageTwo", document.querySelector("#response-message-two").value);
    correlationIds.forEach(id => data.append("correlationIds", id));
  }
  return data;
}

function validateInputs() {
  if (!selectedFiles.length) {
    setStatus("Choose at least one HAR file.", "error");
    return false;
  }
  const totalBytes = selectedFiles.reduce((total, file) => total + file.size, 0);
  if (selectedFiles.some(file => file.size > 25 * 1024 * 1024) || totalBytes > 100 * 1024 * 1024) {
    setStatus("Files must be under 25 MB each and 100 MB combined.", "error");
    return false;
  }
  if (scenarioEnabled.checked) {
    const users = Number(document.querySelector("#users").value);
    const executeTime = Number(document.querySelector("#execute-time").value);
    const rampUp = Number(document.querySelector("#ramp-up").value);
    const rampDown = Number(document.querySelector("#ramp-down").value);
    if (!Number.isInteger(users) || users < 1 || !Number.isInteger(executeTime) || executeTime < 1 ||
        !Number.isInteger(rampUp) || rampUp < 0 || !Number.isInteger(rampDown) || rampDown < 0 ||
        (scenarioSelect.value !== "endurance" && rampUp + rampDown > executeTime)) {
      setStatus("Enter valid whole seconds; ramp-up plus ramp-down must fit within execute time except for Endurance.", "error");
      return false;
    }
  }
  return true;
}

async function analyzeHarFiles() {
  convertButton.disabled = true;
  setStatus("Scanning HARs for auto-correlated components…", "working");
  try {
    const response = await fetch("/api/analyze", { method: "POST", body: buildRequestData(null) });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      throw new Error(result.message || `HAR analysis failed (${response.status}).`);
    }
    const candidates = await response.json();
    showCorrelationCandidates(candidates);
    if (candidates.length === 0) {
      await convertJmx([]);
    } else {
      correlationResults.scrollIntoView({ behavior: "smooth", block: "nearest" });
      setStatus("Review components: agree to auto-correlate, or choose ignore for manual correlation.");
    }
  } catch (error) {
    setStatus(error.message || "HAR analysis failed.", "error");
  } finally {
    convertButton.disabled = selectedFiles.length === 0;
  }
}

async function convertJmx(correlationIds) {
  convertButton.disabled = true;
  if (document.querySelector("#generate-with-correlations")) {
    document.querySelector("#generate-with-correlations").disabled = true;
  }
  if (document.querySelector("#generate-without-correlations")) {
    document.querySelector("#generate-without-correlations").disabled = true;
  }
  setStatus(correlationIds.length ? "Generating JMX with auto-correlations…" : "Generating JMX (manual correlation mode)…", "working");
  try {
    const response = await fetch("/api/convert", { method: "POST", body: buildRequestData(correlationIds) });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      throw new Error(result.message || `Conversion failed (${response.status}).`);
    }
    const file = await response.blob();
    const downloadUrl = URL.createObjectURL(file);
    const anchor = document.createElement("a");
    anchor.href = downloadUrl;
    anchor.download = "har-convertor-script.jmx";
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
    setStatus("JMX generated and downloaded successfully (har-convertor-script.jmx).");
  } catch (error) {
    setStatus(error.message || "Conversion failed. Check the HAR files and try again.", "error");
  } finally {
    convertButton.disabled = selectedFiles.length === 0;
    if (document.querySelector("#generate-with-correlations")) {
      document.querySelector("#generate-with-correlations").disabled = false;
    }
    if (document.querySelector("#generate-without-correlations")) {
      document.querySelector("#generate-without-correlations").disabled = false;
    }
  }
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  if (!validateInputs()) return;
  await analyzeHarFiles();
});

document.querySelector("#generate-with-correlations").addEventListener("click", () => {
  const ids = Array.from(document.querySelectorAll(".candidate-choice:checked"), checkbox => checkbox.value);
  if (ids.length === 0) {
    setStatus("Select at least one component to auto-correlate, or choose 'Ignore / Manual Correlation'.", "error");
    return;
  }
  convertJmx(ids);
});

document.querySelector("#generate-without-correlations").addEventListener("click", () => convertJmx([]));