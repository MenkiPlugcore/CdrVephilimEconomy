# CdrVephilimEconomy 0.1.0-beta.4-RC3

RC3 menambahkan **market statistics + governed dynamic-pricing management** di atas RC1 bounded pricing dan RC2 durable market sampling.

## Market Statistics

RC3 tidak membuat ledger transaksi kedua. Statistik dibaca dari source of truth yang sudah ada:

```text
plugins/CdrVephilimEconomy/logs/audit.log
```

Hanya transaksi `status=SUCCESS` yang dihitung. Command:

```text
/cve pricing stats [shop] [listing] [hours]
```

Default window adalah 24 jam; range 1-720 jam. Output mencakup:

- jumlah BUY/SELL transaction;
- BUY/SELL unit volume;
- BUY/SELL value;
- average effective unit price;
- effective price range;
- net stock flow (`SELL units - BUY units`);
- market turnover;
- timestamp transaksi pertama/terakhir pada window.

Untuk membatasi biaya baca pada audit besar, RC3 hanya membaca maksimum 100.000 baris terbaru dan memberi warning bila scan terpotong.

## Governed Pricing Commands

```text
/cve pricing status
/cve pricing show <shop> <listing>
/cve pricing stats [shop] [listing] [hours]
/cve pricing set <shop> <listing> <enabled|target|sensitivity|min|max> <value>
/cve pricing global <on|off>
/cve pricing stability <quote-cooldown|min-stock-change|reversal-cooldown> <value>
```

Mutation `pricing.yml` tidak menulis file langsung secara buta. Flow:

```text
admin audit REQUEST
  -> strict load
  -> in-memory candidate mutation
  -> full candidate validation
  -> pricing.yml.admin.tmp
  -> verify temp
  -> pricing.yml.admin.bak
  -> atomic replace
  -> safe runtime reload
  -> rollback original file + old runtime bila apply gagal
  -> admin audit SUCCESS/REJECTED
```

Local `logs/admin-audit.log` wajib writable. Jika audit utama tidak dapat ditulis, pricing mutation ditolak fail-closed.

## Governance Rules

Permission baru:

```text
cdrvephilimeconomy.pricing.view
cdrvephilimeconomy.pricing.manage
```

`cdrvephilimeconomy.admin` mewarisi keduanya.

Role-only behavior:

- `ECONOMY_STAFF`: view policy/statistics pada scope, tidak dapat mutation pricing.
- `ECONOMY_MANAGER`: dapat mutation policy listing pada shop scope, tetapi dibatasi guardrail RC3.
- `ROYAL_TREASURER`: dapat mutation policy listing dalam scope tanpa Manager guardrail.
- Global enable/disable dan stability settings membutuhkan full admin, explicit `pricing.manage`, atau Royal Treasurer dengan scope `*`.

Explicit `cdrvephilimeconomy.pricing.manage` dianggap operator override dan melewati role guardrail.

### Manager Policy Guardrail

Per operasi untuk scoped `ECONOMY_MANAGER`:

```text
target-stock-ratio : hanya 0.20..0.80, delta <= 0.10
sensitivity        : hanya 0.00..1.50, delta <= 0.25
min-multiplier     : hanya 0.50..1.00, delta <= 0.25
max-multiplier     : hanya 1.00..2.00, delta <= 0.25
enabled            : boleh toggle listing dalam scope
```

Absolute parser bounds tetap mengikuti pricing engine (`target 0.05..0.95`, sensitivity `0..5`, multiplier `>0..10`). Guardrail Manager sengaja lebih ketat.

## Compatibility

- `pricing.yml` schema tetap v1.
- RC1/RC2 files tidak membutuhkan migration.
- Dynamic pricing tetap default OFF pada install baru.
- Base price tetap berada di `shops.yml`.
- beta.1 transaction safety, beta.2 Shop Management, dan beta.3 Governance baseline tetap dipertahankan.

## RC3 Scope Boundary

RC3 governance adalah capability/scope + bounded direct-edit policy. Dedicated approval queue khusus pricing parameter belum ditambahkan; perubahan di luar Manager guardrail harus dilakukan Royal Treasurer/admin. Final beta.4 hardening dapat memperluas approval jika memang dibutuhkan setelah runtime QA.
