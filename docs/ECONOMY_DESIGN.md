# Economy Design — Vephilim Roleplay

## Tujuan

`CdrVephilimEconomy` dibuat agar ekonomi menjadi bagian dari dunia roleplay, bukan sekadar menu command.

Player harus datang ke lokasi perdagangan dan berinteraksi dengan NPC untuk membeli atau menjual barang.

## Player Flow

Contoh transaksi BUY:

1. Player datang ke NPC Blacksmith.
2. Player berinteraksi dengan NPC.
3. Plugin memverifikasi NPC terdaftar sebagai shop.
4. GUI shop dibuka.
5. Player memilih item.
6. Plugin mengecek stok.
7. Plugin mengecek saldo.
8. Plugin mengecek kapasitas inventory.
9. Transaksi diproses secara atomik.
10. Uang dikurangi, item diberikan, stok dikurangi.
11. Audit log ditulis.

Contoh transaksi SELL:

1. Player membuka NPC shop.
2. Player memilih item yang diterima shop.
3. Plugin memverifikasi jumlah item asli di inventory.
4. Plugin memverifikasi batas stok.
5. Item diambil.
6. Uang diberikan.
7. Stok bertambah.
8. Audit log ditulis.

## Tipe Shop

Shop tidak di-hardcode berdasarkan profesi. Nama berikut hanya contoh konfigurasi:

- Blacksmith
- Farmer
- Fisherman
- Miner
- Merchant
- Cosmetic
- Alchemist
- Carpenter

Admin dapat membuat tipe shop lain tanpa mengubah source code.

## Mode Item

Setiap listing memiliki salah satu mode:

- `BUY_ONLY`
- `SELL_ONLY`
- `BUY_SELL`

Harga BUY dan SELL disimpan terpisah.

## Stock

Setiap item dapat menggunakan stok terbatas.

Aturan dasar:

- BUY player mengurangi stok shop.
- SELL player menambah stok shop.
- Stock tidak boleh turun di bawah 0.
- Stock tidak boleh melebihi batas maksimum bila batas diterapkan.
- Update stok harus menjadi bagian dari transaksi atomik.

## Pricing

### Fase Awal

Harga bersifat statis.

Tujuannya:
- mudah dibalance;
- mudah diaudit;
- meminimalkan exploit sebelum sistem stabil.

### Fase Lanjutan

Harga dapat menjadi semi-dinamis berdasarkan stok atau supply/demand, tetapi wajib memiliki:

- harga dasar;
- harga minimum;
- harga maksimum;
- batas kecepatan perubahan;
- proteksi manipulasi transaksi bolak-balik.

## Economy Governance

Tidak semua staff boleh mengubah ekonomi.

Rencana role:

- Shop Manager — mengelola shop tertentu.
- Economy Staff — mengelola bagian ekonomi yang diberikan.
- Royal Treasurer / Economy Manager — akses ekonomi yang lebih luas.
- Administrator — akses teknis darurat.

Perubahan administratif harus diaudit.

## Audit

Audit minimum transaksi:

- timestamp;
- UUID player;
- username;
- shop ID;
- NPC ID;
- item ID/material;
- jenis transaksi BUY/SELL;
- quantity;
- harga satuan;
- total nilai;
- stok sebelum;
- stok sesudah;
- status SUCCESS/FAILED;
- alasan kegagalan bila relevan.

Audit administratif minimum:

- actor UUID/name;
- action;
- shop/listing target;
- old value;
- new value;
- timestamp.

## Discord Logging

Discord digunakan sebagai audit eksternal, bukan sebagai sumber data utama.

Log lokal/database tetap menjadi sumber kebenaran. Jika Discord webhook gagal, transaksi tidak boleh ikut gagal hanya karena logging eksternal gagal.

## Anti-Abuse Principles

- Tidak percaya data GUI sebagai sumber kebenaran.
- Semua kondisi diverifikasi ulang saat klik transaksi.
- Validasi item dilakukan server-side.
- Saldo dicek tepat sebelum commit.
- Stok dicek tepat sebelum commit.
- Transaksi concurrent pada listing yang sama harus diserialisasi atau dikunci secara aman.
- Tidak ada pemberian item sebelum pengurangan saldo berhasil dipastikan.
- Bila tahap akhir gagal, state harus di-rollback atau transaksi dibatalkan tanpa menghasilkan duplikasi.

## RP Philosophy

Sistem ekonomi bertujuan menciptakan situasi, bukan memaksa dialog RP.

Contoh: jika stok gandum rendah, kerajaan dapat menaikkan harga beli gandum. Hal ini secara natural mendorong player menjadi petani atau pedagang tanpa quest wajib.
