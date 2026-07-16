# event-gateway

Ingests financial events (CREDIT/DEBIT), durably stores them, and forwards each one to
`account-service` exactly once — with idempotent resubmits and a circuit breaker guarding
the downstream call.

## System requirements

- Docker Desktop (or Docker Engine + Compose v2) — everything runs in containers, no local
  JDK required to run the stack.
- A clone of [`account-service`](../account-service) alongside this repo — event-gateway
  depends on it at runtime and does not stand it up itself.
- Only needed if you plan to build/run outside Docker: JDK 21 and Gradle (wrapper included).

## Starting the system

`account-service` and `event-gateway` are independent Compose stacks that communicate over a
shared external Docker network. Create that network once, start `account-service` first,
then start `event-gateway`.

```bash
# 1. One-time: create the shared network both stacks join
docker network create event-ledger-net

# 2. Start account-service (its own Postgres + the service)
cd ../account-service
docker compose up -d --build

# 3. Start event-gateway (its own Postgres + the gateway)
cd ../event-gateway
docker compose up -d --build
```

Ports exposed on localhost:

| Service              | Port |
|----------------------|------|
| event-gateway         | 8080 |
| account-service       | 8081 |
| event-gateway postgres | 5432 |
| account-service postgres | 5433 |

Check both are healthy:

```bash
curl.exe -s localhost:8080/actuator/health
curl.exe -s localhost:8081/health
```

## API

| Method | Path             | Purpose                              |
|--------|------------------|---------------------------------------|
| POST   | `/events`        | Submit an event (idempotent by `eventId`) |
| GET    | `/events/{id}`   | Fetch a stored event                  |
| GET    | `/events?account={accountId}` | List events for an account, chronological |

## Sample curl

Write the payload to a file first — passing multi-line JSON inline to `curl.exe` on
Windows/PowerShell is unreliable (embedded quotes get mangled by native-argument passing).

```powershell
@'
{
  "eventId": "evt-001",
  "accountId": "acct-977",
  "type": "DEBIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T08:30:11Z",
  "metadata": {"source": "mainframe-batch"}
}
'@ | Set-Content evt.json

curl.exe -X POST localhost:8080/events -H "Content-Type: application/json" -d "@evt.json"
```

Resubmitting the same file returns the original stored event (HTTP 200, unchanged
`receivedAt`) instead of re-applying it downstream — that's the idempotency guarantee, not a
bug.

Fetch it back:

```bash
curl.exe -s localhost:8080/events/evt-001
curl.exe -s "localhost:8080/events?account=acct-977"
```

## Running standalone, without Docker

Each service can also run as a bare jar with its own embedded, in-memory H2 database instead
of Postgres — two independent processes, no Docker, no shared state, nothing to install beyond
a JDK. Data does not survive a restart.

```bash
# account-service
cd account-service
./gradlew bootJar
java -jar build/libs/account-service.jar --spring.profiles.active=standalone

# event-gateway (separate terminal)
cd event-gateway
./gradlew bootJar
java -jar build/libs/event-gateway.jar --spring.profiles.active=standalone
```

This uses the `standalone` Spring profile (`application-standalone.yml` in each repo), which
points `spring.datasource.url` at `jdbc:h2:mem:...` instead of Postgres. Everything else —
ports, `ACCOUNT_SERVICE_URL`, Flyway migrations — is unchanged; the same SQL migrations that
run against Postgres in Docker run against H2 here. Verified end-to-end: submit an event via
`POST :8080/events`, and `GET :8081/accounts/{accountId}` on the standalone account-service
reflects the correct balance, with each process owning a completely separate database.

## Configuration

Set via environment variables on the `gateway` service in `docker-compose.yml`:

| Variable              | Default (in-container)         | Purpose                     |
|-----------------------|----------------------------------|------------------------------|
| `DB_URL`              | `jdbc:postgresql://postgres:5432/eventledger` | Gateway's own Postgres |
| `DB_USERNAME`         | `eventledger`                    | —                            |
| `DB_PASSWORD`         | `eventledger`                    | —                            |
| `ACCOUNT_SERVICE_URL` | `http://account-service:8081`    | Base URL for the downstream call — must resolve on `event-ledger-net`, not `localhost` |
