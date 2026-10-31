package com.mobility.core.corporate.member;

import com.mobility.core.corporate.MemberPolicy;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

/** Stores {@link MemberPolicy} as JSON text; the column is JSONB (see {@code @ColumnTransformer} on the field). */
@Converter
class MemberPolicyConverter implements AttributeConverter<MemberPolicy, String> {

	private final JsonMapper jsonMapper;

	MemberPolicyConverter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String convertToDatabaseColumn(MemberPolicy policy) {
		return jsonMapper.writeValueAsString(policy == null ? MemberPolicy.UNRESTRICTED : policy);
	}

	@Override
	public MemberPolicy convertToEntityAttribute(String json) {
		return json == null || json.isBlank() ? MemberPolicy.UNRESTRICTED : jsonMapper.readValue(json, MemberPolicy.class);
	}
}
