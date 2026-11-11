package com.mobility.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class LocalPricingFixtureIT {
    @Autowired JdbcTemplate jdbc;
    @Test @org.springframework.transaction.annotation.Transactional
    void controlledAirportFixtureIsCoveredWithoutMovingCatalogOrDuplicatingSeed() throws Exception {
        String sql=Files.readString(Path.of("../scripts/seed/pricing.sql")).replace("BEGIN;", "").replace("COMMIT;", "");
        var original=jdbc.queryForList("SELECT id,latitude,longitude FROM place.places WHERE name_en='Techo International Airport'");
        jdbc.execute(sql);
        long zones=jdbc.queryForObject("SELECT count(*) FROM pricing.zones",Long.class);
        long rules=jdbc.queryForObject("SELECT count(*) FROM pricing.pricing_rules",Long.class);
        jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing.zones",Long.class)).isEqualTo(zones);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing.pricing_rules",Long.class)).isEqualTo(rules);
        assertThat(jdbc.queryForList("SELECT id,latitude,longitude FROM place.places WHERE name_en='Techo International Airport'")).isEqualTo(original);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM place.places p JOIN pricing.zones z ON ST_Covers(z.area::geometry,p.location::geometry)
            WHERE p.id='7ca0c1ad-7f1e-40e5-a371-aef081d2d017' AND z.zone_type='AIRPORT' AND z.active
            """,Long.class)).isPositive();
    }
}
