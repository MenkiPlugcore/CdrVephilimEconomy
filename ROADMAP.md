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

## beta.5 — RP Market Events

Target: kondisi ekonomi menjadi pemantik roleplay.

- [x] Temporary market modifier layer di atas quote beta.4/static.
- [x] Event `SCARCITY` untuk kelangkaan komoditas.
- [x] Event `KINGDOM_BUY_BONUS` untuk bonus harga kerajaan membeli dari player.
- [x] Event `DISCOUNT` untuk promo pembelian player dari NPC.
- [x] Scope shop/listing dan wildcard `*` untuk price event.
- [x] Duration-based price event dengan durable start/end timestamp.
- [x] Hard clamp stacking event `0.25..4.0`.
- [x] `market-events.yml` schema v1 + backup/temp atomic mutation.
- [x] Safe runtime reload + rollback pada kegagalan apply price event.
- [x] Hook pengumuman / broadcast RP `[Pasar Kerajaan]` pada create/end.
- [x] Riwayat mutation price event di `logs/market-events.log` + admin audit.
- [x] Governance view/manage permissions dan Royal Treasurer scope.
- [x] Stale-price safety saat event mengubah quote pada GUI lama.
- [x] Governed supply event / one-shot stock shipment.
- [x] Supply menargetkan satu shop/listing konkret tanpa wildcard.
- [x] Durable `market-supply.yml` PREPARED/APPLIED/COMPLETED evidence.
- [x] Supply stock mutation melalui ShopAdminService, bukan direct stock.yml write.
- [x] Deterministic restart recovery berdasarkan before/after durable stock snapshot.
- [x] Fail-closed ambiguous supply recovery + explicit CONFIRM recovery command.
- [x] Supply max-stock guard tanpa silent clamp.
- [x] Supply RP broadcast + `logs/market-supply.log` history.
- [x] Automatic natural-expiry lifecycle recorder untuk price event.
- [x] Durable `expiry-recorded-at` / `expiry-recorded-by` evidence di `market-events.yml`.
- [x] Automatic expiry audit/history + RP broadcast dengan at-most-once restart contract.
- [x] Governance quota listener deduplication.
- [x] Supply listener single-bootstrap guard.
- [x] Final bootstrap hardening: supply listener dan expiry lifecycle benar-benar diregistrasikan satu kali setelah admin audit tersedia.
- [x] Final beta.5 regression/security hardening.
- [x] Finalisasi `0.1.0-beta.5`.
- [ ] Optional event templates/presets dari config — deferred, bukan blocker beta.5.

**Status:** `0.1.0-beta.5` **FINAL / frozen RP Market Events baseline**.

Dokumentasi final: [`docs/BETA5_FINAL.md`](docs/BETA5_FINAL.md).

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
