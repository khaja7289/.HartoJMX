package io.github.vdaburon.harweb;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class HarConvertorController {
    private final HarConversionService conversionService;
    private final UploadRateLimiter rateLimiter;

    public HarConvertorController(HarConversionService conversionService, UploadRateLimiter rateLimiter) {
        this.conversionService = conversionService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/config")
    public ResponseEntity<?> config() {
        String geminiKey = System.getenv("GEMINI_API_KEY");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("geminiKey", geminiKey != null ? geminiKey.trim() : "");
        cfg.put("hasGeminiKey", geminiKey != null && !geminiKey.trim().isEmpty());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(cfg);
    }

    @PostMapping(value = "/api/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyze(@RequestParam("files") List<MultipartFile> files,
                                     HttpServletRequest request) {
        if (!rateLimiter.allow(request.getRemoteAddr())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Upload limit reached. Try again in a few minutes.");
        }
        try {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(conversionService.analyze(files));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "Could not analyze the HAR files.");
        }
    }

    @PostMapping(value = "/api/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(@RequestParam("files") List<MultipartFile> files,
                                     @RequestParam(value = "filterInclude", required = false, defaultValue = "") String filterInclude,
                                     @RequestParam(value = "filterExclude", required = false, defaultValue = "") String filterExclude,
                                     @RequestParam(value = "removeHeaders", required = false, defaultValue = "") String removeHeaders,
                                     @RequestParam(value = "scenarioEnabled", defaultValue = "false") boolean scenarioEnabled,
                                     @RequestParam(value = "scenario", defaultValue = "load") String scenario,
                                     @RequestParam(value = "users", defaultValue = "1") int users,
                                     @RequestParam(value = "tph", defaultValue = "10") int tph,
                                     @RequestParam(value = "executeTime", defaultValue = "600") int executeTime,
                                     @RequestParam(value = "rampUp", defaultValue = "5") int rampUp,
                                     @RequestParam(value = "rampDown", defaultValue = "10") int rampDown,
                                     @RequestParam(value = "correlationIds", required = false) List<String> correlationIds,
                                     @RequestParam(value = "responseMessageOne", required = false, defaultValue = "") String responseMessageOne,
                                     @RequestParam(value = "responseMessageTwo", required = false, defaultValue = "") String responseMessageTwo,
                                     HttpServletRequest request) {
        if (!rateLimiter.allow(request.getRemoteAddr())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Upload limit reached. Try again in a few minutes.");
        }

        HarConversionService.ConversionArtifact artifact;
        try {
            artifact = conversionService.convert(files, filterInclude, filterExclude, removeHeaders,
                    scenarioEnabled, scenario, users, tph, executeTime, rampUp, rampDown, correlationIds,
                    responseMessageOne, responseMessageTwo);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (ConversionBusyException ex) {
            return error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "Conversion failed. Check that each file is a valid HAR export.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.apache.jmeter.jmx+xml"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("har-convertor-script.jmx").build());
        headers.setCacheControl("no-store");
        InputStream fileStream;
        try {
            fileStream = Files.newInputStream(artifact.getDownloadPath());
        } catch (IOException ex) {
            artifact.close();
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare the JMX download.");
        }
        InputStream inputStream = new FilterInputStream(fileStream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    artifact.close();
                }
            }
        };
        Resource resource = new InputStreamResource(inputStream);
        return new ResponseEntity<Resource>(resource, headers, HttpStatus.OK);
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Collections.singletonMap("message", message));
    }
}