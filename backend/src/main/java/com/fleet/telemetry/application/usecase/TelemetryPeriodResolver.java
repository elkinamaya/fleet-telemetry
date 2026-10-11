package com.fleet.telemetry.application.usecase;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

@Component
public class TelemetryPeriodResolver {

	public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");

	private final Clock clock;

	public TelemetryPeriodResolver(Clock clock) {
		this.clock = clock;
	}

	public TelemetryTimeRange resolve(TelemetryPeriod period, String from, String to) {
		if (period == null) {
			throw new IllegalArgumentException("Falta el periodo temporal.");
		}
		ZonedDateTime now = clock.instant().atZone(BUSINESS_ZONE);
		return switch (period) {
			case TODAY -> calendarDay(now.toLocalDate());
			case YESTERDAY -> calendarDay(now.toLocalDate().minusDays(1));
			case LAST_HOUR -> trailing(now.toInstant(), now.toInstant().minusSeconds(3600));
			case LAST_24_HOURS -> trailing(now.toInstant(), now.toInstant().minusSeconds(86400));
			case LAST_7_DAYS -> trailing(now.toInstant(), now.toInstant().minusSeconds(7 * 86400L));
			case EXPLICIT -> explicitRange(from, to);
			case ALL -> throw new IllegalArgumentException(
					"El periodo completo no es un intervalo temporal.");
		};
	}

	public TelemetryPeriod period(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Falta el periodo temporal.");
		}
		return switch (value) {
			case "today" -> TelemetryPeriod.TODAY;
			case "yesterday" -> TelemetryPeriod.YESTERDAY;
			case "last_hour" -> TelemetryPeriod.LAST_HOUR;
			case "last_24_hours" -> TelemetryPeriod.LAST_24_HOURS;
			case "last_7_days" -> TelemetryPeriod.LAST_7_DAYS;
			case "explicit" -> TelemetryPeriod.EXPLICIT;
			case "all" -> TelemetryPeriod.ALL;
			default -> throw new IllegalArgumentException("Periodo no soportado.");
		};
	}

	public TelemetryTimeRange explicitRange(String from, String to) {
		if (from == null || from.isBlank() || to == null || to.isBlank()) {
			throw new IllegalArgumentException("El intervalo explícito requiere inicio y fin.");
		}
		try {
			Instant start = parseBound(from, false);
			Instant end = parseBound(to, true);
			return new TelemetryTimeRange(start, end);
		} catch (DateTimeParseException exception) {
			throw new IllegalArgumentException(
					"Las fechas deben ser ISO-8601 o fecha/hora local válida.", exception);
		}
	}

	private TelemetryTimeRange calendarDay(LocalDate date) {
		Instant start = date.atStartOfDay(BUSINESS_ZONE).toInstant();
		Instant end = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
		return new TelemetryTimeRange(start, end);
	}

	private TelemetryTimeRange trailing(Instant end, Instant start) {
		return new TelemetryTimeRange(start, end);
	}

	private Instant parseBound(String value, boolean endBound) {
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException ignored) {
			// Continue with offset or local calendar formats.
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ignored) {
			// Naive values are interpreted as Bogota local time.
		}
		try {
			return LocalDateTime.parse(value).atZone(BUSINESS_ZONE).toInstant();
		} catch (DateTimeParseException ignored) {
			LocalDate date = LocalDate.parse(value);
			return (endBound ? date.plusDays(1) : date).atStartOfDay(BUSINESS_ZONE).toInstant();
		}
	}
}
