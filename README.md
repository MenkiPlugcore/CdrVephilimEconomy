# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, dan controlled market pricing.

## Status

- **Current development:** `0.1.0-beta.4-RC2`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.4`

beta.4 RC2 menambahkan durable sampled market state, quote cooldown, minimum stock delta sebelum resample, dan anti BUY/SELL churn di atas bounded dynamic-pricing RC1.

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

Shop Management menyediakan CRUD shop/listing, Citizens binding, enable/disable, price/stock management, schema migration, candidate validation, backup, admin mutation journal, granular permission, dan local/Discord administrative audit.

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

Contoh:

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

### RC2 Market Sampling

Harga tidak lagi bergerak setiap perubahan stock. Multiplier baru hanya dipersist bila:

1. belum ada sample market;
2. policy fingerprint berubah; atau
3. quote cooldown sudah lewat **dan** stock bergerak minimal sebesar `min-stock-change-to-resample` dari sample terakhir.

State disimpan di:

```text
market-state.yml
market-state.yml.bak
market-state.yml.tmp
```

Karena sample durable, restart tidak mereset quote cooldown atau multiplier market.

### Policy Fingerprint

Sample diikat ke target ratio, sensitivity, min/max multiplier, dan `max-stock`. Perubahan parameter tersebut membuat sample lama invalid dan memaksa resample aman pada quote berikutnya.

### Anti BUY/SELL Churn

Untuk listing dynamic, player yang baru BUY tidak dapat langsung SELL listing yang sama, dan sebaliknya, selama `reversal-cooldown-seconds`. Same-direction BUY→BUY atau SELL→SELL tidak diblokir sebagai demand/supply normal.

### Dynamic Fail-Closed

Jika `market-state.yml` corrupt atau tidak dapat dipersist, dynamic layer masuk `BLOCKED` dan quote kembali ke static base price. Core BUY/SELL tidak ikut dimatikan hanya karena state market bermasalah, dan corrupt evidence tidak ditimpa diam-diam.

### Stale Quote Safety

GUI menyimpan quote yang benar-benar dilihat player. Transaction engine menghitung ulang quote sebelum mutation. Bila sampled quote berubah sebelum klik, transaksi menjadi `PRICE_CHANGED` sebelum uang, item, atau stock berubah, lalu GUI direfresh.

Transaction journal dan audit tetap menyimpan effective unit price yang benar-benar dieksekusi.

## Persistence Penting

```text
stock.yml
safety.lock
pending-transactions/
shops.yml
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
- [`docs/BETA4_TEST_PLAN.md`](docs/BETA4_TEST_PLAN.md)
- [`docs/BETA4_RC2_TEST_PLAN.md`](docs/BETA4_RC2_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.4 Update

Sesudah RC2 QA, fokus berikutnya: **market statistics + volume history + governance untuk perubahan parameter pricing** sebelum final regression/hardening beta.4.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
