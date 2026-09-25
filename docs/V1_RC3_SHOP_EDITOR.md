# CdrVephilimEconomy 1.0.0-RC3 - In-Game Shop Product Editor

RC3 menambahkan editor produk shop berbasis GUI untuk admin/OP agar produk baru dapat ditambahkan tanpa mengedit `shops.yml` secara manual.

## Command

```text
/cve shop editor <shop>
```

Alias root `/veconomy` juga didukung.

Permission:

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.item
```

OP juga diizinkan.

## Flow

1. Admin memegang item produk di main hand.
2. Buka `/cve shop editor <shop>`.
3. Klik `Tambah Produk`.
4. Wizard GUI dibuka.
5. Atur mode, BUY price, SELL price, initial stock, dan max stock.
6. Klik `KONFIRMASI`.

Final mutation tetap dilakukan melalui `ShopAdminService.addItem(...)`, sehingga admin audit, candidate validation, backup, admin mutation journal, stock reconciliation, dan safe runtime reload tidak dibypass.

## Wizard controls

- Mode: cycle `BUY -> SELL -> BUY_SELL`.
- BUY `-` / `+`: klik = 1, shift+klik = 10.
- SELL `-` / `+`: klik = 1, shift+klik = 10.
- Initial stock: cycle preset.
- Max stock: cycle preset.
- Confirm: menyimpan listing secara durable.
- Cancel: kembali ke editor.

## Default behavior

Official Vephilim shop mendapat default mode sesuai fungsi:

```text
food       -> BUY
ore        -> SELL
farmer     -> SELL
fisherman  -> SELL
other      -> BUY_SELL
```

Listing ID dibuat dari nama material, misalnya `IRON_INGOT -> iron_ingot`.

Produk dengan material yang sama pada shop yang sama ditolak untuk menghindari listing plain-material yang ambigu.

Slot shop dipilih dari slot kosong pertama. Bila semua slot sudah digunakan, add ditolak.

## Safety

- GUI membatalkan inventory movement/drag selama editor/wizard aktif.
- Harga aktif harus > 0 sebelum confirm.
- Harga dibatasi non-negative dan finite.
- SELL-only otomatis memakai `initial-stock=0`.
- BUY/BOTH menggunakan initial stock yang dipilih dan tidak boleh melebihi max stock.
- Mutation tidak menulis `shops.yml` secara langsung dari listener GUI.
