
#Requires -Version 5.1
<#
.SYNOPSIS
    Full Azure Infrastructure Provisioning Script
    Payment Processing Microservice — Central India

.DESCRIPTION
    Provisions ALL infrastructure resources from scratch:
      1.  Resource Group
      2.  Log Analytics Workspace
      3.  Azure Container Registry (ACR)   → paymentacr1356
      4.  Azure Database for PostgreSQL    → payment-db-1356
      5.  Azure Cache for Redis            → payment-redis-1356
      6.  Azure Service Bus Namespace      → payment-sb-1356
      7.  Container Apps Environment       → payment-aca-env
      8.  RabbitMQ Container App           → rabbitmq
      9.  Payment-Service Container App    → payment-service
      10. Azure API Management             → payment-apim-1356

    Idempotent — already-existing resources are skipped gracefully.
    Run this script whenever you need to re-provision the environment.

.USAGE
    .\provision-azure.ps1

    Optional flags:
      -SkipBuild        Skip the ACR image build step (if image already pushed)
      -SkipAPIM         Skip APIM creation (takes ~30 min, Consumption tier)
      -ResourceGroup    Override the resource group name (default: payment-microservice-rg)

.NOTES
    Prerequisites:
      - Azure CLI  (az) installed and logged in  →  az login
      - Docker Desktop running (only if -SkipBuild is NOT set)
      - You are in the project root directory
#>

param(
    [switch]$SkipBuild,
    [switch]$SkipAPIM,
    [string]$ResourceGroup = "payment-microservice-rg"
)

$ErrorActionPreference = "Stop"

# ─────────────────────────────────────────────
#  CONFIGURATION  — matches your live resources
# ─────────────────────────────────────────────
$RAND             = "1356"
$LOCATION         = "centralindia"
$RESOURCE_GROUP   = $ResourceGroup

$ACR_NAME         = "paymentacr$RAND"                # paymentacr1356
$DB_SERVER_NAME   = "payment-db-$RAND"               # payment-db-1356
$DB_NAME          = "payments_db"
$DB_ADMIN_USER    = "dbadmin"
$DB_ADMIN_PASS    = "P@ssw0rd12345"

$REDIS_NAME       = "payment-redis-$RAND"            # payment-redis-1356
$SB_NAMESPACE     = "payment-sb-$RAND"               # payment-sb-1356
$SB_QUEUE_NAME    = "payment-queue"

$ACA_ENV_NAME     = "payment-aca-env"
$ACA_APP_NAME     = "payment-service"
$APIM_NAME        = "payment-apim-$RAND"             # payment-apim-1356
$LAW_NAME         = "workspace-paymentmicroservicerg" # Log Analytics Workspace

$PUBLISHER_EMAIL  = "admin@example.com"
$PUBLISHER_NAME   = "PaymentService"

# App tuning (kept identical to what was live)
$MOCK_LATENCY_MS  = "800"
$MOCK_FAIL_RATE   = "0.1"
$HIKARI_POOL_SIZE = "5"

# ─────────────────────────────────────────────
#  HELPERS
# ─────────────────────────────────────────────
function Write-Step([string]$msg) {
    Write-Host "`n━━━  $msg" -ForegroundColor Cyan
}
function Write-OK([string]$msg) {
    Write-Host "  ✅ $msg" -ForegroundColor Green
}
function Write-Skip([string]$msg) {
    Write-Host "  ⏭️  $msg (already exists — skipped)" -ForegroundColor Yellow
}
function Write-Info([string]$msg) {
    Write-Host "  ℹ️  $msg" -ForegroundColor Gray
}
function Write-Err([string]$msg) {
    Write-Host "  ❌ $msg" -ForegroundColor Red
}

function ResourceExists([string]$checkCmd) {
    $result = Invoke-Expression $checkCmd 2>$null
    return ($result -ne $null -and $result -ne "" -and $result -ne "[]")
}

# ─────────────────────────────────────────────
#  PRE-FLIGHT
# ─────────────────────────────────────────────
Write-Host @"

