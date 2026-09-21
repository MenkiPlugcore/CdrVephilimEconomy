# beta.5 RC3 Test Plan

## 1. Startup / Listener Sanity

1. Install `0.1.0-beta.5-RC3` and restart server penuh.
2. Pastikan tidak ada error enable.
3. Jalankan governed direct price/stock mutation yang masih di bawah limit role.
4. Mutation harus dieksekusi satu kali, tidak langsung diblokir cooldown akibat duplicate quota reservation.
5. Jalankan `/cve market supply status`; command harus ditangani satu kali dan tidak menampilkan duplicate response.

## 2. Natural Expiry

Buat event pendek:

```text
/cve market discount rc3_expiry_test blacksmith iron_ingot 0.90 1 RC3 expiry test dimulai
```

Expected:

- sebelum `ends-at`, quote event aktif;
- setelah `ends-at`, quote kembali baseline segera berdasarkan timestamp, tanpa menunggu scheduler;
- maksimal sekitar 60 detik kemudian lifecycle recorder memproses event;
- hanya satu broadcast automatic expiry muncul.

## 3. Durable Expiry Evidence

Setelah automatic expiry, periksa `market-events.yml`:

```text
expiry-recorded-at: <timestamp>
expiry-recorded-by: SYSTEM
```

Lalu:

1. restart server;
2. tunggu lebih dari satu lifecycle interval;
3. event tidak boleh mengirim expiry broadcast kedua;
4. `/cve market show rc3_expiry_test` harus menampilkan `expiryRecordedAt`.

## 4. Market Status

Sebelum lifecycle recorder menangani event yang baru expired, `/cve market status` boleh menunjukkan `expiryPending > 0`.

Setelah lifecycle pass berhasil:

```text
expiryPending=0
```

untuk semua expiry yang sudah direkam.

## 5. History / Audit

Periksa:

```text
logs/market-events.log
logs/admin-audit.log
```

Natural expiry seharusnya menghasilkan `MARKET_EVENT_EXPIRED` dengan event id, type, scope, `ended-at`, dan lifecycle timestamp.

`market-events.yml` tetap dianggap source of truth jika secondary log gagal ditulis.

## 6. Explicit End Regression

Buat event berdurasi lebih panjang, lalu:

```text
/cve market end <id> RC3 explicit-end regression
```

Expected:

- event berhenti segera;
- existing safe runtime reload path tetap bekerja;
- tidak muncul automatic natural-expiry broadcast di kemudian hari karena event sudah `enabled: false`.

## 7. Supply RC2 Regression

Jalankan:

```text
/cve market supply create rc3_supply_test <shop> <listing> <amount> RC3 supply regression
```

Expected:

- stock bertambah tepat satu kali;
- restart tidak menambah ulang stock;
- ID sama tidak dapat digunakan lagi;
- `market-supply.yml` tetap terminal/recoverable sesuai RC2 contract.

## 8. Invalid YAML Safety

Di staging saja:

1. backup `market-events.yml`;
2. buat YAML invalid;
3. tunggu lifecycle tick.

Expected:

- console melaporkan lifecycle error;
- file invalid tidak ditimpa otomatis;
- tidak ada expiry broadcast palsu;
- setelah file dipulihkan, lifecycle kembali sehat pada tick berikutnya.

## 9. BUY / SELL Regression

Setelah seluruh test event selesai:

- BUY 1;
- BUY bulk;
- SELL 1;
- SELL bulk;
- restart;
- cek stock persistence;
- cek transaction audit;
- pastikan stale-price guard masih bekerja ketika quote berubah.

RC3 dianggap lolos bila tidak ada duplicate governance reservation, duplicate supply command response, duplicate expiry broadcast setelah restart, atau regression pada transaction/supply safety.
