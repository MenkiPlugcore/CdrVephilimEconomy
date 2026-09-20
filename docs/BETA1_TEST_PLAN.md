# beta.1 Test Plan — CdrVephilimEconomy

Dokumen ini dipakai sebelum `beta.1` dianggap siap dipasang sebagai build uji Vephilim Roleplay.

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
5. Restart server untuk beta.1 (belum ada runtime reload).
6. Klik kanan NPC dan pastikan GUI shop terbuka.

## Functional Tests

### NPC-only access

- [ ] Player dapat membuka shop dari NPC yang terdaftar.
- [ ] NPC yang tidak terdaftar tidak membuka shop.
- [ ] Tidak ada `/shop` atau command shop player.
- [ ] Setelah GUI terbuka, player yang berjalan/teleport melewati `npc.max-transaction-distance` tidak dapat bertransaksi.
- [ ] GUI ditutup jika NPC binding tidak ada, NPC despawn, world berbeda, atau player terlalu jauh saat klik transaksi.

### BUY

- [ ] Klik kiri membeli 1 item.
- [ ] Shift + klik kiri membeli bulk amount sesuai config.
- [ ] `bulk-amount` tidak pernah efektif melebihi `max-amount`.
- [ ] Saldo berkurang tepat sesuai harga.
- [ ] Stock berkurang tepat sesuai jumlah beli.
- [ ] Item masuk ke inventory sebagai item yang sesuai.
- [ ] BUY ditolak jika listing tidak mengizinkan BUY.
- [ ] BUY ditolak ketika saldo tidak cukup.
- [ ] BUY ditolak ketika stock tidak cukup.
- [ ] BUY ditolak ketika inventory tidak cukup ruang.

### SELL

- [ ] Klik kanan menjual 1 item.
- [ ] Shift + klik kanan menjual bulk amount sesuai config.
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
- [ ] Config `initial-stock` tidak mereset stock runtime setelah restart.
- [ ] Economy failure tidak menghasilkan item gratis.
- [ ] Kegagalan persistence menghasilkan audit `FAILED` dan mencoba rollback.
- [ ] Player quit menghapus cooldown state tanpa mempengaruhi stock/saldo.

## Stock Persistence / Recovery

- [ ] Setiap persist menghasilkan `stock.yml` yang memiliki `meta.schema` dan `meta.updated-at`.
- [ ] `stock.yml.bak` dibuat sebagai recovery snapshot.
- [ ] Setelah transaksi sukses, nilai pada `stock.yml` sesuai nilai runtime.
- [ ] Temporary snapshot diverifikasi sebelum menggantikan file utama.
- [ ] Restart normal tidak mengubah stock yang sudah tersimpan.
- [ ] Jika `stock.yml` rusak secara sintaks YAML, plugin memulihkan dari `stock.yml.bak`.
- [ ] Jika `stock.yml` hilang tetapi backup valid tersedia, plugin memulihkan file utama dari backup.
- [ ] Jika file utama dan backup sama-sama invalid/tidak dapat dipercaya, plugin disable daripada diam-diam memakai `initial-stock`.
- [ ] Stock numerik di bawah 0 di-clamp ke 0 dan snapshot diperbaiki.
- [ ] Stock numerik di atas `max-stock` di-clamp ke `max-stock` dan snapshot diperbaiki.
- [ ] Stock non-integer/string pada listing valid tidak digunakan sebagai stock runtime dan dinormalisasi ke `initial-stock`.

## Audit

- [ ] Transaksi sukses tercatat di `plugins/CdrVephilimEconomy/logs/audit.log` dengan status `SUCCESS`.
- [ ] Internal transaction failure tercatat dengan status `FAILED`.
- [ ] Jika `audit.log-rejected-transactions: true`, saldo kurang, stock kurang, item kurang, inventory penuh, max-stock, dan mode tidak diizinkan tercatat sebagai `REJECTED`.
- [ ] `BUSY` tidak tercatat secara default ketika `audit.log-busy-rejections: false`.
- [ ] Mengaktifkan `audit.log-busy-rejections` membuat BUSY/cooldown rejection ikut tercatat.
- [ ] Log mencatat transaction ID, player, shop, listing, tipe, jumlah request, unit price, total, dan stock before/after.
- [ ] Jika Discord audit diaktifkan, webhook menerima SUCCESS/FAILED tanpa memblokir thread transaksi.
- [ ] `audit.discord.include-rejected: false` mencegah rejection biasa memenuhi Discord.
- [ ] Mengaktifkan `audit.discord.include-rejected` membuat `REJECTED` ikut dikirim ke Discord.
- [ ] Webhook URL tidak pernah ditulis ke console/audit log.

## Restart / Failure Tests

- [ ] Tutup GUI tanpa transaksi: tidak ada perubahan stock/saldo.
- [ ] Player disconnect setelah transaksi selesai: data tetap konsisten.
- [ ] Restart normal melakukan flush stock tanpa error.
- [ ] Simulasikan `stock.yml` corrupt dengan backup valid: recovery berhasil dan startup log memberi warning recovery.
- [ ] Simulasikan `stock.yml` dan backup corrupt: plugin fail-closed/disable dan tidak menghasilkan stock reset diam-diam.

## Exit Criteria beta.1

`beta.1` baru dianggap lulus jika jalur BUY/SELL utama, NPC-only proximity guard, persistence/recovery stock, config validation, audit trail, dan skenario anti-dupe di atas lolos di server uji. Fitur governance, admin GUI, dynamic pricing, dan market event tetap di luar scope beta.1.
