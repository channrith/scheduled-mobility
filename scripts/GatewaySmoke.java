import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Local fixture only. java scripts/GatewaySmoke.java; never prints JWTs or GPS messages. */
class GatewaySmoke {
    static final String BASE = System.getenv().getOrDefault("SMOKE_GATEWAY_URL", "http://localhost:8090");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String token;
    static final List<WebSocket> SOCKETS = new ArrayList<>();

    static HttpResponse<String> request(String method, String path, String body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString());
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static void expect(boolean ok, String check) {
        if (!ok) throw new AssertionError(check);
        System.out.println("PASS " + check);
    }
    static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+|true|false|null))").matcher(json);
        if (!matcher.find()) throw new AssertionError("Missing expected response field: " + name);
        return matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
    }
    static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            buffer.append(text);
            if (last) { messages.add(buffer.toString()); buffer.setLength(0); }
            socket.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(new IllegalStateException("WebSocket failed")); }
    }
    static WebSocket socket(String bearer, Listener listener) {
        WebSocket socket = HTTP.newWebSocketBuilder().header("Authorization", "Bearer " + bearer)
            .connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(BASE.replaceFirst("^http", "ws")
                + "/api/v1/drivers/me/location/live"), listener).join();
        SOCKETS.add(socket);
        return socket;
    }
    static String uploadBody(Instant at) {
        return "{\"latitude\":11.5564,\"longitude\":104.9282,\"accuracyMeters\":10,\"recordedAt\":\"" + at + "\"}";
    }
    static String shortToken(String subject) throws Exception {
        String file = System.getenv("SMOKE_JWT_PRIVATE_KEY_FILE");
        if (file == null) throw new IllegalStateException("SMOKE_JWT_PRIVATE_KEY_FILE required for socket expiry check");
        String pem = Files.readString(Path.of(file)).replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var base64 = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String claims = "{\"sub\":\"" + subject + "\",\"iss\":\"" + System.getenv().getOrDefault("JWT_ISSUER", "mobility-core")
            + "\",\"roles\":[\"DRIVER\"],\"iat\":" + now + ",\"exp\":" + (now + 4) + "}";
        String data = base64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(data.getBytes(StandardCharsets.US_ASCII));
        return data + "." + base64.encodeToString(signer.sign());
    }
    public static void main(String[] args) throws Exception {
        try {
            expect(request("GET", "/actuator/health", null, null).statusCode() == 200, "gateway public health");
            expect(request("GET", "/actuator/env", null, null).statusCode() == 404, "backend actuator not exposed");
            for (String path : List.of("/api/v1/drivers/me/location", "/api/v1/drivers/me/presence", "/api/v1/drivers/me/availability"))
                expect(request("GET", path, null, "invalid").statusCode() == 401, "invalid JWT rejected: " + path);
            String phone = System.getenv().getOrDefault("SMOKE_DRIVER_PHONE", "+85512898339");
            String code = System.getenv("SMOKE_OTP_CODE");
            if (code == null) throw new IllegalStateException("SMOKE_OTP_CODE is required (local fixture only)");
            var otp = request("POST", "/api/v1/auth/otp/request", "{\"phone\":\"" + phone + "\"}", null);
            expect(otp.statusCode() == 202 || otp.statusCode() == 429, "request fixture OTP through core route (or pending cooldown)");
            var login = request("POST", "/api/v1/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", null);
            expect(login.statusCode() == 200, "fixture login through gateway");
            token = field(login.body(), "accessToken");
            var profile = request("GET", "/api/v1/drivers/me", null, token);
            expect(profile.statusCode() == 200 && "APPROVED".equals(field(profile.body(), "status")), "approved core profile through gateway");
            var live = new Listener(); var ws = socket(token, live);
            Instant at = Instant.now();
            expect(request("POST", "/api/v1/drivers/me/location", uploadBody(at), token).statusCode() == 204, "accepted GPS upload");
            String event;
            do {
                event = live.messages.poll(10, TimeUnit.SECONDS);
            } while (event != null && "driver.location.snapshot".equals(field(event, "type")));
            expect(event != null && "driver.location.updated".equals(field(event, "type"))
                && field(profile.body(), "id").equals(field(event, "driverId")), "real Redis GPS event delivered through gateway WebSocket");
            var latest = request("GET", "/api/v1/drivers/me/location", null, token);
            expect(latest.statusCode() == 200 && field(latest.body(), "recordedAt").equals(at.toString()), "latest location read");
            var presence = request("GET", "/api/v1/drivers/me/presence", null, token);
            expect(presence.statusCode() == 200 && "true".equals(field(presence.body(), "online")), "presence refreshed by accepted GPS");
            int timeout = Integer.parseInt(field(presence.body(), "timeoutSeconds"));
            if (timeout > 10) throw new IllegalStateException("Use LOCATION_PRESENCE_TIMEOUT=5s for this local smoke test");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            var reconnect = new Listener(); var reconnected = socket(token, reconnect);
            String snapshot = reconnect.messages.poll(10, TimeUnit.SECONDS);
            expect(snapshot != null && field(snapshot, "type").equals("driver.location.snapshot"), "reconnect snapshot");
            reconnected.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            // Duplicate uploads and repeated reads must not renew presence.
            expect(request("POST", "/api/v1/locations", uploadBody(at), token).body().contains("false"), "compatibility route ignores duplicate GPS");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout + 2);
            boolean offline = false;
            while (System.nanoTime() < deadline) {
                var state = request("GET", "/api/v1/drivers/me/presence", null, token);
                if ("false".equals(field(state.body(), "online"))) { offline = true; break; }
                Thread.sleep(250);
            }
            expect(offline, "presence expires despite repeated reads and duplicate GPS");
            var availability = request("GET", "/api/v1/drivers/me/availability", null, token);
            expect(availability.statusCode() == 200, "availability GET reaches core-app");
            var put = request("PUT", "/api/v1/drivers/me/availability", "{\"status\":\"UNAVAILABLE\",\"expectedVersion\":"
                + field(availability.body(), "version") + "}", token);
            expect(put.statusCode() == 200 && "UNAVAILABLE".equals(field(put.body(), "status")), "availability PUT reaches core-app");
            String subject = field(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8), "sub");
            var expiring = new Listener(); var expiringSocket = socket(shortToken(subject), expiring);
            expect(expiring.closed.get(10, TimeUnit.SECONDS) == 1008, "socket closes when JWT expires");
            expiringSocket.abort();
            // Revoke the fixture refresh session; never print credentials.
            expect(request("POST", "/api/v1/auth/logout", "{\"refreshToken\":\"" + field(login.body(), "refreshToken") + "\"}", null).statusCode() == 204,
                "fixture refresh session revoked");
        } finally {
            SOCKETS.forEach(WebSocket::abort);
            HTTP.close();
        }
    }
}
