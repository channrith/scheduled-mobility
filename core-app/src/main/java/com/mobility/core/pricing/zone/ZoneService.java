package com.mobility.core.pricing.zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.pricing.settings.PricingProperties;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Zones are edited in place (quotes keep a copy of the surcharge they used); every change is audited. */
@Service
public class ZoneService {

	/** @param polygon outer ring, not closed (the first point is not repeated) */
	public record ZoneInput(String city, ZoneType type, String nameKm, String nameEn, List<LatLng> polygon,
			Long surchargeMinor, String surchargeCurrency, Boolean active) {
	}

	/** An active zone containing a position. */
	public record ZoneHit(UUID id, String city, ZoneType type, String nameKm, String nameEn, Long surchargeMinor,
			String surchargeCurrency) {
	}

	static final int MAX_POINTS = 500;

	static final long MAX_AMOUNT_MINOR = 1_000_000_000L;

	private final ZoneRepository zones;

	private final NamedParameterJdbcTemplate jdbc;

	private final PricingProperties properties;

	private final AuditLog auditLog;

	private final JsonMapper json;

	ZoneService(ZoneRepository zones, NamedParameterJdbcTemplate jdbc, PricingProperties properties, AuditLog auditLog,
			JsonMapper json) {
		this.zones = zones;
		this.jdbc = jdbc;
		this.properties = properties;
		this.auditLog = auditLog;
		this.json = json;
	}

	@Transactional
	public Zone create(ZoneInput input) {
		Zone zone = zones.saveAndFlush(new Zone(validate(input, true)));
		auditLog.record("pricing.zone.created", "pricing_zone", zone.getId(), details(zone));
		return zone;
	}

	@Transactional
	public Zone update(UUID id, ZoneInput input, long expectedVersion) {
		Zone zone = get(id);
		if (!Objects.equals(zone.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		zone.apply(validate(input, zone.isActive()));
		zones.saveAndFlush(zone);
		auditLog.record("pricing.zone.updated", "pricing_zone", id, details(zone));
		return zone;
	}

	@Transactional(readOnly = true)
	public Zone get(UUID id) {
		return zones.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.zone-not-found"));
	}

	@Transactional(readOnly = true)
	public List<Zone> list(String city) {
		return zones.findByCityInOrderByTypeAscNameEnAsc(city == null ? properties.cities() : List.of(city));
	}

	/** Active zones covering the position, any city. */
	@Transactional(readOnly = true)
	public List<ZoneHit> zonesAt(LatLng position) {
		return jdbc.query("""
				SELECT id, city, zone_type, name_km, name_en, surcharge_minor, surcharge_currency
				FROM pricing.zones
				WHERE active AND ST_Covers(area, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
				ORDER BY zone_type, id""",
				new MapSqlParameterSource("lat", position.latitude()).addValue("lng", position.longitude()),
				(rs, i) -> new ZoneHit(rs.getObject("id", UUID.class), rs.getString("city"),
						ZoneType.valueOf(rs.getString("zone_type")), rs.getString("name_km"), rs.getString("name_en"),
						(Long) rs.getObject("surcharge_minor"), rs.getString("surcharge_currency")));
	}

	/** The outer ring of a stored zone, without the closing point. */
	public List<LatLng> polygon(Zone zone) {
		JsonNode ring = json.readTree(zone.getArea()).get("coordinates").get(0);
		List<LatLng> points = new ArrayList<>();
		for (int i = 0; i < ring.size() - 1; i++) {
			points.add(new LatLng(ring.get(i).get(1).asDouble(), ring.get(i).get(0).asDouble()));
		}
		return points;
	}

	private ZoneDetails validate(ZoneInput in, boolean defaultActive) {
		String city = in.city() == null ? "" : in.city().strip().toUpperCase(Locale.ROOT);
		if (!properties.cities().contains(city)) {
			throw invalid("city");
		}
		if (in.type() == null) {
			throw invalid("type");
		}
		String nameKm = blankToNull(in.nameKm());
		String nameEn = blankToNull(in.nameEn());
		if (nameKm == null && nameEn == null) {
			throw invalid("name");
		}
		String geoJson = geoJson(in.polygon());
		Boolean valid = jdbc.queryForObject("SELECT ST_IsValid(ST_GeomFromGeoJSON(:g))", Map.of("g", geoJson), Boolean.class);
		if (!Boolean.TRUE.equals(valid)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.zone-invalid");
		}
		Long surcharge = null;
		String currency = null;
		if (in.type() == ZoneType.AIRPORT) {
			surcharge = in.surchargeMinor();
			if (surcharge == null || surcharge < 0 || surcharge > MAX_AMOUNT_MINOR) {
				throw invalid("surchargeMinor");
			}
			currency = in.surchargeCurrency() == null ? "" : in.surchargeCurrency().strip().toUpperCase(Locale.ROOT);
			if (!List.of("USD", "KHR").contains(currency)) {
				throw invalid("surchargeCurrency");
			}
		}
		return new ZoneDetails(city, in.type(), nameKm, nameEn, geoJson, surcharge, currency,
				in.active() == null ? defaultActive : in.active());
	}

	/** Closed GeoJSON Polygon from the points (repeated neighbours and a repeated first point are dropped). */
	private String geoJson(List<LatLng> polygon) {
		if (polygon == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.zone-invalid");
		}
		List<LatLng> points = new ArrayList<>();
		for (LatLng p : polygon) {
			if (p == null || !p.inCambodia()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.zone-invalid");
			}
			if (points.isEmpty() || !points.getLast().equals(p)) {
				points.add(p);
			}
		}
		if (points.size() > 1 && points.getFirst().equals(points.getLast())) {
			points.removeLast();
		}
		if (points.size() < 3 || points.size() > MAX_POINTS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.zone-invalid");
		}
		List<List<Double>> ring = new ArrayList<>();
		for (LatLng p : points) {
			ring.add(List.of(p.longitude(), p.latitude()));
		}
		ring.add(ring.getFirst());
		return json.writeValueAsString(Map.of("type", "Polygon", "coordinates", List.of(ring)));
	}

	private static Map<String, Object> details(Zone zone) {
		Map<String, Object> details = new HashMap<>();
		details.put("city", zone.getCity());
		details.put("type", zone.getType().name());
		details.put("active", zone.isActive());
		return details;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "pricing.invalid-field").withProperty("field", field);
	}
}
