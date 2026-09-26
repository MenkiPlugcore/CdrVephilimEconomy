# CdrVephilimEconomy 1.0.0-RC4

RC4 memperluas Admin Shop Editor menjadi pengelola kategori shop dan Citizens NPC langsung dari dalam game.

## Shop Category Dashboard

`/cve shop editor` sekarang membuka dashboard semua kategori/shop runtime.

- klik kategori untuk membuka editor produk;
- pagination hingga lebih dari 45 kategori;
- status enabled, jumlah produk, ukuran inventory, dan NPC binding terlihat di GUI;
- tombol `Buat Kategori Shop Baru` tersedia untuk admin dengan `cdrvephilimeconomy.shop.create`.

## Membuat kategori baru

Flow in-game:

```text
/cve shop editor
 -> Buat Kategori Shop Baru
 -> ketik ID kategori di chat
 -> ketik nama tampilan di chat
 -> pilih ukuran 9/18/27/36/45/54 slot
 -> ShopAdminService.createShop()
 -> safe runtime reload
 -> editor kategori baru terbuka
```

Kategori baru tetap mengikuti contract ShopAdminService: default `enabled: false`, `npc-id: -1`, candidate validation, admin audit, backup, mutation journal, stock reconcile, dan safe runtime reload.

Chat input menerima `batal` / `cancel` untuk keluar dari wizard.

## Citizens NPC Binding

Setiap editor shop sekarang memiliki tombol `NPC Binding`.

Flow bind:

```text
/cve shop editor <shop>
 -> Bind Citizens NPC
 -> GUI ditutup
 -> klik kanan Citizens NPC di dunia
 -> ShopAdminService.bindNpc()
 -> runtime reload
 -> editor dibuka kembali
```

Jika shop sudah memiliki NPC, GUI menyediakan `Rebind Citizens NPC` dan `Unbind NPC`.

Satu Citizens NPC tidak boleh dibind ke dua shop sekaligus melalui GUI. Jika NPC sudah digunakan shop lain, request ditolak sampai binding lama dilepas.

Permission:

```text
cdrvephilimeconomy.shop.view
cdrvephilimeconomy.shop.create
cdrvephilimeconomy.shop.bind
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.admin
```

OP dan full admin tetap memiliki akses penuh.

## Product Editor Compatibility

Wizard tambah produk RC3 tetap tersedia. Perubahan RC4 juga memperbaiki lifecycle wizard agar pergantian inventory saat menekan tombol harga/mode/stock tidak menghapus state wizard secara prematur.

## Safety

GUI tidak pernah menulis `shops.yml` secara langsung. Category create, NPC bind/unbind/rebind, dan product add tetap melewati ShopAdminService sehingga production mutation safety dari beta.2+ tetap digunakan.
