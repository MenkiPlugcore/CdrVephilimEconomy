# CdrVephilimEconomy 0.1.0-beta.5-RC2

beta.5 RC2 menambahkan **Governed Supply Event / Stock Shipment**. Berbeda dengan RC1 yang hanya memodifikasi quote harga, RC2 dapat benar-benar menambah persistent shop stock untuk satu listing konkret.

## Command

```text
/cve market supply status
/cve market supply list
/cve market supply show <id>
/cve market supply create <id> <shop> <listing> <amount> [announcement]
/cve market supply recover <id> <applied|not-applied> CONFIRM
```

Contoh:

```text
/cve market supply create royal_wheat_01 farmer wheat 500 Kiriman 500 gandum kerajaan telah tiba!
```

Supply tidak mendukung wildcard. Setiap shipment harus menunjuk satu `shop/listing` konkret agar before/after stock dapat dibuktikan secara deterministik.

## Mutation Boundary

Supply tidak menulis `stock.yml` secara langsung. Flow:

```text
validate scope/listing/max-stock
  -> MARKET_SUPPLY_REQUEST admin audit
  -> persist PREPARED ledger evidence
  -> ShopAdminService.changeRuntimeStock(... ADD ...)
  -> verify durable stock snapshot == expected after
  -> persist APPLIED evidence
  -> MARKET_SUPPLY_SUCCESS admin audit
  -> persist COMPLETED evidence
  -> append market-supply history
  -> RP broadcast
```

Dengan demikian stock tetap memakai validation, safety-stop guard, admin mutation health, stock repository persistence, dan stock admin audit yang sudah ada.

## Durable Ledger

```text
market-supply.yml
market-supply.yml.bak
market-supply.yml.tmp
logs/market-supply.log
```

Schema saat ini: `v1`.

Setiap supply ID hanya boleh digunakan satu kali. Ledger mempertahankan evidence:

- `PREPARED`
- `APPLIED`
- `COMPLETED`
- `FAILED_NOT_APPLIED`
- `RECOVERED_APPLIED`
- `RECOVERED_NOT_APPLIED`

## Crash Recovery

Jika server mati setelah PREPARED tetapi sebelum completion, service membandingkan durable stock snapshot terhadap evidence:

```text
current == before -> RECOVERED_NOT_APPLIED
current == after  -> RECOVERED_APPLIED
lainnya           -> BLOCKED / ambiguous
```

Tidak ada replay otomatis untuk kondisi ambiguous.

Untuk recovery manual:

```text
/cve market supply recover <id> applied CONFIRM
```

hanya diterima bila current stock sama dengan `after-stock` evidence.

```text
/cve market supply recover <id> not-applied CONFIRM
```

hanya diterima bila current stock sama dengan `before-stock` evidence.

Jika current stock tidak cocok, admin harus merekonsiliasi stock terlebih dahulu. Recovery command tidak menebak atau mengubah stock secara diam-diam.

## Governance

- full admin / `cdrvephilimeconomy.market.manage`: create + recovery.
- `ROYAL_TREASURER`: create supply hanya pada shop scope miliknya.
- `ECONOMY_MANAGER` / `ECONOMY_STAFF`: read-only ledger sesuai scope.
- `cdrvephilimeconomy.market.view`: global read-only override.
- manual ambiguous recovery tidak diberikan ke Treasurer; hanya admin/operator `market.manage`.

## Bounds

- amount harus integer `> 0`.
- result stock tidak boleh melewati `max-stock` listing.
- supply ditolak saat economy safety stop aktif.
- local admin audit harus writable.
- shop/listing harus ada di runtime.

Tidak ada silent clamp. Jika current stock 900, max-stock 1000, dan supply +500 diminta, request ditolak; plugin tidak diam-diam mengubahnya menjadi +100.

## RP Broadcast

Setelah COMPLETED:

```text
[Pasar Kerajaan] <announcement>
```

Jika announcement kosong, plugin menghasilkan pesan shipment generik.

## RC2 Scope Boundary

RC2 hanya menambah supply/injection. Destructive stock event atau automatic recurring shipment belum ditambahkan. Price events RC1 tetap berdiri sendiri dan tidak menjadi syarat supply event.
