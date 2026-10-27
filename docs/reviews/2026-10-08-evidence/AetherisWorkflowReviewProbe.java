import com.aetheris.workflow.controller.ManagedSparkController;
import com.aetheris.workflow.service.FlinkSqlExecutionClient;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

public class AetherisWorkflowReviewProbe {
  public static void main(String[] args) throws Exception {
    AtomicReference<String> requested = new AtomicReference<>();
    // This server is owned by the probe and binds only to loopback, on an ephemeral port.
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/info", exchange -> {
      requested.set(exchange.getRequestURI().toString());
      byte[] body = "{\"version\":\"review-only\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    });
    server.start();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(RestClient.builder(), Duration.ofSeconds(1), 100);
      ManagedSparkController controller = new ManagedSparkController(null, null, null, null, null, client);
      var response = controller.flinkHealth(endpoint);
      System.out.println("FLINK_HEALTH_LOOPBACK_HTTP_STATUS=" + response.getStatusCode().value());
      System.out.println("FLINK_HEALTH_LOOPBACK_REQUEST=" + requested.get());
      System.out.println("FLINK_HEALTH_LOOPBACK_BODY=" + response.getBody());
      System.out.println("NOTE=direct controller invocation, not an authenticated full HTTP stack test");
    } finally { server.stop(0); }
  }
}
