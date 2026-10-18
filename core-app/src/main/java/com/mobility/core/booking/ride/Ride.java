package com.mobility.core.booking.ride;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.driver.VehicleClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A booking, made from one fare quote; its trips are in {@code booking.trips}. Money in minor units. */
@Entity
@Table(schema = "booking", name = "ride_requests")
public class Ride {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "booking_type", nullable = false)
	private BookingType type;

	@Column(name = "booked_by", nullable = false)
	private UUID bookedBy;

	@Column(name = "rider_user_id", nullable = false)
	private UUID riderUserId;

	@Column(name = "corporate_id")
	private UUID corporateId;

	@Column(name = "corporate_member_id")
	private UUID corporateMemberId;

	@Column(name = "cost_center_id")
	private UUID costCenterId;

	@Column(name = "quote_id", nullable = false, unique = true)
	private UUID quoteId;

	@Column(nullable = false)
	private String city;

	@Enumerated(EnumType.STRING)
	@Column(name = "vehicle_class", nullable = false)
	private VehicleClass vehicleClass;

	@Column(name = "pickup_place_id")
	private UUID pickupPlaceId;

	@Column(name = "pickup_lat", nullable = false)
	private double pickupLat;

	@Column(name = "pickup_lng", nullable = false)
	private double pickupLng;

	@Column(name = "pickup_notes")
	private String pickupNotes;

	@Column(name = "pickup_voice_note_key")
	private String pickupVoiceNoteKey;

	@Column(name = "dropoff_place_id")
	private UUID dropoffPlaceId;

	@Column(name = "dropoff_lat", nullable = false)
	private double dropoffLat;

	@Column(name = "dropoff_lng", nullable = false)
	private double dropoffLng;

	@Column(name = "flight_no")
	private String flightNo;

	@Column(name = "recurrence_days")
	private String recurrenceDays;

	@Column(name = "recurrence_until")
	private LocalDate recurrenceUntil;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false)
	private PaymentMethod paymentMethod;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "fare_minor", nullable = false)
	private long fareMinor;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "equivalent_currency", nullable = false, length = 3)
	private String equivalentCurrency;

	@Column(name = "equivalent_minor", nullable = false)
	private long equivalentMinor;

	@Column(name = "service_fee_minor")
	private Long serviceFeeMinor;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Version
	private Long version;

	protected Ride() {
	}

	/** Everything a booking records; validated by {@link BookingService}. */
	record Details(BookingType type, UUID bookedBy, UUID riderUserId, UUID corporateId, UUID corporateMemberId,
			UUID costCenterId, UUID quoteId, String city, VehicleClass vehicleClass, UUID pickupPlaceId, double pickupLat,
			double pickupLng, String pickupNotes, String pickupVoiceNoteKey, UUID dropoffPlaceId, double dropoffLat,
			double dropoffLng, String flightNo, Set<DayOfWeek> recurrenceDays, LocalDate recurrenceUntil,
			PaymentMethod paymentMethod, String currency, long fareMinor, String equivalentCurrency, long equivalentMinor,
			Long serviceFeeMinor, Instant createdAt) {
	}

	Ride(Details d) {
		this.id = UUID.randomUUID();
		this.type = d.type();
		this.bookedBy = d.bookedBy();
		this.riderUserId = d.riderUserId();
		this.corporateId = d.corporateId();
		this.corporateMemberId = d.corporateMemberId();
		this.costCenterId = d.costCenterId();
		this.quoteId = d.quoteId();
		this.city = d.city();
		this.vehicleClass = d.vehicleClass();
		this.pickupPlaceId = d.pickupPlaceId();
		this.pickupLat = d.pickupLat();
		this.pickupLng = d.pickupLng();
		this.pickupNotes = d.pickupNotes();
		this.pickupVoiceNoteKey = d.pickupVoiceNoteKey();
		this.dropoffPlaceId = d.dropoffPlaceId();
		this.dropoffLat = d.dropoffLat();
		this.dropoffLng = d.dropoffLng();
		this.flightNo = d.flightNo();
		this.recurrenceDays = d.recurrenceDays() == null ? null
				: d.recurrenceDays().stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
		this.recurrenceUntil = d.recurrenceUntil();
		this.paymentMethod = d.paymentMethod();
		this.currency = d.currency();
		this.fareMinor = d.fareMinor();
		this.equivalentCurrency = d.equivalentCurrency();
		this.equivalentMinor = d.equivalentMinor();
		this.serviceFeeMinor = d.serviceFeeMinor();
		this.createdAt = d.createdAt();
	}

	/** Booked by, or riding: the people a ride belongs to. */
	public boolean belongsTo(UUID userId) {
		return bookedBy.equals(userId) || riderUserId.equals(userId);
	}

	public UUID getId() {
		return id;
	}

	public BookingType getType() {
		return type;
	}

	public UUID getBookedBy() {
		return bookedBy;
	}

	public UUID getRiderUserId() {
		return riderUserId;
	}

	public UUID getCorporateId() {
		return corporateId;
	}

	public UUID getCorporateMemberId() {
		return corporateMemberId;
	}

	public UUID getCostCenterId() {
		return costCenterId;
	}

	public UUID getQuoteId() {
		return quoteId;
	}

	public String getCity() {
		return city;
	}

	public VehicleClass getVehicleClass() {
		return vehicleClass;
	}

	public UUID getPickupPlaceId() {
		return pickupPlaceId;
	}

	public double getPickupLat() {
		return pickupLat;
	}

	public double getPickupLng() {
		return pickupLng;
	}

	public String getPickupNotes() {
		return pickupNotes;
	}

	public String getPickupVoiceNoteKey() {
		return pickupVoiceNoteKey;
	}

	public UUID getDropoffPlaceId() {
		return dropoffPlaceId;
	}

	public double getDropoffLat() {
		return dropoffLat;
	}

	public double getDropoffLng() {
		return dropoffLng;
	}

	public String getFlightNo() {
		return flightNo;
	}

	public Set<DayOfWeek> getRecurrenceDays() {
		if (recurrenceDays == null) {
			return Set.of();
		}
		Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
		Arrays.stream(recurrenceDays.split(",")).map(DayOfWeek::valueOf).forEach(days::add);
		return days;
	}

	public LocalDate getRecurrenceUntil() {
		return recurrenceUntil;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public String getCurrency() {
		return currency;
	}

	public long getFareMinor() {
		return fareMinor;
	}

	public String getEquivalentCurrency() {
		return equivalentCurrency;
	}

	public long getEquivalentMinor() {
		return equivalentMinor;
	}

	public Long getServiceFeeMinor() {
		return serviceFeeMinor;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}
