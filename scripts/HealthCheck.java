import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 无额外系统依赖的容器健康探针。 */
public final class HealthCheck {
    private HealthCheck() {}

    public static void main(String[] args) {
        if (args.length != 1) System.exit(2);
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            var request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(status >= 200 && status < 300 ? 0 : 1);
        } catch (Exception ignored) {
            System.exit(1);
        }
    }
}
