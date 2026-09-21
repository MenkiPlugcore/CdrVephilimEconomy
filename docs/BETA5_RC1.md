# CdrVephilimEconomy 0.1.0-beta.5-RC1

beta.5 RC1 membuka fase **RP Market Events**. Tujuannya adalah membuat kondisi ekonomi menjadi pemantik roleplay tanpa melewati transaction safety beta.1, Shop Management beta.2, Governance beta.3, atau Controlled Dynamic Pricing beta.4.

## Prinsip RC1

Market event hanya memodifikasi **quote harga**. RC1 tidak langsung memutasi saldo player, inventory, atau stock shop. Semua BUY/SELL tetap melewati transaction journal, stale-price guard, stock validation, dan audit yang sama seperti baseline sebelumnya.

Urutan harga:

```text
base price shops.yml
  -> beta.4 sampled dynamic multiplier (jika aktif)
  -> beta.5 active RP event multiplier
  -> effective quote yang ditampilkan/dieksekusi
```

Jika dynamic pricing beta.4 OFF, event tetap dapat memodifikasi static base price.

## Event Preset RC1

### SCARCITY

Kelangkaan komoditas. BUY dan SELL sama-sama naik.

```text
BUY  x multiplier
SELL x multiplier
range: 1.0..3.0
```

### KINGDOM_BUY_BONUS

Kerajaan membayar lebih mahal saat player menjual komoditas ke NPC.

```text
BUY  x1.0
SELL x multiplier
range: 1.0..3.0
```

### DISCOUNT

Diskon sementara untuk pembelian player dari NPC.

```text
BUY  x multiplier
SELL x1.0
range: 0.25..1.0
```

Multiple event boleh overlap. Combined event multiplier diberi hard clamp `0.25..4.0` agar stacking event tidak membuat harga tak terbatas.

## Scope

Setiap event memiliki:

```text
shop: <shop-id|*>
listing: <listing-id|*>
```

Contoh:

```text
blacksmith/iron_ingot  -> hanya iron di blacksmith
blacksmith/*           -> semua listing blacksmith
*/*                    -> event global seluruh pasar
```

## Command

```text
/cve market status
/cve market list
/cve market show <id>

/cve market scarcity <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market buybonus <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]
/cve market discount <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]

/cve market end <id> [reason]
```

Durasi valid `1..10080` menit (maksimum 7 hari).

## Persistence

Event disimpan durable di:

```text
market-events.yml
market-events.yml.bak
market-events.yml.tmp
logs/market-events.log
```

`market-events.yml` menggunakan schema v1. Start/end mutation memakai candidate validation, temp file, backup, atomic replace bila filesystem mendukung, safe runtime reload, dan rollback file/runtime jika apply gagal.

Expired event tetap dipertahankan sebagai evidence di `market-events.yml`; event dianggap tidak aktif berdasarkan `ends-at`.

## Announcement / RP Hook

Event yang berhasil dibuat akan broadcast:

```text
[Pasar Kerajaan] <announcement>
```

Jika announcement kosong, plugin memakai pesan generik bahwa event dimulai. `/cve market end` juga broadcast bahwa event berakhir.

History mutation disimpan ke `logs/market-events.log` dan admin mutation ikut masuk `admin-audit.log`.

## Governance

Permission baru:

```text
cdrvephilimeconomy.market.view
cdrvephilimeconomy.market.manage
```

Rules RC1:

- full admin / `market.manage`: create/end event tanpa batas scope role;
- `ROYAL_TREASURER`: create/end hanya pada shop scope miliknya;
- event global `*` membutuhkan Treasurer dengan scope `*` atau operator override;
- `ECONOMY_MANAGER` dan `ECONOMY_STAFF`: read-only sesuai scope;
- `market.view`: global read-only override.

Market mutation membutuhkan local `admin-audit.log` writable. Jika audit tidak writable, mutation ditolak fail-closed.

## Safety Contract

- quote yang dilihat player tetap tersimpan di GUI PDC;
- bila event mulai/berakhir dan quote berubah sebelum player klik, stale-price guard menghasilkan `PRICE_CHANGED` sebelum money/item mutation;
- transaction journal menyimpan effective price yang benar-benar dieksekusi;
- event tidak bypass stock bounds, player balance validation, inventory validation, safety stop, atau anti-dupe transaction journal;
- event-only static listing tidak otomatis masuk BUY/SELL churn guard beta.4 karena event time-governed, bukan stock-driven.

## Scope Boundary RC1

RC1 belum melakukan automatic stock injection/supply shipment. `Event supply tertentu` tetap menjadi target RC berikutnya karena stock mutation harus memakai jalur yang memiliki recovery evidence dan governance yang setara dengan ShopAdminService.
