# ADR 0004: Our own HS256 tokens, validated by Spring's resource server

Date: 2026-08-31
Status: accepted

## Context

The plan calls for email plus password, bcrypt, short lived JWT, and refresh by
logging in again. The scope guardrails say no OAuth and no SSO.

That creates an apparent contradiction: the Spring dependency for validating
JWTs is called `spring-boot-starter-oauth2-resource-server`, and adding
something with "oauth2" in the name to a project whose guardrails forbid OAuth
deserves an explanation.

## Decision

Use `spring-boot-starter-oauth2-resource-server` for token validation, with a
symmetric HS256 key from the `JWT_SECRET` environment variable.

The name is about the OAuth 2.0 *resource server* role, meaning "a service that
accepts bearer tokens". It is not an OAuth login flow. There is no authorisation
server, no redirect, no consent screen, no third party identity provider, and no
client registration. Gridwork issues its own tokens from its own login endpoint
against its own user table.

Tokens live 15 minutes. The subject is the user id, not the email, because
emails can change and the identity a token points at must not. There is no
refresh token, no revocation list, and no token table: the expiry is the only
thing limiting the damage of a leak, which is why it is short.

The application refuses to start if the secret is under 32 bytes, because HS256
with a short key is a real weakness and it is the kind that never announces
itself.

## Alternatives considered

- A hand rolled `OncePerRequestFilter` with a JWT library: about eighty lines,
  and every one of them is a chance to get signature checking, expiry, or
  algorithm confusion wrong. The `alg: none` attack exists because people wrote
  this by hand.
- Sessions and a cookie: simpler in some ways, but the API is meant to be
  stateless across replicas, and a session store is state that has to survive a
  pod restart.
- An external identity provider: forbidden by the guardrails, and correctly so.
  It would be a day of configuration teaching nothing the project needs.

## Consequences

Easier: signature validation, expiry, and clock skew are handled by code that is
widely used and reviewed. Phase 6 can put the API behind a load balancer with no
sticky sessions.

Harder: a leaked token is valid until it expires and there is no way to revoke
it. That is an accepted trade for a portfolio project and it is recorded in
HANDOFF rather than hidden. If this were real, the next step is a token version
column on the user, checked per request.

## The one-minute spoken version

Login returns a signed token that expires in fifteen minutes, and every request
carries it as a bearer header. I did not write the validation myself, I used
Spring's resource server support, because hand written JWT checking is where the
alg-none vulnerabilities come from. The dependency has "oauth2" in its name but
there is no OAuth here: no redirect, no identity provider, no consent. It is
just the standard code for accepting a bearer token. There is no refresh token
either. When it expires you log in again, which is honest for a project this
size.
