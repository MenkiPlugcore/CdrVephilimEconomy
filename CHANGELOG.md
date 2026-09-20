# Changelog

Semua perubahan penting pada `CdrVephilimEconomy` akan dicatat di file ini.

Format mengikuti prinsip Keep a Changelog dan versioning proyek akan menggunakan pola pre-release sampai plugin siap production.

## [Unreleased]

### Added
- Inisialisasi dokumentasi project.
- Roadmap development awal.
- Konsep NPC-only economy untuk Vephilim Roleplay.

## [0.1.0-beta.1-SNAPSHOT]

### Added
- Bootstrap project Maven untuk Paper 1.21.11 / Java 21.
- Integrasi Citizens `2.0.43-SNAPSHOT` untuk membuka shop dari NPC terdaftar.
- Integrasi Vault API untuk saldo, withdraw, deposit, dan format currency.
- Shop registry berbasis `shops.yml`.
- GUI NPC shop dengan BUY, SELL, BUY+SELL.
- Klik kiri/kanan dan bulk transaction via shift-click.
- Static buy/sell pricing.
- Persistent stock sederhana melalui `stock.yml` dengan atomic file replacement bila tersedia.
- Validasi saldo, inventory capacity, item yang dapat dijual, stock, dan max stock.
- Listing-level transaction lock dan player transaction cooldown.
- Best-effort rollback untuk kegagalan mutation/persistence.
- Local audit log dengan transaction ID.
- Discord webhook audit async.
- GitHub Actions build validation dan artifact JAR.
- `docs/BETA1_TEST_PLAN.md` untuk runtime QA/anti-dupe regression.

### Security / Safety
- Player tidak memiliki command shop.
- GUI menggunakan custom inventory holder dan memblokir click/drag mutation.
- SELL beta.1 hanya menerima item vanilla polos yang `isSimilar` dengan template material sehingga custom meta/enchant tidak tersapu sebagai item biasa.

### Status
- Build Maven berhasil di GitHub Actions.
- Runtime QA di server Paper belum menjadi exit criteria yang selesai; beta.1 belum ditandai sebagai release final.
