package com.mobility.core.audit.log;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mobility.core.audit.AuditTrail;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditTrail implements AuditTrail {

	private static final Pattern DETAIL_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,40}$");

	private final NamedParameterJdbcTemplate jdbc;

	private final JsonMapper jsonMapper;

	JdbcAuditTrail(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	@Override
	@Transactional(readOnly = true)
	public List<AuditEntry> find(List<Criterion> anyOf, int limit) {
		if (anyOf.isEmpty()) {
			return List.of();
		}
		MapSqlParameterSource params = new MapSqlParameterSource("limit", Math.clamp(limit, 1, 500));
		List<String> clauses = new ArrayList<>();
		for (int i = 0; i < anyOf.size(); i++) {
			switch (anyOf.get(i)) {
				case Target t -> {
					clauses.add("(target_type = :type" + i + " AND target_id = :id" + i + ")");
					params.addValue("type" + i, t.targetType()).addValue("id" + i, t.targetId());
				}
				case DetailMatch d -> {
					if (!DETAIL_KEY.matcher(d.key()).matches()) {
						throw new IllegalArgumentException("Invalid detail key: " + d.key());
					}
					clauses.add("(target_type = :type" + i + " AND details ->> '" + d.key() + "' = :value" + i + ")");
					params.addValue("type" + i, d.targetType()).addValue("value" + i, d.value());
				}
			}
		}
		return jdbc.query("""
				SELECT id, occurred_at, actor_user_id, actor_roles, action, target_type, target_id, details::text AS details
				FROM audit.audit_logs WHERE """ + String.join(" OR ", clauses) + """
				 ORDER BY occurred_at DESC, id LIMIT :limit""", params, (rs, n) -> {
			Array roles = rs.getArray("actor_roles");
			return new AuditEntry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
					rs.getObject("actor_user_id", UUID.class), List.of((String[]) roles.getArray()),
					rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
					jsonMapper.readValue(rs.getString("details"), new TypeReference<Map<String, Object>>() {
					}));
		});
	}
}
