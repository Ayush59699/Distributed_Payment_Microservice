
#Requires -Version 5.1
<#
.SYNOPSIS
    Safely deletes all Payment Microservice Azure resources.

.DESCRIPTION
    Deletes the entire resource group (and every resource inside it).
    Prompts for confirmation before doing anything destructive.
    Use  -Force  to skip the prompt (e.g. in CI pipelines).

.USAGE
    .\teardown-azure.ps1
    .\teardown-azure.ps1 -Force
#>

param(
    [switch]$Force,
    [string]$ResourceGroup = "payment-microservice-rg"
)

$ErrorActionPreference = "Stop"

Write-Host @"

╔══════════════════════════════════════════════════════════════╗
║        ⚠️  Azure Teardown — Payment Microservice            ║
║        This will DELETE the following resource group:        ║
║          $ResourceGroup
║        And EVERYTHING inside it.                             ║
╚══════════════════════════════════════════════════════════════╝
"@ -ForegroundColor Red

# List what will be deleted
Write-Host "`nResources that will be removed:" -ForegroundColor Yellow
az resource list --resource-group $ResourceGroup --query "[].{Name:name, Type:type}" --output table 2>$null

if (-not $Force) {
    $confirm = Read-Host "`nType  DELETE  to confirm permanent deletion"
    if ($confirm -ne "DELETE") {
        Write-Host "`n  Aborted. Nothing was deleted." -ForegroundColor Green
        exit 0
    }
}

Write-Host "`n  🗑️  Deleting resource group '$ResourceGroup' (running in background)..." -ForegroundColor Cyan
az group delete --name $ResourceGroup --yes --no-wait

Write-Host @"

  ✅ Deletion command submitted.
  The resource group is being torn down in the background — this typically
  takes 5-15 minutes. You can monitor progress in the Azure Portal.

  To re-provision everything:
    .\provision-azure.ps1
"@ -ForegroundColor Green
