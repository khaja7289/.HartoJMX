package io.github.vdaburon.harweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vdaburon.jmeter.har.HarForJMeter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HarConversionService {
    private static final long MAX_TOTAL_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final int JACKSON_STRING_MAX = 20_000_000;
    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_PREVIEW_CANDIDATES = 10;
    private static final int MAX_INDEXED_REQUEST_TOKENS = 50_000;
    private static final int MAX_TOKEN_REQUEST_OCCURRENCES = 16;
    private static final long MAX_RESPONSE_TEXT_BYTES = 2L * 1024L * 1024L;
    private static final Pattern CANDIDATE_TOKEN = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z0-9._~-]{8,}(?![A-Za-z0-9])|(?<![0-9])[0-9]{6,}(?![0-9])");
    private final ObjectMapper objectMapper;
    private final Semaphore conversionSlots = new Semaphore(2);

    public HarConversionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, String>> analyze(List<MultipartFile> uploads) throws Exception {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one HAR file.");
        }
        Path workDirectory = Files.createTempDirectory("har-convertor-analyze-");
        try {
            List<UploadedHar> harFiles = saveAndValidate(uploads, workDirectory);
            List<HarExchange> exchanges = collectExchanges(harFiles);
            return findCorrelationCandidates(harFiles, exchanges);
        } finally {
            deleteTree(workDirectory);
        }
    }

    public ConversionArtifact convert(List<MultipartFile> uploads, String filterInclude, String filterExclude,
                                      String removeHeaders, boolean scenarioEnabled, String scenario,
                                      int users, int executeTime, int rampUp, int rampDown,
                                      List<String> correlationIds,
                                      String responseMessageOne, String responseMessageTwo) throws Exception {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one HAR file.");
        }
        if (scenarioEnabled) {
            validateSchedule(scenario, users, executeTime, rampUp, rampDown);
        }
        if (!conversionSlots.tryAcquire()) {
            throw new ConversionBusyException("The converter is busy. Please try again shortly.");
        }

        Path workDirectory = null;
        boolean keepDirectory = false;
        try {
            workDirectory = Files.createTempDirectory("har-convertor-web-");
            List<UploadedHar> harFiles = saveAndValidate(uploads, workDirectory);
            Path mergedHar = workDirectory.resolve("merged.har");
            Path jmxFile = workDirectory.resolve("har-convertor-script.jmx");
            List<String> transactionNames = mergeHarFiles(harFiles, mergedHar);
            List<HarExchange> exchanges = collectExchanges(harFiles);
            List<Map<String, String>> correlationCandidates = findCorrelationCandidates(harFiles, exchanges);

            HarForJMeter.generateJmxAndRecord(mergedHar.toString(), JACKSON_STRING_MAX, jmxFile.toString(),
                    0L, false, true, true, normalizeFilter(filterInclude), normalizeFilter(filterExclude),
                    "", 1, 1, "", false, false, removeHeaders == null ? "" : removeHeaders.trim());

            finalizeJmx(jmxFile, transactionNames, scenarioEnabled, scenario, users, executeTime, rampUp, rampDown,
                    correlationCandidates, correlationIds, exchanges, responseMessageOne, responseMessageTwo);
            keepDirectory = true;
            return new ConversionArtifact(workDirectory, jmxFile, previewCandidates(correlationCandidates));
        } finally {
            conversionSlots.release();
            if (!keepDirectory) {
                deleteTree(workDirectory);
            }
        }
    }

    public ConversionArtifact convert(List<MultipartFile> uploads, String filterInclude, String filterExclude,
                                      String removeHeaders, String scenario, int users, int executeTime,
                                      int rampUp, int rampDown) throws Exception {
        return convert(uploads, filterInclude, filterExclude, removeHeaders, true, scenario,
                users, executeTime, rampUp, rampDown, Collections.emptyList(), "", "");
    }

    public ConversionArtifact convert(List<MultipartFile> uploads, String filterInclude, String filterExclude,
                                      String removeHeaders) throws Exception {
        return convert(uploads, filterInclude, filterExclude, removeHeaders, false, "load",
                100, 600, 60, 30, Collections.emptyList(), "", "");
    }

    private List<HarExchange> collectExchanges(List<UploadedHar> harFiles) {
        List<HarExchange> exchanges = new ArrayList<>();
        long responseTextBytes = 0;
        for (UploadedHar harFile : harFiles) {
            JsonNode entries = harFile.root.path("log").path("entries");
            for (JsonNode entry : entries) {
                JsonNode request = entry.path("request");
                JsonNode response = entry.path("response");
                JsonNode content = response.path("content");
                String mimeType = content.path("mimeType").asText("");
                String responseText = content.path("text").isTextual() ? content.path("text").asText() : "";
                if ("base64".equalsIgnoreCase(content.path("encoding").asText())) {
                    responseText = "";
                }
                responseTextBytes += responseText.length();
                if (responseTextBytes > MAX_RESPONSE_TEXT_BYTES) {
                    responseText = "";
                }
                exchanges.add(new HarExchange(harFile.originalName, request.path("url").asText(""), mimeType, responseText));
            }
        }
        return exchanges;
    }

    private List<Map<String, String>> findCorrelationCandidates(List<UploadedHar> harFiles, List<HarExchange> exchanges) throws IOException {
        Map<String, List<Integer>> requestTokenIndexes = new LinkedHashMap<>();
        int indexedTokens = 0;
        int exchangeIndex = 0;
        for (UploadedHar harFile : harFiles) {
            JsonNode entries = harFile.root.path("log").path("entries");
            for (JsonNode entry : entries) {
                JsonNode request = entry.path("request");
                String requestJson = objectMapper.writeValueAsString(request);
                if (indexedTokens < MAX_INDEXED_REQUEST_TOKENS) {
                    Matcher requestTokens = CANDIDATE_TOKEN.matcher(requestJson);
                    while (requestTokens.find() && indexedTokens < MAX_INDEXED_REQUEST_TOKENS) {
                        String value = requestTokens.group();
                        if (value.length() > 256) {
                            continue;
                        }
                        List<Integer> occurrences = requestTokenIndexes.computeIfAbsent(value, unused -> new ArrayList<>());
                        if (occurrences.size() < MAX_TOKEN_REQUEST_OCCURRENCES &&
                            (occurrences.isEmpty() || occurrences.get(occurrences.size() - 1) != exchangeIndex)) {
                            occurrences.add(exchangeIndex);
                            indexedTokens++;
                        }
                    }
                }
                exchangeIndex++;
            }
        }

        List<Map<String, String>> candidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Map<String, Integer> varNameCounts = new LinkedHashMap<>();

        for (int sourceIndex = 0; sourceIndex < exchanges.size() && candidates.size() < MAX_CANDIDATES; sourceIndex++) {
            HarExchange source = exchanges.get(sourceIndex);
            if (source.responseText.isEmpty()) {
                continue;
            }
            for (TokenCandidate token : extractTokens(source.mimeType, source.responseText)) {
                if (candidates.size() >= MAX_CANDIDATES || seen.contains(token.value)) {
                    continue;
                }
                List<Integer> requestOccurrences = requestTokenIndexes.get(token.value);
                Integer targetIndex = firstIndexAfter(requestOccurrences, sourceIndex);
                if (targetIndex != null) {
                    HarExchange target = exchanges.get(targetIndex);
                    Map<String, String> candidate = new LinkedHashMap<>();

                    String baseVar = determineTechnologyVariableName(token.key, token.value);
                    int count = varNameCounts.getOrDefault(baseVar, 0) + 1;
                    varNameCounts.put(baseVar, count);
                    String varName = count == 1 ? baseVar : baseVar + "_" + count;

                    String id = "corr_" + (sourceIndex + 1) + "_" + safeVariable(token.key);
                    candidate.put("id", id);
                    candidate.put("variable", varName);
                    candidate.put("key", token.key);
                    candidate.put("sourceIndex", Integer.toString(sourceIndex));
                    candidate.put("sourceFile", source.fileName);
                    candidate.put("sourceUrl", source.url);
                    candidate.put("targetUrl", target.url);
                    candidate.put("value", token.value);
                    candidate.put("extractorType", token.extractorType);
                    if (token.jsonPath != null) {
                        candidate.put("jsonPath", token.jsonPath);
                    }
                    candidates.add(candidate);
                    seen.add(token.value);
                }
            }
        }
        return candidates;
    }

    private String determineTechnologyVariableName(String key, String value) {
        String lowerKey = key != null ? key.toLowerCase().replaceAll("[^a-z0-9]", "") : "";
        String prefix = "C_";

        if (lowerKey.contains("bearer") || lowerKey.contains("accesstoken") || lowerKey.equals("idtoken")
                || lowerKey.equals("jwt") || (value != null && value.startsWith("eyJ") && value.contains("."))) {
            return prefix + "bearerToken";
        }
        if (lowerKey.contains("refresh")) {
            return prefix + "refreshToken";
        }
        if (lowerKey.contains("csrf") || lowerKey.contains("xsrf") || lowerKey.contains("authenticitytoken")
                || lowerKey.contains("anticsrf")) {
            return prefix + "csrfToken";
        }
        if (lowerKey.contains("jsession") || lowerKey.contains("sessionid") || lowerKey.equals("sid") || lowerKey.equals("sessid")) {
            return prefix + "sessionId";
        }
        if (lowerKey.equals("code") || lowerKey.equals("authcode") || lowerKey.equals("authorizationcode")) {
            return prefix + "authCode";
        }
        if (lowerKey.equals("state") || lowerKey.equals("oauthstate")) {
            return prefix + "authState";
        }
        if (lowerKey.contains("viewstate")) {
            return prefix + "viewState";
        }
        if (lowerKey.contains("eventvalidation")) {
            return prefix + "eventValidation";
        }
        if (lowerKey.contains("nonce") || lowerKey.contains("challenge")) {
            return prefix + "nonce";
        }
        if (lowerKey.contains("userid")) {
            return prefix + "userId";
        }
        if (lowerKey.contains("accountid")) {
            return prefix + "accountId";
        }
        if (lowerKey.contains("customerid")) {
            return prefix + "customerId";
        }
        if (lowerKey.contains("token")) {
            return prefix + "token";
        }
        if (lowerKey.contains("auth")) {
            return prefix + "auth";
        }
        if (!lowerKey.isEmpty() && !"value".equals(lowerKey)) {
            return prefix + toCamelCase(key);
        }

        return prefix + "token";
    }

    private String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return "token";
        String[] parts = s.split("[^A-Za-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() == 0) {
                sb.append(p.substring(0, 1).toLowerCase()).append(p.substring(1));
            } else {
                sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1).toLowerCase());
            }
        }
        String res = sb.toString();
        return res.isEmpty() ? "token" : res;
    }

    private Integer firstIndexAfter(List<Integer> indexes, int sourceIndex) {
        if (indexes == null) {
            return null;
        }
        for (Integer index : indexes) {
            if (index > sourceIndex) {
                return index;
            }
        }
        return null;
    }

    private List<TokenCandidate> extractTokens(String mimeType, String responseText) {
        LinkedHashMap<String, TokenCandidate> tokens = new LinkedHashMap<>();
        if (responseText == null || responseText.isEmpty()) {
            return Collections.emptyList();
        }

        boolean isJson = isJsonContent(mimeType, responseText);
        if (isJson) {
            try {
                JsonNode root = objectMapper.readTree(responseText);
                if (root != null) {
                    collectJsonTokensWithPaths(root, "$", "value", tokens);
                }
            } catch (Exception ignored) {
                isJson = false;
            }
        }

        if (!isJson) {
            Matcher matcher = CANDIDATE_TOKEN.matcher(responseText);
            while (matcher.find()) {
                String val = matcher.group();
                if (val.length() >= 8 && val.length() <= 256) {
                    tokens.putIfAbsent(val, new TokenCandidate("value", val, "REGEX", null));
                }
            }
        }
        return new ArrayList<>(tokens.values());
    }

    private boolean isJsonContent(String mimeType, String responseText) {
        if (mimeType != null && mimeType.toLowerCase().contains("json")) {
            return true;
        }
        if (responseText != null) {
            String trimmed = responseText.trim();
            return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                   (trimmed.startsWith("[") && trimmed.endsWith("]"));
        }
        return false;
    }

    private void collectJsonTokensWithPaths(JsonNode node, String currentPath, String parentKey, Map<String, TokenCandidate> tokens) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                String fieldPath = "$".equals(currentPath) ? "$." + escapeJsonPathField(fieldName) : currentPath + "." + escapeJsonPathField(fieldName);
                collectJsonTokensWithPaths(entry.getValue(), fieldPath, fieldName, tokens);
            });
        } else if (node.isArray()) {
            int idx = 0;
            for (JsonNode child : node) {
                String arrayPath = currentPath + "[" + idx + "]";
                collectJsonTokensWithPaths(child, arrayPath, parentKey, tokens);
                idx++;
                if (idx > 20) break;
            }
        } else if (node.isTextual() || node.isNumber()) {
            String value = node.asText();
            if (value != null && value.length() >= 8 && value.length() <= 256) {
                if (CANDIDATE_TOKEN.matcher(value).find()) {
                    tokens.putIfAbsent(value, new TokenCandidate(parentKey, value, "JSON", currentPath));
                }
            }
        }
    }

    private String escapeJsonPathField(String fieldName) {
        if (fieldName.matches("^[a-zA-Z0-9_]+$")) {
            return fieldName;
        }
        return "['" + fieldName.replace("'", "\\'") + "']";
    }

    private String safeVariable(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_]", "_");
        return safe.length() > 30 ? safe.substring(0, 30) : safe;
    }

    private List<Map<String, String>> previewCandidates(List<Map<String, String>> candidates) {
        List<Map<String, String>> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_PREVIEW_CANDIDATES, candidates.size()); i++) {
            Map<String, String> candidate = candidates.get(i);
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("variable", candidate.get("variable"));
            summary.put("extractorType", candidate.get("extractorType"));
            if (candidate.containsKey("jsonPath")) {
                summary.put("jsonPath", candidate.get("jsonPath"));
            }
            summary.put("sourceFile", truncate(candidate.get("sourceFile"), 100));
            summary.put("sourceUrl", truncate(candidate.get("sourceUrl"), 120));
            summary.put("targetUrl", truncate(candidate.get("targetUrl"), 120));
            summary.put("value", truncate(candidate.get("value"), 96));
            preview.add(summary);
        }
        return preview;
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximumLength - 1) + "…";
    }

    private void validateSchedule(String scenario, int users, int executeTime, int rampUp, int rampDown) {
        if (users < 1 || users > 1_000_000) {
            throw new IllegalArgumentException("Peak users must be between 1 and 1,000,000.");
        }
        if (executeTime < 1 || executeTime > 31_536_000) {
            throw new IllegalArgumentException("Execute time must be between 1 and 31,536,000 seconds.");
        }
        if (rampUp < 0 || rampDown < 0) {
            throw new IllegalArgumentException("Ramp-up and ramp-down must be non-negative.");
        }
        if (!"endurance".equals(scenario) && (long) rampUp + rampDown > executeTime) {
            throw new IllegalArgumentException("For this profile, ramp-up and ramp-down cannot exceed execute time.");
        }
        if (!java.util.Arrays.asList("load", "scalability", "spike", "endurance", "stress").contains(scenario)) {
            throw new IllegalArgumentException("Choose a supported scenario profile.");
        }
    }

    private List<UploadedHar> saveAndValidate(List<MultipartFile> uploads, Path workDirectory) throws IOException {
        List<UploadedHar> files = new ArrayList<>();
        long totalBytes = 0;
        for (int i = 0; i < uploads.size(); i++) {
            MultipartFile upload = uploads.get(i);
            String originalName = safeOriginalName(upload.getOriginalFilename());
            if (!originalName.toLowerCase().endsWith(".har")) {
                throw new IllegalArgumentException("Only .har files are accepted: " + originalName);
            }
            if (upload.isEmpty()) {
                throw new IllegalArgumentException("The file is empty: " + originalName);
            }
            totalBytes += upload.getSize();
            if (totalBytes > MAX_TOTAL_UPLOAD_BYTES) {
                throw new IllegalArgumentException("Combined upload size must not exceed 100 MB.");
            }
            Path path = workDirectory.resolve("input-" + i + ".har");
            upload.transferTo(path.toFile());
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode entries = root.path("log").path("entries");
            if (!entries.isArray() || entries.isEmpty()) {
                throw new IllegalArgumentException("No HAR request entries were found in " + originalName + ".");
            }
            files.add(new UploadedHar(originalName, root));
        }
        return files;
    }

    private List<String> mergeHarFiles(List<UploadedHar> harFiles, Path mergedHar) throws IOException {
        ObjectNode mergedRoot = harFiles.get(0).root.deepCopy();
        ObjectNode mergedLog = (ObjectNode) mergedRoot.path("log").deepCopy();
        ArrayNode pages = objectMapper.createArrayNode();
        ArrayNode entries = objectMapper.createArrayNode();
        List<String> transactionNames = new ArrayList<>();

        for (int fileIndex = 0; fileIndex < harFiles.size(); fileIndex++) {
            UploadedHar uploaded = harFiles.get(fileIndex);
            JsonNode log = uploaded.root.path("log");
            ArrayNode harEntries = (ArrayNode) log.path("entries");
            String transactionName = String.format("T%02d_%s", fileIndex + 1, transactionLabel(uploaded.originalName));
            String pageId = "WEB_TRANSACTION_" + (fileIndex + 1);
            JsonNode originalPages = log.path("pages");
            ObjectNode page = originalPages.isArray() && !originalPages.isEmpty() && originalPages.get(0).isObject()
                    ? ((ObjectNode) originalPages.get(0)).deepCopy() : objectMapper.createObjectNode();
            page.put("id", pageId);
            page.put("title", transactionName);
            if (!page.hasNonNull("startedDateTime")) {
                JsonNode firstEntryDate = harEntries.get(0).path("startedDateTime");
                if (firstEntryDate.isValueNode()) {
                    page.set("startedDateTime", firstEntryDate.deepCopy());
                }
            }
            if (!page.has("pageTimings")) {
                ObjectNode timings = objectMapper.createObjectNode();
                timings.put("onContentLoad", -1);
                timings.put("onLoad", -1);
                page.set("pageTimings", timings);
            }
            pages.add(page);

            for (JsonNode entry : harEntries) {
                ObjectNode copiedEntry = ((ObjectNode) entry).deepCopy();
                copiedEntry.put("pageref", pageId);
                JsonNode requestNode = copiedEntry.path("request");
                if (requestNode.isObject()) {
                    String url = requestNode.path("url").asText("");
                    JsonNode qs = requestNode.path("queryString");
                    if (url.contains("?") && (!qs.isArray() || qs.isEmpty())) {
                        ArrayNode qsArray = objectMapper.createArrayNode();
                        String query = url.substring(url.indexOf('?') + 1);
                        for (String part : query.split("&")) {
                            if (!part.isEmpty()) {
                                String[] kv = part.split("=", 2);
                                ObjectNode param = objectMapper.createObjectNode();
                                param.put("name", kv[0]);
                                param.put("value", kv.length > 1 ? kv[1] : "");
                                qsArray.add(param);
                            }
                        }
                        ((ObjectNode) requestNode).set("queryString", qsArray);
                    }
                }
                entries.add(copiedEntry);
            }
            transactionNames.add(transactionName);
        }

        mergedLog.set("pages", pages);
        mergedLog.set("entries", entries);
        mergedRoot.set("log", mergedLog);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(mergedHar.toFile(), mergedRoot);
        return transactionNames;
    }

    private void finalizeJmx(Path jmxFile, List<String> transactionNames, boolean scenarioEnabled, String scenario,
                             int users, int executeTime, int rampUp, int rampDown,
                             List<Map<String, String>> correlationCandidates, List<String> correlationIds,
                             List<HarExchange> exchanges, String responseMessageOne, String responseMessageTwo) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(jmxFile.toFile());
        NodeList controllers = document.getElementsByTagName("TransactionController");
        if (controllers.getLength() != transactionNames.size()) {
            throw new IllegalStateException("The converter did not produce one transaction per HAR file.");
        }
        for (int i = 0; i < controllers.getLength(); i++) {
            Element controller = (Element) controllers.item(i);
            controller.setAttribute("testname", transactionNames.get(i));
            setProperty(controller, "TransactionController.parent", "true");
        }

        Element defaults = findByTagAndTestName(document, "ConfigTestElement", "HTTP Request Defaults");
        String defaultDomain = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.domain");
        String defaultProtocol = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.protocol");
        String defaultPort = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.port");
        if (defaultDomain.startsWith("${") && defaultDomain.endsWith("}")) {
            defaultDomain = argumentValue(document, defaultDomain.substring(2, defaultDomain.length() - 1));
        }
        if (defaultProtocol.startsWith("${") && defaultProtocol.endsWith("}")) {
            defaultProtocol = argumentValue(document, defaultProtocol.substring(2, defaultProtocol.length() - 1));
        }
        if (defaultPort.startsWith("${") && defaultPort.endsWith("}")) {
            defaultPort = argumentValue(document, defaultPort.substring(2, defaultPort.length() - 1));
        }

        NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
        for (int i = 0; i < samplers.getLength(); i++) {
            Element sampler = (Element) samplers.item(i);
            setProperty(sampler, "HTTPSampler.domain", firstNonBlank(propertyValue(sampler, "HTTPSampler.domain"), defaultDomain));
            setProperty(sampler, "HTTPSampler.protocol", firstNonBlank(propertyValue(sampler, "HTTPSampler.protocol"), defaultProtocol));
            setProperty(sampler, "HTTPSampler.port", firstNonBlank(propertyValue(sampler, "HTTPSampler.port"), defaultPort));
            addResponseAssertions(sampler, responseMessageOne, responseMessageTwo);
        }

        applyCorrelations(document, correlationCandidates, correlationIds, exchanges);

        removeComponentAndHashTree(defaults);
        removeComponentAndHashTree(findByTagAndTestName(document, "Arguments", "User Defined Variables"));

        if (scenarioEnabled) {
            replaceThreadGroup(document, scenario, users, executeTime, rampUp, rampDown);
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(jmxFile.toFile()));
    }

    private void applyCorrelations(Document document, List<Map<String, String>> candidates,
                                   List<String> chosenCorrelationIds, List<HarExchange> exchanges) {
        if (chosenCorrelationIds == null || chosenCorrelationIds.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }

        Set<String> selectedIds = new HashSet<>(chosenCorrelationIds);
        NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
        int samplerCount = samplers.getLength();

        for (Map<String, String> candidate : candidates) {
            String id = candidate.get("id");
            if (!selectedIds.contains(id)) {
                continue;
            }

            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(candidate.get("sourceIndex"));
            } catch (Exception ex) {
                continue;
            }

            if (sourceIndex < 0 || sourceIndex >= samplerCount) {
                continue;
            }

            String varName = candidate.get("variable");
            String value = candidate.get("value");
            String key = candidate.get("key");
            String extractorType = candidate.get("extractorType");
            String jsonPath = candidate.get("jsonPath");

            HarExchange sourceExchange = sourceIndex < exchanges.size() ? exchanges.get(sourceIndex) : null;
            String responseText = sourceExchange != null ? sourceExchange.responseText : "";

            Element sourceSampler = (Element) samplers.item(sourceIndex);
            if ("JSON".equalsIgnoreCase(extractorType) && jsonPath != null && !jsonPath.isEmpty()) {
                addJsonExtractor(sourceSampler, varName, jsonPath);
            } else {
                String regex = buildRegexForToken(responseText, key, value);
                addRegexExtractor(sourceSampler, varName, regex);
            }

            // Replace occurrences of value in subsequent requests
            for (int i = sourceIndex + 1; i < samplerCount; i++) {
                Element targetSampler = (Element) samplers.item(i);
                replaceTextInNode(targetSampler, value, "${" + varName + "}");
                Element targetHashTree = nextHashTree(targetSampler);
                if (targetHashTree != null) {
                    replaceTextInNode(targetHashTree, value, "${" + varName + "}");
                }
            }
        }
    }

    private void addJsonExtractor(Element sampler, String varName, String jsonPath) {
        Element samplerHashTree = nextHashTree(sampler);
        if (samplerHashTree == null) {
            return;
        }
        Document document = sampler.getOwnerDocument();
        Element extractor = document.createElement("JSONPostProcessor");
        extractor.setAttribute("guiclass", "JSONPostProcessorGui");
        extractor.setAttribute("testclass", "JSONPostProcessor");
        extractor.setAttribute("testname", "JSON Extractor - " + varName);
        extractor.setAttribute("enabled", "true");
        appendProperty(document, extractor, "stringProp", "JSONPostProcessor.referenceNames", varName);
        appendProperty(document, extractor, "stringProp", "JSONPostProcessor.jsonPathExprs", jsonPath);
        appendProperty(document, extractor, "stringProp", "JSONPostProcessor.match_numbers", "1");
        appendProperty(document, extractor, "stringProp", "JSONPostProcessor.defaultValues", varName + "_NOT_FOUND");
        samplerHashTree.appendChild(extractor);
        samplerHashTree.appendChild(document.createElement("hashTree"));
    }

    private void addRegexExtractor(Element sampler, String varName, String regex) {
        Element samplerHashTree = nextHashTree(sampler);
        if (samplerHashTree == null) {
            return;
        }
        Document document = sampler.getOwnerDocument();
        Element extractor = document.createElement("RegexExtractor");
        extractor.setAttribute("guiclass", "RegexExtractorGui");
        extractor.setAttribute("testclass", "RegexExtractor");
        extractor.setAttribute("testname", "Regex Extractor - " + varName);
        extractor.setAttribute("enabled", "true");
        appendProperty(document, extractor, "stringProp", "RegexExtractor.useHeaders", "false");
        appendProperty(document, extractor, "stringProp", "RegexExtractor.refname", varName);
        appendProperty(document, extractor, "stringProp", "RegexExtractor.regex", regex);
        appendProperty(document, extractor, "stringProp", "RegexExtractor.template", "$1$");
        appendProperty(document, extractor, "stringProp", "RegexExtractor.default", varName + "_NOT_FOUND");
        appendProperty(document, extractor, "stringProp", "RegexExtractor.match_number", "1");
        samplerHashTree.appendChild(extractor);
        samplerHashTree.appendChild(document.createElement("hashTree"));
    }

    private String buildRegexForToken(String responseText, String key, String value) {
        if (responseText == null || responseText.isEmpty() || value == null || value.isEmpty()) {
            return escapeRegex(value != null ? value : "");
        }

        int idx = responseText.indexOf(value);
        if (idx == -1) {
            return escapeRegex(value);
        }

        if (key != null && !key.isEmpty() && !"value".equals(key)) {
            String jsonKeyPattern = "\"" + key + "\"";
            int keyIdx = responseText.lastIndexOf(jsonKeyPattern, idx);
            if (keyIdx != -1 && idx - keyIdx < 40) {
                return "\"" + escapeRegex(key) + "\"\\s*:\\s*\"?([^\"&,;\\s}]+)\"?";
            }
        }

        int leftStart = Math.max(0, idx - 15);
        String leftContext = responseText.substring(leftStart, idx);
        int delimIdx = Math.max(leftContext.lastIndexOf('"'),
                       Math.max(leftContext.lastIndexOf('\''),
                       Math.max(leftContext.lastIndexOf('='),
                       Math.max(leftContext.lastIndexOf(':'), leftContext.lastIndexOf(',')))));
        String leftBound = delimIdx != -1 ? leftContext.substring(delimIdx) : leftContext;

        int rightEnd = Math.min(responseText.length(), idx + value.length() + 10);
        String rightContext = responseText.substring(idx + value.length(), rightEnd);
        int rightDelim = -1;
        for (int i = 0; i < rightContext.length(); i++) {
            char c = rightContext.charAt(i);
            if (c == '"' || c == '\'' || c == '&' || c == ',' || c == '}' || c == ']' || c == ';' || Character.isWhitespace(c)) {
                rightDelim = i;
                break;
            }
        }
        String rightBound = rightDelim != -1 ? rightContext.substring(0, rightDelim + 1) : (rightContext.isEmpty() ? "" : rightContext.substring(0, 1));

        if (!leftBound.isEmpty() && !rightBound.isEmpty()) {
            return escapeRegex(leftBound) + "(.*?)" + escapeRegex(rightBound);
        } else if (!leftBound.isEmpty()) {
            return escapeRegex(leftBound) + "([^&\\s\"',;]+)";
        } else {
            return "(" + escapeRegex(value) + ")";
        }
    }

    private String escapeRegex(String str) {
        if (str == null) return "";
        return str.replaceAll("([\\\\.\\[\\](){}?*+\\-^$|])", "\\\\$1");
    }

    private void replaceTextInNode(Node node, String targetText, String replacementText) {
        if (node == null || targetText == null || targetText.isEmpty()) {
            return;
        }
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = node.getNodeValue();
            if (text != null && text.contains(targetText)) {
                node.setNodeValue(text.replace(targetText, replacementText));
            }
        }
        if (node.hasAttributes()) {
            NamedNodeMap attrs = node.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                String val = attr.getNodeValue();
                if (val != null && val.contains(targetText)) {
                    attr.setNodeValue(val.replace(targetText, replacementText));
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            replaceTextInNode(children.item(i), targetText, replacementText);
        }
    }

    private void replaceThreadGroup(Document document, String scenario, int users, int executeTime,
                                    int rampUp, int rampDown) {
        NodeList groups = document.getElementsByTagName("ThreadGroup");
        if (groups.getLength() != 1) {
            throw new IllegalStateException("Expected one generated Thread Group to replace.");
        }
        Element oldGroup = (Element) groups.item(0);
        Element ultimateGroup = document.createElement("kg.apc.jmeter.threads.UltimateThreadGroup");
        ultimateGroup.setAttribute("guiclass", "kg.apc.jmeter.threads.UltimateThreadGroupGui");
        ultimateGroup.setAttribute("testclass", "kg.apc.jmeter.threads.UltimateThreadGroup");
        ultimateGroup.setAttribute("testname", "jp@gc - Ultimate Thread Group (" + scenario + ")");
        ultimateGroup.setAttribute("enabled", "true");

        Element scheduleData = document.createElement("collectionProp");
        scheduleData.setAttribute("name", "ultimatethreadgroupdata");
        int rowIndex = 0;
        for (ScheduleRow row : buildSchedule(scenario, users, executeTime, rampUp, rampDown)) {
            Element rowElement = document.createElement("collectionProp");
            rowElement.setAttribute("name", Integer.toString(rowIndex++));
            appendScheduleValue(document, rowElement, "0", row.threads);
            appendScheduleValue(document, rowElement, "1", row.delaySeconds);
            appendScheduleValue(document, rowElement, "2", row.startupSeconds);
            appendScheduleValue(document, rowElement, "3", row.holdSeconds);
            appendScheduleValue(document, rowElement, "4", row.shutdownSeconds);
            scheduleData.appendChild(rowElement);
        }
        ultimateGroup.appendChild(scheduleData);

        Element loopController = document.createElement("elementProp");
        loopController.setAttribute("name", "ThreadGroup.main_controller");
        loopController.setAttribute("elementType", "LoopController");
        loopController.setAttribute("guiclass", "LoopControlPanel");
        loopController.setAttribute("testclass", "LoopController");
        loopController.setAttribute("testname", "Loop Controller");
        loopController.setAttribute("enabled", "true");
        appendProperty(document, loopController, "boolProp", "LoopController.continue_forever", "true");
        appendProperty(document, loopController, "stringProp", "LoopController.loops", "-1");
        ultimateGroup.appendChild(loopController);
        oldGroup.getParentNode().replaceChild(ultimateGroup, oldGroup);
    }

    private List<ScheduleRow> buildSchedule(String scenario, int users, int executeTime, int rampUp, int rampDown) {
        int hold = executeTime - rampUp - rampDown;
        switch (scenario) {
            case "scalability":
                return stagedSchedule(users, executeTime, rampUp, rampDown, false);
            case "stress":
                return stagedSchedule(users, executeTime, rampUp, rampDown, true);
            case "spike":
                return spikeSchedule(users, executeTime, rampUp, rampDown, hold);
            case "endurance":
                return Collections.singletonList(new ScheduleRow(users, 0, rampUp, executeTime, rampDown));
            case "load":
            default:
                return Collections.singletonList(new ScheduleRow(users, 0, rampUp, hold, rampDown));
        }
    }

    private List<ScheduleRow> stagedSchedule(int users, int executeTime, int rampUp, int rampDown, boolean stress) {
        int availableSeconds = rampUp;
        int stages = Math.min(4, Math.min(users, Math.max(1, availableSeconds)));
        if (stages == 1) {
            return Collections.singletonList(new ScheduleRow(users, 0, rampUp, executeTime - rampUp - rampDown, rampDown));
        }
        List<ScheduleRow> rows = new ArrayList<>();
        int earlierUsers = 0;
        for (int index = 0; index < stages; index++) {
            int cumulativeUsers = (int) Math.ceil((double) users * (index + 1) / stages);
            int stageUsers = cumulativeUsers - earlierUsers;
            earlierUsers = cumulativeUsers;
            int stageStart = stress
                ? (int) ((long) index * availableSeconds / (stages - 1))
                : (int) ((long) index * availableSeconds / stages);
            int nextStageStart = (int) ((long) (index + 1) * availableSeconds / stages);
            int startup = stress ? 0 : nextStageStart - stageStart;
            int delay = stageStart;
            int shutdownStart = executeTime - rampDown;
            int stageHold = Math.max(0, shutdownStart - delay - startup);
            rows.add(new ScheduleRow(stageUsers, delay, startup, stageHold, rampDown));
        }
        return rows;
    }

    private List<ScheduleRow> spikeSchedule(int users, int executeTime, int rampUp, int rampDown, int hold) {
        if (users < 2 || hold < 5) {
            return Collections.singletonList(new ScheduleRow(users, 0, rampUp, hold, rampDown));
        }
        int baselineUsers = Math.max(1, users / 10);
        int spikeUsers = users - baselineUsers;
        int spikeDuration = Math.min(30, Math.max(5, hold / 10));
        int spikeDelay = executeTime - rampDown - spikeDuration;
        List<ScheduleRow> rows = new ArrayList<>();
        rows.add(new ScheduleRow(baselineUsers, 0, rampUp, hold, rampDown));
        rows.add(new ScheduleRow(spikeUsers, spikeDelay, 0, spikeDuration, rampDown));
        return rows;
    }

    private void appendScheduleValue(Document document, Element row, String name, int value) {
        Element property = document.createElement("stringProp");
        property.setAttribute("name", name);
        property.setTextContent(Integer.toString(value));
        row.appendChild(property);
    }

    private void appendProperty(Document document, Element parent, String tag, String name, String value) {
        Element property = document.createElement(tag);
        property.setAttribute("name", name);
        property.setTextContent(value);
        parent.appendChild(property);
    }

    private void addResponseAssertions(Element sampler, String textCheck1, String textCheck2) {
        Element samplerHashTree = nextHashTree(sampler);
        if (samplerHashTree == null) {
            return;
        }
        Document document = sampler.getOwnerDocument();
        Element assertion = document.createElement("BeanShellAssertion");
        assertion.setAttribute("guiclass", "BeanShellAssertionGui");
        assertion.setAttribute("testclass", "BeanShellAssertion");
        assertion.setAttribute("testname", "Assert HTTP 2xx/3xx and Response Text Checks");
        assertion.setAttribute("enabled", "true");

        StringBuilder script = new StringBuilder();
        script.append("String rc = prev.getResponseCode();\n");
        script.append("int code = 0;\n");
        script.append("try {\n");
        script.append("    code = Integer.parseInt(rc);\n");
        script.append("} catch (Exception e) {}\n");
        script.append("if (code < 200 || code >= 400) {\n");
        script.append("    Failure = true;\n");
        script.append("    FailureMessage = \"Expected positive HTTP response code (2xx or 3xx), got: \" + rc;\n");
        script.append("    return;\n");
        script.append("}\n");

        boolean hasCheck1 = textCheck1 != null && !textCheck1.trim().isEmpty();
        boolean hasCheck2 = textCheck2 != null && !textCheck2.trim().isEmpty();

        if (hasCheck1 || hasCheck2) {
            script.append("String respData = prev.getResponseDataAsString();\n");
            script.append("String respMsg = prev.getResponseMessage();\n");
        }

        if (hasCheck1 && hasCheck2) {
            String c1 = escapeJavaString(textCheck1.trim());
            String c2 = escapeJavaString(textCheck2.trim());
            script.append("boolean pass1 = (respData != null && respData.contains(\"").append(c1).append("\")) || (respMsg != null && respMsg.contains(\"").append(c1).append("\"));\n");
            script.append("boolean pass2 = (respData != null && respData.contains(\"").append(c2).append("\")) || (respMsg != null && respMsg.contains(\"").append(c2).append("\"));\n");
            script.append("if (!pass1 || !pass2) {\n");
            script.append("    Failure = true;\n");
            script.append("    FailureMessage = \"Response text checks failed. Both '").append(c1).append("' and '").append(c2).append("' must be valid in response (pass1=\" + pass1 + \", pass2=\" + pass2 + \")\";\n");
            script.append("}\n");
        } else if (hasCheck1) {
            String c1 = escapeJavaString(textCheck1.trim());
            script.append("boolean pass1 = (respData != null && respData.contains(\"").append(c1).append("\")) || (respMsg != null && respMsg.contains(\"").append(c1).append("\"));\n");
            script.append("if (!pass1) {\n");
            script.append("    Failure = true;\n");
            script.append("    FailureMessage = \"Response text check failed for '").append(c1).append("'\";\n");
            script.append("}\n");
        } else if (hasCheck2) {
            String c2 = escapeJavaString(textCheck2.trim());
            script.append("boolean pass2 = (respData != null && respData.contains(\"").append(c2).append("\")) || (respMsg != null && respMsg.contains(\"").append(c2).append("\"));\n");
            script.append("if (!pass2) {\n");
            script.append("    Failure = true;\n");
            script.append("    FailureMessage = \"Response text check failed for '").append(c2).append("'\";\n");
            script.append("}\n");
        }

        appendProperty(document, assertion, "stringProp", "BeanShellAssertion.query", script.toString());
        appendProperty(document, assertion, "stringProp", "BeanShellAssertion.filename", "");
        appendProperty(document, assertion, "stringProp", "BeanShellAssertion.parameters", "");
        appendProperty(document, assertion, "boolProp", "BeanShellAssertion.resetInterpreter", "false");
        samplerHashTree.appendChild(assertion);
        samplerHashTree.appendChild(document.createElement("hashTree"));
    }

    private String escapeJavaString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private Element nextHashTree(Element element) {
        Node sibling = element.getNextSibling();
        while (sibling != null && sibling.getNodeType() != Node.ELEMENT_NODE) {
            sibling = sibling.getNextSibling();
        }
        return sibling instanceof Element && "hashTree".equals(sibling.getNodeName()) ? (Element) sibling : null;
    }

    private Element findByTagAndTestName(Document document, String tagName, String testName) {
        NodeList elements = document.getElementsByTagName(tagName);
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (testName.equals(element.getAttribute("testname"))) {
                return element;
            }
        }
        return null;
    }

    private String propertyValue(Element component, String propertyName) {
        if (component == null) {
            return "";
        }
        NodeList properties = component.getElementsByTagName("stringProp");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                return property.getTextContent().trim();
            }
        }
        return "";
    }

    private String argumentValue(Document document, String argumentName) {
        NodeList elements = document.getElementsByTagName("elementProp");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (!argumentName.equals(element.getAttribute("name"))) {
                continue;
            }
            NodeList properties = element.getElementsByTagName("stringProp");
            for (int propertyIndex = 0; propertyIndex < properties.getLength(); propertyIndex++) {
                Element property = (Element) properties.item(propertyIndex);
                if ("Argument.value".equals(property.getAttribute("name"))) {
                    return property.getTextContent().trim();
                }
            }
        }
        return "";
    }

    private void setProperty(Element component, String propertyName, String value) {
        NodeList properties = component.getElementsByTagName("stringProp");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                property.setTextContent(value);
                return;
            }
        }
        Element property = component.getOwnerDocument().createElement("stringProp");
        property.setAttribute("name", propertyName);
        property.setTextContent(value);
        component.appendChild(property);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred;
    }

    private void removeComponentAndHashTree(Element component) {
        if (component == null) {
            return;
        }
        Node pairedHashTree = component.getNextSibling();
        while (pairedHashTree != null && pairedHashTree.getNodeType() != Node.ELEMENT_NODE) {
            pairedHashTree = pairedHashTree.getNextSibling();
        }
        Node parent = component.getParentNode();
        parent.removeChild(component);
        if (pairedHashTree != null && "hashTree".equals(pairedHashTree.getNodeName())) {
            parent.removeChild(pairedHashTree);
        }
    }

    private String transactionLabel(String fileName) {
        String label = fileName.substring(0, fileName.length() - 4);
        label = label.replaceFirst("(?i)^T?\\d+[_ .-]*", "");
        label = label.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (label.isEmpty()) {
            label = "transaction";
        }
        return label.length() > 60 ? label.substring(0, 60) : label;
    }

    private String safeOriginalName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "upload.har";
        }
        String normalized = fileName.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private String normalizeFilter(String filter) {
        return filter == null ? "" : filter.trim();
    }

    private void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class UploadedHar {
        private final String originalName;
        private final JsonNode root;

        private UploadedHar(String originalName, JsonNode root) {
            this.originalName = originalName;
            this.root = root;
        }
    }

    private static final class HarExchange {
        private final String fileName;
        private final String url;
        private final String mimeType;
        private final String responseText;

        private HarExchange(String fileName, String url, String mimeType, String responseText) {
            this.fileName = fileName;
            this.url = url;
            this.mimeType = mimeType;
            this.responseText = responseText;
        }
    }

    private static final class TokenCandidate {
        private final String key;
        private final String value;
        private final String extractorType;
        private final String jsonPath;

        private TokenCandidate(String key, String value, String extractorType, String jsonPath) {
            this.key = key;
            this.value = value;
            this.extractorType = extractorType;
            this.jsonPath = jsonPath;
        }
    }

    private static final class ScheduleRow {
        private final int threads;
        private final int delaySeconds;
        private final int startupSeconds;
        private final int holdSeconds;
        private final int shutdownSeconds;

        private ScheduleRow(int threads, int delaySeconds, int startupSeconds, int holdSeconds, int shutdownSeconds) {
            this.threads = threads;
            this.delaySeconds = delaySeconds;
            this.startupSeconds = startupSeconds;
            this.holdSeconds = holdSeconds;
            this.shutdownSeconds = shutdownSeconds;
        }
    }

    public final class ConversionArtifact implements AutoCloseable {
        private final Path workDirectory;
        private final Path jmxPath;
        private final List<Map<String, String>> previewCandidates;

        public ConversionArtifact(Path workDirectory, Path jmxPath, List<Map<String, String>> previewCandidates) {
            this.workDirectory = workDirectory;
            this.jmxPath = jmxPath;
            this.previewCandidates = previewCandidates;
        }

        public ConversionArtifact(Path workDirectory, Path jmxPath) {
            this(workDirectory, jmxPath, Collections.emptyList());
        }

        public Path getDownloadPath() {
            return jmxPath;
        }

        public Path getJmxPath() {
            return jmxPath;
        }

        public byte[] getPreviewCandidatesJson() throws IOException {
            return objectMapper.writeValueAsBytes(previewCandidates);
        }

        @Override
        public void close() {
            deleteTree(workDirectory);
        }
    }
}