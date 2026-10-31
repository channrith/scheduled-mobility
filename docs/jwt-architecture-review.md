# Shared JWT architecture review

Reviewed against the integrated `origin/main` revision `74aedc36748f5d891b53741375a415337457a8ec`.
This is an assessment and migration proposal; audience enforcement, JWKS publishing and gateway
JWT authentication are not implemented by the gateway/location merge.

## Recommendation for this platform

Keep core-app's identity module as the **only access-token issuer**. Mobile logs in once through
the gateway and sends the same access token to the intended core and location API routes.
Core-app owns the RS256 private key. Location-service and, optionally, the gateway receive public
verification keys only. A shared HMAC signing secret across applications would expand who can mint
user tokens and is inappropriate here.

Retain independent Spring Security Resource Server validation and authorization in both backends.
Gateway authentication can reject bad tokens early, but is an additional layer; it cannot replace
the core's role/method rules or location's driver identity/approval checks. Public OTP, refresh,
health, enabled docs and signed-preview routes need explicit treatment if edge authentication is
added. Keep the current transparent Authorization forwarding during this integration.

For the present first-party API, a deliberately shared audience such as `mobility-api` can cover
both core and location. If consumers/services later require separate trust boundaries, use distinct
audiences and an explicit token-exchange/delegation design. Do not simply forward a token to a
service outside its audience. Keep user-delegated profile lookup separate from future machine
credentials: location currently forwards the user's Bearer token to core's private origin and core
returns only that user's profile.

## Source findings

| Concern | Current implementation | Assessment |
| --- | --- | --- |
| Issuance | core `identity/token/AccessTokenIssuer` | Single issuer; location never signs |
| Algorithm | RS256; location explicitly pins RS256; core Nimbus public-key decoder defaults to RS256 | Appropriate asymmetric verification |
| Issuer | Both use `JWT_ISSUER`, default `mobility-core` | Consistent; keep exact values aligned |
| Expiration | Issued access tokens have exp and 15-minute default TTL; both decoders use 30-second skew | Expiry is checked when present; explicitly require exp as a hardening follow-up |
| Subject | User UUID; location resolves core's driver profile and checks userId equality | Preserve user ID versus driver ID distinction |
| Audience | Issuer does not emit aud; decoders do not validate aud | Main token-contract hardening gap |
| Key management | Static PEM; core JWK key ID derives from public-key thumbprint; no exposed JWKS endpoint | Works now; no seamless multi-key verification/rotation |
| Roles | Both map roles to ROLE_* | Same contract; service authorization still necessary |
| Refresh | Core-only rotating refresh tokens; gateway forwards normal auth requests | Keep refresh tokens out of GPS and socket requests |
| WebSocket | Handshake verifies token/profile; exact exp enforced and scheduled close 1008 | Preserve; reconnect with a new access token |
| Gateway | Routes, removes spoofed identity headers, preserves Bearer Authorization | Does not currently authenticate JWT itself |
| Shared Java code | Small duplicate validation/key-loading setup | Shared policy is necessary; a new shared library is optional, not a security prerequisite |

The expiry observation was checked against the installed Spring Security **7.1.1** class:
`JwtTimestampValidator` initializes `allowEmptyExpiryClaim=true`. Thus a custom timestamp validator
alone does not require exp. Existing issued tokens do contain it. This is a source/dependency
finding, not a claim that a missing-exp token was tested against the live system.

## Ordered improvement plan

1. Define an access-token contract: exact issuer, audience, RS256, required exp/sub/iat, short TTL,
   roles/scopes and token type. Add negative tests for wrong issuer/audience/key/algorithm,
   missing/expired exp, malformed subject and missing DRIVER role. Require exp in both decoders
   with `timestamps.setAllowEmptyExpiryClaim(false)`. Keep nbf optional unless issuance also adds it.
2. Add the intended aud at issuance first. After old access tokens have expired (maximum configured
   lifetime plus clock skew), enforce the audience in every resource server. Coordinate all replicas;
   an immediate validator change rejects currently issued tokens without aud.
3. Publish only public JWKs from core and support current plus retiring verification keys. Configure
   location with a trusted, fixed internal JWKS URL and the expected issuer. The existing custom
   issuer string is not a discovery URL; setting `issuer-uri=mobility-core` does not create discovery.
   With custom JwtDecoder beans, wire JWKS and audience validators explicitly; adding Boot
   properties alone does not override those beans.
4. Rotate by publishing the new public key before signing with it. Keep the retiring public key for
   at least the longest valid old access-token lifetime plus clock skew; test cached-key refresh and
   unknown-kid failures. Never use an arbitrary token-supplied jku URL for key retrieval.
5. If edge authentication is desired, use Spring Security's **reactive** resource-server configuration
   in the existing WebFlux gateway. Keep servlet resource-server security in both backend apps.
   A TokenRelay OAuth2-client setup is not required merely to preserve an incoming Bearer header;
   the routing tests and real local checks already verify forwarding.
6. Keep signing material in a restricted secret mount/secret manager, separately from public keys,
   with different keys and preferably distinct issuer values between environments. Never generate
   temporary keys on production startup. For more complex federation/SSO, evaluate a standards-based
   identity provider as a separate architecture decision, not a prerequisite for this MVP.

A common security library can later package claim validators, claim constants and contract tests.
It should expose separate servlet/reactive configuration, and must not distribute the private key
or couple driver authorization to gateway code. No new deployable or gateway framework is needed.

## Evidence and limits

The real local check logs in through the gateway and uses the returned **same access token** for
core's driver profile/availability and location's upload/latest/presence/WebSocket. This establishes
cross-service token compatibility for the current configuration. It does not verify JWKS rotation,
audience enforcement, missing-exp rejection, emergency access-token revocation or a deployed staging
edge. Refresh logout does not automatically revoke an already-issued stateless access JWT; choose
short TTLs and an explicit revocation strategy if immediate revocation becomes a requirement.

## Primary references

- [Spring Security resource-server JWT validation, audience and key rotation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security reactive resource-server JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html)
- [Spring Cloud Gateway security integration](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/spring-security.html)
- [Spring JwtTimestampValidator defaults](https://docs.spring.io/spring-security/site/docs/7.0.x/api/org/springframework/security/oauth2/jwt/JwtTimestampValidator.html)
- [RFC 8725: algorithm, issuer and audience validation](https://www.rfc-editor.org/rfc/rfc8725.html)
