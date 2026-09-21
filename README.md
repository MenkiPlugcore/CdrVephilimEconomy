# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, controlled pricing, dan RP market events.

## Status

- **Current development:** `0.1.0-beta.5-RC1`
- **Frozen Controlled Dynamic Pricing baseline:** `0.1.0-beta.4`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.5`

beta.5 RC1 membuka fase **RP Market Events** dengan modifier harga temporer yang tetap melewati transaction safety dan stale-quote protection baseline sebelumnya.

## Core Economy

- Citizens NPC sebagai front-end transaksi.
- BUY / SELL / BUY_SELL per listing.
- Persistent real stock.
- Vault economy bridge.
- Transaction journal + persistent safety lock.
- Fail-closed recovery untuk stock/pending transaction.
- Local/Discord transaction audit.
- `/cve doctor`, `/cve safety status`, explicit recovery.

## Shop Management beta.2

CRUD shop/listing, Citizens binding, enable/disable, base-price/stock management, schema migration, candidate validation, backup, admin mutation journal, granular permission, dan local/Discord administrative audit.

## Economy Staff & Governance beta.3

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Governance memiliki per-shop scope, sensitive-change approval, anti-self-approval, rolling quota/cooldown, dan two-person approval untuk extreme mutation.

## beta.4 FINAL — Controlled Dynamic Pricing

Base price tetap berasal dari `shops.yml`. `pricing.yml` mengatur bounded sampled multiplier market per listing.

```text
stockRatio -> pressure -> sampled bounded multiplier -> effective quote
```

Fitur frozen beta.4:

- durable `market-state.yml`;
- quote cooldown + minimum stock delta;
- stale-GUI price guard;
- rapid opposite-direction BUY/SELL churn guard;
- market statistics dari `logs/audit.log`;
- governed `/cve pricing` management;
- Manager pricing guardrail;
- pricing backup/temp/runtime rollback.

Dokumentasi: [`docs/BETA4_FINAL.md`](docs/BETA4_FINAL.md).

## beta.5 RC1 — RP Market Events

Event RC1 dipasang **setelah** quote beta.4:

```text
base price
  -> beta.4 dynamic multiplier (jika aktif)
  -> beta.5 RP event multiplier
  -> effective quote
```

Dynamic pricing beta.4 boleh OFF; event tetap dapat memodifikasi static base price.

### Event Preset

```text
SCARCITY
BUY  x multiplier
SELL x multiplier
range 1.0..3.0

KINGDOM_BUY_BONUS
BUY  x1.0
SELL x multiplier
range 1.0..3.0

DISCOUNT
BUY  x multiplier
SELL x1.0
range 0.25..1.0
```

Multiple event boleh overlap. Combined event multiplier di-hard-clamp `0.25..4.0`.

### Scope

```text
blacksmith/iron_ingot
blacksmith/*
*/*
```

### Command

```text
/cve market status
/cve market list
/cve market show <id>

/cve market scarcity <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market buybonus <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market discount <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market end <id> [reason]
```

Durasi event `1..10080` menit.

### Persistence & RP Hook

```text
market-events.yml
market-events.yml.bak
market-events.yml.tmp
logs/market-events.log
```

Create/end event menggunakan admin audit, candidate validation, backup, atomic replace, safe runtime reload, dan rollback bila apply gagal. Event start/end mengirim broadcast `[Pasar Kerajaan]` sebagai hook announcement RP.

Expired event tetap disimpan sebagai evidence tetapi tidak lagi memodifikasi quote.

### Governance

Permission:

```text
cdrvephilimeconomy.market.view
cdrvephilimeconomy.market.manage
```

Rules RC1:

- Staff/Manager: read-only sesuai shop scope;
- Royal Treasurer: create/end sesuai scope;
- event global `*` membutuhkan Treasurer scope `*`;
- `market.view`: global read override;
- `market.manage`: operator mutation override;
- full admin mewarisi keduanya.

Market mutation membutuhkan local `admin-audit.log` writable; bila tidak, mutation fail-closed.

### Safety

RP event tidak langsung memutasi saldo, item, atau stock. Semua transaksi tetap melewati balance/inventory/stock validation, transaction journal, safety stop, dan stale-price check. Bila harga event berubah saat player memegang GUI lama, transaksi menjadi `PRICE_CHANGED` sebelum mutation.

## Persistence Penting

```text
stock.yml
safety.lock
pending-transactions/
shops.yml
pricing.yml
pricing.yml.admin.bak
market-state.yml
market-events.yml
market-events.yml.bak
governance.yml
governance-approvals.yml
governance-usage.yml
governance-dual-approval.yml
```

## Integrasi

- Paper 1.21.11 / Java 21
- Citizens
- Vault + economy provider
- LuckPerms opsional
- Discord webhook opsional

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md)
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md)
- [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md)
- [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md)
- [`docs/BETA4_FINAL.md`](docs/BETA4_FINAL.md)
- [`docs/BETA5_RC1.md`](docs/BETA5_RC1.md)
- [`docs/BETA5_RC1_TEST_PLAN.md`](docs/BETA5_RC1_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.5 Update

RC berikutnya menargetkan **governed supply event / stock shipment** dengan durable evidence dan recovery, lalu automatic expiry lifecycle/history hardening.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
