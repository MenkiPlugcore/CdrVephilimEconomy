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

**Status implementasi:** `0.1.0-beta.1` **FINAL / frozen baseline**.

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
- [x] Final beta.2 runtime regression.
- [x] Finalisasi `0.1.0-beta.2`.
- [ ] Admin GUI opsional untuk operasi rutin tanpa command panjang — deferred, bukan blocking beta.2.

**Status implementasi:** `0.1.0-beta.2` **FINAL / frozen Shop Management baseline**.

Dokumentasi final: [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md).

## beta.3 — Economy Staff & Governance

Target: ekonomi dapat dikelola sebagai bagian dari RP kerajaan tanpa memberi full admin access ke seluruh staff.

- [x] Role `ECONOMY_STAFF`.
- [x] Scope akses per shop.
- [x] Role `ECONOMY_MANAGER`.
- [x] Role `ROYAL_TREASURER`.
- [x] Persistent `governance.yml` berbasis UUID + backup/atomic write.
- [x] Command grant/revoke/list/who/reload governance.
- [x] Integrasi role capability dengan command shop beta.2 tanpa menghapus permission lama.
- [x] Audit grant/revoke melalui administrative audit beta.2.
- [x] Discord governance log melalui administrative Discord audit sink yang sama.
- [x] Guardrail perubahan harga per operasi untuk Staff/Manager.
- [x] Guardrail runtime stock add/remove per operasi untuk Staff/Manager.
- [x] Runtime stock SET dibatasi ke Royal Treasurer/admin/explicit beta.2 permission.
- [x] Approval queue untuk perubahan sensitif.
- [x] Anti-self-approval.
- [x] Threshold perubahan yang wajib approval.
- [x] Expiry/cancel approval request.
- [x] Durable approval persistence + backup/atomic write.
- [x] Pre-execution `EXECUTING` evidence untuk crash-window approval.
- [x] Manual recovery declaration untuk ambiguous EXECUTING state.
- [x] Approval hierarchy: Manager dapat review Staff; Treasurer dapat review Manager/Staff.
- [x] Rolling governance quota untuk cumulative direct price/stock mutation.
- [x] Persistent `governance-usage.yml` + backup/atomic write.
- [x] Cooldown anti-command-burst per role.
- [x] Quota health fail-closed untuk role-only direct mutation.
- [x] RC3 quota listener benar-benar wired ke runtime command.
- [x] Two-person approval untuk perubahan sangat sensitif.
- [x] Durable first-review evidence + fingerprint request.
- [x] Distinct second reviewer + minimal satu senior reviewer.
- [x] Fail-closed dual approval storage + history evidence.
- [ ] Final beta.3 regression dan security audit.
- [ ] Finalisasi `0.1.0-beta.3`.

**Status implementasi:** `0.1.0-beta.3-RC4` pada branch `dev/beta.3`. RC1 membangun role/scope foundation, RC2 menambahkan sensitive-change approval, RC3 menutup bypass perubahan kecil berulang melalui rolling quota + cooldown durable, dan RC4 menambahkan two-person approval untuk mutation ekstrem sekaligus memperbaiki wiring quota runtime.

Dokumentasi: [`docs/BETA3_RC1.md`](docs/BETA3_RC1.md), [`docs/BETA3_RC2.md`](docs/BETA3_RC2.md), [`docs/BETA3_RC3.md`](docs/BETA3_RC3.md), [`docs/BETA3_RC4.md`](docs/BETA3_RC4.md), dan [`docs/BETA3_TEST_PLAN.md`](docs/BETA3_TEST_PLAN.md).

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

- command `/shop` untuk player;
- auction house global;
- player market otomatis;
- bank kompleks;
- dynamic pricing agresif;
- kalkulasi ekonomi setiap tick.

Filosofi development: **NPC first, transaction safety first, RP second layer, complexity only when needed.**
