package com.mobility.core.corporate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Drives the corporate module through its HTTP API. */
public class CorporateApi {

	public static final String REASON = "Unpaid invoices for three months";

	public final RestTestClient client;

	public final AccessTokenIssuer issuer;

	public final UUID adminId = UUID.randomUUID();

	public final String adminToken;

	public CorporateApi(RestTestClient client, AccessTokenIssuer issuer) {
		this.client = client;
		this.issuer = issuer;
		this.adminToken = SignedTokens.forUser(issuer, adminId, Role.ADMIN);
	}

	public record Company(UUID id, String adminToken) {
	}

	/** Creates an account plus a company admin (via the platform endpoint) and a token for that admin. */
	public Company company() {
		return company(Map.of());
	}

	public Company company(Map<String, Object> overrides) {
		Map<String, Object> body = new HashMap<>(Map.of("legalName", "Company " + UUID.randomUUID().toString().substring(0, 8)));
		body.putAll(overrides);
		UUID id = UUID.fromString(post("/api/v1/admin/corporates", adminToken, body).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody().get("id").asString());
		post("/api/v1/admin/corporates/" + id + "/admins", adminToken,
				Map.of("phone", SamplePhones.next(), "fullName", "Company Admin")).expectStatus().isOk();
		return new Company(id, SignedTokens.corporate(issuer, id, Role.CORPORATE_ADMIN));
	}

	public JsonNode addMember(Company company, String role, Map<String, Object> extra) {
		Map<String, Object> body = new HashMap<>(Map.of("phone", SamplePhones.next(), "fullName", "Member", "role", role));
		body.putAll(extra);
		return post("/api/v1/corporates/" + company.id() + "/members", company.adminToken(), body).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public JsonNode addCostCenter(Company company, String code) {
		return post("/api/v1/corporates/" + company.id() + "/cost-centers", company.adminToken(),
				Map.of("code", code, "name", "Cost centre " + code)).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public String tokenFor(Role... roles) {
		return SignedTokens.withRoles(issuer, roles);
	}

	public RestTestClient.ResponseSpec get(String path, String token) {
		return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").exchange();
	}

	public RestTestClient.ResponseSpec post(String path, String token, Object body) {
		return send(client.post().uri(path), token, body);
	}

	public RestTestClient.ResponseSpec put(String path, String token, Object body) {
		return send(client.put().uri(path), token, body);
	}

	public RestTestClient.ResponseSpec delete(String path, String token) {
		return client.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").exchange();
	}

	private RestTestClient.ResponseSpec send(RestTestClient.RequestBodySpec spec, String token, Object body) {
		return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body).exchange();
	}
}
