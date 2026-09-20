# CdrVephilimEconomy 0.1.0-beta.4 FINAL

`0.1.0-beta.4` membekukan **Controlled Dynamic Pricing** sebagai baseline resmi di atas Core Economy beta.1, Shop Management beta.2, dan Economy Staff & Governance beta.3.

## Release Contract

beta.4 FINAL mempertahankan prinsip berikut:

- base BUY/SELL price tetap dimiliki `shops.yml`;
- `pricing.yml` hanya mengatur multiplier pasar;
- dynamic pricing default **OFF** pada instalasi/upgrade baru;
- harga efektif selalu bounded oleh floor/ceiling policy;
- market quote tidak berubah pada setiap klik/transaksi;
- sampled market state persisten lintas restart;
- stale GUI quote tidak pernah dieksekusi diam-diam;
- rapid opposite-direction BUY/SELL churn dibatasi;
- pricing mutation tunduk pada governance + mandatory admin audit;
- statistik market diturunkan dari authoritative `logs/audit.log`, bukan ledger transaksi kedua;
- kerusakan dynamic layer tidak boleh mematikan core BUY/SELL atau menghasilkan harga liar.

## Dynamic Pricing Model

Per listing dynamic:

```text
stockRatio = sampledStock / maxStock
pressure   = normalized distance dari target-stock-ratio, -1..1
multiplier = clamp(1 + sensitivity * pressure, minMultiplier, maxMultiplier)
effective  = round2(basePrice * multiplier)
```

Stock di bawah target memberi pressure positif dan dapat menaikkan harga. Stock di atas target memberi pressure negatif dan dapat menurunkan harga. BUY dan SELL memakai multiplier market yang sama sehingga spread dasar dari `shops.yml` tetap proporsional.

Policy file:

```text
pricing.yml
```

Schema final beta.4:

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

Absolute parser bounds:

- `target-stock-ratio`: `0.05..0.95`;
- `sensitivity`: `0..5`;
- `min-multiplier`: `>0..10`;
- `max-multiplier`: `>0..10`, wajib `>= min-multiplier`;
- quote cooldown: `1..3600` detik;
- minimum stock delta: `1..2304` item;
- reversal cooldown: `0..600` detik.

## Durable Market Sampling

Market multiplier disimpan ke:

```text
market-state.yml
market-state.yml.bak
market-state.yml.tmp
```

State per listing menyimpan sampled stock, sampled multiplier, sampled timestamp, policy fingerprint, dan sample count.

Resample terjadi hanya bila:

1. belum ada sample;
2. fingerprint policy berubah; atau
3. quote cooldown selesai **dan** stock bergerak minimal sebesar configured stock delta.

Policy fingerprint mengikat parameter pricing serta `max-stock`, sehingga perubahan konfigurasi penting tidak memakai sample lama secara tidak sengaja.

Restart server tidak mereset sampled multiplier atau cooldown evidence.

## Stale Quote Safety

GUI menyimpan effective BUY/SELL price yang benar-benar dilihat player. Transaction engine menghitung quote lagi di dalam transaction/listing lock sebelum mutation.

Jika quote berbeda:

```text
PRICE_CHANGED
```

Transaksi ditolak sebelum withdraw/deposit, item mutation, atau stock mutation. GUI kemudian direfresh ke quote terbaru.

Transaction journal dan audit menyimpan effective unit price yang benar-benar dieksekusi.

## Anti-Churn

Untuk listing dynamic, player yang baru melakukan BUY tidak dapat langsung melakukan SELL listing yang sama, dan sebaliknya, selama `reversal-cooldown-seconds`.

Guard hanya menahan opposite-direction reversal:

```text
BUY -> BUY   allowed
SELL -> SELL allowed
BUY -> SELL  guarded
SELL -> BUY  guarded
```

Same-direction demand/supply tetap dapat berlangsung normal.

## Dynamic Layer Failure Policy

Jika `market-state.yml` invalid/corrupt atau state tidak dapat dipersist:

- dynamic layer masuk `BLOCKED`;
- quote kembali ke static base price dari `shops.yml`;
- core transaction engine tetap tersedia selama safety subsystem utama sehat;
- corrupt state tidak ditimpa diam-diam dengan state baru;
- admin wajib memperbaiki state/config lalu melakukan reload/restart yang sesuai.

Ini berbeda dari transaction safety stop beta.1: kegagalan market-state tidak dianggap bukti bahwa uang/item/stock core telah divergen.

## Market Statistics

Command:

```text
/cve pricing stats [shop] [listing] [hours]
```

Window valid `1..720` jam, default 24 jam.

