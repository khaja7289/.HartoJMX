package io.github.vdaburon.harweb;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class UploadRateLimiter {
    private static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final int REQUESTS_PER_WINDOW = 60;
    private static final int MAX_TRACKED_CLIENTS = 10000;
    private final Map<String, ClientWindow> clients = new HashMap<>();

    public synchronized boolean allow(String clientAddress) {
        long now = System.currentTimeMillis();
        ClientWindow window = clients.get(clientAddress);
        if (window == null || now - window.startedAt >= WINDOW_MILLIS) {
            prune(now);
            if (clients.size() >= MAX_TRACKED_CLIENTS) {
                return false;
            }
            clients.put(clientAddress, new ClientWindow(now));
            return true;
        }
        if (window.requests >= REQUESTS_PER_WINDOW) {
            return false;
        }
        window.requests++;
        return true;
    }

    public synchronized void reset() {
        clients.clear();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, ClientWindow>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().startedAt >= WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static final class ClientWindow {
        private final long startedAt;
        private int requests = 1;

        private ClientWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}