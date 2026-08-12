# Helyx HR

Multi-tenant HRIS and Leave Management SaaS. See `docs/Helyx_PRD.md` for the product spec and `docs/CURRENT_PHASE.md` for what's being worked on right now.

## Local setup

1. Install Java 25 (e.g. via [SDKMAN](https://sdkman.io/): `sdk install java 25-tem`).
2. Install PostgreSQL 17 locally (`brew install postgresql@17` on macOS, or your distro's package, or the official installer).
3. Create the database role and database:
   ```
   createuser helyx --pwprompt
   createdb helyx -O helyx
   psql helyx -c "CREATE SCHEMA helyx_hr AUTHORIZATION helyx;"
   ```
4. Clone this repo.
5. `cp .env.example .env` and fill in `DB_PASSWORD` (and `DB_USER` if you used a different role name). Also set `HELYX_EMPLOYEE_ENCRYPTION_KEY` — generate one with `openssl rand -base64 32` (see `.env.example` for details; this key must stay stable once real data is encrypted with it).
6. Export the env vars and start the app:
   ```
   export $(grep -v '^#' .env | xargs)
   ./mvnw spring-boot:run
   ```
7. Seed a dev tenant (tenant provisioning is SQL-only for now, per the implementation plan):
   ```
   psql helyx -c "INSERT INTO helyx_hr.tenant (slug, name) VALUES ('mhz', 'MHZ Software');"
   ```
8. Visit http://mhz.localhost:8080 — you should see "Hello from MHZ Software". (Browsers resolve `*.localhost` to 127.0.0.1 automatically; the bare http://localhost:8080 returns 404 by design, since every page belongs to a tenant subdomain.)
9. Run tests: `./mvnw test` (spins up its own ephemeral Postgres via Testcontainers — requires Docker running).
10. Format code: `./mvnw spotless:apply`.
11. Full local verify (tests + static analysis + coverage report): `./mvnw verify`.
