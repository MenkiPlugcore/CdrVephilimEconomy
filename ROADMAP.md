# CdrVephilimEconomy Roadmap

Roadmap ini memecah development menjadi fase kecil agar plugin tetap ringan, mudah dites, dan aman terhadap exploit ekonomi.

## beta.1 — Core NPC Shop

Target: membuktikan loop transaksi dasar dengan aman.

- [ ] Bootstrap project plugin.
- [ ] Integrasi Citizens.
- [ ] Registrasi NPC shop.
- [ ] NPC membuka GUI shop saat diinteraksi.
- [ ] BUY item.
- [ ] SELL item.
- [ ] Mode BUY-only / SELL-only / BUY+SELL.
- [ ] Harga statis per item.
- [ ] Stock engine sederhana.
- [ ] Validasi saldo player.
- [ ] Validasi inventory penuh/kosong.
- [ ] Atomic transaction / rollback bila transaksi gagal.
- [ ] Anti double-click / anti spam transaksi.
- [ ] Audit log lokal.
- [ ] Discord audit log dasar.
- [ ] Tidak ada command shop untuk player.

Exit criteria:
- transaksi tidak bisa menghasilkan item/uang ganda;
- stok selalu konsisten setelah buy/sell;
- disconnect atau inventory penuh tidak merusak transaksi;
- setiap transaksi penting tercatat.

## beta.2 — Shop Management

Target: admin dapat mengelola shop tanpa edit source code.

- [ ] File konfigurasi shop.
- [ ] Tambah/hapus NPC shop.
- [ ] Tambah/hapus item.
- [ ] Edit harga BUY/SELL.
- [ ] Edit stok awal dan batas stok.
- [ ] Reload konfigurasi aman.
- [ ] Permission admin yang granular.
- [ ] Identitas penanggung jawab shop.
- [ ] Audit perubahan konfigurasi ekonomi.

## beta.3 — Economy Staff & Governance

Target: ekonomi dapat dikelola sebagai bagian dari RP kerajaan.

- [ ] Role Economy Staff.
- [ ] Scope akses per shop.
- [ ] Economy Manager / Royal Treasurer.
- [ ] Approval untuk perubahan sensitif.
- [ ] Audit siapa mengubah apa dan kapan.
- [ ] Discord log untuk perubahan administratif.
- [ ] Batas perubahan harga/stok untuk mencegah abuse.

## beta.4 — Controlled Dynamic Pricing

Target: harga merespons kondisi pasar tanpa menjadi liar.

- [ ] Harga berdasarkan supply/demand sederhana.
- [ ] Minimum price.
- [ ] Maximum price.
- [ ] Sensitivity/rate per shop atau item.
- [ ] Cooldown perubahan harga.
- [ ] Proteksi manipulasi transaksi bolak-balik.
- [ ] Statistik harga dan stok.

## beta.5 — RP Market Events

Target: kondisi ekonomi menjadi pemantik roleplay.

- [ ] Market modifier sementara.
- [ ] Kelangkaan komoditas.
- [ ] Bonus harga beli kerajaan.
- [ ] Event supply tertentu.
- [ ] Hook untuk pengumuman alun-alun / broadcast RP.
- [ ] Riwayat event ekonomi.

## v1.0.0 — Production

Target: stabil untuk digunakan sebagai economy utama Vephilim Roleplay.

- [ ] Regression test transaksi.
- [ ] Stress test transaksi bersamaan.
- [ ] Recovery setelah restart/crash.
- [ ] Dokumentasi instalasi.
- [ ] Dokumentasi konfigurasi.
- [ ] Dokumentasi permission.
- [ ] Migration/versioning data.
- [ ] Final security audit.
- [ ] Production release.

## Non-goals Awal

Hal berikut sengaja tidak menjadi prioritas fase pertama:

- command `/shop` untuk player;
- auction house global;
- player market otomatis;
- bank kompleks;
- dynamic pricing agresif;
- kalkulasi ekonomi setiap tick.

Filosofi development: **NPC first, transaction safety first, RP second layer, complexity only when needed.**
