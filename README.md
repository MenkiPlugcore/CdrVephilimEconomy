# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus untuk **Vephilim Roleplay**.

Fokus utamanya adalah membuat perdagangan terasa sebagai bagian dari dunia roleplay kerajaan: player bertransaksi melalui NPC, bukan melalui command shop biasa.

## Prinsip Utama

- Player **tidak memiliki command shop**.
- Semua transaksi dilakukan melalui NPC.
- NPC hanya menjadi front-end/interaksi dunia; logika transaksi ditangani plugin.
- Setiap shop dapat memiliki kategori dan fungsi berbeda seperti Blacksmith, Farmer, Merchant, Cosmetic, dan shop lain yang dapat ditambahkan kemudian.
- Sistem mendukung BUY, SELL, atau BUY + SELL per item.
- Shop memiliki stok nyata.
- Harga fase awal bersifat statis agar mudah dibalance dan diaudit.
- Semua transaksi penting dan perubahan administratif harus dapat diaudit.
- Fokus utama development adalah keamanan transaksi, anti-dupe, dan performa ringan.

## Scope Awal — beta.1

- Integrasi NPC/Citizens.
- NPC-only shop.
- GUI Buy/Sell.
- Stock engine sederhana.
- Harga statis.
- Validasi saldo dan inventory.
- Atomic transaction / proteksi anti-dupe.
- Audit log lokal.
- Discord audit log dasar.
- Tidak ada command shop untuk player.

## Integrasi yang Direncanakan

- Citizens — representasi NPC.
- Vault — economy bridge.
- LuckPerms — permission staff/admin.
- Discord webhook — audit log eksternal.

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md) — tahapan development.
- [`CHANGELOG.md`](CHANGELOG.md) — riwayat perubahan.
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md) — konsep ekonomi.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — rancangan arsitektur plugin.

## Status

**Pre-development / beta.1 planning**

Plugin ini dikembangkan oleh **MenkiPlugcore** untuk project **Vephilim Roleplay**.