╔══════════════════════════════════════════════════════════════════╗
║       Payment Microservice — Azure Infrastructure Provisioner    ║
║       Region : $LOCATION                                    ║
║       RG     : $RESOURCE_GROUP                         ║
╚══════════════════════════════════════════════════════════════════╝
"@ -ForegroundColor Magenta

# Verify az CLI is available
try { az version --output none } catch {
    Write-Err "Azure CLI not found. Install from https://aka.ms/installazurecliwindows"
    exit 1
}

# Verify logged in
$account = az account show --query "name" -o tsv 2>$null
if (-not $account) {
    Write-Err "Not logged in to Azure. Run:  az login"
    exit 1
}
Write-Info "Logged in to Azure subscription: $account"

# ─────────────────────────────────────────────
#  STEP 0: Register Resource Providers
# ─────────────────────────────────────────────
Write-Step "Registering required Azure Resource Providers"
$providers = @(
    "Microsoft.ContainerRegistry",
    "Microsoft.App",
    "Microsoft.DBforPostgreSQL",
    "Microsoft.Cache",
    "Microsoft.ApiManagement",
    "Microsoft.OperationalInsights",
    "Microsoft.ServiceBus"
)
foreach ($provider in $providers) {
    $state = az provider show --namespace $provider --query "registrationState" -o tsv 2>$null
    if ($state -ne "Registered") {
        Write-Info "Registering $provider ..."
        az provider register --namespace $provider --wait | Out-Null
        Write-OK "$provider registered"
    } else {
        Write-Skip "$provider"
    }
}

# ─────────────────────────────────────────────
#  STEP 1: Resource Group
# ─────────────────────────────────────────────
Write-Step "Resource Group: $RESOURCE_GROUP"
$rgExists = az group exists --name $RESOURCE_GROUP
if ($rgExists -eq "true") {
    Write-Skip "Resource group $RESOURCE_GROUP"
} else {
    az group create --name $RESOURCE_GROUP --location $LOCATION | Out-Null
    Write-OK "Resource group created"
}

# ─────────────────────────────────────────────
#  STEP 2: Log Analytics Workspace
# ─────────────────────────────────────────────
Write-Step "Log Analytics Workspace: $LAW_NAME"
$lawExists = az monitor log-analytics workspace show `
    --resource-group $RESOURCE_GROUP `
    --workspace-name $LAW_NAME `
    --query "name" -o tsv 2>$null
if ($lawExists) {
    Write-Skip "Log Analytics Workspace $LAW_NAME"
    $LAW_ID = az monitor log-analytics workspace show `
        --resource-group $RESOURCE_GROUP `
        --workspace-name $LAW_NAME `
        --query "id" -o tsv
} else {
    az monitor log-analytics workspace create `
        --resource-group $RESOURCE_GROUP `
        --workspace-name $LAW_NAME `
        --location $LOCATION `
        --sku PerGB2018 | Out-Null
    $LAW_ID = az monitor log-analytics workspace show `
        --resource-group $RESOURCE_GROUP `
        --workspace-name $LAW_NAME `
        --query "id" -o tsv
    Write-OK "Log Analytics Workspace created: $LAW_NAME"
}

# ─────────────────────────────────────────────
#  STEP 3: Azure Container Registry (ACR)
# ─────────────────────────────────────────────
Write-Step "Azure Container Registry: $ACR_NAME"
$acrExists = az acr show --name $ACR_NAME --query "name" -o tsv 2>$null
if ($acrExists) {
    Write-Skip "ACR $ACR_NAME"
} else {
    az acr create `
        --resource-group $RESOURCE_GROUP `
        --name $ACR_NAME `
        --sku Basic `
        --location $LOCATION `
        --admin-enabled true | Out-Null
    Write-OK "ACR created: $ACR_NAME.azurecr.io"
    Write-Info "Waiting 30s for ACR to be fully ready..."
    Start-Sleep -Seconds 30
}

# Enable admin (idempotent)
az acr update --name $ACR_NAME --admin-enabled true | Out-Null

