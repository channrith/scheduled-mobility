package com.mobility.core.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Populated V16 databases must upgrade, not only fresh databases. */
class BookingFareMigrationIT {
    @Test
    void preservesQuotedRowsAndExplicitlyTranslatesExperimentalTypes() throws Exception {
        try (var db =
                new PostgreSQLContainer(
                        DockerImageName.parse("postgis/postgis:16-3.4")
                                .asCompatibleSubstituteFor("postgres"))) {
            db.start();
            Flyway.configure()
                    .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration")
                    .target("16")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate(
                        """
                        INSERT INTO booking.ride_requests
                        (id, booking_type, booked_by, rider_user_id, city, vehicle_class, pickup_lat, pickup_lng,
                         payment_method, currency, equivalent_currency, created_at, quote_id, dropoff_lat, dropoff_lng,
                         fare_minor, equivalent_minor, meter_terms, flight_no)
                        VALUES
                        (gen_random_uuid(), 'SCHEDULED', gen_random_uuid(), gen_random_uuid(), 'PNH', 'CAR', 11.5, 104.9,
                         'CASH', 'USD', 'KHR', now(), gen_random_uuid(), 11.6, 104.8, 1000, 40000, null, null),
                        (gen_random_uuid(), 'METERED', gen_random_uuid(), gen_random_uuid(), 'PNH', 'CAR', 11.5, 104.9,
                         'CASH', 'USD', 'KHR', now(), null, null, null, null, null, '{}', null),
                        (gen_random_uuid(), 'AIRPORT_METERED', gen_random_uuid(), gen_random_uuid(), 'PNH', 'CAR', 11.5, 104.9,
                         'CASH', 'USD', 'KHR', now(), null, null, null, null, null, '{}', 'K6101')
                        """);
                Flyway.configure()
                        .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                try (var rows =
                        sql.executeQuery(
                                "SELECT booking_type, fare_model, fare_minor FROM"
                                    + " booking.ride_requests ORDER BY booking_type")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("AIRPORT");
                    assertThat(rows.getString(2)).isEqualTo("METERED");
                    assertThat(rows.getObject(3)).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("ON_DEMAND");
                    assertThat(rows.getString(2)).isEqualTo("METERED");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("SCHEDULED");
                    assertThat(rows.getString(2)).isEqualTo("QUOTED");
                    assertThat(rows.getLong(3)).isEqualTo(1000);
                    assertThat(rows.next()).isFalse();
                }
                assertThatThrownBy(
                                () ->
                                        sql.executeUpdate(
                                                "UPDATE booking.ride_requests SET"
                                                    + " fare_model='METERED' WHERE"
                                                    + " booking_type='SCHEDULED'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(
                                () ->
                                        sql.executeUpdate(
                                                "UPDATE booking.ride_requests SET"
                                                    + " booking_type='METERED' WHERE"
                                                    + " booking_type='ON_DEMAND'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }
}
