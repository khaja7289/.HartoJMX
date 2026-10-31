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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
                                      int users, int tph, int executeTime, int rampUp, int rampDown,
                                      List<String> correlationIds,
                                      String responseMessageOne, String responseMessageTwo) throws Exception {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one HAR file.");
        }
        if (scenarioEnabled) {
            validateSchedule(scenario, users, tph, executeTime, rampUp, rampDown);
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

            finalizeJmx(jmxFile, transactionNames, scenarioEnabled, scenario, users, tph, executeTime, rampUp, rampDown,
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
                                      String removeHeaders, boolean scenarioEnabled, String scenario,
                                      int users, int executeTime, int rampUp, int rampDown,
                                      List<String> correlationIds,
                                      String responseMessageOne, String responseMessageTwo) throws Exception {
        return convert(uploads, filterInclude, filterExclude, removeHeaders, scenarioEnabled, scenario,
                users, 10, executeTime, rampUp, rampDown, correlationIds, responseMessageOne, responseMessageTwo);
    }

    public ConversionArtifact convert(List<MultipartFile> uploads, String filterInclude, String filterExclude,
                                      String removeHeaders, String scenario, int users, int executeTime,
                                      int rampUp, int rampDown) throws Exception {
        return convert(uploads, filterInclude, filterExclude, removeHeaders, true, scenario,
                users, 10, executeTime, rampUp, rampDown, Collections.emptyList(), "", "");
    }

    public ConversionArtifact convert(List<MultipartFile> uploads, String filterInclude, String filterExclude,
                                      String removeHeaders) throws Exception {
        return convert(uploads, filterInclude, filterExclude, removeHeaders, false, "load",
                1, 10, 600, 5, 10, Collections.emptyList(), "", "");
    }

    private List<HarExchange> collectExchanges(List<UploadedHar> harFiles) {
        List<HarExchange> exchanges = new ArrayList<>();
        long responseTextBytes = 0;
        for (UploadedHar harFile : harFiles) {
            JsonNode entries = harFile.root.path("log").path("entries");
            for (JsonNode entry : entries) {
                JsonNode request = entry.path("request");
                JsonNode response = entry.path("response");
                int statusCode = response.path("status").asInt(200);
                String statusText = response.path("statusText").asText("");
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
                exchanges.add(new HarExchange(harFile.originalName, request.path("url").asText(""), mimeType, responseText, statusCode, statusText));
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

    private void validateSchedule(String scenario, int users, int tph, int executeTime, int rampUp, int rampDown) {
        if (users < 1 || users > 1_000_000) {
            throw new IllegalArgumentException("Peak users must be between 1 and 1,000,000.");
        }
        if (tph < 1 || tph > 10_000_000) {
            throw new IllegalArgumentException("Target TPH must be between 1 and 10,000,000.");
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

    private void validateSchedule(String scenario, int users, int executeTime, int rampUp, int rampDown) {
        validateSchedule(scenario, users, 10, executeTime, rampUp, rampDown);
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
                             int users, int tph, int executeTime, int rampUp, int rampDown,
                             List<Map<String, String>> correlationCandidates, List<String> correlationIds,
                             List<HarExchange> exchanges, String responseMessageOne, String responseMessageTwo) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document sourceDoc = factory.newDocumentBuilder().parse(jmxFile.toFile());
        NodeList controllers = sourceDoc.getElementsByTagName("TransactionController");
        if (controllers.getLength() != transactionNames.size()) {
            throw new IllegalStateException("The converter did not produce one transaction per HAR file.");
        }
        for (int i = 0; i < controllers.getLength(); i++) {
            Element controller = (Element) controllers.item(i);
            controller.setAttribute("testname", transactionNames.get(i));
            setProperty(controller, "TransactionController.parent", "true");
            setBoolProperty(controller, "TransactionController.includeTimers", false);
        }

        Element defaults = findByTagAndTestName(sourceDoc, "ConfigTestElement", "HTTP Request Defaults");
        String defaultDomain = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.domain");
        String defaultProtocol = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.protocol");
        String defaultPort = defaults == null ? "" : propertyValue(defaults, "HTTPSampler.port");
        if (defaultDomain.startsWith("${") && defaultDomain.endsWith("}")) {
            defaultDomain = argumentValue(sourceDoc, defaultDomain.substring(2, defaultDomain.length() - 1));
        }
        if (defaultProtocol.startsWith("${") && defaultProtocol.endsWith("}")) {
            defaultProtocol = argumentValue(sourceDoc, defaultProtocol.substring(2, defaultProtocol.length() - 1));
        }
        if (defaultPort.startsWith("${") && defaultPort.endsWith("}")) {
            defaultPort = argumentValue(sourceDoc, defaultPort.substring(2, defaultPort.length() - 1));
        }

        NodeList sourceSamplers = sourceDoc.getElementsByTagName("HTTPSamplerProxy");
        Set<String> uniqueDomains = new LinkedHashSet<>();
        for (int i = 0; i < sourceSamplers.getLength(); i++) {
            Element sampler = (Element) sourceSamplers.item(i);
            String domain = firstNonBlank(propertyValue(sampler, "HTTPSampler.domain"), defaultDomain);
            if (!domain.isEmpty()) {
                uniqueDomains.add(domain);
            }
        }
        if (uniqueDomains.isEmpty()) {
            uniqueDomains.add("www.example.com");
        }
        Map<String, String> domainToVar = new LinkedHashMap<>();
        int dIdx = 1;
        for (String d : uniqueDomains) {
            domainToVar.put(d, "ApplicationURL" + (dIdx++));
        }

        for (int i = 0; i < sourceSamplers.getLength(); i++) {
            Element sampler = (Element) sourceSamplers.item(i);
            String domain = firstNonBlank(propertyValue(sampler, "HTTPSampler.domain"), defaultDomain);
            String protocol = firstNonBlank(propertyValue(sampler, "HTTPSampler.protocol"), defaultProtocol);
            if (protocol.isEmpty()) {
                protocol = "https";
            }
            String port = firstNonBlank(propertyValue(sampler, "HTTPSampler.port"), defaultPort);
            setProperty(sampler, "HTTPSampler.domain", domain);
            setProperty(sampler, "HTTPSampler.protocol", protocol);
            setProperty(sampler, "HTTPSampler.port", port);
        }

        Document templateDoc = loadBaseTemplate(factory);

        Element udv = findByTagAndTestName(templateDoc, "Arguments", "User Defined Variables");
        setArgumentValue(udv, "ShutDownTime", String.valueOf(rampDown));
        setArgumentValue(udv, "TestDuration", String.valueOf(executeTime));
        setArgumentValue(udv, "StartupTime", String.valueOf(rampUp));
        setArgumentValue(udv, "v_AcceptableRespTime", "5000");

        clearArgumentsMatching(udv, Pattern.compile("^ApplicationURL\\d+$"));
        for (Map.Entry<String, String> entry : domainToVar.entrySet()) {
            String domain = entry.getKey();
            String varName = entry.getValue();
            setArgumentValue(udv, varName, "${__P(" + varName + "," + domain + ")}");
        }

        Element wlm = findByTagAndTestName(templateDoc, "Arguments", "WLM");
        setArgumentValue(wlm, "Test_Users", String.valueOf(users));
        setArgumentValue(wlm, "Test_TPH", String.valueOf(tph));

        configureUltimateThreadGroupSchedule(templateDoc, scenarioEnabled, scenario, users, executeTime, rampUp, rampDown);

        Element utg = findByTagAndTestName(templateDoc, "kg.apc.jmeter.threads.UltimateThreadGroup", "BaseTestTamplet");
        if (utg == null) {
            utg = (Element) templateDoc.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").item(0);
        }
        Element utgHashTree = nextHashTree(utg);
        while (utgHashTree.getFirstChild() != null) {
            utgHashTree.removeChild(utgHashTree.getFirstChild());
        }

        Element saveStart = createModuleController(templateDoc, "SaveItrateStartTime", "PaceingInterval", "-971294028");
        utgHashTree.appendChild(saveStart);
        utgHashTree.appendChild(templateDoc.createElement("hashTree"));

        int[] exchangePointer = new int[]{0};
        for (int i = 0; i < controllers.getLength(); i++) {
            Element controller = (Element) controllers.item(i);
            Element controllerHashTree = nextHashTree(controller);

            Element importedTxn = (Element) templateDoc.importNode(controller, true);
            Element importedTxnHashTree = (Element) templateDoc.importNode(controllerHashTree, true);

            utgHashTree.appendChild(importedTxn);
            utgHashTree.appendChild(importedTxnHashTree);

            String txnName = transactionNames.get(i);
            NodeList samplersInTxn = importedTxnHashTree.getElementsByTagName("HTTPSamplerProxy");
            for (int s = 0; s < samplersInTxn.getLength(); s++) {
                Element sampler = (Element) samplersInTxn.item(s);
                String currentDomain = propertyValue(sampler, "HTTPSampler.domain");
                String varName = domainToVar.get(currentDomain);
                if (varName != null) {
                    setProperty(sampler, "HTTPSampler.domain", "${" + varName + "}");
                }
                if (propertyValue(sampler, "HTTPSampler.protocol").isEmpty()) {
                    setProperty(sampler, "HTTPSampler.protocol", "https");
                }
                setProperty(sampler, "HTTPSampler.implementation", "HttpClient4");

                HarExchange match = findMatchingExchange(sampler, exchanges, exchangePointer, currentDomain);
                int statusCode = match != null ? match.statusCode : 200;
                setBoolProperty(sampler, "HTTPSampler.follow_redirects", statusCode != 302);
                setBoolProperty(sampler, "HTTPSampler.auto_redirects", false);
                setBoolProperty(sampler, "HTTPSampler.use_keepalive", true);

                Element samplerHashTree = nextHashTree(sampler);
                if (samplerHashTree != null) {
                    for (Map.Entry<String, String> entry : domainToVar.entrySet()) {
                        replaceTextInNode(samplerHashTree, entry.getKey(), "${" + entry.getValue() + "}");
                    }
                }

                addJSR223Assertion(sampler, txnName, responseMessageOne, responseMessageTwo, match);
            }

            if (i < controllers.getLength() - 1) {
                Element thinkTime = createModuleController(templateDoc, "ThinkTime", "ThinkTime", "1310565055");
                utgHashTree.appendChild(thinkTime);
                utgHashTree.appendChild(templateDoc.createElement("hashTree"));

                Element logCtrl = createModuleController(templateDoc, "LogController", "LogController", "-1446982176");
                utgHashTree.appendChild(logCtrl);
                utgHashTree.appendChild(templateDoc.createElement("hashTree"));
            } else {
                Element logCtrl = createModuleController(templateDoc, "LogController", "LogController", "-1446982176");
                utgHashTree.appendChild(logCtrl);
                utgHashTree.appendChild(templateDoc.createElement("hashTree"));

                Element paceTime = createModuleController(templateDoc, "PaceTime", "PaceTime", "809585568");
                utgHashTree.appendChild(paceTime);
                utgHashTree.appendChild(templateDoc.createElement("hashTree"));
            }
        }

        applyCorrelations(templateDoc, correlationCandidates, correlationIds, exchanges);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(templateDoc), new StreamResult(jmxFile.toFile()));
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

    private void configureUltimateThreadGroupSchedule(Document templateDoc, boolean scenarioEnabled, String scenario,
                                                      int users, int executeTime, int rampUp, int rampDown) {
        Element utg = findByTagAndTestName(templateDoc, "kg.apc.jmeter.threads.UltimateThreadGroup", "BaseTestTamplet");
        if (utg == null) {
            utg = (Element) templateDoc.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").item(0);
        }
        if (utg == null) return;

        if (scenarioEnabled && !"load".equalsIgnoreCase(scenario)) {
            utg.setAttribute("testname", "BaseTestTamplet (" + scenario + ")");
            Element scheduleData = findFirstChildByTagAndName(utg, "collectionProp", "ultimatethreadgroupdata");
            if (scheduleData != null) {
                while (scheduleData.getFirstChild() != null) {
                    scheduleData.removeChild(scheduleData.getFirstChild());
                }
                int rowIndex = 0;
                for (ScheduleRow row : buildSchedule(scenario, users, executeTime, rampUp, rampDown)) {
                    Element rowElement = templateDoc.createElement("collectionProp");
                    rowElement.setAttribute("name", Integer.toString(rowIndex++));
                    appendScheduleValue(templateDoc, rowElement, "0", row.threads);
                    appendScheduleValue(templateDoc, rowElement, "1", row.delaySeconds);
                    appendScheduleValue(templateDoc, rowElement, "2", row.startupSeconds);
                    appendScheduleValue(templateDoc, rowElement, "3", row.holdSeconds);
                    appendScheduleValue(templateDoc, rowElement, "4", row.shutdownSeconds);
                    scheduleData.appendChild(rowElement);
                }
            }
        } else {
            utg.setAttribute("testname", "BaseTestTamplet");
            Element scheduleData = findFirstChildByTagAndName(utg, "collectionProp", "ultimatethreadgroupdata");
            if (scheduleData != null) {
                while (scheduleData.getFirstChild() != null) {
                    scheduleData.removeChild(scheduleData.getFirstChild());
                }
                Element rowElement = templateDoc.createElement("collectionProp");
                rowElement.setAttribute("name", "1844437372");
                appendScheduleString(templateDoc, rowElement, "1586624139", "${Test_Users}");
                appendScheduleString(templateDoc, rowElement, "0", "0");
                appendScheduleString(templateDoc, rowElement, "-337860278", "${StartupTime}");
                appendScheduleString(templateDoc, rowElement, "523912544", "${TestDuration}");
                appendScheduleString(templateDoc, rowElement, "523912544", "${ShutDownTime}");
                scheduleData.appendChild(rowElement);
            }
        }
    }

    private void appendScheduleString(Document document, Element row, String name, String value) {
        Element property = document.createElement("stringProp");
        property.setAttribute("name", name);
        property.setTextContent(value);
        row.appendChild(property);
    }

    private void appendScheduleValue(Document doc, Element parent, String name, int value) {
        Element property = doc.createElement("stringProp");
        property.setAttribute("name", name);
        property.setTextContent(Integer.toString(value));
        parent.appendChild(property);
    }

    private void appendProperty(Document doc, Element parent, String propertyType, String name, String value) {
        Element property = doc.createElement(propertyType);
        property.setAttribute("name", name);
        property.setTextContent(value);
        parent.appendChild(property);
    }

    private List<ScheduleRow> buildSchedule(String scenario, int users, int executeTime, int rampUp, int rampDown) {
        List<ScheduleRow> rows = new ArrayList<>();
        if ("scalability".equalsIgnoreCase(scenario)) {
            int cohorts = 4;
            int stepRamp = Math.max(1, rampUp / cohorts);
            int baseUsers = users / cohorts;
            int remainder = users % cohorts;
            for (int i = 0; i < cohorts; i++) {
                int threads = baseUsers + (i < remainder ? 1 : 0);
                int delay = i * stepRamp;
                int startup = stepRamp;
                int hold = Math.max(0, executeTime - (delay + startup) - rampDown);
                rows.add(new ScheduleRow(threads, delay, startup, hold, rampDown));
            }
        } else if ("spike".equalsIgnoreCase(scenario)) {
            int baseline = Math.max(1, (int) Math.round(users * 0.10));
            int spikeThreads = Math.max(1, users - baseline);
            int baselineHold = Math.max(0, executeTime - rampUp - rampDown);
            rows.add(new ScheduleRow(baseline, 0, rampUp, baselineHold, rampDown));

            int spikeStartup = Math.max(1, (int) Math.round(rampUp * 0.20));
            int spikeShutdown = Math.max(1, (int) Math.round(rampDown * 0.20));
            int spikeHold = Math.max(1, (int) Math.round((executeTime - rampUp - rampDown) * 0.20));
            int spikeDelay = rampUp + (int) Math.round((executeTime - rampUp - rampDown) * 0.50);
            rows.add(new ScheduleRow(spikeThreads, spikeDelay, spikeStartup, spikeHold, spikeShutdown));
        } else if ("endurance".equalsIgnoreCase(scenario)) {
            rows.add(new ScheduleRow(users, 0, rampUp, executeTime, rampDown));
        } else if ("stress".equalsIgnoreCase(scenario)) {
            int cohorts = 4;
            int stepDelay = Math.max(1, rampUp / cohorts);
            int baseUsers = users / cohorts;
            int remainder = users % cohorts;
            for (int i = 0; i < cohorts; i++) {
                int threads = baseUsers + (i < remainder ? 1 : 0);
                int delay = i * stepDelay;
                int startup = 0;
                int hold = Math.max(0, executeTime - delay - rampDown);
                rows.add(new ScheduleRow(threads, delay, startup, hold, rampDown));
            }
        } else {
            int hold = Math.max(0, executeTime - rampUp - rampDown);
            rows.add(new ScheduleRow(users, 0, rampUp, hold, rampDown));
        }
        return rows;
    }

    private Element createModuleController(Document doc, String mcName, String targetName, String targetPropName) {
        Element mc = doc.createElement("ModuleController");
        mc.setAttribute("guiclass", "ModuleControllerGui");
        mc.setAttribute("testclass", "ModuleController");
        mc.setAttribute("testname", mcName);
        mc.setAttribute("enabled", "true");
        Element col = doc.createElement("collectionProp");
        col.setAttribute("name", "ModuleController.node_path");

        appendStringProp(doc, col, "764597751", "Test Plan");
        appendStringProp(doc, col, "764597751", "Test Plan");
        appendStringProp(doc, col, "170071582", "Test Fragment");
        appendStringProp(doc, col, targetPropName, targetName);

        mc.appendChild(col);
        return mc;
    }

    private void appendStringProp(Document doc, Element parent, String name, String value) {
        Element sp = doc.createElement("stringProp");
        sp.setAttribute("name", name);
        sp.setTextContent(value);
        parent.appendChild(sp);
    }

    private Document loadBaseTemplate(DocumentBuilderFactory factory) throws Exception {
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream is = getClass().getResourceAsStream("/BaseTamPlet.jmx")) {
            if (is != null) {
                return builder.parse(is);
            }
        }
        Path rootPath = Path.of("BaseTamPlet.jmx");
        if (Files.exists(rootPath)) {
            try (InputStream is = Files.newInputStream(rootPath)) {
                return builder.parse(is);
            }
        }
        Path parentPath = Path.of("..", "BaseTamPlet.jmx");
        if (Files.exists(parentPath)) {
            try (InputStream is = Files.newInputStream(parentPath)) {
                return builder.parse(is);
            }
        }
        throw new IllegalStateException("Base template BaseTamPlet.jmx not found in classpath or filesystem.");
    }

    private HarExchange findMatchingExchange(Element sampler, List<HarExchange> exchanges, int[] pointer, String originalDomain) {
        if (exchanges == null || exchanges.isEmpty()) {
            return null;
        }
        String path = propertyValue(sampler, "HTTPSampler.path");
        int start = pointer[0];
        for (int idx = start; idx < exchanges.size(); idx++) {
            HarExchange ex = exchanges.get(idx);
            if (matchesExchange(ex, originalDomain, path)) {
                pointer[0] = idx + 1;
                return ex;
            }
        }
        for (int idx = 0; idx < start; idx++) {
            HarExchange ex = exchanges.get(idx);
            if (matchesExchange(ex, originalDomain, path)) {
                return ex;
            }
        }
        if (pointer[0] < exchanges.size()) {
            return exchanges.get(pointer[0]++);
        }
        return exchanges.get(exchanges.size() - 1);
    }

    private boolean matchesExchange(HarExchange ex, String domain, String path) {
        if (ex == null || ex.url == null) return false;
        if (domain != null && !domain.isEmpty() && !ex.url.contains(domain)) {
            return false;
        }
        if (path != null && !path.isEmpty()) {
            String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            if (!cleanPath.isEmpty() && !"/".equals(cleanPath)) {
                return ex.url.contains(cleanPath);
            }
        }
        return true;
    }

    private void setBoolProperty(Element component, String propertyName, boolean value) {
        NodeList properties = component.getElementsByTagName("boolProp");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                property.setTextContent(Boolean.toString(value));
                return;
            }
        }
        Element property = component.getOwnerDocument().createElement("boolProp");
        property.setAttribute("name", propertyName);
        property.setTextContent(Boolean.toString(value));
        component.appendChild(property);
    }

    private void setArgumentValue(Element argumentsComponent, String argName, String argValue) {
        if (argumentsComponent == null) return;
        Element col = findFirstChildByTag(argumentsComponent, "collectionProp");
        if (col == null) {
            col = argumentsComponent.getOwnerDocument().createElement("collectionProp");
            col.setAttribute("name", "Arguments.arguments");
            argumentsComponent.appendChild(col);
        }
        NodeList elementProps = col.getElementsByTagName("elementProp");
        for (int i = 0; i < elementProps.getLength(); i++) {
            Element elem = (Element) elementProps.item(i);
            if (argName.equals(elem.getAttribute("name"))) {
                setProperty(elem, "Argument.value", argValue);
                return;
            }
        }
        Document doc = argumentsComponent.getOwnerDocument();
        Element elem = doc.createElement("elementProp");
        elem.setAttribute("name", argName);
        elem.setAttribute("elementType", "Argument");
        appendProperty(doc, elem, "stringProp", "Argument.name", argName);
        appendProperty(doc, elem, "stringProp", "Argument.value", argValue);
        appendProperty(doc, elem, "stringProp", "Argument.metadata", "=");
        col.appendChild(elem);
    }

    private void clearArgumentsMatching(Element argumentsComponent, Pattern pattern) {
        if (argumentsComponent == null) return;
        Element col = findFirstChildByTag(argumentsComponent, "collectionProp");
        if (col == null) return;
        NodeList elementProps = col.getElementsByTagName("elementProp");
        List<Element> toRemove = new ArrayList<>();
        for (int i = 0; i < elementProps.getLength(); i++) {
            Element elem = (Element) elementProps.item(i);
            String name = elem.getAttribute("name");
            if (name != null && pattern.matcher(name).matches()) {
                toRemove.add(elem);
            }
        }
        for (Element elem : toRemove) {
            col.removeChild(elem);
        }
    }

    private Element findFirstChildByTag(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private Element findFirstChildByTagAndName(Element parent, String tagName, String nameAttr) {
        if (parent == null) return null;
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element && tagName.equals(n.getNodeName())) {
                Element e = (Element) n;
                if (nameAttr.equals(e.getAttribute("name"))) {
                    return e;
                }
            }
        }
        return null;
    }

    private void addJSR223Assertion(Element sampler, String txnName, String textCheck1, String textCheck2) {
        addJSR223Assertion(sampler, txnName, textCheck1, textCheck2, null);
    }

    private void addJSR223Assertion(Element sampler, String txnName, String textCheck1, String textCheck2, HarExchange exchange) {
        Element samplerHashTree = nextHashTree(sampler);
        if (samplerHashTree == null) {
            return;
        }
        Document document = sampler.getOwnerDocument();
        Element assertion = document.createElement("JSR223Assertion");
        assertion.setAttribute("guiclass", "TestBeanGUI");
        assertion.setAttribute("testclass", "JSR223Assertion");
        assertion.setAttribute("testname", "JSR223 Assertion");
        assertion.setAttribute("enabled", "true");

        appendProperty(document, assertion, "stringProp", "cacheKey", "true");
        appendProperty(document, assertion, "stringProp", "filename", "");
        appendProperty(document, assertion, "stringProp", "parameters", "");

        String[] identified = identifyTwoTextChecks(exchange, textCheck1, textCheck2);
        String check1 = escapeGroovyString(identified[0]);
        String check2 = escapeGroovyString(identified[1]);
        String safeTxnName = txnName != null ? escapeGroovyString(txnName) : "Transaction";

        StringBuilder script = new StringBuilder();
        script.append("String TXN_NAME = \"").append(safeTxnName).append("\"\n");
        script.append("String TEXT_CHECK = \"").append(check1).append("\"\n");
        script.append("String TEXT_CHECK1 = \"").append(check2).append("\"\n\n");
        script.append("Set<String> OK_CODES = [\"200\", \"201\", \"202\", \"204\", \"302\"] as Set\n");
        script.append("int MAX_ERR_LEN = 500\n\n");
        script.append("// ---- Status values ---------------------------------------------------------\n");
        script.append("String ST_PASS = \"Pass\"\n");
        script.append("String ST_SLOW = \"Slow\"\n");
        script.append("String ST_FAIL = \"Fail\"\n\n");
        script.append("// ---- Response data --------------------------------------------------------\n");
        script.append("String responseCode = prev.getResponseCode() ?: \"\"\n");
        script.append("String response = prev.getResponseDataAsString() ?: \"\"\n\n");
        script.append("// Strip characters that break Grafana ingestion\n");
        script.append("for (String ch : ['\\r', '\\n', ',', '-', '?', \"'\", '{', '}', '/', '\"']) {\n");
        script.append("    response = response.replace(ch, \"\")\n");
        script.append("}\n\n");
        script.append("// ---- Load time and threshold ----------------------------------------------\n");
        script.append("long loadTime = prev.getTime()\n");
        script.append("vars.put(\"LoadTime\", String.valueOf(loadTime))\n\n");
        script.append("long acceptableRespTime = Long.MAX_VALUE\n");
        script.append("try {\n");
        script.append("    acceptableRespTime = Long.parseLong(\n");
        script.append("        vars.get(\"v_AcceptableRespTime\") ?: \"\"\n");
        script.append("    )\n");
        script.append("} catch (NumberFormatException e) {\n");
        script.append("    log.warn(\"v_AcceptableRespTime missing or not numeric, skipping slow check\")\n");
        script.append("}\n\n");
        script.append("// ---- Fields logged to Grafana ---------------------------------------------\n");
        script.append("String errShort = response.length() > MAX_ERR_LEN\n");
        script.append("    ? response.substring(0, MAX_ERR_LEN)\n");
        script.append("    : response\n\n");
        script.append("vars.put(\"TransactionName\", TXN_NAME)\n");
        script.append("vars.put(\"ResponseCode\", responseCode)\n");
        script.append("vars.put(\"UniqueIdentifier\", \"Application ID: \" + (vars.get(\"C_ApplicationID\") ?: \"NA\"))\n");
        script.append("vars.put(\"ErrorMsg\", \"NA\")\n\n");
        script.append("// ---- Validation ------------------------------------------------------------\n");
        script.append("boolean codeOk = OK_CODES.contains(responseCode.trim())\n");
        script.append("boolean textOk = (TEXT_CHECK.isEmpty() || TEXT_CHECK.equals(\"NotFoundResponseData\") || response.contains(TEXT_CHECK)) &&\n");
        script.append("                 (TEXT_CHECK1.isEmpty() || TEXT_CHECK1.equals(\"NotFoundResponseData\") || response.contains(TEXT_CHECK1))\n\n");
        script.append("if (codeOk && textOk) {\n");
        script.append("    if (loadTime > acceptableRespTime) {\n");
        script.append("        vars.put(\"status\", ST_SLOW)\n");
        script.append("        vars.put(\"ErrorMsg\",\n");
        script.append("            \"ResponseTime \" + loadTime +\n");
        script.append("            \" ms higher than \" + acceptableRespTime + \" ms\")\n");
        script.append("    } else {\n");
        script.append("        vars.put(\"status\", ST_PASS)\n");
        script.append("    }\n\n");
        script.append("    AssertionResult.setFailure(false)\n\n");
        script.append("} else {\n");
        script.append("    String msg\n\n");
        script.append("    if (responseCode.contains(\"Non HTTP response code\")) {\n");
        script.append("        vars.put(\"ResponseCode\", \"ReceivedNonHTTPResponseCode\")\n");
        script.append("        msg = \"ReceivedNonHTTPResponseCode\"\n");
        script.append("    } else if (!codeOk) {\n");
        script.append("        msg = \"Unexpected response code: \" + responseCode\n");
        script.append("    } else {\n");
        script.append("        msg = \"Expected text not found: \" +\n");
        script.append("              TEXT_CHECK + \" / \" + TEXT_CHECK1\n");
        script.append("    }\n\n");
        script.append("    vars.put(\"ErrorMsg\", msg)\n");
        script.append("    vars.put(\"status\", ST_FAIL)\n\n");
        script.append("    AssertionResult.setFailure(true)\n");
        script.append("    AssertionResult.setFailureMessage(msg)\n");
        script.append("}\n");

        appendProperty(document, assertion, "stringProp", "script", script.toString());
        appendProperty(document, assertion, "stringProp", "scriptLanguage", "groovy");
        samplerHashTree.appendChild(assertion);
        samplerHashTree.appendChild(document.createElement("hashTree"));
    }

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "true", "false", "null", "undefined", "none", "string", "number", "object", "array", "boolean",
            "html", "head", "body", "script", "style", "meta", "link", "title", "span", "div", "table", "tbody", "thead",
            "class", "href", "src", "type", "name", "value", "width", "height", "xmlns", "doctype",
            "function", "var", "let", "const", "return", "import", "export", "this", "that", "with",
            "from", "http", "https", "www", "com", "org", "net", "and", "the", "for", "then", "else"
    ));

    private boolean isStopWord(String word) {
        return word == null || word.length() < 3 || STOP_WORDS.contains(word.toLowerCase());
    }

    private String sanitizeForGrafana(String text) {
        if (text == null) return "";
        String s = text;
        for (char ch : new char[]{'\r', '\n', ',', '-', '?', '\'', '{', '}', '/', '"', '\\'}) {
            s = s.replace(String.valueOf(ch), "");
        }
        return s;
    }

    private String[] identifyTwoTextChecks(HarExchange exchange, String userCheck1, String userCheck2) {
        boolean hasUser1 = userCheck1 != null && !userCheck1.trim().isEmpty();
        boolean hasUser2 = userCheck2 != null && !userCheck2.trim().isEmpty();
        if (hasUser1 && hasUser2) {
            return new String[]{userCheck1.trim(), userCheck2.trim()};
        }

        boolean hasResponse = exchange != null && exchange.responseText != null && !exchange.responseText.trim().isEmpty();
        List<String> candidates = hasResponse ? extractTextCheckCandidates(exchange.mimeType, exchange.responseText) : Collections.emptyList();
        String sanitized = hasResponse ? sanitizeForGrafana(exchange.responseText) : "";

        String check1 = "";
        if (hasUser1) {
            check1 = userCheck1.trim();
        } else if (hasResponse) {
            for (String c : candidates) {
                if (sanitized.contains(c)) {
                    check1 = c;
                    break;
                }
            }
            if (check1.isEmpty() && exchange.statusText != null && !exchange.statusText.isEmpty() && !isStopWord(exchange.statusText)) {
                check1 = exchange.statusText;
            }
        }
        if (check1.isEmpty()) {
            check1 = "NotFoundResponseData";
        }

        String check2 = "";
        if (hasUser2) {
            check2 = userCheck2.trim();
        } else if (hasResponse) {
            for (String c : candidates) {
                if (sanitized.contains(c) && !c.equalsIgnoreCase(check1)) {
                    check2 = c;
                    break;
                }
            }
        }
        if (check2.isEmpty()) {
            check2 = "NotFoundResponseData";
        }

        return new String[]{check1, check2};
    }

    private List<String> extractTextCheckCandidates(String mimeType, String responseText) {
        List<String> candidates = new ArrayList<>();
        if (responseText == null || responseText.trim().isEmpty()) {
            return candidates;
        }

        String sanitized = sanitizeForGrafana(responseText);
        String trimmed = responseText.trim();
        if ((mimeType != null && mimeType.toLowerCase().contains("json")) ||
                (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonNode root = objectMapper.readTree(responseText);
                collectJsonCandidates(root, candidates);
            } catch (Exception ignored) {
            }
        }

        if ((mimeType != null && mimeType.toLowerCase().contains("html")) || responseText.contains("<html")) {
            collectHtmlCandidates(responseText, candidates);
        }

        Pattern wordPattern = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]{2,25}\\b");
        Matcher m = wordPattern.matcher(responseText);
        while (m.find()) {
            String w = m.group();
            if (!isStopWord(w) && sanitized.contains(w) && !candidates.contains(w)) {
                candidates.add(w);
            }
        }

        return candidates;
    }

    private void collectJsonCandidates(JsonNode node, List<String> candidates) {
        if (node.isObject()) {
            List<String> priorityKeys = Arrays.asList("status", "message", "result", "success", "title", "name", "code", "type", "action", "state");
            for (String pKey : priorityKeys) {
                JsonNode valNode = node.get(pKey);
                if (valNode != null && valNode.isTextual()) {
                    addCleanCandidateWords(valNode.asText(), candidates);
                }
            }
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (val.isTextual()) {
                    addCleanCandidateWords(val.asText(), candidates);
                }
                if (!isStopWord(key) && key.length() >= 3 && !candidates.contains(key)) {
                    candidates.add(key);
                }
                if (val.isContainerNode()) {
                    collectJsonCandidates(val, candidates);
                }
            });
        } else if (node.isArray()) {
            int count = 0;
            for (JsonNode item : node) {
                collectJsonCandidates(item, candidates);
                if (++count > 10) break;
            }
        }
    }

    private void collectHtmlCandidates(String html, List<String> candidates) {
        Matcher titleMatcher = Pattern.compile("(?i)<title[^>]*>(.*?)</title>").matcher(html);
        if (titleMatcher.find()) {
            addCleanCandidateWords(titleMatcher.group(1), candidates);
        }
        Matcher hMatcher = Pattern.compile("(?i)<h[12][^>]*>(.*?)</h[12]>").matcher(html);
        while (hMatcher.find()) {
            addCleanCandidateWords(hMatcher.group(1), candidates);
        }
        Matcher btnMatcher = Pattern.compile("(?i)(?:type=[\"']submit[\"']|class=[\"'][^\"']*btn[^\"']*[\"'])[^>]*value=[\"']([^\"']+)[\"']").matcher(html);
        while (btnMatcher.find()) {
            addCleanCandidateWords(btnMatcher.group(1), candidates);
        }
    }

    private void addCleanCandidateWords(String text, List<String> candidates) {
        if (text == null || text.trim().isEmpty()) return;
        String[] parts = text.split("[^A-Za-z0-9_]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() >= 3 && trimmed.length() <= 30 && !isStopWord(trimmed) && !candidates.contains(trimmed)) {
                candidates.add(trimmed);
            }
        }
        if (!text.matches(".*[\\r\\n,\\-?'{}/\"\\\\].*") && text.trim().length() >= 3 && text.trim().length() <= 30) {
            String full = text.trim();
            if (!isStopWord(full) && !candidates.contains(full)) {
                candidates.add(full);
            }
        }
    }

    private String escapeGroovyString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("$", "\\$")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
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
        private final int statusCode;
        private final String statusText;

        private HarExchange(String fileName, String url, String mimeType, String responseText, int statusCode, String statusText) {
            this.fileName = fileName;
            this.url = url;
            this.mimeType = mimeType;
            this.responseText = responseText;
            this.statusCode = statusCode;
            this.statusText = statusText != null ? statusText : "";
        }

        private HarExchange(String fileName, String url, String mimeType, String responseText, int statusCode) {
            this(fileName, url, mimeType, responseText, statusCode, "");
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