if (-not $SkipBuild) {
    Write-Step "Building & Pushing Docker image via ACR Build"
    Write-Info "Using ACR remote build — Docker Desktop NOT required locally"
    az acr build `
        --registry $ACR_NAME `
        --image "payment-service:latest" `
        --file Dockerfile `
        . | Out-Null
    Write-OK "Image pushed: $ACR_NAME.azurecr.io/payment-service:latest"
} else {
    Write-Skip "Docker image build (-SkipBuild flag set)"
}

$ACR_PASSWORD = az acr credential show --name $ACR_NAME --query "passwords[0].value" -o tsv

# ─────────────────────────────────────────────
#  STEP 4: Azure Database for PostgreSQL
# ─────────────────────────────────────────────
Write-Step "PostgreSQL Flexible Server: $DB_SERVER_NAME"
$dbExists = az postgres flexible-server show `
    --resource-group $RESOURCE_GROUP `
    --name $DB_SERVER_NAME `
    --query "name" -o tsv 2>$null
if ($dbExists) {
    Write-Skip "PostgreSQL server $DB_SERVER_NAME"
} else {
    az postgres flexible-server create `
        --resource-group $RESOURCE_GROUP `
        --name $DB_SERVER_NAME `
        --location $LOCATION `
        --admin-user $DB_ADMIN_USER `
        --admin-password $DB_ADMIN_PASS `
        --sku-name Standard_B1ms `
        --tier Burstable `
        --storage-size 32 `
        --version 15 `
        --public-access 0.0.0.0 | Out-Null
    Write-OK "PostgreSQL server created"
    Write-Info "Waiting 60s for PostgreSQL to initialise..."
    Start-Sleep -Seconds 60
}

# Create DB (idempotent — errors if exists are swallowed)
$dbNameExists = az postgres flexible-server db show `
    --resource-group $RESOURCE_GROUP `
    --server-name $DB_SERVER_NAME `
    --database-name $DB_NAME `
    --query "name" -o tsv 2>$null
if ($dbNameExists) {
    Write-Skip "Database $DB_NAME"
} else {
    az postgres flexible-server db create `
        --resource-group $RESOURCE_GROUP `
        --server-name $DB_SERVER_NAME `
        --database-name $DB_NAME | Out-Null
    Write-OK "Database '$DB_NAME' created"
}

# Firewall — allow Azure services (0.0.0.0 rule)
az postgres flexible-server firewall-rule create `
    --resource-group $RESOURCE_GROUP `
    --name $DB_SERVER_NAME `
    --rule-name AllowAzureServices `
    --start-ip-address 0.0.0.0 `
    --end-ip-address 0.0.0.0 2>$null | Out-Null

# ─────────────────────────────────────────────
#  STEP 5: Azure Cache for Redis
# ─────────────────────────────────────────────
Write-Step "Redis Cache: $REDIS_NAME"
$redisExists = az redis show `
    --resource-group $RESOURCE_GROUP `
    --name $REDIS_NAME `
    --query "name" -o tsv 2>$null
if ($redisExists) {
    Write-Skip "Redis $REDIS_NAME"
} else {
    az redis create `
        --resource-group $RESOURCE_GROUP `
        --name $REDIS_NAME `
        --location $LOCATION `
        --sku Basic `
        --vm-size C0 | Out-Null
    Write-OK "Redis cache created"
    Write-Info "Waiting 60s for Redis to be ready..."
    Start-Sleep -Seconds 60
}

$REDIS_KEY = az redis list-keys `
    --name $REDIS_NAME `
    --resource-group $RESOURCE_GROUP `
    --query "primaryKey" -o tsv

# ─────────────────────────────────────────────
#  STEP 6: Azure Service Bus Namespace + Queue
# ─────────────────────────────────────────────
Write-Step "Service Bus Namespace: $SB_NAMESPACE"
$sbExists = az servicebus namespace show `
    --resource-group $RESOURCE_GROUP `
    --name $SB_NAMESPACE `
    --query "name" -o tsv 2>$null
if ($sbExists) {
    Write-Skip "Service Bus namespace $SB_NAMESPACE"
} else {
    az servicebus namespace create `
        --resource-group $RESOURCE_GROUP `
        --name $SB_NAMESPACE `
        --location $LOCATION `
        --sku Basic | Out-Null
    Write-OK "Service Bus namespace created"
}

# Queue (idempotent)
$queueExists = az servicebus queue show `
    --resource-group $RESOURCE_GROUP `
    --namespace-name $SB_NAMESPACE `
    --name $SB_QUEUE_NAME `
    --query "name" -o tsv 2>$null
if ($queueExists) {
    Write-Skip "Queue $SB_QUEUE_NAME"
} else {
    az servicebus queue create `
        --resource-group $RESOURCE_GROUP `
        --namespace-name $SB_NAMESPACE `
        --name $SB_QUEUE_NAME `
        --max-size 1024 `
        --default-message-time-to-live P14D | Out-Null
    Write-OK "Queue '$SB_QUEUE_NAME' created"
}

