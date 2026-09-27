import com.github.yonaprojects.yona.YonaApplication;
import com.github.yonaprojects.yona.queue.TaskRegistry;
import com.github.yonaprojects.yona.domain.user.User;
import com.github.yonaprojects.yona.domain.user.UserService;
import com.github.yonaprojects.yona.domain.user.UserState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;

class QueueProductionBoundarySmoke {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("com.github.yonaprojects.yona.queue.acceptance.QueueAdminHttpAcceptanceHarness");
            throw new AssertionError("Test harness is present on the production-only classpath");
        } catch (ClassNotFoundException expected) { }
        try (var app = new SpringApplicationBuilder(YonaApplication.class).run(args);
             var client = HttpClient.newHttpClient()) {
            var bootstrap = new User();
            bootstrap.setLoginId("queue-production-smoke");
            bootstrap.setName("Production boundary smoke");
            bootstrap.setEmail("queue-production-smoke@example.invalid");
            bootstrap.setState(UserState.SITE_ADMIN);
            app.getBean(UserService.class).createUser(bootstrap);
            var registry = app.getBean(TaskRegistry.class);
            for (String scenario : List.of("success", "retryable-then-success", "permanent-failure", "gated-cancel",
                    "unguarded-claim", "stale-fence-file", "success-publish-file", "process-crash-replay",
                    "process-crash-unsafe", "retry-budget-exhaustion")) {
                if (registry.find("queue.acceptance.worker." + scenario, 1) != null) throw new AssertionError("Fixture handler registered in production");
            }
            for (String scenario : List.of("manual-retry", "manual-retry-permanent", "idempotent", "large-result")) {
                if (registry.find("queue.acceptance.http." + scenario, 1) != null) throw new AssertionError("HTTP fixture handler registered in production");
            }
            String base = "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port")
                + app.getEnvironment().getProperty("server.servlet.context-path", "");
            for (String path : List.of("/health", "/pool", "/sessions", "/runs", "/runs/00000000-0000-0000-0000-000000000000",
                    "/runs/00000000-0000-0000-0000-000000000000/actions/release-gate")) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/__test__/queue/v1" + path))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 404) throw new AssertionError("Test route was not absent: " + path + " status=" + response.statusCode());
            }
            var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/queue/v1/jobs"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (denied.statusCode() != 401 || !denied.body().contains("UNAUTHENTICATED")) throw new AssertionError("Production queue authentication boundary failed");
            System.out.println("PRODUCTION_BOUNDARY_SMOKE_OK: fixture class absent, 14 fixture handler types absent, six test route shapes 404, queue anonymous JSON401");
        }
    }
}
