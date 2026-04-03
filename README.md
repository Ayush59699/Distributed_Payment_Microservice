# 💳 Payment Processing Microservice

A production-grade, distributed **Payment Processing Microservice** built with **Java 17** and **Spring Boot 3.4**. Engineered for reliability and scale, it integrates with Stripe for payment processing, uses Redis for idempotency, RabbitMQ for async event-driven communication (Saga pattern), and PostgreSQL for persistent storage.

---

## 🏗️ Architecture Overview

```
┌──────────────┐     REST API      ┌──────────────────────────────────────┐
│   Client     │ ─────────────────► │        Payment Service               │
└──────────────┘                    │                                      │
                                    │  ┌─────────────┐  ┌───────────────┐ │
                                    │  │Idempotency  │  │Circuit Breaker│ │
                                    │  │(Redis SETNX)│  │ (Resilience4j)│ │
                                    │  └──────┬──────┘  └───────┬───────┘ │
                                    └─────────│──────────────────│─────────┘
                                              │                  │
                              ┌───────────────┘      ┌──────────┘
                              ▼                       ▼
                     ┌────────────────┐     ┌──────────────────┐
                     │   PostgreSQL   │     │  Stripe Gateway  │
                     │  (Audit Logs)  │     │  (Webhooks)      │
                     └────────────────┘     └──────────────────┘
                              ▲
                              │  Events (Saga Pattern)
                     ┌────────────────┐
                     │    RabbitMQ    │
                     │  (Order/Invent │
                     │   /Payment MQ) │
                     └────────────────┘
```

### Key Design Patterns

| Pattern | Technology | Purpose |
|---|---|---|
| **Idempotency Engine** | Redis (`SETNX` + TTL) | Prevent double-charging on retries |
| **Saga Pattern** | RabbitMQ (Choreography) | Distributed transaction management |
| **Circuit Breaker** | Resilience4j | Protect against gateway failures |
| **Async Webhooks** | Spring Retry / RabbitMQ DLQ | Reliable async payment status updates |
| **Audit Logging** | PostgreSQL (immutable table) | PCI-DSS compliance & traceability |
| **Tokenization** | Input Validation Layer | PCI-DSS: prevent raw PAN storage |

---

## 🛠️ Tech Stack

- **Language:** Java 17
- **Framework:** Spring Boot 3.4.3
- **Database:** PostgreSQL 15
- **Cache / Idempotency:** Redis 7
- **Message Broker:** RabbitMQ 3 (with Management UI)
- **Payment Gateway:** Stripe Java SDK v28
- **ORM:** Spring Data JPA / Hibernate
- **Build Tool:** Maven
- **Containerization:** Docker & Docker Compose
- **Testing:** JUnit 5 + Spring Boot Test (MockMvc)
- **Utilities:** Lombok

---

## 📁 Project Structure

```
payment-service/
├── src/
│   ├── main/
│   │   ├── java/com/plugpoint/paymentservice/
│   │   │   ├── PaymentServiceApplication.java   # Entry point
│   │   │   ├── config/                          # Spring config (Redis, RabbitMQ, Stripe)
│   │   │   ├── controller/
│   │   │   │   ├── PaymentController.java        # Production REST API
│   │   │   │   └── MockPaymentController.java    # Mock endpoints (no infra needed)
│   │   │   ├── dto/                             # Request/Response DTOs
│   │   │   ├── model/
│   │   │   │   └── Payment.java                 # JPA entity with audit fields
│   │   │   ├── repository/                      # Spring Data JPA repositories
│   │   │   └── service/
│   │   │       ├── IdempotencyService.java       # Redis-backed idempotency
│   │   │       └── StripePaymentService.java     # Stripe integration + Saga events
│   │   └── resources/
│   │       ├── application.properties           # Production config
│   │       └── application-mock.properties      # Mock profile (H2, no Redis/RabbitMQ)
│   └── test/
│       └── java/com/plugpoint/paymentservice/
│           └── controller/
│               └── MockPaymentControllerTest.java  # 7 integration tests
├── docker-compose.yml                           # PostgreSQL + Redis + RabbitMQ
└── pom.xml
```

---

## ⚙️ Prerequisites

Make sure you have the following installed:

