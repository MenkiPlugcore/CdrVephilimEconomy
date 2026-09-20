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

### BUY

- [ ] Klik kiri membeli 1 item.
- [ ] Shift + klik kiri membeli bulk amount sesuai config.
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

## Audit

- [ ] Transaksi sukses tercatat di `plugins/CdrVephilimEconomy/logs/audit.log`.
- [ ] Log mencatat transaction ID, player, shop, listing, tipe, jumlah, total, dan stock before/after.
- [ ] Jika Discord audit diaktifkan, webhook menerima log tanpa memblokir thread transaksi.
- [ ] Webhook URL tidak pernah ditulis ke console/audit log.

## Restart / Failure Tests

- [ ] Tutup GUI tanpa transaksi: tidak ada perubahan stock/saldo.
- [ ] Player disconnect setelah transaksi selesai: data tetap konsisten.
- [ ] Restart normal melakukan flush stock tanpa error.
- [ ] `stock.yml` rusak/tidak valid tidak menyebabkan dupe; plugin harus fail-safe atau meng-clamp nilai yang dapat dibaca.

## Exit Criteria beta.1

`beta.1` baru dianggap lulus jika jalur BUY/SELL utama, persistence stock, dan skenario anti-dupe di atas lolos di server uji. Fitur governance, admin GUI, dynamic pricing, dan market event tetap di luar scope beta.1.
