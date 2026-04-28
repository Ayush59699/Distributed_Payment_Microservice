# 💳 Payment Processing Microservice

A production-grade, distributed **Payment Processing Microservice** built with **Java 17** and **Spring Boot 3.4**. Engineered for reliability and scale, it uses a **Mock Payment Gateway** to demonstrate complex distributed systems architecture without real external dependencies.

---

## 🏗️ Architecture Overview

```
                         ┌─────────────────────────────┐
  Client ──► APIM ──────►│      payment-service         │ (3–10 replicas)
  (Rate-limited:          │  Azure Container Apps        │
   5 req / 60 s)         │                              │
                         │  ┌────────────┐  ┌────────┐ │
                         │  │Idempotency │  │ Async  │ │
                         │  │(Redis)     │  │Consumer│ │
                         │  └─────┬──────┘  └───┬────┘ │
                         └────────│─────────────│───────┘
                                  │             │
                     ┌────────────┘    ┌────────┘
                     ▼                 ▼
            ┌──────────────┐  ┌───────────────────┐
            │  PostgreSQL  │  │  Azure Service Bus │
            │  (payments)  │  │  payment-queue     │
            └──────────────┘  └───────────────────┘
                     ▲
                     │  Redis
            ┌──────────────┐
            │   RabbitMQ   │ (internal Container App)
            └──────────────┘
```

### Key Design Patterns

| Pattern | Technology | Purpose |
|---|---|---|
| **Idempotency Engine** | Redis (`SETNX` + TTL) | Prevent double-charging on retries |
| **Async Processing** | Azure Service Bus | Decouple HTTP thread from DB writes |
| **Rate Limiting** | Azure API Management | 5 req / 60 s per subscription |
| **Auto-scaling** | Azure Container Apps | 3 min replicas → 10 max under load |
| **Audit Logging** | PostgreSQL (immutable table) | Full payment state trail |
| **Circuit Breaker** | Resilience4j | Protect against gateway failures |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.3 |
| Database | Azure PostgreSQL Flexible Server 15 (Standard_B1ms) |
| Cache / Idempotency | Azure Cache for Redis (Basic C0, SSL :6380) |
| Message Broker | Azure Service Bus (Basic, `payment-queue`) |
| Secondary Broker | RabbitMQ 3 (internal Container App) |
| Container Runtime | Azure Container Apps (3–10 replicas, 0.5 vCPU / 1 GiB) |
| Container Registry | Azure Container Registry (Basic) |
| API Gateway | Azure API Management (Consumption tier) |
| Observability | Azure Log Analytics Workspace |
| Build Tool | Maven |
| Local Dev Stack | Docker Compose (PostgreSQL + Redis + RabbitMQ) |

---

## 📁 Project Structure

```
payment-service/
├── src/
│   ├── main/
│   │   ├── java/com/plugpoint/paymentservice/
│   │   │   ├── PaymentServiceApplication.java   # Entry point
│   │   │   ├── config/                          # Spring config (Redis, Service Bus)
│   │   │   ├── controller/
│   │   │   │   ├── PaymentController.java        # Production REST API
│   │   │   │   └── MockPaymentController.java    # Mock endpoints (no infra needed)
│   │   │   ├── dto/                             # Request/Response DTOs
│   │   │   ├── model/
│   │   │   │   └── Payment.java                 # JPA entity with audit fields
│   │   │   ├── repository/                      # Spring Data JPA repositories
│   │   │   └── service/
│   │   │       ├── IdempotencyService.java       # Redis-backed idempotency
│   │   │       └── MockPaymentService.java       # Simulated integration + events
│   │   └── resources/
│   │       ├── application.properties           # Azure production config
│   │       └── application-mock.properties      # Mock profile (H2, local Redis)
│   └── test/
│       └── java/com/plugpoint/paymentservice/
│           └── controller/
│               └── MockPaymentControllerTest.java  # 7 integration tests
├── Dockerfile                                   # Multi-stage build (Maven → JRE 17)
├── docker-compose.yml                           # Local dev: PostgreSQL + Redis + RabbitMQ
├── apim-policy.xml                              # APIM rate-limiting policy
├── provision-azure.ps1                          # ✅ Full Azure infra provisioner
├── teardown-azure.ps1                           # ✅ Full Azure infra teardown
├── load-test.js                                 # k6 load test (ramp to 100 VUs)
├── RUNNN.txt                                    # Quick test commands (auto-updated)
├── payment_payload.json                         # Sample payment request body
└── pom.xml
```

---

## ⚙️ Prerequisites

