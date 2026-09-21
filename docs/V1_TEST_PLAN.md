# CdrVephilimEconomy v1.0.0 Production Test Plan

Gunakan copy/staging data server untuk destructive recovery tests.

## A. Startup / dependency

- Paper 1.21.11 + Java 21.
- Citizens aktif.
- Vault + economy provider aktif.
- Plugin enable tanpa stacktrace.
- `/cve status` dan `/cve doctor` dapat dijalankan.

## B. Core transaction regression

Untuk satu listing BUY, SELL, dan BUY_SELL:

- BUY satuan.
- BUY bulk.
- SELL satuan.
- SELL bulk.
- saldo tidak cukup.
- inventory penuh.
- stock tidak cukup.
- klik cepat/double-click.
- dua player pada listing sama.
- stale GUI setelah harga/stock berubah.

Invariant: saldo, item, stock, transaction journal, dan audit harus konsisten.

## C. Restart / crash evidence

- restart normal setelah transaksi sukses;
- restart dengan active market event;
- restart setelah supply COMPLETED;
- pending transaction evidence harus mengaktifkan safety stop;
- safety lock tidak hilang karena restart;
- explicit recovery harus mengarsipkan evidence sebelum unlock.

## D. Governance persistence RC1

- beta.5 `governance.yml` valid tanpa marker -> load + marker dibuat;
- restart -> assignments sama;
- primary hilang + marker + valid backup -> recover backup;
- primary hilang + marker tanpa backup -> fail-closed;
- corrupt backup pada lost-primary scenario -> fail-closed;
- grant/revoke normal lalu restart -> state sama.

## E. Market runtime bootstrap

- satu supply command menghasilkan satu mutation saja;
- satu expiry menghasilkan satu lifecycle evidence/broadcast saja;
- `/cve reload` tidak menggandakan listener/task;
- restart server tidak menggandakan completed shipment.

## F. Official NPC catalog

Fresh-install template:

```text
food
ore
farmer
fisherman
```

- semua default disabled dan unbound;
- food hanya BUY;
- ore/farmer/fisherman hanya SELL;
- blacklist tidak muncul sebagai listing;
- harga sesuai `docs/VEPHILIM_NPC_CATALOG.md`.

## G. Production exit criteria

Production `1.0.0` hanya boleh dipromosikan setelah:

- exact-head CI success;
- core regression pass;
- governance lost-state recovery pass;
- supply/expiry single-bootstrap pass;
- concurrency/stress test pass;
- migration/versioning audit selesai;
- permission/config/install docs selesai;
- final security audit tidak memiliki blocker terbuka.
