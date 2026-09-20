# Changelog

Semua perubahan penting pada `CdrVephilimEconomy` akan dicatat di file ini.

Format mengikuti prinsip Keep a Changelog dan versioning proyek akan menggunakan pola pre-release sampai plugin siap production.

## [Unreleased]

### Added
- Inisialisasi dokumentasi project.
- Roadmap development awal.
- Konsep NPC-only economy untuk Vephilim Roleplay.

## [0.1.0-beta.1-RC2]

### Added
- Command admin `/cve reload` untuk reload runtime tanpa restart server.
- Command admin `/cve status` untuk melihat ringkasan version, shop, NPC binding, listing, dan stock entry.
- Alias `/veconomy` untuk command admin.
- Runtime stock reconcile yang mempertahankan stock listing existing, menginisialisasi listing baru dari `initial-stock`, menghapus entry listing yang sudah dihapus, dan clamp stock jika `max-stock` diperkecil.
- Strict YAML loading untuk `shops.yml` agar syntax error tidak berubah menjadi registry kosong secara diam-diam.

### Changed
- Reload runtime membangun registry, transaction service, GUI service, audit service, dan listener baru lalu mengganti runtime lama tanpa restart server.
- Semua GUI shop aktif ditutup saat reload agar holder/listing lama tidak dapat dipakai setelah konfigurasi berubah.
- `config.yml` ikut direload sehingga cooldown, bulk amount, max amount, NPC distance, audit policy, Discord webhook, serta messages dapat diperbarui tanpa restart.
- Reload ditolak bila `shops.yml` invalid atau memiliki rejected definition; runtime lama tetap aktif.
- Artifact/version candidate dinaikkan menjadi `0.1.0-beta.1-RC2`.

### Security / Safety
- `/cve reload` hanya dapat dipakai oleh `cdrvephilimeconomy.admin`.
- Stock reconcile bersifat rollback-on-persist-failure; jika snapshot baru gagal ditulis, stock runtime lama dipulihkan.
- Runtime reload tidak mengandalkan `/reload`, PlugMan, atau plugin hot-unload pihak ketiga.
- Reload dengan konfigurasi shop invalid tidak mengganti binding NPC aktif yang sebelumnya sehat.

### Status
- `0.1.0-beta.1-RC2` menggantikan RC1 sebagai kandidat runtime QA.
- Runtime QA tetap wajib sebelum `beta.1` ditandai final.

## [0.1.0-beta.1-RC1]

### Added
- Bootstrap project Maven untuk Paper 1.21.11 / Java 21.
- Integrasi Citizens `2.0.43-SNAPSHOT` untuk membuka shop dari NPC terdaftar.
- Integrasi Vault API untuk saldo, withdraw, deposit, format currency, dan tampilan saldo pada GUI.
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
- `stock.yml.initialized` marker untuk mendeteksi kehilangan seluruh snapshot setelah storage pernah diinisialisasi.
- Startup diagnostics untuk jumlah shop, shop aktif, NPC binding, listing, stock entry, dan rejected definition.
- Clean shutdown yang menutup GUI shop aktif, membersihkan transaction state, flush stock, lalu menutup audit writer.

### Changed
- `bulk-amount` sekarang otomatis dinormalisasi agar tidak melebihi `max-amount`.
- Shop/listing ID dinormalisasi ke lowercase dan hanya menerima `[a-z0-9_-]` maksimal 48 karakter.
- Definisi shop invalid sekarang ditolak secara fail-closed tanpa ikut masuk registry aktif.
- Duplicate Citizens NPC binding ditolak sebelum shop dimasukkan ke registry.
- Validasi startup diperketat untuk display name, NPC ID, inventory size, material, mode, harga finite/non-negatif, dan stock bounds.
- Startup log sekarang menampilkan jumlah shop aktif, definition yang ditolak, transaction guard efektif, batas jarak NPC, audit policy, dan storage diagnostics.
- Setiap klik transaksi memverifikasi ulang NPC masih spawn, binding masih valid, world sama, dan player masih berada di jarak yang diizinkan.
- Persistence stock sekarang menulis temporary snapshot, memverifikasi hasil serialisasi, lalu melakukan atomic replace bila filesystem mendukung.
- Snapshot stock corrupt tidak lagi diam-diam dianggap stock kosong; plugin mencoba backup dan jika recovery gagal akan fail-closed.
- Jika semua snapshot stock hilang setelah storage pernah diinisialisasi, plugin fail-closed daripada memakai `initial-stock` dan berisiko menggandakan supply.
- Nilai stock non-integer/out-of-bounds dinormalisasi secara eksplisit dan ditulis ulang ke snapshot sehat.
- Discord audit tetap menerima SUCCESS/FAILED secara default, tetapi REJECTED hanya ikut jika diaktifkan.
- GUI sekarang menampilkan saldo player, stock, harga, jumlah bulk efektif, serta status stok habis/penuh.
- Artifact/version candidate dinaikkan dari SNAPSHOT ke `0.1.0-beta.1-RC1`.

### Security / Safety
- Player tidak memiliki command shop.
- GUI menggunakan custom inventory holder dan memblokir click/drag mutation.
- SELL beta.1 hanya menerima item vanilla polos yang `isSimilar` dengan template material sehingga custom meta/enchant tidak tersapu sebagai item biasa.
- Batas jumlah transaksi tidak lagi hardcoded sebagai satu-satunya kontrol; nilai konfigurasi tetap dipagari hard limit internal.
- Konfigurasi shop yang ambigu/duplikat tidak boleh menghasilkan binding NPC parsial.
- Player tidak dapat membuka NPC shop lalu berjalan atau teleport jauh untuk tetap bertransaksi dari jarak jauh.
- Stock persistence menggunakan backup/recovery dan plugin memilih disable daripada mereset stock secara diam-diam ketika snapshot utama dan backup sama-sama tidak dapat dipercaya.
- Marker initialization mencegah kondisi semua file stock hilang berubah menjadi bootstrap stock baru tanpa peringatan.
- Penolakan transaksi penting memiliki jejak audit lokal untuk membantu investigasi exploit tanpa menjadikan BUSY spam sebagai default.
- Cooldown map tidak menahan UUID player selamanya setelah player keluar dan seluruh transaction state dibersihkan saat plugin disable.

### Status
- `0.1.0-beta.1-RC1` adalah kandidat runtime QA pertama.
- Build Maven berjalan melalui GitHub Actions untuk setiap update branch `dev/beta.1`.
- Runtime QA di server Paper masih wajib sebelum `beta.1` ditandai final.
