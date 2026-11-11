package com.mobility.core.place.reverse;

import com.mobility.core.shared.web.ApiException;
import com.mobility.core.shared.openapi.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/places/reverse")
class ReversePlaceController {
    private final ReversePlaces places;
    ReversePlaceController(ReversePlaces places) { this.places = places; }

    @GetMapping
    @Operation(summary = "Label a selected map pin", description = "Preserves selected coordinates. Internal placeId is returned only for an exact canonical landmark coordinate. No service eligibility guarantee. English/Khmer fallback. No result is 200 resolved=false; unavailable geocoder is 503.")
    @ProblemResponse(status = 503, description = "places.geocoding-unavailable")
    @ProblemResponse(status = 429, description = "places.geocoding-rate-limited; Retry-After in seconds")
    ReversePlaces.ReversePlaceResult reverse(@RequestParam double lat, @RequestParam double lng, Locale locale,
                                 jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        validate(lat, lng);
        try { return places.reverse(lat, lng, "en".equals(locale.getLanguage()) ? "en" : "km"); }
        catch(org.springframework.dao.DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"places.geocoding-unavailable");
        }
    }

    static void validate(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
    }
}
