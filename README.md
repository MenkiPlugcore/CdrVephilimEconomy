# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, governance staff, dan controlled market pricing.

## Status

- **Current development:** `0.1.0-beta.4-RC1`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.4`

beta.4 RC1 membuka **Controlled Dynamic Pricing** dengan bounded stock-ratio quote engine. Dynamic pricing default **OFF**, sehingga upgrade dari beta.3 tidak otomatis mengubah harga server.

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

```text
/cve shop list
/cve shop info <shop>
/cve shop schema
/cve shop validate
/cve shop create <id> [size] [display name]
/cve shop delete <id> CONFIRM
/cve shop name <shop> <display name>
/cve shop size <shop> <size>
/cve shop bind <shop> <npc-id|-1>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop manager <shop> <name|none>
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
/cve shop slot <shop> <listing> <slot>
/cve shop price <shop> <listing> <buy|sell> <value>
/cve shop initialstock <shop> <listing> <value>
/cve shop maxstock <shop> <listing> <value>
/cve shop stock <shop> <listing> <set|add|remove> <amount>
```

Shop management memakai `shops.yml` schema v2, candidate validation, backup, admin mutation journal, local administrative audit, dan optional Discord administrative audit.

## Economy Staff & Governance beta.3

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Governance memiliki per-shop scope, sensitive-change approval, anti-self-approval, rolling quota/cooldown, dan two-person approval untuk extreme mutation. Frozen contract beta.3 didokumentasikan di [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md).

## beta.4 RC1 — Controlled Dynamic Pricing

Base price tetap berasal dari `shops.yml`. File baru `pricing.yml` menentukan policy market per listing:

```yaml
meta:
  schema: 1

enabled: false

shops:
  blacksmith:
    iron_ingot:
      enabled: false
      target-stock-ratio: 0.50
      sensitivity: 0.50
      min-multiplier: 0.75
      max-multiplier: 1.50
```

Master switch dan listing switch harus sama-sama `true` untuk mengaktifkan dynamic pricing.

Model RC1:

```text
stockRatio = currentStock / maxStock
pressure   = normalized distance stockRatio dari target, -1..1
multiplier = clamp(1 + sensitivity * pressure, minMultiplier, maxMultiplier)
effective  = round2(basePrice * multiplier)
```

Stock di bawah target menaikkan harga; stock di atas target menurunkan harga. Min/max multiplier menjadi price floor/ceiling relatif terhadap base price.

Contoh base BUY `50`, SELL `30`, target `50%`, sensitivity `0.50`, min `0.75`, max `1.50`:

```text
stock 0%   -> x1.50 -> BUY 75.00 / SELL 45.00
stock 50%  -> x1.00 -> BUY 50.00 / SELL 30.00
stock 100% -> x0.75 -> BUY 37.50 / SELL 22.50
```

BUY dan SELL memakai multiplier yang sama sehingga spread base tetap proporsional.

### Stale Quote Safety

GUI menyimpan quote yang benar-benar dilihat player. Transaction engine menghitung ulang quote di dalam listing lock. Bila harga telah berubah karena stock berubah, transaksi dikembalikan sebagai `PRICE_CHANGED` **sebelum uang, item, atau stock dimutasi**, lalu GUI direfresh.

Transaction journal dan audit menyimpan effective unit price yang benar-benar dieksekusi, bukan static base price.

### Runtime Safety

- `pricing.yml` invalid membatalkan startup/reload secara aman.
- `/cve reload` dengan candidate pricing invalid mempertahankan runtime lama.
- `/cve status` menampilkan status pricing.
- `/cve shop info <shop>` menampilkan base -> effective price untuk listing dinamis.
- Policy yang merujuk shop/listing tidak dikenal menghasilkan warning.
- Dengan `pricing.yml.enabled=false`, behavior harga sama seperti beta.3.

## Governance Persistence

```text
governance.yml
governance-approvals.yml
governance-usage.yml
governance-dual-approval.yml
logs/governance-dual-approval-history.log
```

Masing-masing durable governance file memiliki backup/temp persistence sesuai kontrak beta.3.

## Permissions

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.view
cdrvephilimeconomy.shop.create
cdrvephilimeconomy.shop.delete
cdrvephilimeconomy.shop.bind
cdrvephilimeconomy.shop.toggle
cdrvephilimeconomy.shop.edit
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.shop.price
cdrvephilimeconomy.shop.stock
cdrvephilimeconomy.shop.manager
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.approve
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` mewarisi seluruh permission plugin.

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
- [`docs/BETA4_TEST_PLAN.md`](docs/BETA4_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.4 Update

Setelah RC1 QA aman, update berikutnya fokus ke **market quote cooldown/sampling + durable market state + anti-churn**, supaya player tidak dapat sengaja menggerakkan harga terlalu agresif melalui transaksi bolak-balik.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
