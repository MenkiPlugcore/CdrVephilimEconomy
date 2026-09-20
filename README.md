# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, dan controlled market pricing.

## Status

- **Current development:** `0.1.0-beta.4-RC3`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.4`

beta.4 RC3 menambahkan market statistics berbasis transaction audit dan governance khusus untuk perubahan parameter dynamic pricing di atas RC1 bounded pricing + RC2 durable market sampling.

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

Shop Management menyediakan CRUD shop/listing, Citizens binding, enable/disable, base-price/stock management, schema migration, candidate validation, backup, admin mutation journal, granular permission, dan local/Discord administrative audit.

## Economy Staff & Governance beta.3

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Governance memiliki per-shop scope, sensitive-change approval, anti-self-approval, rolling quota/cooldown, dan two-person approval untuk extreme mutation. Frozen contract beta.3 didokumentasikan di [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md).

## beta.4 — Controlled Dynamic Pricing

Base price tetap berasal dari `shops.yml`. `pricing.yml` hanya mengatur multiplier market per listing.

```yaml
meta:
  schema: 1

enabled: false

stability:
  quote-cooldown-seconds: 30
  min-stock-change-to-resample: 8
  reversal-cooldown-seconds: 15

shops:
  blacksmith:
    iron_ingot:
      enabled: false
      target-stock-ratio: 0.50
      sensitivity: 0.50
      min-multiplier: 0.75
      max-multiplier: 1.50
```

Dynamic pricing default **OFF**, sehingga upgrade tidak otomatis mengubah harga server.

Model dasarnya:

```text
stockRatio = sampledStock / maxStock
pressure   = normalized distance stockRatio dari target, -1..1
multiplier = clamp(1 + sensitivity * pressure, minMultiplier, maxMultiplier)
effective  = round2(basePrice * multiplier)
```

Stock langka menaikkan harga; stock berlebih menurunkan harga. BUY dan SELL memakai multiplier yang sama sehingga spread base price tetap proporsional.

### Durable Market Sampling RC2

Harga tidak bergerak setiap perubahan stock. Multiplier baru hanya dipersist bila belum ada sample, policy fingerprint berubah, atau quote cooldown sudah lewat **dan** stock bergerak minimal sebesar `min-stock-change-to-resample` dari sample terakhir.

State disimpan di:

```text
market-state.yml
market-state.yml.bak
market-state.yml.tmp
```

Restart tidak mereset sampled multiplier/cooldown. Jika market state rusak atau tidak writable, dynamic layer masuk `BLOCKED` dan quote kembali ke static base price tanpa mematikan core BUY/SELL.

### Anti BUY/SELL Churn

Untuk listing dynamic, player yang baru BUY tidak dapat langsung SELL listing yang sama, dan sebaliknya, selama `reversal-cooldown-seconds`. Same-direction BUY→BUY atau SELL→SELL tetap dianggap demand/supply normal.

### Stale Quote Safety

GUI menyimpan quote yang dilihat player. Transaction engine menghitung ulang quote sebelum mutation. Bila sampled quote berubah, transaksi menjadi `PRICE_CHANGED` sebelum uang, item, atau stock berubah, lalu GUI direfresh.

### RC3 Market Statistics

```text
/cve pricing stats [shop] [listing] [hours]
```

Statistik dibaca dari `logs/audit.log`, bukan ledger transaksi kedua. Hanya `status=SUCCESS` yang dihitung. Output mencakup BUY/SELL transaction count, unit volume, value, average effective unit price, price range, net stock flow, turnover, serta first/last transaction pada window. Default 24 jam, maksimum 720 jam.

### RC3 Governed Pricing Management

```text
/cve pricing status
/cve pricing show <shop> <listing>
/cve pricing stats [shop] [listing] [hours]
/cve pricing set <shop> <listing> <enabled|target|sensitivity|min|max> <value>
/cve pricing global <on|off>
/cve pricing stability <quote-cooldown|min-stock-change|reversal-cooldown> <value>
```

Mutation `pricing.yml` menggunakan candidate validation, `pricing.yml.admin.tmp`, `pricing.yml.admin.bak`, safe runtime reload, rollback bila apply gagal, serta mandatory local admin audit.

Governance behavior:

- `ECONOMY_STAFF`: view policy/statistics pada shop scope; tidak dapat mutation pricing.
- `ECONOMY_MANAGER`: dapat mutation policy listing dalam scope dengan guardrail ketat.
- `ROYAL_TREASURER`: dapat mutation policy listing dalam scope; global/stability memerlukan scope `*`.
- `cdrvephilimeconomy.pricing.manage`: explicit operator override.

Manager guardrail per operasi:

```text
target-stock-ratio : 0.20..0.80, delta <= 0.10
sensitivity        : 0.00..1.50, delta <= 0.25
min-multiplier     : 0.50..1.00, delta <= 0.25
max-multiplier     : 1.00..2.00, delta <= 0.25
enabled            : toggle listing dalam scope
```

Permission baru:

```text
cdrvephilimeconomy.pricing.view
cdrvephilimeconomy.pricing.manage
```

`cdrvephilimeconomy.admin` mewarisi keduanya.

## Persistence Penting

```text
stock.yml
safety.lock
pending-transactions/
shops.yml
pricing.yml
pricing.yml.admin.bak
market-state.yml
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
- [`docs/BETA4_RC1.md`](docs/BETA4_RC1.md)
- [`docs/BETA4_RC2.md`](docs/BETA4_RC2.md)
- [`docs/BETA4_RC3.md`](docs/BETA4_RC3.md)
- [`docs/BETA4_TEST_PLAN.md`](docs/BETA4_TEST_PLAN.md)
- [`docs/BETA4_RC2_TEST_PLAN.md`](docs/BETA4_RC2_TEST_PLAN.md)
- [`docs/BETA4_RC3_TEST_PLAN.md`](docs/BETA4_RC3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.4 Update

Sesudah RC3 QA, fokus berikutnya adalah **final beta.4 regression/security hardening**. Dedicated approval queue khusus parameter pricing dapat ditambahkan hanya bila runtime QA menunjukkan kebutuhan; RC3 saat ini memakai role/scope + bounded Manager guardrail dan Treasurer/admin escalation.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
