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
- Persistent stock melalui `stock.yml`.
- Validasi saldo, inventory capacity, item yang dapat dijual, stock, dan max stock.
- Listing-level transaction lock dan player transaction cooldown.
- Best-effort rollback untuk kegagalan mutation/persistence.
- Local audit log dengan transaction ID.
- Discord webhook audit async.
- GitHub Actions build validation dan artifact JAR.
- `docs/BETA1_TEST_PLAN.md` untuk runtime QA/anti-dupe regression.
- Configurable `transaction.max-amount` dengan hard safety clamp 1-2304.
- Pesan khusus untuk transaksi yang tidak diizinkan.
- NPC proximity guard melalui `npc.max-transaction-distance` agar GUI yang sudah terbuka tidak bisa dipakai untuk remote trading.
- `stock.yml.bak` sebagai recovery snapshot stock.
- Metadata schema dan timestamp pada snapshot stock.
- Strict YAML validation untuk stock utama, backup, dan temporary snapshot.
- Audit `REJECTED` untuk transaksi yang gagal validasi bisnis, dapat dikontrol melalui config.
- Config terpisah untuk menahan spam audit BUSY/cooldown.
- Opsi Discord `include-rejected` agar penolakan normal tidak memenuhi webhook secara default.
- Cleanup cooldown state saat player keluar dari server.

### Changed
- `bulk-amount` sekarang otomatis dinormalisasi agar tidak melebihi `max-amount`.
- Shop/listing ID dinormalisasi ke lowercase dan hanya menerima `[a-z0-9_-]` maksimal 48 karakter.
- Definisi shop invalid sekarang ditolak secara fail-closed tanpa ikut masuk registry aktif.
- Duplicate Citizens NPC binding ditolak sebelum shop dimasukkan ke registry.
- Validasi startup diperketat untuk display name, NPC ID, inventory size, material, mode, harga finite/non-negatif, dan stock bounds.
- Startup log sekarang menampilkan jumlah shop aktif, definition yang ditolak, transaction guard efektif, batas jarak NPC, dan audit policy.
- Setiap klik transaksi memverifikasi ulang NPC masih spawn, binding masih valid, world sama, dan player masih berada di jarak yang diizinkan.
- Persistence stock sekarang menulis temporary snapshot, memverifikasi hasil serialisasi, lalu melakukan atomic replace bila filesystem mendukung.
- Snapshot stock corrupt tidak lagi diam-diam dianggap stock kosong; plugin mencoba backup dan jika recovery gagal akan fail-closed.
- Nilai stock non-integer/out-of-bounds dinormalisasi secara eksplisit dan ditulis ulang ke snapshot sehat.
- Discord audit tetap menerima SUCCESS/FAILED secara default, tetapi REJECTED hanya ikut jika diaktifkan.

### Security / Safety
- Player tidak memiliki command shop.
- GUI menggunakan custom inventory holder dan memblokir click/drag mutation.
- SELL beta.1 hanya menerima item vanilla polos yang `isSimilar` dengan template material sehingga custom meta/enchant tidak tersapu sebagai item biasa.
- Batas jumlah transaksi tidak lagi hardcoded sebagai satu-satunya kontrol; nilai konfigurasi tetap dipagari hard limit internal.
- Konfigurasi shop yang ambigu/duplikat tidak boleh menghasilkan binding NPC parsial.
- Player tidak dapat membuka NPC shop lalu berjalan atau teleport jauh untuk tetap bertransaksi dari jarak jauh.
- Stock persistence menggunakan backup/recovery dan plugin memilih disable daripada mereset stock secara diam-diam ketika snapshot utama dan backup sama-sama tidak dapat dipercaya.
- Penolakan transaksi penting memiliki jejak audit lokal untuk membantu investigasi exploit tanpa menjadikan BUSY spam sebagai default.
- Cooldown map tidak menahan UUID player selamanya setelah player keluar.

### Status
- Build Maven berjalan melalui GitHub Actions untuk setiap update branch `dev/beta.1`.
- Runtime QA di server Paper belum menjadi exit criteria yang selesai; beta.1 belum ditandai sebagai release final.
