# beta.5 RC1 Runtime Test Plan

Target: `0.1.0-beta.5-RC1`

## 1. Baseline Upgrade

1. Ganti JAR beta.4 FINAL dengan beta.5 RC1.
2. Restart server normal.
3. Jalankan `/cve status`, `/cve doctor`, `/cve pricing status`, `/cve market status`.
4. Pastikan shop beta.4 existing tetap dapat BUY/SELL.
5. Pastikan `market-events.yml` dibuat otomatis dengan schema v1 jika sebelumnya belum ada.

Expected: tidak ada perubahan harga bila belum ada active event.

## 2. SCARCITY

Base example:

```text
BUY 100
SELL 60
```

Create:

```text
/cve market scarcity iron_crisis blacksmith iron_ingot 1.5 10 Besi menjadi langka di kerajaan!
```

Expected:

```text
BUY 150
SELL 90
```

Jika beta.4 dynamic multiplier juga aktif, event x1.5 diterapkan setelah sampled dynamic quote.

## 3. KINGDOM BUY BONUS

```text
/cve market buybonus grain_order farmer wheat 1.5 10 Kerajaan membeli gandum dengan harga premium!
```

Expected:

- BUY player dari NPC tetap x1.0 event;
- SELL player ke NPC menjadi x1.5 event.

## 4. DISCOUNT

```text
/cve market discount festival_sale blacksmith iron_ingot 0.75 10 Festival kerajaan: harga besi turun sementara!
```

Expected:

- BUY menjadi x0.75 event;
- SELL tidak berubah oleh event tersebut.

## 5. Scope

Test:

```text
blacksmith/iron_ingot
blacksmith/*
*/*
```

Pastikan event tidak bocor ke shop/listing di luar scope.

## 6. Event Stacking Clamp

Aktifkan beberapa event pada listing sama hingga hasil multiplier gabungan >4.0 atau <0.25.

Expected: event multiplier gabungan di-hard-clamp menjadi `0.25..4.0`.

## 7. Stale Quote

1. Player A buka shop sebelum event aktif.
2. Admin create event yang mengubah harga.
3. Player A klik item GUI lama.

Expected: transaksi ditolak `PRICE_CHANGED`; tidak ada uang/item/stock berubah; GUI refresh menunjukkan harga event.

Ulangi dengan event di-end saat GUI lama masih terbuka.

## 8. Persistence / Restart

1. Buat event durasi 30 menit.
2. Restart server setelah beberapa menit.
3. `/cve market show <id>`.

Expected: event masih aktif sampai original `ends-at`, bukan mendapat durasi baru dari restart.

## 9. Natural Expiry

Buat event 1 menit, tunggu lewat `ends-at`.

Expected:

- event tidak lagi memodifikasi quote;
- `/cve market list` menunjukkan event inactive/expired;
- evidence event tidak dihapus otomatis.

## 10. Manual End

```text
/cve market end iron_crisis pasokan besi sudah pulih
```

Expected:

- event langsung inactive;
- runtime reload sukses;
- quote kembali ke baseline beta.4/static;
- broadcast end muncul;
- `market-events.yml.bak` tersedia;
- tidak ada stale `market-events.yml.tmp`.

## 11. Governance

Test akun:

- ECONOMY_STAFF scope `blacksmith`;
- ECONOMY_MANAGER scope `blacksmith`;
- ROYAL_TREASURER scope `blacksmith`;
- ROYAL_TREASURER scope `*`;
- player dengan explicit `cdrvephilimeconomy.market.manage`.

Expected:

- Staff/Manager hanya view event yang sesuai scope;
- Staff/Manager tidak dapat create/end;
- Treasurer `blacksmith` dapat create/end event `blacksmith`, tidak dapat global `*`;
- Treasurer `*` dapat global event;
- explicit `market.manage` bypass role scope.

## 12. Audit / History

Setelah create/end, cek:

```text
logs/admin-audit.log
logs/market-events.log
```

Expected: REQUEST + SUCCESS/REJECTED tercatat; history market start/end tersedia.

Buat admin-audit tidak writable pada environment test. Expected: market mutation ditolak fail-closed.

## 13. Invalid Input

Harus ditolak:

- duplicate event ID;
- ID di luar `[a-z0-9_-]`;
- duration 0 atau >10080;
- SCARCITY / buybonus di luar 1.0..3.0;
- DISCOUNT di luar 0.25..1.0;
- YAML `market-events.yml` invalid.

Invalid event config tidak boleh diam-diam menghasilkan price modifier baru.

## 14. Regression

Ulangi:

- BUY 1 / bulk;
- SELL 1 / bulk;
- beta.4 dynamic quote sampling;
- anti BUY/SELL churn pada listing dynamic;
- pricing governance;
- shop management;
- governance approval/quota;
- restart stock persistence;
- `/cve doctor` dan safety state.
