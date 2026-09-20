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
- [x] Stock engine persisten.
- [x] Validasi saldo player.
- [x] Validasi inventory penuh/kosong.
- [x] Atomic-style transaction flow / compensation rollback.
- [x] Listing lock, cooldown, dan per-player in-flight guard.
- [x] Audit log lokal.
- [x] Discord audit log dasar.
- [x] Tidak ada command shop untuk player.
- [x] NPC proximity guard.
- [x] Strict config validation.
- [x] Safe runtime reload `/cve reload`.
- [x] Stock backup/recovery dan fail-closed data-loss guard.
- [x] `stock.yml.initialized` anti-silent-reset marker.
- [x] Persistent safety circuit breaker.
- [x] Explicit safety recovery + evidence archive.
- [x] `/cve doctor` health diagnostics.
- [x] Durable write-ahead transaction journal untuk crash window.
- [x] Startup pending-transaction recovery scan.
- [x] Inventory rollback snapshot untuk mutation failure.
- [x] Clean shutdown/flush.
- [x] Final anti-dupe / regression hardening.

**Status implementasi:** `0.1.0-beta.1` **FINAL / frozen baseline**. Rangkaian RC1-RC7 sudah ditutup dan build ini menjadi baseline stabil untuk core NPC economy sebelum `beta.2` dimulai.

Exit criteria beta.1:
- transaksi tidak menghasilkan item/uang ganda pada jalur yang diketahui;
- stok konsisten setelah BUY/SELL normal;
- restart/shutdown bersih mempertahankan state ekonomi;
- crash window meninggalkan recovery evidence dan memicu fail-closed;
- kehilangan/corrupt snapshot stock tidak menyebabkan silent stock reset;
- persistent safety stop tidak dapat dilewati dengan reload/restart;
- transaksi penting dan failure kritis memiliki audit trail;
- admin memiliki diagnostics untuk memeriksa kesehatan runtime/storage.

Dokumentasi final: [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md).

## beta.2 — Shop Management

Target: admin dapat mengelola shop tanpa edit source code atau bergantung pada edit YAML manual untuk operasi rutin.

- [x] Command/listing admin CRUD foundation.
- [x] Tambah/hapus NPC shop.
- [x] Bind/unbind Citizens NPC.
- [x] Enable/disable shop.
- [x] Tambah/hapus item listing.
- [x] Edit mode BUY/SELL/BUY_SELL.
- [x] Edit harga BUY/SELL.
- [x] Set/add/remove stock secara aman.
- [x] Edit initial-stock dan max-stock.
- [x] Persistence perubahan konfigurasi transactional dengan candidate validation + backup.
- [x] Safe runtime apply tanpa restart setelah perubahan admin.
- [x] Permission admin granular.
- [x] Identitas penanggung jawab shop (`manager`).
- [x] Audit lokal perubahan konfigurasi ekonomi (`admin-audit.log`).
- [x] Destructive confirmation untuk delete shop/listing.
- [x] Safety guard: runtime stock mutation ditolak saat economy safety stop aktif.
- [x] Formal `shops.yml` schema v2.
- [x] Migration legacy beta.1/RC1 dengan `shops.yml.schema-v1.bak`.
- [x] Future-schema fail-closed guard.
- [x] Discord administrative audit sink async.
- [x] Management diagnostics `/cve shop schema` + `/cve shop validate`.
- [x] QoL display name / GUI size / listing slot.
- [x] Durable journal untuk crash-window admin config mutation.
- [x] Deterministic admin mutation recovery berbasis original/candidate SHA-256.
- [x] Schema migration pending marker + verified backup/candidate hash.
- [x] Interrupted schema migration recovery / fail-closed ambiguity guard.
- [ ] Admin GUI opsional untuk operasi rutin tanpa command panjang.
- [ ] Final beta.2 runtime regression + recovery drill.
- [ ] Finalisasi `0.1.0-beta.2`.

**Status implementasi:** `0.1.0-beta.2-RC3` pada branch `dev/beta.2`. RC1 dan RC2 smoke test dinyatakan aman. RC3 adalah kandidat hardening terakhir: fokus pada crash-window perubahan config admin, interrupted schema migration recovery, dan final regression tanpa mengubah core transaction engine beta.1.

Jika RC3 lolos runtime regression, tahap berikutnya langsung `0.1.0-beta.2 FINAL`; RC tambahan hanya dibuat bila ada bug nyata.

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

- [ ] Regression test transaksi skala production.
- [ ] Stress test transaksi bersamaan.
- [ ] Recovery drill setelah restart/crash.
- [ ] Dokumentasi instalasi.
- [ ] Dokumentasi konfigurasi.
- [ ] Dokumentasi permission.
- [ ] Migration/versioning data lintas beta.
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
