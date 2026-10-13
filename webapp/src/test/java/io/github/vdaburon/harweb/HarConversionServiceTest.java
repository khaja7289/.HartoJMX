package io.github.vdaburon.harweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarConversionServiceTest {
    @Test
    void convertsOrderedHarFilesIntoOneJmxWithNamedTransactions() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_launch.har", "https://example.test/launch", "p1", "token-xyz-12345678");
        MultipartFile login = harFile("T02_login.har", "https://example.test/login?auth=token-xyz-12345678", "p2", "");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }
            NodeList controllers = document.getElementsByTagName("TransactionController");

            assertEquals(2, controllers.getLength());
            assertEquals("T01_launch", controllers.item(0).getAttributes().getNamedItem("testname").getNodeValue());
            assertEquals("T02_login", controllers.item(1).getAttributes().getNamedItem("testname").getNodeValue());
            assertTrue(document.getElementsByTagName("HTTPSamplerProxy").getLength() >= 2);
            assertEquals(1, document.getElementsByTagName("ThreadGroup").getLength());
            assertEquals(0, document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").getLength());
        }
    }

    @Test
    void appliesCorrelationsIntoJmxWhenAgreed() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_launch.har", "https://example.test/launch", "p1", "token-xyz-12345678");
        MultipartFile login = harFile("T02_login.har", "https://example.test/login?auth=token-xyz-12345678", "p2", "");

        List<Map<String, String>> candidates = service.analyze(Arrays.asList(launch, login));
        assertFalse(candidates.isEmpty());
        String correlationId = candidates.get(0).get("id");
        String variable = candidates.get(0).get("variable");
        assertNotNull(correlationId);
        assertNotNull(variable);

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "", false, "load", 10, 600, 60, 30,
                Collections.singletonList(correlationId), "OK", "success")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList jsonExtractors = document.getElementsByTagName("JSONPostProcessor");
            assertEquals(1, jsonExtractors.getLength(), "JSONPostProcessor should be added to source sampler for JSON response");
            assertEquals("JSON Extractor - " + variable, jsonExtractors.item(0).getAttributes().getNamedItem("testname").getNodeValue());
            assertTrue(variable.startsWith("C_"), "Variable name should start with C_");

            NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
            boolean foundCorrelated = false;
            for (int i = 0; i < samplers.getLength(); i++) {
                Element s = (Element) samplers.item(i);
                if (s.getTextContent().contains("${" + variable + "}") || s.getAttribute("testname").contains("${" + variable + "}")) {
                    foundCorrelated = true;
                }
            }
            assertTrue(foundCorrelated, "Target sampler should use the correlated variable reference");
            assertFalse(samplers.item(1).getTextContent().contains("token-xyz-12345678"),
                    "Target sampler should not have the raw correlated token");

            NodeList assertions = document.getElementsByTagName("BeanShellAssertion");
            assertTrue(assertions.getLength() >= 2);
            String assertionQuery = assertions.item(0).getTextContent();
            assertTrue(assertionQuery.contains("code < 200 || code >= 400"), "BeanShell assertion should accept 2xx and 3xx");
            assertTrue(assertionQuery.contains("OK") && assertionQuery.contains("success"),
                    "BeanShell assertion should check both text values");
        }
    }

    @Test
    void appliesRegexExtractorForHtmlOrNonJsonResponse() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = htmlHarFile("T01_launch.har", "https://example.test/launch", "p1", "csrf-token-xyz-98765432");
        MultipartFile login = harFile("T02_login.har", "https://example.test/login?csrf=csrf-token-xyz-98765432", "p2", "");

        List<Map<String, String>> candidates = service.analyze(Arrays.asList(launch, login));
        assertFalse(candidates.isEmpty());
        String correlationId = candidates.get(0).get("id");
        String variable = candidates.get(0).get("variable");
        assertEquals("REGEX", candidates.get(0).get("extractorType"));
        assertTrue(variable.startsWith("C_"), "Variable should start with C_");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "", false, "load", 10, 600, 60, 30,
                Collections.singletonList(correlationId), "OK", "success")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList regexExtractors = document.getElementsByTagName("RegexExtractor");
            assertEquals(1, regexExtractors.getLength(), "RegexExtractor should be added for non-JSON response");
            assertEquals("Regex Extractor - " + variable, regexExtractors.item(0).getAttributes().getNamedItem("testname").getNodeValue());
        }
    }

    @Test
    void manualCorrelationLeavesTokensUnmodified() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_launch.har", "https://example.test/launch", "p1", "token-xyz-12345678");
        MultipartFile login = harFile("T02_login.har", "https://example.test/login?auth=token-xyz-12345678", "p2", "");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "", false, "load", 10, 600, 60, 30,
                Collections.emptyList(), "", "")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            assertEquals(0, document.getElementsByTagName("RegexExtractor").getLength(),
                    "No RegexExtractor should be added when correlation is ignored");
        }
    }

    @Test
    void scenarioEnabledCreatesUltimateThreadGroup() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_launch.har", "https://example.test/launch", "p1", "token-xyz-12345678");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Collections.singletonList(launch), "", "", "", true, "load", 50, 300, 30, 20,
                Collections.emptyList(), "", "")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            assertEquals(1, document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").getLength());
            assertEquals(0, document.getElementsByTagName("ThreadGroup").getLength());
        }
    }

    private MultipartFile harFile(String name, String url, String pageId, String tokenValue) {
        String responseBody = tokenValue.isEmpty() ? "{}" : "{\"auth_token\":\"" + tokenValue + "\"}";
        String queryJson = "[]";
        if (url.contains("?")) {
            String query = url.substring(url.indexOf('?') + 1);
            String[] parts = query.split("&");
            StringBuilder qb = new StringBuilder("[");
            for (int i = 0; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (i > 0) qb.append(",");
                qb.append("{\"name\":\"").append(kv[0]).append("\",\"value\":\"").append(kv.length > 1 ? kv[1] : "").append("\"}");
            }
            qb.append("]");
            queryJson = qb.toString();
        }
        String har = "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"test\",\"version\":\"1\"},"
                + "\"pages\":[{\"startedDateTime\":\"2024-01-01T00:00:00.000Z\",\"id\":\"" + pageId
                + "\",\"title\":\"" + url + "\",\"pageTimings\":{\"onContentLoad\":-1,\"onLoad\":-1}}],"
                + "\"entries\":[{\"startedDateTime\":\"2024-01-01T00:00:00.000Z\",\"time\":2,\"pageref\":\"" + pageId
                + "\",\"request\":{\"method\":\"GET\",\"url\":\"" + url
                + "\",\"httpVersion\":\"HTTP/1.1\",\"cookies\":[],\"headers\":[],\"queryString\":" + queryJson + ",\"headersSize\":-1,\"bodySize\":0},"
                + "\"response\":{\"status\":200,\"statusText\":\"OK\",\"httpVersion\":\"HTTP/1.1\",\"cookies\":[],\"headers\":[],"
                + "\"content\":{\"size\":" + responseBody.length() + ",\"mimeType\":\"application/json\",\"text\":\"" + responseBody.replace("\"", "\\\"") + "\"},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":0},"
                + "\"cache\":{},\"timings\":{\"send\":0,\"wait\":1,\"receive\":1}}]}}";
        return new MockMultipartFile("files", name, "application/json", har.getBytes(StandardCharsets.UTF_8));
    }

    private MultipartFile htmlHarFile(String name, String url, String pageId, String tokenValue) {
        String responseBody = "<html><body><input type=\"hidden\" name=\"csrf_token\" value=\"" + tokenValue + "\"/></body></html>";
        String har = "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"test\",\"version\":\"1\"},"
                + "\"pages\":[{\"startedDateTime\":\"2024-01-01T00:00:00.000Z\",\"id\":\"" + pageId
                + "\",\"title\":\"" + url + "\",\"pageTimings\":{\"onContentLoad\":-1,\"onLoad\":-1}}],"
                + "\"entries\":[{\"startedDateTime\":\"2024-01-01T00:00:00.000Z\",\"time\":2,\"pageref\":\"" + pageId
                + "\",\"request\":{\"method\":\"GET\",\"url\":\"" + url
                + "\",\"httpVersion\":\"HTTP/1.1\",\"cookies\":[],\"headers\":[],\"queryString\":[],\"headersSize\":-1,\"bodySize\":0},"
                + "\"response\":{\"status\":200,\"statusText\":\"OK\",\"httpVersion\":\"HTTP/1.1\",\"cookies\":[],\"headers\":[],"
                + "\"content\":{\"size\":" + responseBody.length() + ",\"mimeType\":\"text/html\",\"text\":\"" + responseBody.replace("\"", "\\\"") + "\"},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":0},"
                + "\"cache\":{},\"timings\":{\"send\":0,\"wait\":1,\"receive\":1}}]}}";
        return new MockMultipartFile("files", name, "application/json", har.getBytes(StandardCharsets.UTF_8));
    }
}