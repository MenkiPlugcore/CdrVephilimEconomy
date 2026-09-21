# CdrVephilimEconomy Roadmap

Roadmap ini memecah development menjadi fase kecil agar plugin tetap ringan, mudah dites, dan aman terhadap exploit ekonomi.

## beta.1 — Core NPC Shop

Target: membuktikan loop transaksi dasar dengan aman.

- [x] Citizens NPC shop.
- [x] BUY / SELL / BUY_SELL.
- [x] Persistent stock.
- [x] Vault economy bridge.
- [x] Balance/inventory/stock validation.
- [x] Compensation rollback.
- [x] Anti-double-click, listing lock, per-player in-flight guard.
- [x] Local + Discord transaction audit.
- [x] Safe reload.
- [x] Persistent safety circuit breaker + explicit recovery.
- [x] `/cve doctor`.
- [x] Durable pending transaction journal.
- [x] Final anti-dupe / restart / crash hardening.
- [x] Finalisasi `0.1.0-beta.1`.

**Status:** `0.1.0-beta.1` **FINAL / frozen Core Economy baseline**.

Dokumentasi final: [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md).

## beta.2 — Shop Management

Target: admin dapat mengelola shop tanpa edit source code atau bergantung pada edit YAML manual untuk operasi rutin.

- [x] Command/listing CRUD.
- [x] Create/delete shop.
- [x] Bind/unbind Citizens NPC.
- [x] Enable/disable shop.
- [x] Add/remove listing.
- [x] Edit mode, harga, slot, display name, GUI size.
- [x] Set/add/remove runtime stock.
- [x] Edit initial-stock / max-stock.
- [x] Granular permissions.
- [x] Shop manager metadata.
- [x] Local + Discord admin audit.
- [x] `shops.yml` schema v2 + migration.
- [x] Candidate validation + backup + safe runtime apply.
- [x] Durable admin mutation journal + deterministic recovery.
- [x] Interrupted schema migration recovery.
- [x] Final beta.2 regression.
- [x] Finalisasi `0.1.0-beta.2`.
- [ ] Admin GUI opsional — deferred, bukan blocker.

**Status:** `0.1.0-beta.2` **FINAL / frozen Shop Management baseline**.

Dokumentasi final: [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md).

## beta.3 — Economy Staff & Governance

Target: ekonomi dapat dikelola sebagai bagian dari RP kerajaan tanpa memberi full admin access ke seluruh staff.

- [x] Role `ECONOMY_STAFF`, `ECONOMY_MANAGER`, `ROYAL_TREASURER`.
- [x] Scope per shop / global `*`.
- [x] Persistent governance assignment berbasis UUID.
- [x] Command grant/revoke/list/who/reload.
- [x] Integrasi capability ke Shop Management beta.2.
- [x] Per-operation price/stock guardrail.
- [x] Durable sensitive-change approval queue.
- [x] Anti-self-approval + reviewer hierarchy/scope.
- [x] Expiry, cancel, reject.
- [x] Durable `EXECUTING` evidence + manual recovery.
- [x] Rolling price/stock quota + cooldown.
- [x] Persistent governance usage evidence.
- [x] Two-person approval untuk extreme mutation.
- [x] Distinct reviewer + senior reviewer requirement.
- [x] Durable first-review evidence + SHA-256 request fingerprint.
- [x] Final beta.3 regression/security hardening.
- [x] Finalisasi `0.1.0-beta.3`.

**Status:** `0.1.0-beta.3` **FINAL / frozen Economy Staff & Governance baseline**.

Dokumentasi final: [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md).

## beta.4 — Controlled Dynamic Pricing

Target: harga merespons kondisi pasar tanpa menjadi liar.

- [x] Harga stock-driven supply/demand sederhana dari stock ratio.
- [x] Minimum price guard melalui `min-multiplier`.
- [x] Maximum price guard melalui `max-multiplier`.
- [x] Sensitivity + target-stock-ratio per listing.
- [x] `pricing.yml` schema v1 dengan master switch default OFF.
- [x] Effective BUY/SELL quote tampil di NPC GUI.
- [x] Transaction journal/audit memakai harga efektif aktual.
- [x] Stale-GUI price guard sebelum money/item mutation.
- [x] Invalid `pricing.yml` membatalkan startup/reload secara aman.
- [x] Quote cooldown/sampling supaya harga tidak berubah setiap transaksi.
- [x] Minimum stock delta sebelum market resample.
- [x] Durable `market-state.yml` + backup/temp persistence.
- [x] Restart continuity untuk sampled multiplier.
- [x] Policy fingerprint invalidation saat parameter market berubah.
- [x] Fail-closed dynamic layer ke static base price jika market state rusak.
- [x] Rapid opposite-direction BUY/SELL reversal guard per player/listing.
- [x] Statistik transaction count, unit volume, turnover, effective-price average/range dari audit source of truth.
- [x] Statistik window 1-720 jam + scoped shop/listing view.
- [x] Governed `/cve pricing` management commands.
- [x] Pricing view/manage permissions.
- [x] Role/scope integration: Staff view, Manager bounded mutation, Treasurer escalation.
- [x] Manager guardrail untuk target/sensitivity/floor/ceiling.
- [x] Candidate validation + pricing backup/temp + runtime rollback.
- [x] Mandatory local admin audit untuk pricing mutation.
- [x] Final regression + security hardening beta.4.
- [x] Finalisasi `0.1.0-beta.4`.
- [ ] Dedicated pricing-parameter approval queue — deferred, bukan blocker beta.4.

**Status:** `0.1.0-beta.4` **FINAL / frozen Controlled Dynamic Pricing baseline**.

Dokumentasi final: [`docs/BETA4_FINAL.md`](docs/BETA4_FINAL.md).

Dokumentasi development: [`docs/BETA4_RC1.md`](docs/BETA4_RC1.md), [`docs/BETA4_RC2.md`](docs/BETA4_RC2.md), [`docs/BETA4_RC3.md`](docs/BETA4_RC3.md), dan test plan RC1-RC3 pada folder `docs/`.

## beta.5 — RP Market Events

Target: kondisi ekonomi menjadi pemantik roleplay.

- [ ] Market modifier sementara.
- [ ] Kelangkaan komoditas.
- [ ] Bonus harga beli kerajaan.
- [ ] Event supply tertentu.
- [ ] Hook pengumuman alun-alun / broadcast RP.
- [ ] Riwayat event ekonomi.

**Status:** fase berikutnya setelah beta.4 dipromosikan ke `main` dan branch `dev/beta.5` dibuat dari baseline tersebut.

## v1.0.0 — Production

Target: stabil untuk digunakan sebagai economy utama Vephilim Roleplay.

- [ ] Regression test transaksi skala production.
- [ ] Stress test transaksi bersamaan.
- [ ] Recovery drill restart/crash.
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
