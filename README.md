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
5. `cp .env.example .env` and fill in `DB_PASSWORD` (and `DB_USER` if you used a different role name).
6. Export the env vars and start the app:
   ```
   export $(grep -v '^#' .env | xargs)
   ./mvnw spring-boot:run
   ```
7. Visit http://localhost:8080 — you should see "Hello from Helyx".
8. Run tests: `./mvnw test` (spins up its own ephemeral Postgres via Testcontainers — requires Docker running).
9. Format code: `./mvnw spotless:apply`.
10. Full local verify (tests + static analysis + coverage report): `./mvnw verify`.