| Tool | Required For |
|---|---|
| [Java 17+](https://adoptium.net/) | Running / building the app |
| [Maven 3.8+](https://maven.apache.org/) | Building the app |
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | Local full-stack mode |
| [Azure CLI](https://aka.ms/installazurecliwindows) | Cloud provisioning |
| [k6](https://k6.io/docs/get-started/installation/) | Load testing |

---

## 🚀 Running Locally

### Option 1: Mock Mode — no infrastructure required

Uses an **H2 file-based database** and disables all external services. Ideal for development.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

Test it:

```powershell
$URL = "http://localhost:8080"
curl.exe -X POST "$URL/api/v1/payments/process" -H "Content-Type: application/json" -d "@payment_payload.json"
curl.exe -X GET "$URL/"
# H2 Console → http://localhost:8080/h2-console
```

---

### Option 2: Full Local Mode — Docker Compose

```bash
# 1. Start local infrastructure
docker-compose up -d

# 2. Run the app (connects to local PostgreSQL + Redis + RabbitMQ)
mvn spring-boot:run

# 3. Stop when done
docker-compose down
```

Local services:

| Service | Port | Credentials |
|---|---|---|
| PostgreSQL | `5432` | `postgres / postgres` |
| Redis | `6379` | — |
| RabbitMQ | `5672` / `15672` | `guest / guest` |

RabbitMQ UI → [http://localhost:15672](http://localhost:15672)

---

## ☁️ Azure Deployment

### Provision (re-create everything from scratch)

```powershell
cd "d:\ANTIGRATIVY TESTS\Payment Processing Microservice"

# Full provision — builds image via ACR and wires all secrets automatically
.\provision-azure.ps1

# Skip image build (if already pushed to ACR)
.\provision-azure.ps1 -SkipBuild

# Skip APIM (saves ~25 min — useful for quick re-provisioning)
.\provision-azure.ps1 -SkipAPIM
```

The script is **idempotent** — already-existing resources are skipped safely.
After completion it auto-updates `RUNNN.txt` with the live URL.

### Azure Resources Provisioned

| Resource | Name | SKU |
|---|---|---|
| Resource Group | `payment-microservice-rg` | — |
| Log Analytics Workspace | `workspace-paymentmicroservicerg` | PerGB2018 |
| Container Registry | `paymentacr1356` | Basic |
| PostgreSQL Flexible Server | `payment-db-1356` | Standard_B1ms / Burstable |
| Redis Cache | `payment-redis-1356` | Basic C0 |
| Service Bus Namespace | `payment-sb-1356` | Basic |
| Service Bus Queue | `payment-queue` | — |
| Container Apps Environment | `payment-aca-env` | — |
| Container App | `rabbitmq` | 0.5 vCPU / 1 GiB |
| Container App | `payment-service` | 0.5 vCPU / 1 GiB, 3–10 replicas |
| API Management | `payment-apim-1356` | Consumption |

### Teardown (delete everything)

```powershell
.\teardown-azure.ps1
# → type  DELETE  to confirm
```

---

## 🔌 API Endpoints

### Production Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Health check + available routes |
| `POST` | `/api/v1/payments/process` | Process a new payment |
| `GET` | `/api/v1/payments/{id}` | Get payment status by ID |
| `POST` | `/api/v1/wallets/transfer` | Peer-to-peer wallet transfer |
| `GET` | `/api/v1/wallets/balance/{username}` | Get wallet balance |

**Payment Request Body:**

```json
{
  "orderId": "ORDER-001",
  "amount": 99.99,
  "currency": "USD",
  "paymentMethodId": "pm_card_visa",
  "idempotencyKey": "unique-request-key-001"
}
```

**Transfer Request Body:**

```json
{
  "fromUsername": "alice",
  "toUsername": "bob",
  "amount": 50.00,
  "idempotencyKey": "transfer-key-001"
}
```

**Expected Response Codes:**

| Code | Meaning |
|---|---|
| `200` | Payment / transfer succeeded |
| `202` | Accepted — processing asynchronously |
| `409` | Duplicate `idempotencyKey` — safely ignored |

---

## 🧪 Load Testing

Uses [k6](https://k6.io/) — ramps up to **100 virtual users**, alternating payment and transfer calls:

```powershell
k6 run load-test.js
```

Thresholds: `p(95) < 3000 ms`, error rate `< 20%`.

---

## 🧪 Unit Tests

```bash
mvn test
mvn test -Dtest=MockPaymentControllerTest   # only controller tests
```

Expected: **7/7 passing**.

---

## 🔐 Security & Compliance

- **PCI-DSS Awareness:** Raw PANs and CVVs are never stored — all sensitive input is tokenized before persistence.
- **Idempotency:** Every payment call requires a unique `idempotencyKey`. Redis atomically locks with `SETNX` + TTL.
- **Audit Trail:** All state transitions (`PENDING → PROCESSING → COMPLETED/FAILED`) are written to an immutable log in PostgreSQL.
- **Rate Limiting:** APIM enforces 5 requests / 60 s per subscription key.
- **Hikari Tuning:** Max pool size capped at 5 per replica — with up to 10 replicas, this stays within Azure PostgreSQL B1ms's ~50 connection limit.

---

## ⚡ Quick Reference

```powershell
# Local mock mode (no infra)
mvn spring-boot:run -Dspring-boot.run.profiles=mock

# Local full mode
docker-compose up -d && mvn spring-boot:run

# Build JAR
mvn clean package -DskipTests

# Tests
mvn test

# Provision Azure
.\provision-azure.ps1

# Teardown Azure
.\teardown-azure.ps1

# Load test
k6 run load-test.js
```

---

> Built with ☕ Java, 🍃 Spring Boot, and a love for distributed systems.
