# Akka Actor Model Demo

A focused Akka Typed demo showcasing:
- Actor Model basics (tell/ask)
- Supervision and fault tolerance
- Cluster + Sharding for horizontal scaling
- HTTP entrypoint to drive the system

## Prerequisites
- Java 11+
- sbt 1.8+

## Run (single node)
```
sbt run
```
Server: http://127.0.0.1:8080

### Quick local testing (curl)
- Greet (ask pattern):
```
curl -s "http://127.0.0.1:8080/api/hello?name=Akka" | jq .
```
- Trigger supervised failure (fault tolerance demo - no retry):
```
curl -s -X POST "http://127.0.0.1:8080/api/work/fail?rate=0.6"
```
- Application-level retry (Option A):
```
curl -s -X POST "http://127.0.0.1:8080/api/work/retry?rate=0.8&attempts=3&delayMs=200" | jq .
```
- Persistence-backed retry (Option B):
```
curl -s -X POST "http://127.0.0.1:8080/api/work/persisted?rate=0.8" | jq .
```
- Increment sharded counter and read value:
```
ID=user-42
curl -s -X POST "http://127.0.0.1:8080/api/counter/$ID/inc?n=3" | jq .
curl -s "http://127.0.0.1:8080/api/counter/$ID" | jq .
```
Note: jq is optional; remove `| jq .` if not installed.

## Run multiple nodes (one machine)
Open two terminals and run:

Terminal 1:
```
sbt -Dakka.remote.artery.canonical.port=2551 -Dapp.http.port=8080 run
```

Terminal 2:
```
sbt -Dakka.remote.artery.canonical.port=2552 -Dapp.http.port=8081 run
```

Both nodes will join the same cluster via seed-nodes from application.conf and share sharded entities.

## API
Base path: /api

- GET /api/hello?name=Alice
  - Demonstrates ask pattern with Greeter actor
  - Example:
    - curl -s "http://127.0.0.1:8080/api/hello?name=Alice" | jq .
  - Response: {"message":"Hello, Alice! ..."}

- POST /api/work/fail?rate=0.5
  - Sends a DoWork command to a supervised FlakyWorker
  - With probability=rate it throws, and the supervisor restarts it (message not retried)
  - Example:
    - curl -s -X POST "http://127.0.0.1:8080/api/work/fail?rate=0.5"
  - Response: 202 Accepted

- POST /api/work/retry?rate=0.8&attempts=3&delayMs=200
  - Application-level retry with exponential backoff using RetryingWorker
  - Example:
    - curl -s -X POST "http://127.0.0.1:8080/api/work/retry?rate=0.8&attempts=3&delayMs=200" | jq .
  - Response: {"status":"done"} or {"status":"failed","reason":"exhausted"}

- POST /api/work/persisted?rate=0.8
  - Persistence-backed retry: persists the intent and re-executes on restart via event replay
  - Example:
    - curl -s -X POST "http://127.0.0.1:8080/api/work/persisted?rate=0.8" | jq .
  - Response: {"status":"intent-persisted"}

- POST /api/counter/{id}/inc?n=1
  - Increments a sharded counter entity with identity {id}
  - Example:
    - curl -s -X POST "http://127.0.0.1:8080/api/counter/user-1/inc?n=1" | jq .
  - Response: {"id":"{id}", "newValue":N}

- GET /api/counter/{id}
  - Reads current value of a sharded counter entity
  - Example:
    - curl -s "http://127.0.0.1:8080/api/counter/user-1" | jq .
  - Response: {"id":"{id}", "value":N}

## Key Concepts
- Actor Model: Encapsulation of state + behavior, async message passing
- Supervision: Parents decide child failure handling (restart, stop, resume)
- Retry Patterns:
  - Supervision-only (no retry): failure => restart, message may be lost
  - Application-level retry (Option A): explicit retries with backoff and final outcome
  - Persistence-backed retry (Option B): at-least-once via persisted intent and replay after crashes
- Cluster & Sharding: Location transparency; entities distributed across nodes automatically

## Notes
- Default binding:
  - Remoting: 127.0.0.1:2551 (override with -Dakka.remote.artery.canonical.port)
  - HTTP: 127.0.0.1:8080 (override with -Dapp.http.port)
- Messages use Jackson JSON for Akka Typed compatibility.
