package io.github.vdaburon.harweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HarConvertorWebIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesAnonymousUploadUi() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Add recordings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Analyze HARs")));
    }

    @Test
    void analyzesHarUploadsForCorrelationCandidates() throws Exception {
        byte[] launchBytes = Files.readAllBytes(new ClassPathResource("fixtures/T01_launch.har").getFile().toPath());
        byte[] loginBytes = Files.readAllBytes(new ClassPathResource("fixtures/T02_login.har").getFile().toPath());
        MockMultipartFile launch = new MockMultipartFile("files", "T01_launch.har", "application/json", launchBytes);
        MockMultipartFile login = new MockMultipartFile("files", "T02_login.har", "application/json", loginBytes);

        String json = mockMvc.perform(multipart("/api/analyze").file(launch).file(login))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        JsonNode candidates = new ObjectMapper().readTree(json);
        assertTrue(candidates.isArray());
        assertTrue(candidates.size() >= 1);
        assertEquals("candidate-session-token-123456", candidates.get(0).path("value").asText());
    }

    @Test
    void acceptsTwoAnonymousHarUploadsAndReturnsDirectJmxDownloadWithCorrelations() throws Exception {
        byte[] launchBytes = Files.readAllBytes(new ClassPathResource("fixtures/T01_launch.har").getFile().toPath());
        byte[] loginBytes = Files.readAllBytes(new ClassPathResource("fixtures/T02_login.har").getFile().toPath());
        MockMultipartFile launch = new MockMultipartFile("files", "T01_launch.har", "application/json", launchBytes);
        MockMultipartFile login = new MockMultipartFile("files", "T02_login.har", "application/json", loginBytes);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(multipart("/api/convert").file(launch).file(login)
                        .param("scenarioEnabled", "true")
                        .param("scenario", "scalability").param("users", "12")
                        .param("executeTime", "600").param("rampUp", "60").param("rampDown", "30")
                        .param("correlationIds", "corr_1_access_token")
                        .param("responseMessageOne", "OK")
                        .param("responseMessageTwo", "access_token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apache.jmeter.jmx+xml"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"har-convertor-script.jmx\""))
                .andReturn();

        byte[] jmxBytes = result.getResponse().getContentAsByteArray();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(jmxBytes));
        NodeList controllers = document.getElementsByTagName("TransactionController");

        assertEquals(2, controllers.getLength());
        assertEquals("T01_launch", controllers.item(0).getAttributes().getNamedItem("testname").getNodeValue());
        assertEquals("T02_login", controllers.item(1).getAttributes().getNamedItem("testname").getNodeValue());

        NodeList ultimateGroups = document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup");
        assertEquals(1, ultimateGroups.getLength());
        assertEquals(4, ((Element) ultimateGroups.item(0)).getElementsByTagName("collectionProp").getLength() - 1);

        NodeList samplers = document.getElementsByTagName("HTTPSamplerProxy");
        assertTrue(samplers.getLength() >= 2);

        NodeList jsonExtractors = document.getElementsByTagName("JSONPostProcessor");
        assertEquals(1, jsonExtractors.getLength(), "JSONPostProcessor should be added to reflect correlation");
        assertEquals("JSON Extractor - C_bearerToken", jsonExtractors.item(0).getAttributes().getNamedItem("testname").getNodeValue());

        // Subsequent sampler URL should have token substituted with variable reference
        Element targetSampler = (Element) samplers.item(1);
        String targetXml = targetSampler.getTextContent();
        assertTrue(targetXml.contains("${C_bearerToken}") || targetSampler.getAttribute("testname").contains("${C_bearerToken}"),
                "Dynamic token should be replaced with variable in target sampler");

        NodeList assertions = document.getElementsByTagName("BeanShellAssertion");
        assertEquals(samplers.getLength(), assertions.getLength());
        String assertionText = assertions.item(0).getTextContent();
        assertTrue(assertionText.contains("code < 200 || code >= 400"), "Assertion must check 2xx/3xx codes");
        assertTrue(assertionText.contains("OK") && assertionText.contains("access_token"), "Assertion must check both text checks");
    }

    @Test
    void convertsWithoutScenarioWhenScenarioUnchecked() throws Exception {
        byte[] launchBytes = Files.readAllBytes(new ClassPathResource("fixtures/T01_launch.har").getFile().toPath());
        MockMultipartFile launch = new MockMultipartFile("files", "T01_launch.har", "application/json", launchBytes);

        byte[] jmxBytes = mockMvc.perform(multipart("/api/convert").file(launch)
                        .param("scenarioEnabled", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apache.jmeter.jmx+xml"))
                .andReturn().getResponse().getContentAsByteArray();

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(jmxBytes));
        assertEquals(1, document.getElementsByTagName("ThreadGroup").getLength());
        assertEquals(0, document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup").getLength());
    }

    @Test
    void acceptsMoreThanTenHarFiles() throws Exception {
        byte[] harBytes = Files.readAllBytes(new ClassPathResource("fixtures/T01_launch.har").getFile().toPath());
        List<MockMultipartFile> uploads = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            uploads.add(new MockMultipartFile("files", String.format("T%02d_step.har", i), "application/json", harBytes));
        }

        MockMultipartHttpServletRequestBuilder request = multipart("/api/convert");
        for (MockMultipartFile upload : uploads) {
            request.file(upload);
        }
        byte[] jmx = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apache.jmeter.jmx+xml"))
                .andReturn().getResponse().getContentAsByteArray();

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(jmx));
        NodeList controllers = document.getElementsByTagName("TransactionController");
        assertEquals(11, controllers.getLength());
        assertEquals("T11_step", controllers.item(10).getAttributes().getNamedItem("testname").getNodeValue());
    }

    @Test
    void scenarioProfilesCreateExpectedScheduleRows() throws Exception {
        String[] scenarios = {"load", "scalability", "spike", "endurance", "stress"};
        int[] expectedRows = {1, 4, 2, 1, 4};
        MockMultipartFile har = new MockMultipartFile("files", "journey.har", "application/json",
                Files.readAllBytes(new ClassPathResource("fixtures/T01_launch.har").getFile().toPath()));
        for (int i = 0; i < scenarios.length; i++) {
            byte[] jmx = mockMvc.perform(multipart("/api/convert").file(har)
                            .param("scenarioEnabled", "true")
                            .param("scenario", scenarios[i]).param("users", "20")
                            .param("executeTime", "600").param("rampUp", "60").param("rampDown", "30"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(jmx));
            NodeList ultimateGroups = document.getElementsByTagName("kg.apc.jmeter.threads.UltimateThreadGroup");
            assertEquals(1, ultimateGroups.getLength());
            Element schedule = (Element) ((Element) ultimateGroups.item(0)).getElementsByTagName("collectionProp").item(0);
            assertEquals(expectedRows[i], schedule.getElementsByTagName("collectionProp").getLength());
        }
    }
}