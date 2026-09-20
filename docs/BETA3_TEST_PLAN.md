# beta.3 RC1 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.3-RC1`.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- Baseline beta.2 shop/stock sehat.
- Player test staff sudah pernah join server.

## Governance Storage

- [ ] First startup membuat `governance.yml` schema v1.
- [ ] `governance.yml.bak` muncul setelah mutation berikutnya.
- [ ] `/cve governance status` menunjukkan healthy=true.
- [ ] YAML corrupt membuat role-based governance fail-closed.
- [ ] `cdrvephilimeconomy.admin` tetap dapat recovery walaupun governance storage rusak.
- [ ] `/cve governance reload` memuat ulang file yang sudah diperbaiki.

## Role Assignment

- [ ] `/cve governance grant <player> ECONOMY_STAFF blacksmith` sukses.
- [ ] Target yang belum pernah join ditolak.
- [ ] Scope shop tidak dikenal ditolak.
- [ ] `/cve governance who <player>` menampilkan role + scopes.
- [ ] Grant role yang sama ke shop kedua menambah scope.
- [ ] Grant role berbeda mengganti role dan tidak mewarisi scope lama tanpa eksplisit grant ulang.
- [ ] `/cve governance revoke <player> blacksmith` mencabut satu scope.
- [ ] `/cve governance revoke <player> all` menghapus assignment.

## Scope Enforcement

Buat dua shop, misalnya `blacksmith` dan `farmer`.

- [ ] ECONOMY_STAFF scope `blacksmith` dapat `/cve shop info blacksmith`.
- [ ] Staff yang sama ditolak untuk mutation `farmer`.
- [ ] Tab completion tidak menawarkan shop di luar capability/scope untuk mutation scoped.
- [ ] Scope `*` berlaku global.
- [ ] Permission beta.2 eksplisit tetap bekerja sebagai backward-compatible override.

## ECONOMY_STAFF

- [ ] Dapat price change dalam limit default 20%.
- [ ] Price change >20% per operasi ditolak.
- [ ] Perubahan harga dari base 0 ditolak untuk role-only staff.
- [ ] Dapat runtime stock `add/remove` <=128.
- [ ] Delta >128 ditolak.
- [ ] Runtime stock `SET` ditolak.
- [ ] Tidak dapat bind/toggle/item config/create/delete shop.

## ECONOMY_MANAGER

- [ ] Dapat bind/unbind NPC pada scope.
- [ ] Dapat enable/disable shop pada scope.
- [ ] Dapat edit display name/size pada scope.
- [ ] Dapat add/remove/move listing dan mode pada scope.
- [ ] Dapat initial/max stock config pada scope.
- [ ] Dapat manager metadata pada scope.
- [ ] Price change <=50% per operasi sukses.
- [ ] Price change >50% ditolak.
- [ ] Runtime stock add/remove <=1024 sukses.
- [ ] Delta >1024 ditolak.
- [ ] Runtime stock SET ditolak.
- [ ] Tidak dapat create/delete shop kecuali mendapat permission beta.2 eksplisit.

## ROYAL_TREASURER

- [ ] Scope tertentu hanya berlaku pada shop tersebut untuk mutation scoped.
- [ ] Scope `*` dapat mengakses semua shop.
- [ ] Dapat create/delete shop dengan scope `*`.
- [ ] Price guardrail role tidak membatasi treasurer.
- [ ] Runtime stock SET diizinkan.

## Audit

File: `logs/admin-audit.log`.

- [ ] Grant mencatat REQUEST + SUCCESS.
- [ ] Revoke mencatat REQUEST + SUCCESS.
- [ ] Persistence failure meninggalkan FAILED audit bila writer tersedia.
- [ ] Admin audit REQUEST gagal → grant/revoke dibatalkan fail-closed.
- [ ] Discord admin audit aktif dapat menerima governance event secara async.

## Core Regression

- [ ] BUY 1 sukses.
- [ ] BUY bulk sukses.
- [ ] SELL 1 sukses.
- [ ] SELL bulk sukses.
- [ ] Stock restart persistence tetap benar.
- [ ] `/cve shop schema`, `/cve shop validate`, `/cve doctor`, `/cve safety status` tetap bekerja.
- [ ] Pending transaction/admin mutation recovery beta.1/beta.2 tidak berubah.

## RC1 Exit Criteria

RC1 lulus bila role/scope enforcement konsisten, permission beta.2 tetap backward compatible, governance storage persisten, guardrail price/stock bekerja sesuai config, governance mutation memiliki audit trail, dan tidak ada regression pada core BUY/SELL atau shop management beta.2.
