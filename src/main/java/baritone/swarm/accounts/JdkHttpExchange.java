package baritone.swarm.accounts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Ostinato: the real device-code client. Failures do not keep the response body. */
public final class JdkHttpExchange implements MicrosoftAuth.HttpExchange {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Override
    public Response exchange(String method, String url, String contentType, String body, String authorization) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
            if ("GET".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            if (authorization != null && !authorization.isBlank()) {
                builder.header("Authorization", authorization);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
    }
}