- [Java 17+](https://adoptium.net/)
- [Maven 3.8+](https://maven.apache.org/)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for full mode)

---

## 🚀 Running the Application

### Option 1: Mock Mode (No External Services Required)

Perfect for local development and demos — uses an **H2 in-memory database** and disables Redis/RabbitMQ.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

Test the mock endpoint:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/mock/payments/process" `
  -Method Post -ContentType "application/json" `
  -Body '{"orderId":"ORDER-001","amount":99.99,"currency":"USD","paymentMethodId":"pm_mock_visa","idempotencyKey":"key-001"}'
```

---

### Option 2: Full Production Mode (Docker)

**Step 1:** Start all infrastructure services:

```bash
docker-compose up -d
```

This spins up:
- **PostgreSQL** on port `5432`
- **Redis** on port `6379`
- **RabbitMQ** on ports `5672` (AMQP) and `15672` (Management UI)

**Step 2:** Configure your Stripe API key in `application.properties`:

```properties
stripe.api.key=sk_test_YOUR_ACTUAL_KEY_HERE
```

**Step 3:** Run the application:

```bash
mvn spring-boot:run
```

---

## 🔌 API Endpoints

### Production Endpoints (`/api/v1/payments`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/payments/process` | Process a new payment |
| `GET` | `/api/v1/payments/{id}` | Get payment status by ID |

**Request Body (Process Payment):**

```json
{
  "orderId": "ORDER-001",
  "amount": 99.99,
  "currency": "USD",
  "paymentMethodId": "pm_card_visa",
  "idempotencyKey": "unique-request-key-001"
}
```

### Mock Endpoints (`/api/v1/mock`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/mock/payments/process` | Simulate a successful payment |
| `POST` | `/api/v1/mock/payments/fail` | Simulate a failed payment |
| `GET` | `/api/v1/mock/payments/{id}` | Get mock payment status |

---

## 🔄 Distributed Transaction Flow (Saga Pattern)

```
Client ──► Payment Service ──► (1) Check Idempotency Key in Redis
                             ──► (2) Create Order in PostgreSQL (PENDING)
                             ──► (3) Publish `OrderCreated` to RabbitMQ
                                          │
                          Inventory Service ◄── (4) Listen for `OrderCreated`
                                          ──► (5) Reserve stock
                                          ──► (6) Publish `InventoryReserved`
                                                       │
                          Payment Service  ◄── (7) Listen for `InventoryReserved`
                                          ──► (8) Call Stripe via Circuit Breaker
                                          ──► (9) Await Stripe webhook
                                          ──► (10) Update Order to COMPLETED

        ── On Failure ──────────────────────────────────────────────────►
        PaymentFailed event → Inventory released → Order marked FAILED
```

---

## 🧪 Running Tests

```bash
# Run all tests
mvn test

# Run only the mock controller tests
mvn test -Dtest=MockPaymentControllerTest
```

**Test Results (7/7 passing):**

```
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
Running com.plugpoint.paymentservice.controller.MockPaymentControllerTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.03 s

Results:
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

---

## 🐳 Docker Services

| Service | Container | Port(s) | Credentials |
|---|---|---|---|
| PostgreSQL | `payments-db` | `5432` | `postgres / postgres` |
| Redis | `payments-redis` | `6379` | — |
| RabbitMQ | `payments-mq` | `5672`, `15672` | `guest / guest` |

RabbitMQ Management UI: [http://localhost:15672](http://localhost:15672)

---

## 🔐 Security & Compliance

- **PCI-DSS Awareness:** Raw PANs (card numbers) and CVVs are never stored. All sensitive input is tokenized before persistence.
- **Idempotency:** Every payment API call requires a unique `idempotencyKey`. Redis atomically locks requests (`SETNX` + TTL) to prevent double-charges even under concurrent retries.
- **Audit Trail:** All payment state transitions (`PENDING → PROCESSING → COMPLETED/FAILED`) are written to an immutable audit log in PostgreSQL for traceability.
- **Circuit Breaker:** Resilience4j protects the system from cascading failures when Stripe is unavailable, failing fast instead of exhausting threads.

---

## 🏃 Quick Reference

```bash
# Start infrastructure
docker-compose up -d

# Run in mock mode (no infrastructure needed)
mvn spring-boot:run -Dspring-boot.run.profiles=mock

# Run in production mode
mvn spring-boot:run

# Run tests
mvn test

# Build JAR
mvn clean package -DskipTests

# Stop infrastructure
docker-compose down
```

---

## 📄 License

This project is for educational and portfolio purposes.

---

> Built with ☕ Java, 🍃 Spring Boot, and a love for distributed systems.
