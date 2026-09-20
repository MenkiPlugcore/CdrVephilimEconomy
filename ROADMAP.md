# CdrVephilimEconomy Roadmap

Roadmap ini memecah development menjadi fase kecil agar plugin tetap ringan, mudah dites, dan aman terhadap exploit ekonomi.

## beta.1 — Core NPC Shop

Target: membuktikan loop transaksi dasar dengan aman.

- [x] Bootstrap project plugin.
- [x] Integrasi Citizens.
- [x] Registrasi NPC shop.
- [x] NPC membuka GUI shop saat diinteraksi.
- [x] BUY item.
- [x] SELL item.
- [x] Mode BUY-only / SELL-only / BUY+SELL.
- [x] Harga statis per item.
- [x] Stock engine sederhana.
- [x] Validasi saldo player.
- [x] Validasi inventory penuh/kosong.
- [x] Atomic-style transaction flow / best-effort rollback bila transaksi gagal.
- [x] Anti double-click / anti spam transaksi.
- [x] Audit log lokal.
- [x] Discord audit log dasar.
- [x] Tidak ada command shop untuk player.
- [x] NPC proximity guard.
- [x] Strict config validation.
- [x] Stock backup/recovery dan fail-closed data-loss guard.
- [x] Startup diagnostics.
- [x] Clean shutdown/flush.
- [x] GUI transaction feedback final untuk kandidat uji.

**Status implementasi:** `0.1.0-beta.1-RC1` sudah menjadi kandidat runtime QA. Core sudah berhasil disusun untuk Paper 1.21.11 / Java 21 dan build divalidasi melalui GitHub Actions. Runtime QA menggunakan [`docs/BETA1_TEST_PLAN.md`](docs/BETA1_TEST_PLAN.md) tetap wajib sebelum `beta.1` ditandai final.

Exit criteria:
- transaksi tidak bisa menghasilkan item/uang ganda;
- stok selalu konsisten setelah buy/sell;
- disconnect atau inventory penuh tidak merusak transaksi;
- kehilangan/corrupt snapshot stock tidak menyebabkan silent stock reset;
- setiap transaksi penting tercatat;
- restart/shutdown bersih mempertahankan state ekonomi.

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
