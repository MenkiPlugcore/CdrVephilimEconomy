# CdrVephilimEconomy 0.1.0-beta.5-RC3

beta.5 RC3 fokus pada **automatic market-event expiry lifecycle**, **history hardening**, dan **listener deduplication** sebelum beta.5 final regression.

## Automatic Expiry Lifecycle

Price event RC1 sebenarnya selalu berhenti memengaruhi quote saat `ends-at` terlewati. RC3 menambahkan lifecycle recorder yang berjalan otomatis setiap 60 detik dan sekali pada startup.

Ketika event natural-expire, plugin sekarang menulis durable evidence ke `market-events.yml`:

```text
expiry-recorded-at: <ISO-8601 timestamp>
expiry-recorded-by: SYSTEM
```

Setelah evidence tersimpan, plugin mencoba:

1. menulis `MARKET_EVENT_EXPIRED` ke admin audit;
2. menambahkan record ke `logs/market-events.log`;
3. broadcast `[Pasar Kerajaan] Event pasar <id> telah berakhir secara otomatis.`

`/cve market status` juga menampilkan jumlah `expiryPending`, dan `/cve market show <id>` menampilkan `expiryRecordedAt`.

## At-Most-Once Broadcast Contract

RC3 sengaja memakai urutan:

```text
persist expiry evidence
  -> audit/history
  -> broadcast
```

Tujuannya menghindari duplicate expiry broadcast setelah restart/crash.

Konsekuensinya: jika server crash tepat setelah evidence dipersist tetapi sebelum broadcast, chat notification dapat terlewat. Economic state tetap benar karena effect event sudah berhenti berdasarkan `ends-at`, dan durable evidence tetap tersimpan.

`market-events.yml` adalah source of truth lifecycle; `logs/market-events.log` dan chat broadcast adalah secondary evidence/notification.

## History Hardening

Natural expiry sekarang memiliki durable field di event record, bukan hanya append-only log. Ini membuat restart dapat membedakan:

```text
expired + expiry-recorded-at missing  -> lifecycle masih perlu direkam
expired + expiry-recorded-at present  -> sudah diproses, jangan broadcast ulang
```

File tetap menggunakan schema v1 karena field expiry baru bersifat backward-compatible optional metadata.

## Listener Deduplication

Sebelum RC3, governance runtime membangun dua `GovernanceQuotaCommandListener` dan dua quota-ledger instance:

- satu dari `GovernanceService`;
- satu lagi dari `CveCommand`.

RC3 menghapus listener/quota ownership dari `GovernanceService`. Sekarang hanya `CveCommand` yang memiliki dan mendaftarkan quota listener/ledger aktif.

Ini penting karena dua listener dapat menyebabkan satu command price/stock role-only di-reserve dua kali atau terkena cooldown dari reservation listener pertama.

## Supply Runtime Registration

RC2 supply command listener sebelumnya di-bootstrap dari constructor administrative `MarketEventService`, sehingga setiap administrative instance baru berpotensi mendaftarkan listener tambahan.

RC3 memindahkan bootstrap supply listener menjadi satu kali pada lifecycle governance runtime. Administrative `MarketEventService` sekarang tidak punya side effect registrasi listener.

Hasilnya:

```text
1 GovernanceQuotaCommandListener
1 MarketSupplyCommandListener
1 MarketEventLifecycleService scheduler
```

per plugin enable.

## Safety Invariants

- event price tetap berhenti tepat berdasarkan `ends-at`, tidak menunggu scheduler;
- scheduler hanya merekam lifecycle, bukan menentukan quote validity;
- automatic expiry tidak mengubah saldo, inventory, atau stock;
- automatic expiry tidak melakukan `reloadRuntime()` sehingga tidak menutup GUI player hanya untuk mencatat event expired;
- corrupt `market-events.yml` tidak ditimpa oleh lifecycle recorder;
- lifecycle write memakai temp file + validation + backup + atomic replace fallback;
- explicit `/cve market end` tetap memakai safe runtime reload karena event dapat dihentikan sebelum `ends-at`;
- supply RC2 tetap memakai PREPARED/APPLIED/COMPLETED recovery evidence.

## Scope Boundary RC3

Optional configurable event templates/presets belum ditambahkan. Setelah RC3 runtime QA, beta.5 cukup membutuhkan optional template decision dan final regression/security hardening sebelum FINAL.
