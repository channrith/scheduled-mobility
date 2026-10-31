package com.mobility.core.pricing.quote;

import java.util.List;

import com.mobility.core.pricing.quote.FareCalculator.Line;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Quote breakdown lines as JSON text; the column is JSONB (see {@code @ColumnTransformer} on the field). */
@Converter
class BreakdownConverter implements AttributeConverter<List<Line>, String> {

	private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper;

	BreakdownConverter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String convertToDatabaseColumn(List<Line> lines) {
		return jsonMapper.writeValueAsString(lines == null ? List.of() : lines);
	}

	@Override
	public List<Line> convertToEntityAttribute(String json) {
		return json == null || json.isBlank() ? List.of() : jsonMapper.readValue(json, LINES);
	}
}
