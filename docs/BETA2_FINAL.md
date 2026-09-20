# CdrVephilimEconomy 0.1.0-beta.2 FINAL

`0.1.0-beta.2` adalah frozen baseline untuk fase **Shop Management** Vephilim Roleplay. Build ini mempertahankan core transaction safety `0.1.0-beta.1` dan menambahkan management layer yang transactional, permission-aware, audited, serta memiliki recovery evidence untuk crash-window administrasi.

## Final Scope

- Command-driven CRUD shop dan listing.
- Bind/unbind Citizens NPC tanpa restart.
- Enable/disable shop.
- Edit display name dan GUI size.
- Tambah/hapus/move listing.
- Edit mode `BUY`, `SELL`, `BUY_SELL`.
- Edit buy/sell price.
- Edit `initial-stock`, `max-stock`, dan runtime stock.
- Manager metadata per shop.
- Granular permissions untuk staff.
- Local administrative audit mandatory.
- Discord administrative audit async opsional.
- Destructive confirmation untuk delete shop/listing.
- `shops.yml` schema v2 dan migration legacy v1.
- Future-schema fail-closed guard.
- Durable schema migration recovery evidence.
- Durable admin mutation journal berbasis SHA-256 original/candidate.
- Fail-closed admin mutation jika recovery state ambigu.

## Frozen Management Contract

Player tetap tidak mendapat command shop. Player trading hanya melalui Citizens NPC.

Perubahan admin normal dilakukan melalui `/cve shop ...`; service membuat candidate config, menjalankan validation, menyimpan audit request, membuat backup/recovery evidence, mengganti live config, lalu melakukan runtime reload. Invalid candidate tidak boleh mengganti runtime sehat.

`mode` tetap source of truth untuk arah transaksi. Menambahkan buy-price pada listing `SELL` tidak otomatis mengaktifkan BUY.

Runtime stock tetap tersimpan di `stock.yml`; migration `shops.yml` tidak boleh mereset stock existing.

## Schema

Current schema:

```yaml
meta:
  schema: 2
```

Legacy file tanpa `meta.schema` dianggap schema v1. Migration menyimpan backup `shops.yml.schema-v1.bak` dan recovery marker agar interrupted migration dapat direkonsiliasi.

Schema yang lebih baru dari kemampuan plugin ditolak fail-closed.

## Administrative Recovery Evidence

```text
shops.yml.schema-v1.bak
shops.yml.schema.pending
shops.yml.schema.last
shops.yml.admin.bak
shops.yml.admin.pending
shops.yml.admin.pending.last
logs/admin-recovery.log
logs/admin-audit.log
```

Jika `shops.yml.admin.pending` tertinggal setelah crash, startup membandingkan hash live config dengan original/candidate. State yang dapat dibuktikan direkonsiliasi; state ambigu memblokir mutation administratif sampai investigasi.

Core economy safety tetap memakai mekanisme beta.1:

```text
stock.yml
stock.yml.bak
stock.yml.initialized
safety.lock
pending-transactions/
transaction-recovery/
logs/audit.log
```

## Operational Checks

Sesudah upgrade atau perubahan besar, jalankan:

```text
/cve shop schema
/cve shop validate
/cve doctor
/cve safety status
```

Expected normal state:

```text
schema=v2/v2 CURRENT
schemaRecovery=OK
adminMutation=OK
safety=OK
pendingTx=0
```

## Runtime Acceptance

Sebelum finalisasi, RC1 dan RC2 management smoke test dinyatakan aman. RC3 menjadi final hardening candidate dan runtime normal dinyatakan aman oleh operator sebelum promotion ke `0.1.0-beta.2`.

Acceptance baseline mencakup management CRUD/QoL, schema migration, restart persistence, dan regression BUY/SELL satuan serta bulk. Recovery drill destruktif tetap direkomendasikan hanya pada staging/test environment.

## Deferred

Admin GUI adalah fitur opsional dan tidak menjadi blocking requirement beta.2. Jika dibutuhkan, fitur tersebut dapat dimasukkan pada fase terpisah tanpa mengubah frozen command/storage contract beta.2.

## Next Phase

Fitur baru berikutnya masuk **beta.3 — Economy Staff & Governance**, terutama role economy staff, per-shop scope, treasury/manager governance, approval perubahan sensitif, dan abuse limits.

Bug/regression pada baseline ini harus diperbaiki sebagai patch beta.2 tanpa mencampurkan fitur governance baru.
