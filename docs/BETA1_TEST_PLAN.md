# beta.1 Test Plan — CdrVephilimEconomy

Dokumen ini dipakai sebelum `beta.1` dianggap siap dipasang sebagai build uji Vephilim Roleplay.

Target build saat ini: `0.1.0-beta.1-RC4`.

## Prasyarat

- Paper 1.21.11 / Java 21.
- Citizens yang kompatibel dengan server.
- Vault.
- Economy provider yang terdaftar melalui Vault.
- Minimal satu Citizens NPC untuk shop uji.

## Setup Uji

1. Jalankan plugin sekali agar `config.yml` dan `shops.yml` dibuat.
2. Ambil ID NPC Citizens yang akan dijadikan pedagang.
3. Isi `npc-id` pada shop uji.
4. Ubah `enabled: true`.
5. Jalankan `/cve reload`.
6. Klik kanan NPC dan pastikan GUI shop terbuka tanpa restart server.

## Runtime Reload

- [ ] `/cve reload` dapat menerapkan perubahan `shops.yml` tanpa restart server.
- [ ] `/cve reload` dapat menerapkan perubahan `config.yml` tanpa restart server.
- [ ] GUI shop aktif ditutup saat reload.
- [ ] NPC binding berubah sesuai `npc-id` terbaru setelah reload.
- [ ] Harga BUY/SELL berubah sesuai config terbaru setelah reload.
- [ ] `bulk-amount`, cooldown, max amount, dan NPC distance berubah sesuai config terbaru.
- [ ] Stock listing existing tetap dipertahankan setelah reload.
- [ ] Listing baru mulai dari `initial-stock`.
- [ ] `shops.yml` dengan syntax YAML invalid membuat reload gagal dan runtime lama tetap aktif.
- [ ] Definisi shop invalid membuat reload gagal dan runtime lama tetap aktif.
- [ ] `config.yml` dengan syntax YAML invalid membuat reload gagal dan runtime lama tetap aktif.
- [ ] `/cve status` tetap menunjukkan runtime lama setelah reload gagal.
- [ ] Jika safety stop sudah aktif, `/cve reload` tidak mereset safety latch.

## Mode Transaksi

`mode` adalah sumber kebenaran arah transaksi:

- `BUY` = hanya dapat dibeli player.
- `SELL` = hanya dapat dijual player.
- `BUY_SELL` = dapat dibeli dan dijual.

Harga saja tidak mengaktifkan arah transaksi.

- [ ] Listing `mode: SELL` tetap tidak dapat dibeli walau `buy-price > 0`.
- [ ] Kondisi di atas menghasilkan warning console setelah startup/reload.
- [ ] Mengubah listing menjadi `mode: BUY_SELL` lalu `/cve reload` langsung mengaktifkan BUY tanpa restart.
- [ ] Listing `mode: BUY` tetap tidak dapat dijual walau `sell-price > 0` dan menghasilkan warning console.

## Startup Diagnostics

- [ ] Console menampilkan version RC4 saat plugin enable.
- [ ] Console menampilkan jumlah shop, shop enabled, NPC binding, listing, stock entry, rejected definition, config warning, dan safety state.
- [ ] Jika tidak ada active NPC binding, plugin tetap enable tetapi memberi warning yang jelas.
- [ ] Vault economy provider yang dipakai tercetak di console.
- [ ] Nilai transaction guard efektif (cooldown, bulk, max amount, NPC distance) tercetak di console.
- [ ] `/cve status` menampilkan `safety=OK` pada kondisi normal.

## Functional Tests

### NPC-only access

- [ ] Player dapat membuka shop dari NPC yang terdaftar.
- [ ] NPC yang tidak terdaftar tidak membuka shop.
- [ ] Tidak ada `/shop` atau command shop player.
- [ ] Setelah GUI terbuka, player yang berjalan/teleport melewati `npc.max-transaction-distance` tidak dapat bertransaksi.
- [ ] GUI ditutup jika NPC binding tidak ada, NPC despawn, world berbeda, atau player terlalu jauh saat klik transaksi.

### GUI feedback

- [ ] GUI menampilkan nama item, stock, saldo player, harga beli/jual, dan jumlah bulk efektif.
- [ ] Listing BUY dengan stock 0 menampilkan status `STOK HABIS`.
- [ ] Listing SELL dengan stock mencapai `max-stock` menampilkan status `STOK PEDAGANG PENUH`.
- [ ] Setelah transaksi sukses, GUI refresh dan saldo/stock yang tampil berubah sesuai transaksi.

