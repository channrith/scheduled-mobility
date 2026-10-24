package com.mobility.core.dispatch.issue;

import java.util.Map;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Converter
class DetailsConverter implements AttributeConverter<Map<String, Object>, String> {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JsonMapper json;

	DetailsConverter(JsonMapper json) {
		this.json = json;
	}

	@Override
	public String convertToDatabaseColumn(Map<String, Object> details) {
		return json.writeValueAsString(details == null ? Map.of() : details);
	}

	@Override
	public Map<String, Object> convertToEntityAttribute(String value) {
		return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
	}
}
