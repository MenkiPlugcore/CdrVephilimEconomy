# beta.4 RC2 Test Plan

Target: memastikan sampled market quote stabil, state survive restart, dan rapid BUY/SELL reversal tidak dapat dipakai untuk churn harga.

## 1. Compatibility / Dynamic OFF

- Install RC2 di atas data beta.4 RC1.
- Biarkan `pricing.yml.enabled=false`.
- `/cve reload` harus sukses.
- BUY/SELL harus tetap memakai base price dari `shops.yml`.
- `/cve status` harus menunjukkan pricing OFF, bukan error.

## 2. Initial Dynamic Sample

Aktifkan root pricing dan satu listing.

```yaml
enabled: true

stability:
  quote-cooldown-seconds: 30
  min-stock-change-to-resample: 8
  reversal-cooldown-seconds: 15
```

- `/cve reload`.
- Buka NPC shop.
- `market-state.yml` harus dibuat dan memiliki sample listing tersebut.
- GUI harus menampilkan dynamic multiplier/harga.

## 3. Quote Tidak Bergerak Setiap Transaksi

- Catat quote awal.
- Lakukan BUY/SELL yang mengubah stock kurang dari 8 item.
- Buka GUI kembali sebelum cooldown selesai.
- Quote harus tetap menggunakan sampled multiplier lama walaupun current stock sudah berubah.

## 4. Resample Setelah Cooldown + Stock Delta

- Ubah stock kumulatif minimal 8 item dari sampled stock.
- Tunggu >30 detik.
- Buka GUI kembali.
- `market-state.yml` harus memperbarui `sampled-stock`, `multiplier`, `sampled-at-epoch-ms`, dan menaikkan `sample-count`.

## 5. Cooldown Saja Tidak Cukup

- Setelah sample baru, tunggu >30 detik tetapi ubah stock <8 item.
- Quote harus tetap sama karena minimum stock movement belum terpenuhi.

## 6. Durable Restart Continuity

- Ambil sample aktif lalu restart server sebelum 30 detik habis.
- Setelah restart, quote harus tetap memakai sample yang sama dari `market-state.yml`.
- Restart tidak boleh membuat multiplier baru hanya karena proses Java baru dimulai.

## 7. Policy Fingerprint Invalidation

- Dengan sample aktif, ubah salah satu: `target-stock-ratio`, `sensitivity`, `min-multiplier`, `max-multiplier`, atau listing `max-stock`.
- `/cve reload`.
- Quote pertama setelah reload harus membuat sample baru walaupun market cooldown lama belum habis.

## 8. Anti BUY/SELL Churn

- BUY listing dynamic berhasil.
- Dalam <15 detik coba SELL listing yang sama dengan player yang sama.
- SELL harus ditolak dengan pesan market churn dan tidak mengubah uang/item/stock.
- Setelah 15 detik, SELL boleh diproses normal bila seluruh validasi lain terpenuhi.
- BUY -> BUY atau SELL -> SELL tidak boleh diblokir oleh reversal guard.

## 9. Listing Isolation

- BUY `blacksmith/iron_ingot`.
- Segera SELL listing dynamic lain.
- Transaksi listing lain tidak boleh diblokir karena churn guard scoped per player + shop + listing.

## 10. Corrupt Market State Drill (staging only)

Jangan dilakukan di production.

- Backup data folder.
- Rusakkan `market-state.yml`.
- Restart/reload.
- Dynamic pricing harus masuk `BLOCKED`/fallback static base price.
- Core shop tetap tidak boleh menghasilkan harga liar.
- Plugin tidak boleh diam-diam menimpa corrupt evidence dengan state market baru.

## 11. Stale Quote Regression

- Player A dan B buka GUI pada quote yang sama.
- Buat kondisi sampai sample market berubah.
- Player B klik item dari GUI quote lama.
- Transaction harus `PRICE_CHANGED`, tanpa mutation, lalu GUI refresh.

## 12. Core Regression

Ulang:

```text
BUY 1
BUY 16
SELL 1
SELL 16
restart
/cve status
/cve doctor
/cve safety status
```

Stock, balance, journal, dan safety baseline beta.1-beta.3 harus tetap konsisten.

## Exit Criteria RC2

- sampled multiplier survive restart;
- quote tidak bergerak setiap klik;
- resample hanya setelah cooldown + meaningful stock delta atau policy fingerprint berubah;
- opposite-direction churn guard bekerja;
- corrupt/unwritable market state tidak membuat dynamic price liar;
- regression BUY/SELL baseline tetap aman.
