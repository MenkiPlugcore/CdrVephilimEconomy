# beta.5 RC2 Test Plan — Governed Supply Event

Target build: `0.1.0-beta.5-RC2`

## 1. Baseline

1. Start server dengan data beta.5 RC1 existing.
2. `/cve status` dan `/cve doctor` tidak menunjukkan regression core economy.
3. RC1 price market events masih bekerja.

## 2. Basic Supply Success

Pilih listing dengan headroom stock yang jelas.

```text
/cve market supply create test_wheat_01 farmer wheat 50 Kiriman gandum tiba!
```

Expected:

- stock bertambah tepat 50;
- tidak melewati max-stock;
- broadcast RP muncul sekali;
- `market-supply.yml` membuat entry `COMPLETED`;
- `logs/market-supply.log` mendapat history;
- admin audit memiliki request/success;
- stock admin audit existing juga mencatat mutation.

## 3. Duplicate ID

Jalankan lagi ID yang sama.

Expected: ditolak dan stock tidak berubah.

## 4. Max Stock Guard

Buat request sehingga `current + amount > max-stock`.

Expected: ditolak tanpa silent clamp dan tanpa stock change.

## 5. Invalid Scope

Tes shop/listing tidak ada dan wildcard:

```text
/cve market supply create bad1 * wheat 10
/cve market supply create bad2 farmer * 10
```

Expected: ditolak.

## 6. Governance

- ECONOMY_STAFF scope farmer: list/show boleh pada farmer, create ditolak.
- ECONOMY_MANAGER scope farmer: list/show boleh, create ditolak.
- ROYAL_TREASURER scope farmer: create farmer boleh, shop lain ditolak.
- `market.view`: bisa melihat semua supply ledger tetapi tidak create.
- `market.manage`: create + recovery override.

## 7. Safety Stop

Aktifkan/test pada environment aman dengan economy safety stop aktif.

Expected: supply create ditolak.

## 8. Persistence / Restart

Setelah supply COMPLETED, restart server.

Expected:

- stock tetap;
- ledger tetap COMPLETED;
- tidak ada replay stock;
- tidak ada broadcast ulang.

## 9. Crash-Recovery Drill — PREPARED Before Apply

Hanya di test server. Buat PREPARED evidence dengan stock masih sama dengan `before-stock` lalu restart.

Expected: startup/command refresh mengubah evidence menjadi `RECOVERED_NOT_APPLIED`; stock tidak ditambah.

## 10. Crash-Recovery Drill — Applied Before Finalize

Hanya di test server. Siapkan evidence PREPARED/APPLIED sementara durable stock sudah sama dengan `after-stock`, lalu restart.

Expected: evidence menjadi `RECOVERED_APPLIED`; stock tidak ditambah lagi.

## 11. Ambiguous Recovery

Buat pending evidence dengan current stock yang bukan `before-stock` maupun `after-stock`.

Expected:

- supply engine `BLOCKED`;
- supply baru ditolak;
- tidak ada replay otomatis.

Setelah stock direkonsiliasi ke salah satu evidence value:

```text
/cve market supply recover <id> applied CONFIRM
```

atau:

```text
/cve market supply recover <id> not-applied CONFIRM
```

Expected: hanya mode yang cocok dengan current stock yang diterima.

## 12. Regression BUY/SELL

Setelah supply selesai:

- BUY 1 dan BUY bulk;
- SELL 1 dan SELL bulk;
- dynamic pricing bila aktif tetap membaca stock baru;
- stale-price protection tetap berlaku bila perubahan stock memicu sample harga baru.

## Pass Criteria

RC2 dianggap lolos bila supply tidak dapat diduplikasi oleh restart/recovery, governance scope benar, max-stock selalu dipatuhi, dan core transaction beta.1–beta.4 tidak mengalami regression.
