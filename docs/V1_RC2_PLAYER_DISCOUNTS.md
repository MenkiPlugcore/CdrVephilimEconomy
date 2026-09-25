# CdrVephilimEconomy 1.0.0-RC2 — Per-Player Shop Discount

RC2 menambahkan diskon BUY khusus per player yang hanya dapat diatur oleh admin/operator.

## Behavior

Personal discount diterapkan setelah seluruh market pricing layer:

```text
base price
  -> beta.4 dynamic pricing
  -> beta.5 RP market event
  -> per-player BUY discount
  -> authoritative transaction price
```

SELL price tidak berubah.

Jika admin mengubah diskon ketika GUI player sedang terbuka, transaction service menghitung ulang harga. Quote GUI lama akan ditolak dengan `PRICE_CHANGED` sebelum saldo, item, atau stock dimutasi.

## Scope

Diskon dapat berlaku untuk satu shop:

```text
/cve discount set Candra food 15
```

atau seluruh shop:

```text
/cve discount set Candra * 10
```

Rule shop-specific mengoverride rule `*` untuk shop tersebut.

Contoh:

```text
*    = 10%
food = 25%
```

Player memperoleh 25% di `food` dan 10% di shop lainnya.

Maximum personal discount adalah 90% agar transaksi BUY tidak berubah menjadi harga nol/gratis secara tidak sengaja.

## Commands

```text
/cve discount status
/cve discount list
/cve discount show <player|uuid>
/cve discount set <player|uuid> <shop|*> <percent>
/cve discount remove <player|uuid> <shop|*>
/cve discount clear <player|uuid> CONFIRM
/cve discount reload
```

Player name harus pernah join server, kecuali admin menggunakan UUID secara langsung.

## Permissions

```text
cdrvephilimeconomy.discount.view
cdrvephilimeconomy.discount.manage
```

`cdrvephilimeconomy.admin` mewarisi keduanya. `discount.manage` default OP.

## Persistence

```text
discounts.yml
discounts.yml.bak
discounts.yml.tmp
discounts.yml.initialized
```

`discounts.yml` menyimpan UUID, last known player name, shop scope, dan percentage.

Jika primary file hilang setelah pernah diinisialisasi, plugin mencoba recovery dari backup. Jika state tidak dapat dipercaya, personal discount runtime menjadi unhealthy dan mutation admin ditolak sampai state diperbaiki lalu `/cve discount reload` berhasil.

Admin mutation menggunakan `logs/admin-audit.log` dengan action:

```text
PLAYER_DISCOUNT_SET_REQUEST
PLAYER_DISCOUNT_SET_SUCCESS
PLAYER_DISCOUNT_SET_FAILED
PLAYER_DISCOUNT_REMOVE_REQUEST
PLAYER_DISCOUNT_REMOVE_SUCCESS
PLAYER_DISCOUNT_REMOVE_FAILED
PLAYER_DISCOUNT_CLEAR_REQUEST
PLAYER_DISCOUNT_CLEAR_SUCCESS
PLAYER_DISCOUNT_CLEAR_FAILED
```

## GUI

Player dengan discount melihat:

```text
Diskon pribadi: -15%
Harga sebelum diskon: $100
Harga beli kamu: $85
```

Quote yang ditulis ke item GUI adalah harga sesudah personal discount sehingga stale-price guard tetap authoritative.
