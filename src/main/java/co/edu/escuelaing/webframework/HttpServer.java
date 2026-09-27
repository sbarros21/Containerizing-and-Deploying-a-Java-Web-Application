package co.edu.escuelaing.webframework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent HTTP server. Each accepted connection is handled on a
 * separate worker thread from a fixed-size thread pool, so multiple
 * clients can be served at the same time. Shutdown remains graceful: the
 * listening socket is closed first (no new connections accepted), and the
 * thread pool is given time to finish in-flight requests before exiting.
 */
public class HttpServer {

    private static final int THREAD_POOL_SIZE = 20;

    private final Router router;
    private final StaticFileService staticFileService;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public HttpServer(Router router, StaticFileService staticFileService) {
        this.router = router;
        this.staticFileService = staticFileService;
    }

    public void start(int port) throws IOException {
        running = true;
        try (ServerSocket socket = new ServerSocket(port)) {
            this.serverSocket = socket;
            System.out.println("Server listening on port " + port
                    + " with a pool of " + THREAD_POOL_SIZE + " worker threads...");

            while (running) {
                try {
                    Socket clientSocket = socket.accept();
                    workerPool.submit(() -> handleClientSafely(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                }
            }
        }

        shutdownWorkerPool();
        System.out.println("Server stopped gracefully.");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void shutdownWorkerPool() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void handleClientSafely(Socket clientSocket) {
        try {
            handleClient(clientSocket);
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private void handleClient(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = clientSocket.getOutputStream();

        String requestLine = in.readLine();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // Drain remaining headers.
        }

        if (requestLine == null || requestLine.isBlank()) {
            return;
        }
        System.out.println("[" + Thread.currentThread().getName() + "] Request line: " + requestLine);

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            sendError(out, 400, "Bad Request");
            return;
        }

        String method = parts[0];
        String rawPath = parts[1];

        if (!"GET".equalsIgnoreCase(method)) {
            sendError(out, 405, "Method Not Allowed");
            return;
        }

        String pathOnly = rawPath.contains("?")
                ? rawPath.substring(0, rawPath.indexOf('?'))
                : rawPath;
        String decodedPath = URLDecoder.decode(pathOnly, StandardCharsets.UTF_8);
        Map<String, String> queryParams = parseQuery(rawPath);

        Route route = router.resolve(decodedPath);
        if (route != null) {
            Request request = new Request(decodedPath, queryParams);
            Response response = new Response();
            String body;
            try {
                body = route.getService().handle(request, response);
            } catch (Exception e) {
                System.err.println("Error executing route " + decodedPath + ": " + e.getMessage());
                sendError(out, 500, "Internal Server Error");
                return;
            }
            byte[] bodyBytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            sendResponse(out, 200, "OK", response.getContentType(), bodyBytes);
            return;
        }

        String staticPath = decodedPath.equals("/") ? "/index.html" : decodedPath;
        if (staticFileService.canServe(staticPath)) {
            try {
                if (!staticFileService.exists(staticPath)) {
                    sendError(out, 404, "Not Found");
                    return;
                }
                byte[] body = staticFileService.readBytes(staticPath);
                String contentType = staticFileService.getContentType(staticPath);
                sendResponse(out, 200, "OK", contentType, body);
            } catch (SecurityException e) {
                System.err.println("Rejected unsafe path: " + staticPath);
                sendError(out, 404, "Not Found");
            }
            return;
        }

        sendError(out, 404, "Not Found");
    }

    private Map<String, String> parseQuery(String rawPath) {
        Map<String, String> result = new HashMap<>();
        if (!rawPath.contains("?")) {
            return result;
        }
        String query = rawPath.substring(rawPath.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private void sendResponse(OutputStream out, int status, String statusText,
                              String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void sendError(OutputStream out, int status, String statusText) throws IOException {
        String body = status + " " + statusText;
        sendResponse(out, status, statusText, "text/plain; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}