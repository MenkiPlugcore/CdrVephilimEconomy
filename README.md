# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus untuk **Vephilim Roleplay**.

Fokus utamanya adalah membuat perdagangan terasa sebagai bagian dari dunia roleplay kerajaan: player bertransaksi melalui NPC, bukan melalui command shop biasa.

## Prinsip Utama

- Player **tidak memiliki command shop**.
- Semua transaksi dilakukan melalui NPC Citizens.
- NPC hanya menjadi front-end/interaksi dunia; logika transaksi ditangani plugin.
- Sistem mendukung BUY, SELL, atau BUY + SELL per item.
- Shop memiliki stok nyata yang persisten.
- Harga beta.1 bersifat statis agar mudah dibalance dan diaudit.
- Semua transaksi penting memiliki audit trail.
- Fokus utama development adalah keamanan transaksi, anti-dupe, fail-closed recovery, dan performa ringan.

## Status

**Current stable beta baseline: `0.1.0-beta.1`**

Build ini merupakan finalisasi rangkaian RC1-RC7 untuk core NPC shop. Runtime BUY/SELL satuan dan bulk sudah diuji pada Blacksmith Vephilim dan dinyatakan aman untuk menjadi baseline sebelum development `beta.2` dimulai.

`beta.1` membekukan scope core berikut:

- Citizens NPC-only shop.
- GUI BUY / SELL / BUY_SELL.
- Hot reload aman melalui `/cve reload`.
- Static pricing.
- Persistent stock + backup/recovery.
- Stock anti-reset marker.
- Transaction lock, cooldown, per-player in-flight guard.
- Inventory mutation rollback.
- Persistent safety circuit breaker dan recovery eksplisit.
- Write-ahead transaction journal untuk crash-window recovery.
- Local audit + Discord audit async.
- `/cve status`, `/cve doctor`, dan `/cve safety ...`.
- NPC proximity guard.
- Strict config validation.
- Clean shutdown dan fail-closed startup behavior.

## Admin Commands

```text
/cve reload
/cve status
/cve doctor
/cve safety status
/cve safety unlock CONFIRM
```

Permission admin:

```text
cdrvephilimeconomy.admin
```

Tidak ada command shop untuk player.

## Integrasi

- Paper 1.21.11 / Java 21.
- Citizens.
- Vault + economy provider.
- Discord webhook opsional untuk audit eksternal.
- LuckPerms dapat digunakan untuk memberikan permission admin.

## Storage beta.1

Definisi shop berada di `shops.yml`. Stock runtime dipersist ke `stock.yml` dengan backup, temporary snapshot verification, dan marker initialization. Safety stop menggunakan `safety.lock`, sedangkan transaksi yang belum terbukti committed saat crash dicatat pada `pending-transactions/` dan wajib direkonsiliasi sebelum ekonomi dibuka kembali.

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md) — tahapan development.
- [`CHANGELOG.md`](CHANGELOG.md) — riwayat RC dan perubahan.
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md) — baseline final beta.1 dan batas scope.
- [`docs/BETA1_TEST_PLAN.md`](docs/BETA1_TEST_PLAN.md) — regression checklist beta.1.
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md) — konsep ekonomi.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — arsitektur aktual core beta.1.

## Next Phase

Development berikutnya adalah **beta.2 — Shop Management**, dengan fokus admin CRUD, pengelolaan item/harga/stock tanpa edit manual, permission yang lebih granular, dan audit perubahan administratif.

Plugin ini dikembangkan oleh **MenkiPlugcore** untuk project **Vephilim Roleplay**.