### BUY

- [x] Klik kiri membeli 1 item — lolos pengujian awal user pada Blacksmith.
- [x] Shift + klik kiri membeli bulk 16 — lolos pengujian awal user pada Blacksmith.
- [ ] `bulk-amount` tidak pernah efektif melebihi `max-amount`.
- [ ] Saldo berkurang tepat sesuai harga.
- [ ] Stock berkurang tepat sesuai jumlah beli.
- [ ] Item masuk ke inventory sebagai item yang sesuai.
- [ ] BUY ditolak jika listing tidak mengizinkan BUY.
- [ ] BUY ditolak ketika saldo tidak cukup.
- [ ] BUY ditolak ketika stock tidak cukup.
- [ ] BUY ditolak ketika inventory tidak cukup ruang.

### SELL

- [x] Klik kanan menjual 1 item — lolos pengujian awal user pada Blacksmith.
- [x] Shift + klik kanan menjual bulk 16 — lolos pengujian awal user pada Blacksmith.
- [ ] Saldo bertambah tepat sesuai harga.
- [ ] Stock bertambah tepat sesuai jumlah jual.
- [ ] SELL ditolak jika listing tidak mengizinkan SELL.
- [ ] SELL ditolak ketika player tidak memiliki item polos yang cukup.
- [ ] SELL ditolak jika transaksi melewati `max-stock`.
- [ ] Item dengan custom meta/enchant tidak ikut terjual sebagai item vanilla polos pada beta.1.

## Config Validation

- [ ] Shop ID dengan karakter di luar `[a-z0-9_-]` ditolak.
- [ ] Listing ID invalid ditolak bersama definisi shop terkait.
- [ ] `npc-id < -1` ditolak.
- [ ] Size GUI yang bukan kelipatan 9 atau di luar 9-54 ditolak.
- [ ] Material invalid/AIR ditolak.
- [ ] Mode selain BUY/SELL/BUY_SELL ditolak.
- [ ] Harga negatif, nol pada mode aktif, NaN/Infinity, atau stock bounds invalid ditolak.
- [ ] Slot listing negatif ditolak.
- [ ] Slot listing `>= size` ditolak.
- [ ] Duplicate slot dalam satu shop ditolak.
- [ ] Duplicate NPC ID antar shop aktif ditolak tanpa menghasilkan binding parsial.
- [ ] Shop invalid tidak ikut dimuat ke stock runtime.

## Anti-abuse / Consistency

- [ ] Spam klik tidak menghasilkan transaksi ganda di luar transaksi yang sah.
- [ ] Double-click inventory tidak memindahkan item shop GUI.
- [ ] Drag item ke GUI shop diblokir.
- [ ] Shift click dari inventory player tidak memasukkan item ke GUI shop.
- [ ] Stock tidak pernah menjadi negatif.
- [ ] Stock tidak pernah melewati `max-stock`.
- [ ] Restart server mempertahankan stock dari `stock.yml`.
- [ ] Config `initial-stock` tidak mereset stock runtime setelah restart/reload.
- [ ] Economy failure tidak menghasilkan item gratis.
- [ ] Kegagalan persistence menghasilkan audit `FAILED` dan mencoba rollback.
- [ ] Player quit menghapus cooldown state tanpa mempengaruhi stock/saldo.

## Transaction Safety Stop / Circuit Breaker

Bagian ini sengaja untuk failure injection/staging; jangan dilakukan di server production dengan data penting.

- [ ] Kondisi normal menunjukkan `/cve status` dengan `safety=OK`.
- [ ] Simulasikan kegagalan write `stock.yml` saat BUY/SELL; transaksi gagal dan safety latch menjadi `STOPPED`.
- [ ] Setelah safety stop aktif, semua transaksi BUY/SELL berikutnya ditolak tanpa mutation saldo/item/stock.
- [ ] GUI ditutup ketika player mencoba transaksi saat safety stop aktif.
- [ ] Player menerima `messages.safety-stop`.
- [ ] `/cve status` menampilkan timestamp stop dan ringkasan reason.
- [ ] `/cve reload` tetap dapat reload config/shop tetapi safety latch tetap STOPPED.
- [ ] Pada BUY persistence failure, jika item rollback gagal, refund tidak dilakukan otomatis sehingga tidak tercipta item gratis + uang kembali.
- [ ] Pada SELL persistence failure, jika payout rollback gagal, item tidak dikembalikan otomatis sehingga tidak tercipta kombinasi payout + item kembali.
- [ ] Failure transaction yang memicu safety stop tercatat sebagai `FAILED` dengan intended total dan detail compensation.
- [ ] Setelah investigasi/fix storage dan restart server/plugin, safety kembali `OK` dan stock/saldo diverifikasi manual sebelum membuka ekonomi lagi.