Statistik dibaca dari:

```text
logs/audit.log
```

Hanya baris transaksi `status=SUCCESS` yang dihitung. Output mencakup:

- BUY/SELL transaction count;
- BUY/SELL unit volume;
- BUY/SELL value;
- average effective unit price;
- effective price range;
- net stock flow (`SELL units - BUY units`);
- turnover;
- first/last transaction timestamp pada window.

Untuk membatasi biaya scan, implementation membaca maksimum 100.000 baris audit terbaru dan memberikan warning bila window statistik berpotensi terpotong.

## Governed Pricing Management

Commands:

```text
/cve pricing status
/cve pricing show <shop> <listing>
/cve pricing stats [shop] [listing] [hours]
/cve pricing set <shop> <listing> <enabled|target|sensitivity|min|max> <value>
/cve pricing global <on|off>
/cve pricing stability <quote-cooldown|min-stock-change|reversal-cooldown> <value>
```

Pricing mutation flow:

```text
admin audit REQUEST
  -> strict load pricing.yml
  -> candidate mutation
  -> full validation
  -> pricing.yml.admin.tmp
  -> temp verification
  -> pricing.yml.admin.bak
  -> atomic replace when supported
  -> safe runtime reload
  -> rollback original file/runtime if apply fails
  -> admin audit SUCCESS/REJECTED
```

Local `logs/admin-audit.log` merupakan evidence utama dan wajib writable. Discord admin audit tetap optional/asynchronous.

## Governance Contract

Permissions:

```text
cdrvephilimeconomy.pricing.view
cdrvephilimeconomy.pricing.manage
```

`cdrvephilimeconomy.admin` mewarisi keduanya.

Role-only behavior:

- `ECONOMY_STAFF`: view policy/statistics pada assigned shop scope;
- `ECONOMY_MANAGER`: bounded direct policy mutation pada assigned shop scope;
- `ROYAL_TREASURER`: unrestricted listing policy mutation pada scope; global/stability membutuhkan scope `*`;
- explicit `cdrvephilimeconomy.pricing.manage`: operator override.

Manager direct-edit guardrail:

```text
target-stock-ratio : 0.20..0.80, delta <= 0.10 per operation
sensitivity        : 0.00..1.50, delta <= 0.25 per operation
min-multiplier     : 0.50..1.00, delta <= 0.25 per operation
max-multiplier     : 1.00..2.00, delta <= 0.25 per operation
enabled            : toggle listing within scope
```

Perubahan di luar guardrail dieskalasikan ke Royal Treasurer/admin/operator override. Dedicated pricing-parameter approval queue **deferred** dan bukan blocker beta.4 FINAL; sistem beta.3 approval untuk base price/stock tetap tidak berubah.

## Persistence Contract

File/evidence penting sampai beta.4 FINAL:

```text
stock.yml
stock.yml.bak
stock.yml.initialized
safety.lock
pending-transactions/
shops.yml
shops.yml.admin.bak
pricing.yml
pricing.yml.admin.bak
market-state.yml
market-state.yml.bak
governance.yml
governance-approvals.yml
governance-usage.yml
governance-dual-approval.yml
logs/audit.log
logs/admin-audit.log
```

Temporary files (`*.tmp`) adalah evidence write yang belum selesai dan tidak boleh dianggap data authoritative tanpa recovery logic masing-masing subsystem.

## Upgrade / Operational Checks

Setelah mengganti JAR dari RC3 ke FINAL, gunakan restart plugin/server normal untuk memuat Java classes baru. `/cve reload` hanya untuk runtime configuration, bukan mengganti JAR.

Checks minimum:

```text
/cve status
/cve doctor
/cve pricing status
/cve pricing show <shop> <listing>
/cve pricing stats <shop> <listing> 24
```

Regression minimum:

- static pricing OFF tetap identik dengan beta.3;
- dynamic BUY 1 / BUY bulk;
- dynamic SELL 1 / SELL bulk;
- stale quote rejection;
- quote sampling/cooldown;
- restart continuity market state;
- opposite-direction churn guard;
- governed Manager/Treasurer mutation;
- invalid candidate pricing rollback;
- transaction safety/journal beta.1 tetap sehat;
- shop management beta.2 dan governance beta.3 tetap sehat.

## Frozen Baseline

Setelah release ini dipromosikan ke `main`, `0.1.0-beta.4` menjadi frozen **Controlled Dynamic Pricing baseline**. Fitur market-event sementara, scarcity event, kerajaan buy bonus, dan RP economic event history masuk fase **beta.5 — RP Market Events** dan tidak boleh ditambahkan kembali ke branch beta.4.
