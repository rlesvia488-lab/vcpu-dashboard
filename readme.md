# Cloud VCPU Dashboard

A dependency-free Java 17 dashboard that reads technical accounts and Paris/North OCS endpoints from Vault, requests a CMAAS OAuth token for each account, calls each configured server `/details` URL, and totals VCPU capacity by technical account.

The OCS parser reads `servers[].flavor.original_name` (for example, `XLarge 8vCPU-16GB`) and extracts the VCPU count. It falls back to `servers[].flavor.vcpus` when a custom flavor name does not contain a VCPU number.

## Build and test

```powershell
.\build.ps1
java -jar .\dist\vcpu-dashboard.jar --self-test
```

## Run locally

```powershell
$env:VAULT_URI="https://vault.example.com"
$env:VAULT_ROLE_ID="..."
$env:VAULT_SECRET_ID="..."
$env:VAULT_NAMESPACE="myVault/default"
$env:VAULT_TECH_ACCOUNTS_PATH="your/context/default"
java -jar .\dist\vcpu-dashboard.jar
```

Open `http://localhost:8080`. To preview without Vault, set `DEMO_MODE=true`.

For Linux deployment, copy `dist/vcpu-dashboard.jar` and `start.sh` together, make `start.sh` executable, and provide the Vault environment variables. Credentials and OAuth tokens are held only in memory and are never returned to the browser.

The Vault JSON structure matches the existing DNF application. Each `ocs_servers_url` must be the complete OCS endpoint that returns the `{"servers": [...]}` details payload; the application calls the stored URL exactly as provided.

## Endpoints

- `GET /` — dashboard
- `GET /api/dashboard` — cached aggregated inventory
- `POST /api/dashboard` — start a fresh Vault/OCS refresh
- `GET /api/health` — service health

The UI polls the cached dashboard every 10 seconds. External APIs are called only at startup or when **Refresh data** is selected.
