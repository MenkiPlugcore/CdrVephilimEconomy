# beta.2 Admin Commands — CdrVephilimEconomy

Build: `0.1.0-beta.2-RC2`.

Semua command berada di root `/cve shop`.

## Read-only / diagnostics

```text
/cve shop list
/cve shop info <shop>
/cve shop schema
/cve shop validate
```

Permission: `cdrvephilimeconomy.shop.view`.

`schema` menampilkan versi schema `shops.yml`. `validate` melakukan strict validation terhadap schema dan seluruh definisi shop/listing tanpa mengubah runtime stock.

## Shop lifecycle

```text
/cve shop create <id> [size] [display name]
/cve shop delete <id> CONFIRM
/cve shop bind <shop> <npc-id|-1>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop manager <shop> <name|none>
```

Shop baru dibuat disabled dengan `npc-id: -1` agar tidak langsung aktif sebelum selesai dikonfigurasi.

## Shop appearance / layout

```text
/cve shop name <shop> <display name>
/cve shop size <shop> <9|18|27|36|45|54>
```

Permission: `cdrvephilimeconomy.shop.edit`.

Perubahan size divalidasi terhadap slot listing existing. Jika size diperkecil sehingga ada listing di luar range GUI, perubahan ditolak dan runtime lama tetap aktif.

## Listing lifecycle

```text
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
/cve shop slot <shop> <listing> <slot>
```

`hand` memakai material item di main hand admin. Identitas listing masih material vanilla/polos seperti core beta.1. Perubahan slot tetap melewati duplicate-slot dan out-of-range validation.

## Price

```text
/cve shop price <shop> <listing> buy <value>
/cve shop price <shop> <listing> sell <value>
```

Mengubah harga tidak otomatis mengubah mode listing. `mode` tetap source of truth arah transaksi.

## Stock

```text
/cve shop initialstock <shop> <listing> <value>
/cve shop maxstock <shop> <listing> <value>
/cve shop stock <shop> <listing> set <amount>
/cve shop stock <shop> <listing> add <amount>
/cve shop stock <shop> <listing> remove <amount>
```

`initialstock` mengubah bootstrap configuration dan tidak mereset stock listing existing. `stock` mengubah runtime stock serta `stock.yml` secara langsung. Runtime stock mutation diblokir ketika economy safety stop aktif.

## Permission nodes

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
```

`cdrvephilimeconomy.admin` mewarisi seluruh permission shop. Permission granular dapat diberikan ke staff melalui LuckPerms tanpa memberi full admin access.

## shops.yml schema v2

RC2 memperkenalkan formal schema version:

```yaml
meta:
  schema: 2
```

File beta.1/RC1 yang belum memiliki `meta.schema` dianggap schema v1. Shop management otomatis memigrasikan v1 ke v2 dan membuat backup:

```text
plugins/CdrVephilimEconomy/shops.yml.schema-v1.bak
```

Schema yang lebih baru daripada versi plugin ditolak fail-closed agar plugin lama tidak menulis format yang tidak dipahami.

Setiap mutation administratif juga memperbarui `meta.updated-at` dan mempertahankan `meta.schema: 2`.

## Persistence & local audit

Perubahan konfigurasi dibuat melalui candidate YAML yang divalidasi sebelum mengganti `shops.yml`. Snapshot sebelum perubahan disimpan sebagai:

```text
plugins/CdrVephilimEconomy/shops.yml.admin.bak
```

Administrative audit utama berada di:

```text
plugins/CdrVephilimEconomy/logs/admin-audit.log
```

Mutation administratif menulis `*_REQUEST` sebelum perubahan. Jika request audit tidak dapat ditulis, perubahan dibatalkan. Setelah perubahan sukses, service menulis `*_SUCCESS`; kegagalan/rejection menghasilkan `*_FAILED` atau `*_REJECTED`.

## Discord administrative audit

Discord admin audit terpisah dari transaction audit dan dapat memakai channel/webhook berbeda:

```yaml
admin-audit:
  discord:
    enabled: false
    webhook-url: ""
    include-requests: false
```

Saat enabled, `SUCCESS`, `FAILED`, dan `REJECTED` dikirim asynchronous ke Discord. `REQUEST` hanya ikut bila `include-requests: true`. Kegagalan Discord tidak membatalkan mutation yang local audit-nya sudah berhasil, karena local audit tetap source of truth.

Delete shop dan remove listing selalu memerlukan literal `CONFIRM` karena dapat menghapus runtime stock entry terkait.
