# beta.2 Admin Commands — CdrVephilimEconomy

Build: `0.1.0-beta.2-RC1`.

Semua command berada di root `/cve shop`.

## Read-only

```text
/cve shop list
/cve shop info <shop>
```

Permission: `cdrvephilimeconomy.shop.view`.

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

## Listing lifecycle

```text
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
```

`hand` memakai material item di main hand admin. Pada RC1 identitas listing masih material vanilla/polos seperti core beta.1.

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
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.shop.price
cdrvephilimeconomy.shop.stock
cdrvephilimeconomy.shop.manager
```

`cdrvephilimeconomy.admin` mewarisi seluruh permission shop. Permission granular dapat diberikan ke staff melalui LuckPerms tanpa memberi full admin access.

## Persistence & audit

Perubahan konfigurasi dibuat melalui candidate YAML yang divalidasi sebelum mengganti `shops.yml`. Snapshot sebelum perubahan disimpan sebagai `shops.yml.admin.bak`.

Administrative audit berada di:

```text
plugins/CdrVephilimEconomy/logs/admin-audit.log
```

Mutation administratif menulis `*_REQUEST` sebelum perubahan. Jika request audit tidak dapat ditulis, perubahan dibatalkan. Setelah perubahan sukses, service menulis `*_SUCCESS`.

Delete shop dan remove listing selalu memerlukan literal `CONFIRM` karena dapat menghapus runtime stock entry terkait.