$SB_CONNECTION_STRING = az servicebus namespace authorization-rule keys list `
    --resource-group $RESOURCE_GROUP `
    --namespace-name $SB_NAMESPACE `
    --name RootManageSharedAccessKey `
    --query "primaryConnectionString" -o tsv

# ─────────────────────────────────────────────
#  STEP 7: Container Apps Environment
# ─────────────────────────────────────────────
Write-Step "Container Apps Environment: $ACA_ENV_NAME"
$acaEnvExists = az containerapp env show `
    --resource-group $RESOURCE_GROUP `
    --name $ACA_ENV_NAME `
    --query "name" -o tsv 2>$null
if ($acaEnvExists) {
    Write-Skip "Container Apps environment $ACA_ENV_NAME"
} else {
    az containerapp env create `
        --name $ACA_ENV_NAME `
        --resource-group $RESOURCE_GROUP `
        --location $LOCATION `
        --logs-workspace-id $LAW_ID | Out-Null
    Write-OK "Container Apps environment created"
    Write-Info "Waiting 30s for environment to stabilise..."
    Start-Sleep -Seconds 30
}

# ─────────────────────────────────────────────
#  STEP 8: RabbitMQ Container App
# ─────────────────────────────────────────────
Write-Step "Container App: rabbitmq"
$rabbitExists = az containerapp show `
    --resource-group $RESOURCE_GROUP `
    --name rabbitmq `
    --query "name" -o tsv 2>$null
