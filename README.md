# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, controlled pricing, dan RP market events.

## Status

- **Current development:** `0.1.0-beta.5-RC3`
- **Frozen Controlled Dynamic Pricing baseline:** `0.1.0-beta.4`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.5`

beta.5 RC3 menambahkan automatic natural-expiry lifecycle, durable expiry evidence/history hardening, dan listener deduplication setelah RC2 supply shipment.

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

## beta.5 — RP Market Events

### RC1 Price Events

Event price dipasang **setelah** quote beta.4:

```text
base price
  -> beta.4 dynamic multiplier (jika aktif)
  -> beta.5 RP event multiplier
  -> effective quote
```

Dynamic pricing beta.4 boleh OFF; event tetap dapat memodifikasi static base price.

Preset:

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

Scope price event:

```text
blacksmith/iron_ingot
blacksmith/*
*/*
```

Command price event:

```text
/cve market status
/cve market list
/cve market show <id>
/cve market scarcity <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market buybonus <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market discount <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market end <id> [reason]
```

Persistence:

```text
market-events.yml
market-events.yml.bak
market-events.yml.tmp
logs/market-events.log
```

### RC2 Governed Supply Event

RC2 dapat menambah persistent stock satu listing melalui jalur stock admin yang sudah ada.

```text
/cve market supply status
/cve market supply list
/cve market supply show <id>
/cve market supply create <id> <shop> <listing> <amount> [announcement]
/cve market supply recover <id> <applied|not-applied> CONFIRM
```

Contoh:

```text
/cve market supply create royal_wheat_01 farmer wheat 500 Kiriman gandum kerajaan telah tiba!
```

Supply harus menargetkan satu shop/listing konkret; wildcard tidak diizinkan.

Flow mutation:

```text
validate
 -> admin audit REQUEST
 -> PREPARED durable evidence
 -> ShopAdminService stock ADD
 -> verify durable stock snapshot
 -> APPLIED evidence
 -> admin audit SUCCESS
 -> COMPLETED evidence
 -> RP broadcast
```

Durable files:

```text
market-supply.yml
market-supply.yml.bak
market-supply.yml.tmp
logs/market-supply.log
```

Crash recovery membandingkan current durable stock dengan evidence:

```text
current == before -> RECOVERED_NOT_APPLIED
current == after  -> RECOVERED_APPLIED
lainnya           -> BLOCKED / manual recovery
```

Supply tidak melakukan silent clamp. Bila shipment melebihi `max-stock`, seluruh request ditolak.

### RC3 Automatic Expiry Lifecycle

Price event sekarang memiliki lifecycle recorder otomatis. Effect harga tetap berhenti persis saat `ends-at`; scheduler hanya merekam bahwa natural expiry sudah diproses.

Durable metadata baru di `market-events.yml`:

```text
expiry-recorded-at: <timestamp>
expiry-recorded-by: SYSTEM
```

Setelah evidence tersimpan, plugin mencoba menulis `MARKET_EVENT_EXPIRED` ke admin audit dan `logs/market-events.log`, lalu broadcast automatic expiry.

Urutan sengaja:

```text
persist expiry evidence
 -> audit/history
 -> broadcast
```

sehingga restart/crash tidak menyebabkan duplicate expiry announcement. Broadcast memakai contract at-most-once; crash tepat setelah evidence commit dapat membuat chat notification terlewat, tetapi economic state dan durable lifecycle evidence tetap benar.

RC3 juga merapikan runtime listener menjadi satu quota command listener dan satu supply command listener per plugin enable. Ini menghilangkan risiko double quota reservation/cooldown dari duplicate governance listener.

### Governance

Permission:

```text
cdrvephilimeconomy.market.view
cdrvephilimeconomy.market.manage
```

Behavior:

- Staff/Manager: read-only market/supply evidence sesuai shop scope;
- Royal Treasurer: create/end price event serta create supply sesuai scope;
- `market.view`: global read override;
- `market.manage`: operator mutation + supply recovery override;
- full admin mewarisi keduanya.

Manual ambiguous supply recovery hanya untuk admin/operator `market.manage`.

### Safety

Price event tidak langsung memutasi saldo, item, atau stock. Supply event memang memutasi stock, tetapi hanya melalui `ShopAdminService`, setelah PREPARED recovery evidence dipersist. Economy safety stop, max-stock, stock repository persistence, dan admin audit tetap berlaku.

Natural expiry tidak melakukan `reloadRuntime()` dan tidak menentukan quote validity; quote event sudah otomatis invalid setelah `ends-at`.

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
market-supply.yml
market-supply.yml.bak
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
- [`docs/BETA5_RC2.md`](docs/BETA5_RC2.md)
- [`docs/BETA5_RC2_TEST_PLAN.md`](docs/BETA5_RC2_TEST_PLAN.md)
- [`docs/BETA5_RC3.md`](docs/BETA5_RC3.md)
- [`docs/BETA5_RC3_TEST_PLAN.md`](docs/BETA5_RC3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.5 Update

Setelah RC3 runtime QA, tinggal keputusan apakah optional event template/preset memang perlu. Jika tidak ada kebutuhan nyata, langsung final regression/security hardening menuju `0.1.0-beta.5 FINAL`.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
