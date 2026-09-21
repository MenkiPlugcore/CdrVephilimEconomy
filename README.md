# CdrVephilimEconomy

`CdrVephilimEconomy` adalah economy plugin khusus **Vephilim Roleplay** dengan pendekatan NPC-first. Citizens menjadi front-end perdagangan, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, controlled dynamic pricing, dan RP market events.

## Status

- **Current development:** `1.0.0-RC1` — Production Hardening
- **Frozen RP Market Events baseline:** `0.1.0-beta.5`
- **Frozen Controlled Dynamic Pricing baseline:** `0.1.0-beta.4`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Active branch: `dev/v1.0.0`

`1.0.0-RC1` bukan production FINAL. RC ini menutup gap persistence/runtime yang ditemukan saat final production audit awal.

## 1.0.0-RC1 Production Hardening

RC1 menambahkan:

- official Vephilim NPC catalog (`food`, `ore`, `farmer`, `fisherman`);
- `governance.yml.initialized` untuk mencegah assignment governance ter-reset diam-diam saat primary file hilang;
- deterministic recovery dari `governance.yml.bak` bila marker menunjukkan state pernah diinisialisasi;
- fail-closed bila governance primary hilang dan backup tidak valid/tersedia;
- post-commit governance audit semantics: mutation yang sudah committed tidak lagi dilaporkan sebagai gagal hanya karena SUCCESS audit write gagal;
- explicit `MarketRuntimeBootstrap` untuk single registration supply listener + expiry lifecycle.

Dokumentasi RC1: [`docs/V1_RC1.md`](docs/V1_RC1.md)  
Production regression plan: [`docs/V1_TEST_PLAN.md`](docs/V1_TEST_PLAN.md)

## Official Vephilim NPC Catalog

Default `shops.yml` membawa empat shop resmi:

```text
food       — player BUY makanan dari NPC
ore        — player SELL ore/mineral ke NPC
farmer     — player SELL hasil farm ke NPC
fisherman  — player SELL hasil nelayan ke NPC
```

Harga dan blacklist resmi: [`docs/VEPHILIM_NPC_CATALOG.md`](docs/VEPHILIM_NPC_CATALOG.md).

Semua default shop tetap:

```yaml
npc-id: -1
enabled: false
```

Existing server yang sudah memiliki `plugins/CdrVephilimEconomy/shops.yml` tidak ditimpa otomatis. Catalog harus di-merge ke config live atau dibuat melalui `/cve shop ...` agar binding/config existing aman.

## Core Economy

- Citizens NPC shop.
- BUY / SELL / BUY_SELL per listing.
- Persistent stock.
- Vault economy bridge.
- transaction journal + persistent safety lock.
- anti-double-click / per-player in-flight / listing lock.
- stale-GUI price protection.
- local + Discord transaction audit.
- `/cve doctor`, `/cve status`, `/cve safety`.

## Shop Management

- create/delete shop;
- bind/unbind Citizens NPC;
- enable/disable;
- listing CRUD;
- mode, slot, display name, GUI size;
- base price + runtime/config stock management;
- schema migration + admin mutation journal;
- candidate validation + backup + rollback;
- granular permissions.

## Governance

Role:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Governance memiliki per-shop scope, sensitive-change approval, anti-self-approval, rolling quota/cooldown, two-person approval untuk extreme mutation, dan durable approval/recovery evidence.

## Controlled Dynamic Pricing

Base price tetap berasal dari `shops.yml`.

```text
base price
 -> bounded stock-driven multiplier
 -> sampled market state
 -> RP market event multiplier
 -> effective quote
```

Dynamic layer memiliki min/max multiplier, quote cooldown, minimum stock delta, persistent `market-state.yml`, stale-price protection, anti BUY↔SELL churn, market statistics, dan governed pricing management.

## RP Market Events

Price events:

```text
SCARCITY
KINGDOM_BUY_BONUS
DISCOUNT
```

Supply shipment:

```text
/cve market supply create <id> <shop> <listing> <amount> [announcement]
```

Supply menggunakan PREPARED/APPLIED/COMPLETED durable evidence dan deterministic crash recovery. Natural expiry event memiliki durable `expiry-recorded-at` evidence dan at-most-once notification contract.

## Persistence Penting

```text
stock.yml
safety.lock
pending-transactions/
shops.yml
pricing.yml
market-state.yml
market-events.yml
market-supply.yml
governance.yml
governance.yml.initialized
governance-approvals.yml
governance-usage.yml
governance-dual-approval.yml
```

## Runtime

- Paper 1.21.11
- Java 21
- Citizens
- Vault + economy provider
- LuckPerms opsional
- Discord webhook opsional

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md)
- [`docs/V1_RC1.md`](docs/V1_RC1.md)
- [`docs/V1_TEST_PLAN.md`](docs/V1_TEST_PLAN.md)
- [`docs/VEPHILIM_NPC_CATALOG.md`](docs/VEPHILIM_NPC_CATALOG.md)
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md)
- [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md)
- [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md)
- [`docs/BETA4_FINAL.md`](docs/BETA4_FINAL.md)
- [`docs/BETA5_FINAL.md`](docs/BETA5_FINAL.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Production Exit

Sebelum `1.0.0` FINAL: core regression, concurrency/stress test, restart/crash recovery drill, persistence migration audit, install/config/permission docs, dan final security audit harus selesai tanpa blocker.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
