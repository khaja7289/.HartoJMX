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
            assertEquals(1, document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").getLength());

            NodeList moduleControllers = document.getElementsByTagName("ModuleController");
            assertTrue(moduleControllers.getLength() >= 4);

            NodeList args = document.getElementsByTagName("Arguments");
            boolean foundWlm = false;
            for (int i = 0; i < args.getLength(); i++) {
                Element arg = (Element) args.item(i);
                if ("WLM".equals(arg.getAttribute("testname"))) {
                    foundWlm = true;
                    assertTrue(arg.getTextContent().contains("Test_Users"));
                    assertTrue(arg.getTextContent().contains("Test_TPH"));
                }
            }
            assertTrue(foundWlm, "WLM element should exist with Test_Users and Test_TPH");
        }
    }

    @Test
    void unchecksRedirectionOnlyFor302Calls() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile redirect = harFile("T01_redirect.har", "https://example.test/redir", "p1", "", 302);
        MultipartFile ok = harFile("T02_ok.har", "https://example.test/page", "p2", "", 200);

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(redirect, ok), "", "", "")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
            assertEquals(2, samplers.getLength());

            Element s1 = (Element) samplers.item(0);
            Element s2 = (Element) samplers.item(1);

            assertEquals("false", getBoolProp(s1, "HTTPSampler.follow_redirects"), "302 request must NOT follow redirects (unchecked)");
            assertEquals("false", getBoolProp(s1, "HTTPSampler.auto_redirects"), "auto_redirects must be false");

            assertEquals("true", getBoolProp(s2, "HTTPSampler.follow_redirects"), "200 request must follow redirects (checked)");
            assertEquals("false", getBoolProp(s2, "HTTPSampler.auto_redirects"), "auto_redirects must be false");
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
                Arrays.asList(launch, login), "", "", "", false, "load", 1, 10, 600, 5, 10,
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

            NodeList assertions = document.getElementsByTagName("JSR223Assertion");
            assertTrue(assertions.getLength() >= 2);
            String assertionQuery = assertions.item(0).getTextContent();
            assertTrue(assertionQuery.contains("OK_CODES = [\"200\", \"201\", \"202\", \"204\", \"302\"] as Set"),
                    "JSR223 assertion should accept 2xx and 302");
            assertTrue(assertionQuery.contains("String TXN_NAME = \"T01_launch\""), "Assertion should have transaction name");
            assertTrue(assertionQuery.contains("String TEXT_CHECK = \"OK\""), "Assertion should have TEXT_CHECK");
            assertTrue(assertionQuery.contains("String TEXT_CHECK1 = \"success\""), "Assertion should have TEXT_CHECK1");
        }
    }

    @Test
    void modifiesTransactionNamesAndTwoTextChecksInAssertions() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_home_page.har", "https://example.test/home", "p1", "some content");
        MultipartFile login = harFile("T02_user_auth.har", "https://example.test/auth", "p2", "token");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "", false, "load", 1, 10, 600, 5, 10,
                Collections.emptyList(), "submit", "function")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList assertions = document.getElementsByTagName("JSR223Assertion");
            assertTrue(assertions.getLength() >= 2);

            String a1 = assertions.item(0).getTextContent();
            assertTrue(a1.contains("String TXN_NAME = \"T01_home_page\""), "Assertion 1 should have transaction name T01_home_page");
            assertTrue(a1.contains("String TEXT_CHECK = \"submit\""), "Assertion 1 should have text check 1 submit");
            assertTrue(a1.contains("String TEXT_CHECK1 = \"function\""), "Assertion 1 should have text check 2 function");

            String a2 = assertions.item(1).getTextContent();
            assertTrue(a2.contains("String TXN_NAME = \"T02_user_auth\""), "Assertion 2 should have transaction name T02_user_auth");
            assertTrue(a2.contains("String TEXT_CHECK = \"submit\""), "Assertion 2 should have text check 1 submit");
            assertTrue(a2.contains("String TEXT_CHECK1 = \"function\""), "Assertion 2 should have text check 2 function");
        }
    }

    @Test
    void autoIdentifiesTwoDifferentTextChecksFromHarResponseWhenBlank() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile launch = harFile("T01_home_page.har", "https://example.test/home", "p1",
                "{\"status\":\"active\",\"message\":\"Welcome user dashboard\"}");
        MultipartFile login = harFile("T02_user_auth.har", "https://example.test/auth", "p2",
                "{\"result\":\"success\",\"code\":\"authorized\"}");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Arrays.asList(launch, login), "", "", "", false, "load", 1, 10, 600, 5, 10,
                Collections.emptyList(), "", "")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList assertions = document.getElementsByTagName("JSR223Assertion");
            assertTrue(assertions.getLength() >= 2);

            String a1 = assertions.item(0).getTextContent();
            assertTrue(a1.contains("String TXN_NAME = \"T01_home_page\""));
            java.util.regex.Matcher m1_c1 = java.util.regex.Pattern.compile("String TEXT_CHECK = \"([^\"]+)\"").matcher(a1);
            java.util.regex.Matcher m1_c2 = java.util.regex.Pattern.compile("String TEXT_CHECK1 = \"([^\"]+)\"").matcher(a1);
            assertTrue(m1_c1.find(), "TEXT_CHECK should be non-empty and auto-identified");
            assertTrue(m1_c2.find(), "TEXT_CHECK1 should be non-empty and auto-identified");
            String check1 = m1_c1.group(1);
            String check2 = m1_c2.group(1);
            assertFalse(check1.equalsIgnoreCase(check2), "Text check 1 and 2 must be different");

            String a2 = assertions.item(1).getTextContent();
            assertTrue(a2.contains("String TXN_NAME = \"T02_user_auth\""));
            java.util.regex.Matcher m2_c1 = java.util.regex.Pattern.compile("String TEXT_CHECK = \"([^\"]+)\"").matcher(a2);
            java.util.regex.Matcher m2_c2 = java.util.regex.Pattern.compile("String TEXT_CHECK1 = \"([^\"]+)\"").matcher(a2);
            assertTrue(m2_c1.find(), "TEXT_CHECK should be non-empty and auto-identified");
            assertTrue(m2_c2.find(), "TEXT_CHECK1 should be non-empty and auto-identified");
            String check2_1 = m2_c1.group(1);
            String check2_2 = m2_c2.group(1);
            assertFalse(check2_1.equalsIgnoreCase(check2_2), "Text check 1 and 2 must be different in transaction 2");
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
                Arrays.asList(launch, login), "", "", "", false, "load", 1, 10, 600, 5, 10,
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
                Arrays.asList(launch, login), "", "", "", false, "load", 1, 10, 600, 5, 10,
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
                Collections.singletonList(launch), "", "", "", true, "load", 50, 100, 300, 30, 20,
                Collections.emptyList(), "", "")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            assertEquals(1, document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").getLength());
        }
    }

    private String getBoolProp(Element element, String name) {
        NodeList boolProps = element.getElementsByTagName("boolProp");
        for (int i = 0; i < boolProps.getLength(); i++) {
            Element bp = (Element) boolProps.item(i);
            if (name.equals(bp.getAttribute("name"))) {
                return bp.getTextContent();
            }
        }
        return "";
    }

    private MultipartFile harFile(String name, String url, String pageId, String tokenValue) {
        return harFile(name, url, pageId, tokenValue, 200);
    }

    private MultipartFile harFile(String name, String url, String pageId, String tokenValue, int status) {
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
                + "\"response\":{\"status\":" + status + ",\"statusText\":\"OK\",\"httpVersion\":\"HTTP/1.1\",\"cookies\":[],\"headers\":[],"
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

    @Test
    void setsNotFoundResponseDataWhenResponseDataNotAvailable() throws Exception {
        HarConversionService service = new HarConversionService(new ObjectMapper());
        MultipartFile noDataHar = harFile("T01_no_body.har", "https://example.test/api/empty", "p1", "");

        try (HarConversionService.ConversionArtifact artifact = service.convert(
                Collections.singletonList(noDataHar), "", "", "", false, "load", 1, 10, 600, 5, 10,
                Collections.emptyList(), "", "")) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document;
            try (InputStream input = java.nio.file.Files.newInputStream(artifact.getJmxPath())) {
                document = factory.newDocumentBuilder().parse(input);
            }

            NodeList assertions = document.getElementsByTagName("JSR223Assertion");
            assertTrue(assertions.getLength() >= 1);
            String script = assertions.item(0).getTextContent();
            assertTrue(script.contains("String TEXT_CHECK = \"NotFoundResponseData\""),
                    "Should set TEXT_CHECK to NotFoundResponseData when response has no body");
            assertTrue(script.contains("String TEXT_CHECK1 = \"NotFoundResponseData\""),
                    "Should set TEXT_CHECK1 to NotFoundResponseData when response has no body");
            assertTrue(script.contains("TEXT_CHECK.equals(\"NotFoundResponseData\")"),
                    "Groovy validation should accept NotFoundResponseData");
        }
    }
}