## Stock Persistence / Recovery

- [ ] First boot menghasilkan `stock.yml`, `stock.yml.bak`, dan `stock.yml.initialized`.
- [ ] Setiap persist menghasilkan `stock.yml` yang memiliki `meta.schema` dan `meta.updated-at`.
- [ ] `stock.yml.bak` dapat dipakai sebagai recovery snapshot.
- [ ] Setelah transaksi sukses, nilai pada `stock.yml` sesuai nilai runtime.
- [ ] Temporary snapshot diverifikasi sebelum menggantikan file utama.
- [ ] Restart normal tidak mengubah stock yang sudah tersimpan.
- [ ] Jika `stock.yml` rusak secara sintaks YAML, plugin memulihkan dari `stock.yml.bak`.
- [ ] Jika `stock.yml` hilang tetapi backup valid tersedia, plugin memulihkan file utama dari backup.
- [ ] Jika file utama dan backup sama-sama invalid/tidak dapat dipercaya, plugin disable daripada diam-diam memakai `initial-stock`.
- [ ] Setelah storage pernah diinisialisasi, hapus `stock.yml`, backup, dan temp tetapi biarkan `stock.yml.initialized`: plugin wajib fail-closed dan tidak bootstrap ulang supply.
- [ ] Stock numerik di bawah 0 di-clamp ke 0 dan snapshot diperbaiki.
- [ ] Stock numerik di atas `max-stock` di-clamp ke `max-stock` dan snapshot diperbaiki.
- [ ] Stock non-integer/string pada listing valid tidak digunakan sebagai stock runtime dan dinormalisasi ke `initial-stock`.

## Audit

- [ ] Transaksi sukses tercatat di `plugins/CdrVephilimEconomy/logs/audit.log` dengan status `SUCCESS`.
- [ ] Internal transaction failure tercatat dengan status `FAILED`.
- [ ] `FAILED` mencatat intended transaction total untuk membantu rekonsiliasi.
- [ ] Jika `audit.log-rejected-transactions: true`, saldo kurang, stock kurang, item kurang, inventory penuh, max-stock, dan mode tidak diizinkan tercatat sebagai `REJECTED`.
- [ ] `BUSY` tidak tercatat secara default ketika `audit.log-busy-rejections: false`.
- [ ] Mengaktifkan `audit.log-busy-rejections` membuat BUSY/cooldown rejection ikut tercatat.
- [ ] Log mencatat transaction ID, player, shop, listing, tipe, jumlah request, unit price, total, dan stock before/after.
- [ ] Jika Discord audit diaktifkan, webhook menerima SUCCESS/FAILED tanpa memblokir thread transaksi.
- [ ] `audit.discord.include-rejected: false` mencegah rejection biasa memenuhi Discord.
- [ ] Mengaktifkan `audit.discord.include-rejected` membuat `REJECTED` ikut dikirim ke Discord.
- [ ] Webhook URL tidak pernah ditulis ke console/audit log.

## Restart / Shutdown Tests

- [ ] Tutup GUI tanpa transaksi: tidak ada perubahan stock/saldo.
- [ ] Player disconnect setelah transaksi selesai: data tetap konsisten.
- [ ] Restart normal melakukan flush stock tanpa error.
- [ ] Saat plugin/server disable, semua GUI CdrVephilimEconomy yang masih terbuka ditutup.
- [ ] Shutdown log mengonfirmasi stock snapshot berhasil di-flush.
- [ ] Enable kembali setelah shutdown bersih mempertahankan saldo dan stock terakhir.
- [ ] Simulasikan `stock.yml` corrupt dengan backup valid: recovery berhasil dan startup log memberi warning recovery.
- [ ] Simulasikan `stock.yml` dan backup corrupt: plugin fail-closed/disable dan tidak menghasilkan stock reset diam-diam.

## Exit Criteria beta.1

`beta.1` baru dianggap lulus jika jalur BUY/SELL utama, safe runtime reload, NPC-only proximity guard, persistence/recovery stock, config validation, audit trail, clean shutdown, safety-stop behavior, dan skenario anti-dupe di atas lolos di server uji. Fitur governance, admin GUI, dynamic pricing, dan market event tetap di luar scope beta.1.
