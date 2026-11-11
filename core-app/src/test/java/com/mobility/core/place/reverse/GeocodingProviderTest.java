package com.mobility.core.place.reverse;

import com.sun.net.httpserver.HttpServer;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class GeocodingProviderTest {
    HttpServer server;
    volatile int status=200;
    volatile String body="{\"name\":\"Market\",\"display_name\":\"Phnom Penh, Cambodia\"}";
    GeocodingProvider provider;
    volatile long delay;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/reverse",e->{
            try { Thread.sleep(delay); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        provider=new GeocodingProvider("http://127.0.0.1:"+server.getAddress().getPort()+"/reverse",Duration.ofSeconds(1),JsonMapper.builder().build());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void readsNameAndAddressSeparately() {
        var label=provider.reverse(11.5,104.9,"km");
        assertThat(label.name()).isEqualTo("Market");assertThat(label.address()).isEqualTo("Phnom Penh, Cambodia");
    }
    @Test void distinguishesNoResultFromOutageAndMalformedPayload() {
        status=404;body="{\"error\":\"Unable to geocode\"}";assertThat(provider.reverse(11.5,104.9,"en").resolved()).isFalse();
        status=503;assertThatThrownBy(()->provider.reverse(11.5,104.9,"en")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("places.geocoding-unavailable"));
        status=200;body="{}";assertThatThrownBy(()->provider.reverse(11.5,104.9,"en")).isInstanceOf(ApiException.class);
    }
    @Test void providerTimeoutRemainsUnavailable() {
        delay=250;
        var impatient=new GeocodingProvider("http://127.0.0.1:"+server.getAddress().getPort()+"/reverse",Duration.ofMillis(30),JsonMapper.builder().build());
        assertThatThrownBy(()->impatient.reverse(11.5,104.9,"en")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(503));
    }
    @Test void providerRateLimitIsRecoverableAndDistinctFromOutage() {
        status=429;
        assertThatThrownBy(()->provider.reverse(11.5,104.9,"en"))
            .isInstanceOfSatisfying(ApiException.class,e->{
                assertThat(e.getStatus().value()).isEqualTo(429);
                assertThat(e.getCode()).isEqualTo("places.geocoding-rate-limited");
                assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("60");
            });
    }
    @Test void noImplicitProviderAndLanguageFallback() {
        var disabled=new GeocodingProvider("",Duration.ofSeconds(1),JsonMapper.builder().build());
        assertThatThrownBy(()->disabled.reverse(11.5,104.9,"en")).isInstanceOf(ApiException.class);
        assertThat(ReversePlaces.fallback("km","English",null)).isEqualTo("English");
        assertThat(ReversePlaces.fallback("en",null,"ខ្មែរ")).isEqualTo("ខ្មែរ");
        assertThatThrownBy(()->ReversePlaceController.validate(Double.NaN,104.9)).isInstanceOf(ApiException.class);
    }
}
