# HAR to JMeter Web App

This standalone Spring Boot 3.5 application provides a browser UI for anonymous HAR-to-JMX conversion. It uses the same `har-to-jmeter-convertor` library as the JMeter plugin and does not alter the plugin's packaging or installation behavior. The web app requires Java 17 or later; the existing JMeter plugin keeps its Java 8 target.

## Run locally

From this directory, build and run the executable application:

```powershell
mvn clean package
java -jar target/har-convertor-webapp-10.0.0.jar
```

## Run with Docker

### Using Docker Compose (Recommended)
From the repository root:
```bash
docker compose up -d --build
```
Then navigate to <http://localhost:8080>.

To stop:
```bash
docker compose down
```

### Using Docker CLI
Build the image (from the repository root):
```bash
docker build -t har-convertor-webapp .
```
*(Or from within `webapp/`: `docker build -t har-convertor-webapp .`)*

Run the container:
```bash
docker run -d -p 8080:8080 --name har-convertor-webapp har-convertor-webapp
```

To configure custom environment variables (e.g. port or memory limits):
```bash
docker run -d -p 9090:9090 \
  -e PORT=9090 \
  -e JAVA_OPTS="-Xms256m -Xmx1024m" \
  --name har-convertor-webapp har-convertor-webapp
```


Open <http://localhost:8080>. Select one or more `.har` files. Filenames are sorted naturally at selection time, and the list can be reordered before conversion. Each input HAR becomes one transaction controller named `T01_<filename>`, `T02_<filename>`, and so on. The ZIP download contains `har-convertor-script.jmx` and `correlation-candidates.json`.

Choose a scenario profile and enter peak users, execute time, ramp-up, and ramp-down in seconds. Execute time is total wall-clock duration for Load, Scalability, Spike, and Stress, so ramp-up plus ramp-down must fit inside it. For Endurance, execute time is the full-load hold duration, and the ramps are additional.

| Profile | Generated schedule |
| --- | --- |
| Load | One group ramps to the target, holds, then ramps down |
| Scalability | Up to four gradual user cohorts ramp across the ramp-up window, then hold the target |
| Spike | A 10% baseline with the remaining users added for a short peak near the end |
| Endurance | One group ramps to the target and holds it for the full execute time, with ramps additional |
| Stress | Up to four abrupt user cohorts are added across the ramp-up window, then held |

Execute time includes ramp-up and ramp-down. The test plan uses the Ultimate Thread Group and requires JMeter Plugins Manager's **Custom Thread Groups** plugin (`jpgc-casutg`). Every generated HTTP sampler also receives a BeanShell Assertion expecting response code `200` and response message `OK`.

Correlation detection compares values in HAR responses against subsequent HAR requests. It identifies dynamic parameters and presents them in an interactive review step with standardized technology-based variable naming (`C_<variableName>`, e.g., `C_bearerToken`, `C_csrfToken`, `C_sessionId`). When auto-correlation is confirmed, the converter automatically:
- Inserts a **JSON Extractor** (`JSONPostProcessor`) with the resolved JSONPath (e.g., `$.access_token`) for JSON responses.
- Inserts a **Regex Extractor** (`RegexExtractor`) for HTML or text responses.
- Replaces downstream occurrences of the captured values with the variable reference `${C_<variableName>}` across subsequent HTTP requests.
- Full details are also exported in `correlation-candidates.json`. Users can choose to auto-correlate all, select specific tokens, or ignore to maintain manual correlation.

## Public deployment notes

The app intentionally has no login yet. There is no HAR file-count limit; each file is limited to 25 MB and each request to 100 MB total. It allows at most two concurrent conversions and limits each remote address to eight conversion attempts per ten minutes. HAR inputs and generated JMX files are kept in a temporary directory and removed after the download stream completes.

Do not expose the application directly to the public internet without a TLS-terminating reverse proxy, request-body and connection limits, and infrastructure-level abuse protection. HAR files often contain authentication cookies, tokens, and personal data. Forwarded headers are ignored by default. If the app sits behind a trusted proxy, configure it to remove incoming forwarding headers and set its own, then set `FORWARD_HEADERS_STRATEGY=framework`; the app rate-limits using the address seen by the servlet container.

Set `PORT` to change the listening port. Set `FORWARD_HEADERS_STRATEGY=framework` only behind a trusted header-sanitizing proxy. Spring multipart storage uses the system temporary directory by default.