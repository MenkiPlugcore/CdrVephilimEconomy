# CdrVephilimEconomy 0.1.0-beta.5 FINAL

`0.1.0-beta.5` membekukan fase **RP Market Events** di atas baseline Core Economy, Shop Management, Governance, dan Controlled Dynamic Pricing.

## Final Scope

beta.5 FINAL mencakup tiga lapisan utama:

1. **Temporary RP price events**
   - `SCARCITY`
   - `KINGDOM_BUY_BONUS`
   - `DISCOUNT`
   - scope per shop/listing atau wildcard untuk price event
   - duration-based start/end
   - combined event multiplier hard-clamp `0.25x..4.0x`
   - stale-GUI price protection tetap berlaku

2. **Governed one-shot supply shipment**
   - menambah stock satu listing konkret
   - mutation melalui `ShopAdminService`, bukan direct write ke `stock.yml`
   - PREPARED/APPLIED/COMPLETED durable evidence
   - deterministic crash recovery dari before/after stock snapshot
   - explicit recovery untuk kondisi ambiguous
   - tidak ada wildcard dan tidak ada silent max-stock clamp

3. **Automatic natural-expiry lifecycle**
   - efek harga berhenti tepat di `ends-at`
   - lifecycle recorder berjalan periodik untuk durable expiry evidence
   - `expiry-recorded-at` / `expiry-recorded-by`
   - admin audit + `logs/market-events.log`
   - RP expiry broadcast memakai at-most-once contract

## Final Runtime Hardening

Final regression menemukan gap bootstrap setelah RC3 listener deduplication: class supply/lifecycle sudah ada tetapi tidak lagi memiliki owner runtime yang eksplisit. FINAL memperbaikinya dengan `MarketRuntimeBootstrap`.

Bootstrap dijalankan setelah `AdminAuditService` tersedia dan menjamin per plugin instance:

```text
1x GovernanceQuotaCommandListener
1x MarketSupplyCommandListener
1x MarketEventLifecycleService
```

`MarketRuntimeBootstrap` memakai guard per plugin instance sehingga bootstrap kedua tidak mendaftarkan listener/task ganda.

Ini menjaga dua invariant penting:

- direct governance price/stock mutation tidak double-reserve quota/cooldown;
- `/cve market supply ...` dan automatic expiry lifecycle benar-benar aktif setelah startup tanpa hidden constructor registration.

## Price Event Commands

```text
/cve market status
/cve market list
/cve market show <id>
/cve market scarcity <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market buybonus <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market discount <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market end <id> [reason]
```

## Supply Commands

```text
/cve market supply status
/cve market supply list
/cve market supply show <id>
/cve market supply create <id> <shop> <listing> <amount> [announcement]
/cve market supply recover <id> <applied|not-applied> CONFIRM
```

## Governance

Permissions:

```text
cdrvephilimeconomy.market.view
cdrvephilimeconomy.market.manage
```

Rules:

- `ECONOMY_STAFF`: read-only market/supply evidence sesuai scope.
- `ECONOMY_MANAGER`: read-only market/supply evidence sesuai scope.
- `ROYAL_TREASURER`: create/end price event dan create supply sesuai shop scope.
- `market.view`: global read override.
- `market.manage`: operator mutation + supply recovery override.
- full admin mewarisi keduanya.
- ambiguous supply recovery tetap admin/operator only.

## Persistence Contract

Price event:

```text
market-events.yml
market-events.yml.bak
market-events.yml.tmp
logs/market-events.log
```

Supply event:

```text
market-supply.yml
market-supply.yml.bak
market-supply.yml.tmp
logs/market-supply.log
```

Existing economy safety evidence tetap berlaku:

```text
stock.yml
safety.lock
pending-transactions/
market-state.yml
governance.yml
governance-approvals.yml
governance-usage.yml
governance-dual-approval.yml
```

## Supply Crash Contract

Supply ID bersifat one-shot. Flow normal:

```text
REQUEST audit
 -> PREPARED
 -> ShopAdminService ADD
 -> durable stock verification
 -> APPLIED
 -> SUCCESS audit
 -> COMPLETED
 -> history + broadcast
```

Recovery setelah crash:

```text
current stock == before -> RECOVERED_NOT_APPLIED
current stock == after  -> RECOVERED_APPLIED
otherwise               -> BLOCKED / manual reconciliation
```

Tidak ada automatic replay pada state ambiguous.

## Expiry Contract

Economic validity tidak bergantung pada scheduler. `MarketEvent.activeAt()` langsung berhenti menganggap event aktif setelah `ends-at`.

Lifecycle scheduler hanya merekam evidence dan side effect:

```text
ends-at crossed
 -> persist expiry evidence
 -> admin audit / history best effort
 -> RP broadcast
```

Evidence dipersist sebelum side effect agar restart tidak menghasilkan duplicate expiry announcement. Konsekuensinya, crash tepat setelah evidence commit dapat membuat notification tidak terkirim; ini diterima sebagai at-most-once notification contract.

## Final Regression Checklist

Sebelum promosi ke `main`, verifikasi minimum:

- BUY/SELL satuan dan bulk tetap normal.
- price event memodifikasi effective quote dan stale GUI ditolak dengan aman.
- event berhenti tepat setelah `ends-at`.
- expiry evidence dicatat satu kali dan tidak broadcast ulang setelah restart.
- supply +amount menghasilkan exact expected stock.
- supply ID yang sama tidak dapat digunakan ulang.
- shipment di atas `max-stock` ditolak tanpa perubahan stock.
- restart tidak mengulang shipment yang sudah applied/completed.
- unresolved supply hanya recovery berdasarkan asserted before/after stock.
- role/scope market governance tetap berlaku.
- direct governance quota tidak tercatat dua kali.
- startup log menunjukkan satu market runtime bootstrap.

## Deferred / Non-Blocker

Optional event templates/presets dari config **deferred**. Tiga preset command yang ada sudah cukup untuk baseline beta.5 dan template system tidak diperlukan untuk correctness atau security.

## Frozen Baseline

Setelah exact-head CI dan runtime regression lulus, branch `dev/beta.5` diperlakukan sebagai frozen beta.5 baseline dan dipromosikan ke `main` sebelum pekerjaan production `v1.0.0` dimulai.
