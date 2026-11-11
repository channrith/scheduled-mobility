package com.mobility.core.place.reverse;

import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ReversePlaces {
    public record ReversePlaceResult(
            @io.swagger.v3.oas.annotations.media.Schema(types={"string", "null"}, format="uuid") UUID placeId,
            @io.swagger.v3.oas.annotations.media.Schema(types={"string", "null"}) String name,
            @io.swagger.v3.oas.annotations.media.Schema(types={"string", "null"}) String formattedAddress,
            double latitude, double longitude,
            @io.swagger.v3.oas.annotations.media.Schema(types={"string", "null"}) String pickupInstructions,
            boolean resolved) {}
    record Key(double lat, double lng, String language) {}
    record Cached(ReversePlaceResult result, Instant until) {}
    private final Map<Key, Cached> cache = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;
    private final GeocodingProvider provider;
    private final CurrentUserProvider users;
    private final StringRedisTemplate redis;
    ReversePlaces(JdbcTemplate jdbc, GeocodingProvider provider, CurrentUserProvider users, StringRedisTemplate redis) {
        this.jdbc=jdbc; this.provider=provider; this.users=users; this.redis=redis;
    }
    public ReversePlaceResult reverse(double lat, double lng, String language) {
        UUID user = users.require().userId();
        rate("places:reverse:user:" + user, 60);
        // Exact coordinates only: a nearby landmark ID would move the pin during quote resolution.
        var rows = jdbc.query("""
            SELECT id,name_en,name_km,formatted_address_en,formatted_address_km,pickup_notes_en,pickup_notes_km
            FROM place.places WHERE status='ACTIVE' AND latitude=? AND longitude=? ORDER BY id LIMIT 1
            """, (rs,i) -> new ReversePlaceResult(rs.getObject("id",UUID.class),
                fallback(language,rs.getString("name_en"),rs.getString("name_km")),
                fallback(language,rs.getString("formatted_address_en"),rs.getString("formatted_address_km")),lat,lng,
                fallback(language,rs.getString("pickup_notes_en"),rs.getString("pickup_notes_km")),true),lat,lng);
        if (!rows.isEmpty()) return rows.getFirst();
        var key = new Key(lat,lng,language);
        synchronized(cache) {
            var cached=cache.get(key);
            if (cached!=null && cached.until().isAfter(Instant.now())) return cached.result();
        }
        rate("places:reverse:provider", 30);
        var label=provider.reverse(lat,lng,language);
        var result=new ReversePlaceResult(null,label.name(),label.address(),lat,lng,null,label.resolved());
        synchronized(cache) {
            cache.put(key,new Cached(result,Instant.now().plusSeconds(label.resolved()?300:30)));
            while(cache.size()>1000) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }
    private void rate(String prefix, int max) {
        try {
            String key=prefix;
            Long n=redis.opsForValue().increment(key);
            if(n!=null && n==1) redis.expire(key,Duration.ofSeconds(60));
            if(n!=null && n>max) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"places.geocoding-rate-limited").withHeader("Retry-After","60");
        } catch(ApiException e) { throw e; }
        catch(RuntimeException e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"places.geocoding-unavailable"); }
    }
    static String fallback(String language,String en,String km) {
        String preferred="en".equals(language)?en:km, other="en".equals(language)?km:en;
        return preferred==null || preferred.isBlank()?other:preferred;
    }
}