if ($rabbitExists) {
    Write-Skip "Container App rabbitmq"
} else {
    az containerapp create `
        --name rabbitmq `
        --resource-group $RESOURCE_GROUP `
        --environment $ACA_ENV_NAME `
        --image rabbitmq:3-management `
        --target-port 5672 `
        --ingress internal `
        --min-replicas 1 `
        --max-replicas 1 `
        --cpu 0.5 --memory 1.0Gi | Out-Null
    Write-OK "RabbitMQ container app deployed"
}

# ─────────────────────────────────────────────
#  STEP 9: Payment Service Container App
# ─────────────────────────────────────────────
Write-Step "Container App: $ACA_APP_NAME"
$paymentAppExists = az containerapp show `
    --resource-group $RESOURCE_GROUP `
    --name $ACA_APP_NAME `
    --query "name" -o tsv 2>$null
if ($paymentAppExists) {
    Write-Skip "Container App $ACA_APP_NAME — updating env vars only"
    az containerapp update `
        --name $ACA_APP_NAME `
        --resource-group $RESOURCE_GROUP `
        --set-env-vars `
            "SPRING_DATASOURCE_URL=jdbc:postgresql://$DB_SERVER_NAME.postgres.database.azure.com:5432/$DB_NAME" `
            "SPRING_DATASOURCE_USERNAME=$DB_ADMIN_USER" `
            "SPRING_DATASOURCE_PASSWORD=$DB_ADMIN_PASS" `
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$HIKARI_POOL_SIZE" `
            "SPRING_DATA_REDIS_HOST=$REDIS_NAME.redis.cache.windows.net" `
            "SPRING_DATA_REDIS_PORT=6380" `
            "SPRING_DATA_REDIS_PASSWORD=$REDIS_KEY" `
            "SPRING_DATA_REDIS_SSL=true" `
            "SERVICEBUS_CONNECTION_STRING=$SB_CONNECTION_STRING" `
            "PAYMENT_MOCK_LATENCY_MS=$MOCK_LATENCY_MS" `
            "PAYMENT_MOCK_FAILURE_RATE=$MOCK_FAIL_RATE" | Out-Null
} else {
    az containerapp create `
        --name $ACA_APP_NAME `
        --resource-group $RESOURCE_GROUP `
        --environment $ACA_ENV_NAME `
        --image "$ACR_NAME.azurecr.io/payment-service:latest" `
        --registry-server "$ACR_NAME.azurecr.io" `
        --registry-username $ACR_NAME `
        --registry-password $ACR_PASSWORD `
        --target-port 8080 `
        --ingress external `
        --min-replicas 3 `
        --max-replicas 10 `
        --cpu 0.5 --memory 1.0Gi `
        --env-vars `
            "SPRING_DATASOURCE_URL=jdbc:postgresql://$DB_SERVER_NAME.postgres.database.azure.com:5432/$DB_NAME" `
            "SPRING_DATASOURCE_USERNAME=$DB_ADMIN_USER" `
            "SPRING_DATASOURCE_PASSWORD=$DB_ADMIN_PASS" `
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$HIKARI_POOL_SIZE" `
            "SPRING_DATA_REDIS_HOST=$REDIS_NAME.redis.cache.windows.net" `
            "SPRING_DATA_REDIS_PORT=6380" `
            "SPRING_DATA_REDIS_PASSWORD=$REDIS_KEY" `
            "SPRING_DATA_REDIS_SSL=true" `
            "SERVICEBUS_CONNECTION_STRING=$SB_CONNECTION_STRING" `
            "PAYMENT_MOCK_LATENCY_MS=$MOCK_LATENCY_MS" `
            "PAYMENT_MOCK_FAILURE_RATE=$MOCK_FAIL_RATE" | Out-Null
    Write-OK "Payment service deployed (3 replicas, auto-scale to 10)"
}

$FQDN = az containerapp show `
    --name $ACA_APP_NAME `
    --resource-group $RESOURCE_GROUP `
    --query "properties.configuration.ingress.fqdn" -o tsv

# ─────────────────────────────────────────────
#  STEP 10: API Management (optional — ~25 min)
# ─────────────────────────────────────────────
if (-not $SkipAPIM) {
    Write-Step "API Management: $APIM_NAME  (Consumption tier — takes ~25 min)"
    $apimExists = az apim show `
        --resource-group $RESOURCE_GROUP `
        --name $APIM_NAME `
        --query "name" -o tsv 2>$null
    if ($apimExists) {
        Write-Skip "APIM $APIM_NAME"
    } else {
        az apim create `
            --name $APIM_NAME `
            --resource-group $RESOURCE_GROUP `
            --publisher-email $PUBLISHER_EMAIL `
            --publisher-name $PUBLISHER_NAME `
            --sku-name Consumption `
            --location $LOCATION | Out-Null
        Write-OK "APIM instance created"
    }

    # API definition
    $apiExists = az apim api show `
        --resource-group $RESOURCE_GROUP `
        --service-name $APIM_NAME `
        --api-id payment-api `
        --query "name" -o tsv 2>$null
    if ($apiExists) {
        Write-Skip "APIM API 'payment-api'"
    } else {
        az apim api create `
            --resource-group $RESOURCE_GROUP `
            --service-name $APIM_NAME `
            --api-id payment-api `
            --path v1 `
            --display-name "Payment API" `
            --protocols https `
            --service-url "https://$FQDN/api/v1" | Out-Null
        Write-OK "APIM API 'payment-api' created"
    }

    # Rate limiting policy (5 calls / 60 s per subscription)
    if (Test-Path "apim-policy.xml") {
        az apim api policy set `
            --resource-group $RESOURCE_GROUP `
            --service-name $APIM_NAME `
            --api-id payment-api `
            --xml-content (Get-Content apim-policy.xml -Raw) | Out-Null
        Write-OK "Rate-limiting policy applied (5 req / 60 s)"
    } else {
        Write-Info "apim-policy.xml not found — skipping policy apply"
    }
} else {
    Write-Host "`n  ⏭️  APIM creation skipped (-SkipAPIM flag set)" -ForegroundColor Yellow
    $APIM_NAME = "<not-provisioned>"
}

# ─────────────────────────────────────────────
#  FINAL SUMMARY
# ─────────────────────────────────────────────
$APIM_URL = if ($SkipAPIM) { "N/A (skipped)" } else { "https://$APIM_NAME.azure-api.net/v1" }

Write-Host @"

╔══════════════════════════════════════════════════════════════════════════════╗
║                       ✅  PROVISIONING COMPLETE                             ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  Resource Group    : $RESOURCE_GROUP
║  Location          : $LOCATION
╠══════════════════════════════════════════════════════════════════════════════╣
║  RESOURCE                     NAME / ENDPOINT
║  ─────────────────────────── ──────────────────────────────────────────────
║  Log Analytics Workspace    : $LAW_NAME
║  Container Registry          : $ACR_NAME.azurecr.io
║  PostgreSQL Flexible Server  : $DB_SERVER_NAME.postgres.database.azure.com
║  Redis Cache                 : $REDIS_NAME.redis.cache.windows.net:6380
║  Service Bus Namespace       : $SB_NAMESPACE  (queue: $SB_QUEUE_NAME)
║  Container Apps Environment  : $ACA_ENV_NAME
║  RabbitMQ (internal)         : rabbitmq:5672
║  Payment Service (public)    : https://$FQDN
║  API Management Gateway      : $APIM_URL
╠══════════════════════════════════════════════════════════════════════════════╣
║  QUICK TEST COMMANDS
║  ─────────────────────────────────────────────────────────────────────────
║  `$URL = 'https://$FQDN'
║  curl.exe `$URL/
║  curl.exe -X POST "`$URL/api/v1/payments/process" ``
║       -H "Content-Type: application/json" -d "@payment_payload.json"
║  curl.exe -X POST "`$URL/api/v1/wallets/transfer" ``
║       -H "Content-Type: application/json" -d "@transfer_payload.json"
╠══════════════════════════════════════════════════════════════════════════════╣
║  TO DELETE EVERYTHING WHEN DONE:
║    az group delete --name $RESOURCE_GROUP --yes --no-wait
╚══════════════════════════════════════════════════════════════════════════════╝
"@ -ForegroundColor Green

# Write the live app URL to RUNNN.txt so it stays up to date
$runnContent = @"
# ── Auto-generated by provision-azure.ps1 ────────────────────────────────────
`$URL = 'https://$FQDN'

# 💳 Test Payment API
curl.exe -X POST "`$URL/api/v1/payments/process" -H "Content-Type: application/json" -d "@payment_payload.json"

# 💰 Test Wallet Transfer API
curl.exe -X POST "`$URL/api/v1/wallets/transfer" -H "Content-Type: application/json" -d "@transfer_payload.json"

# 🏠 Health Check
curl.exe -X GET "`$URL/"

# 📋 Stream container logs
az containerapp logs show --name payment-service --resource-group $RESOURCE_GROUP --tail 50 | Select-String "\[Messaging\]|RECEIVED"

# ⚡ Load Test
k6 run load-test.js

# ─── DB Console ──────────────────────────────────────────────────────────────
# psql "host=$DB_SERVER_NAME.postgres.database.azure.com user=$DB_ADMIN_USER dbname=postgres sslmode=require"
# Password: $DB_ADMIN_PASS
# \c $DB_NAME
# SELECT * FROM wallet_transactions;

# ─── Redis Console ───────────────────────────────────────────────────────────
# Portal → $REDIS_NAME → Console → keys *

# ─── Delete ALL resources when done ─────────────────────────────────────────
# az group delete --name $RESOURCE_GROUP --yes --no-wait
"@
$runnContent | Set-Content -Path "RUNNN.txt" -Encoding UTF8
Write-Info "RUNNN.txt updated with the new live URL."
