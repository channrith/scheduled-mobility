package com.mobility.core.place.catalog;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mobility.core.place.PlaceType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuzzy landmark search with pg_trgm on the Khmer and English names. {@code word_similarity} matches a query
 * against any part of a name (Khmer has no spaces between words); a name that starts with the query gets a
 * bonus. With a position, closer places rank higher: score = 0.7 × text + 0.3 × proximity.
 */
@Component
public class PlaceSearch {

	/** Minimum text match to be a result at all. */
	static final double MIN_TEXT_SCORE = 0.3;

	/** Distance (km) at which the proximity part has dropped to one half. */
	static final double PROXIMITY_HALF_KM = 5.0;

	public record PlaceHit(UUID id, String nameKm, String nameEn, PlaceType type, double latitude, double longitude,
			String pickupNotesKm, String pickupNotesEn, boolean verified, Double distanceMeters, double score) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	PlaceSearch(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<PlaceHit> search(String query, Double lat, Double lng, int limit) {
		String q = query.strip();
		boolean near = lat != null && lng != null;
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("q", q)
			.addValue("qLower", q.toLowerCase(Locale.ROOT))
			.addValue("prefix", q + "%")
			.addValue("prefixLower", q.toLowerCase(Locale.ROOT) + "%")
			.addValue("min", MIN_TEXT_SCORE)
			.addValue("halfKm", PROXIMITY_HALF_KM)
			.addValue("limit", Math.clamp(limit, 1, 30));
		String distance = "NULL::double precision";
		if (near) {
			params.addValue("lat", lat).addValue("lng", lng);
			distance = "ST_Distance(p.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)";
		}
		String sql = """
				WITH scored AS (
				  SELECT p.*, %s AS distance_m,
				         LEAST(1.0, GREATEST(word_similarity(:q, coalesce(p.name_km, '')),
				                             word_similarity(:qLower, lower(coalesce(p.name_en, ''))))
				                    + CASE WHEN p.name_km LIKE :prefix OR lower(p.name_en) LIKE :prefixLower
				                           THEN 0.2 ELSE 0 END) AS text_score
				  FROM place.places p
				  WHERE p.status = 'ACTIVE'
				)
				SELECT *, CASE WHEN distance_m IS NULL THEN text_score
				               ELSE 0.7 * text_score + 0.3 / (1 + distance_m / 1000.0 / :halfKm) END AS score
				FROM scored
				WHERE text_score >= :min
				ORDER BY score DESC, usage_count DESC, id
				LIMIT :limit""".formatted(distance);
		return jdbc.query(sql, params, (rs, i) -> new PlaceHit(rs.getObject("id", UUID.class), rs.getString("name_km"),
				rs.getString("name_en"), PlaceType.valueOf(rs.getString("place_type")), rs.getDouble("latitude"),
				rs.getDouble("longitude"), rs.getString("pickup_notes_km"), rs.getString("pickup_notes_en"),
				rs.getBoolean("verified"), (Double) rs.getObject("distance_m"), rs.getDouble("score")));
	}
}
