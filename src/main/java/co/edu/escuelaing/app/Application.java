package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.*;

/**
 * Example application demonstrating the framework: static resources,
 * two lambda-based GET services, query-string extraction, and an
 * environment-configured, development-only shutdown route.
 */
public class Application {

    public static void main(String[] args) throws Exception {

        String staticFilesPath = System.getenv().getOrDefault("STATIC_FILES_PATH", "/webroot");
        staticfiles(staticFilesPath);

        get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            if (name == null || name.isBlank()) {
                name = "world";
            }
            String greetingPrefix = System.getenv().getOrDefault("GREETING_PREFIX", "Hello");
            return greetingPrefix + " " + name;
        });

        get("/pi", (req, resp) -> String.valueOf(Math.PI));

        // The shutdown route is registered ONLY in development, so it is
        // never reachable in the production (cloud) deployment.
        String environment = System.getenv().getOrDefault("APP_ENV", "development");
        if (environment.equals("development")) {
            get("/shutdown", (req, resp) -> {
                stop();
                return "Server will stop after this response.";
            });
        }

        get("/slow", (req, resp) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Slow response after 5 seconds, handled by "
                    + Thread.currentThread().getName();
        });

        start();
    }
}
