# Running the Payment Microservice in VS Code

> **Short Answer:** YES, VS Code is completely fine for Spring Boot + Maven projects.  
> NetBeans is *one option* but VS Code with the Java extension pack is arguably better (faster, lighter, Git-integrated).

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Docker Desktop | Latest | `docker --version` |
| VS Code Extensions | See below | — |

### Required VS Code Extensions

Install these from the Extensions panel (`Ctrl+Shift+X`):

1. **Extension Pack for Java** (by Microsoft) – provides IntelliSense, build, debug
2. **Spring Boot Extension Pack** (by VMware) – run configs, live beans, request mappings
3. **REST Client** (by Humao) – for sending test HTTP requests directly from VS Code (optional but recommended)

---

## Option A: Mock Mode (No Docker / No External Services)

This is the fastest way to start. Uses H2 in-memory DB — no PostgreSQL, Redis, or RabbitMQ needed.

### Step 1 – Open Project
```
File → Open Folder → select "Payment Processing Microservice"
```

### Step 2 – Let Maven sync
Wait for the bottom status bar to show "Java Projects" resolved (usually 30–60 seconds on first open).

### Step 3 – Run with mock profile

**Terminal method (recommended):**
```powershell
cd "c:\Users\Hp\OneDrive\Desktop\ANTIGRATIVY TESTS\Payment Processing Microservice"
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

**OR via VS Code Spring Boot Dashboard:**
- Open the Spring Boot Dashboard panel (left sidebar, plant icon)
- Right-click `payment-service` → `Run` → Edit launch config to add `"vmArgs": "-Dspring.profiles.active=mock"`

### Step 4 – Verify startup
Look for this log line:
```
Started PaymentServiceApplication in X.XXX seconds
```

### Step 5 – Test mock endpoints

Open a new terminal and run:

```powershell
# ✅ Test 1: Successful payment
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/mock/payments/process" `
  -Method Post -ContentType "application/json" `
  -Body '{"orderId":"ORDER-001","amount":99.99,"currency":"USD","paymentMethodId":"pm_mock_visa","idempotencyKey":"key-001"}'

# ✅ Test 2: Failed payment simulation
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/mock/payments/fail" `
  -Method Post -ContentType "application/json" `
  -Body '{"orderId":"ORDER-002","amount":200.00,"currency":"INR","paymentMethodId":"pm_mock_decline","idempotencyKey":"key-002"}'

# ✅ Test 3: Status check
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/mock/payments/status/ORDER-001" -Method Get
```

### Step 6 – Run unit tests (no services needed)
```powershell
mvn test -Dtest=MockPaymentControllerTest
```

---

## Option B: Full Mode (with Docker)

This runs the real stack: PostgreSQL + Redis + RabbitMQ.

### Step 1 – Start all services
```powershell
cd "c:\Users\Hp\OneDrive\Desktop\ANTIGRATIVY TESTS\Payment Processing Microservice"
docker compose up -d
```

Verify containers are up:
```powershell
docker ps
```

### Step 2 – Set your Stripe key

Edit [src/main/resources/application.properties](file:///c:/Users/Hp/OneDrive/Desktop/ANTIGRATIVY%20TESTS/Payment%20Processing%20Microservice/src/main/resources/application.properties):
```properties
stripe.api.key=sk_test_YOUR_ACTUAL_KEY_HERE
```
> Get a test key from https://dashboard.stripe.com/test/apikeys

### Step 3 – Run the app (default profile)
```powershell
mvn spring-boot:run
```

### Step 4 – Test real endpoints
```powershell
# Uses real Stripe test card token
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/process" `
  -Method Post -ContentType "application/json" `
  -Body '{"orderId":"ORDER-REAL-001","amount":50.00,"currency":"USD","paymentMethodId":"pm_card_visa","idempotencyKey":"real-key-001"}'
```

### Step 5 – Stop services when done
```powershell
docker compose down
```

---

## Debugging in VS Code

1. Open [PaymentServiceApplication.java](file:///c:/Users/Hp/OneDrive/Desktop/ANTIGRATIVY%20TESTS/Payment%20Processing%20Microservice/src/main/java/com/plugpoint/paymentservice/PaymentServiceApplication.java)
2. Click the **Run | Debug** codelens above the `main` method
3. Select **Debug** — VS Code will stop at breakpoints

---

## VS Code vs NetBeans — Which to Use?

| Feature | VS Code | NetBeans |
|---------|---------|----------|
| Speed | ⚡ Fast startup | 🐢 Heavier |
| Spring Boot support | ✅ Excellent (Official extension) | ✅ Good (built-in) |
| Git integration | ✅ Built-in | ⚠️ Plugin needed |
| Maven GUI | ✅ Maven panel | ✅ Built-in |
| Debugger | ✅ Excellent | ✅ Excellent |
| Recommendation | ✅ **Go with VS Code** | Works if you prefer it |

> **Conclusion:** VS Code is a fully supported, industry-standard IDE for Spring Boot. NetBeans is not "preferred" – it's just traditional. Both work perfectly.
