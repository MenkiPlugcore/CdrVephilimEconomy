# CdrVephilimEconomy 1.0.0-RC1

`1.0.0-RC1` adalah production-hardening candidate pertama setelah beta.5 FINAL.

## Scope RC1

### 1. Official Vephilim NPC catalog baseline

Default `shops.yml` membawa empat shop resmi:

```text
food       BUY-only
ore        SELL-only
farmer     SELL-only
fisherman  SELL-only
```

Harga dan blacklist: `docs/VEPHILIM_NPC_CATALOG.md`.

Semua default shop tetap `enabled: false` dan `npc-id: -1` sampai operator melakukan binding Citizens dan review.

### 2. Governance lost-state protection

Governance assignment sekarang memiliki initialization marker:

```text
governance.yml.initialized
```

Behavior:

- fresh install tanpa file/marker -> bootstrap empty governance + marker;
- existing beta install dengan `governance.yml` valid tetapi belum punya marker -> marker dibuat setelah successful strict load;
- marker ada tetapi `governance.yml` hilang -> recover hanya dari `governance.yml.bak` yang lolos strict validation;
- marker ada, primary hilang, backup tidak tersedia/invalid -> governance fail-closed dan assignment tidak di-reset diam-diam.

### 3. Governance commit/audit semantics

`grant`/`revoke` sekarang membedakan dua kegagalan:

```text
persistence failure BEFORE commit -> mutation gagal
success-audit failure AFTER commit -> mutation tetap SUCCESS + warning
```

Sebelumnya kegagalan menulis SUCCESS audit dapat membuat command melaporkan gagal walaupun state sudah committed ke disk.

### 4. Explicit beta.5 market runtime bootstrap

`GovernanceService` tidak lagi mendaftarkan supply listener dan expiry task secara langsung. Ia memanggil `MarketRuntimeBootstrap`, yang memiliki guard per plugin instance.

Invariant runtime:

```text
1x MarketSupplyCommandListener
1x MarketEventLifecycleService
```

### 5. Version line

```text
1.0.0-RC1
```

RC1 belum dinyatakan production FINAL. Production release tetap memerlukan runtime regression, concurrency/stress test, recovery drill, dan final persistence/security audit.

## Minimum Runtime QA

1. Start server dari beta.5 data existing; governance harus load dan membuat marker bila belum ada.
2. Restart; marker tidak boleh menyebabkan assignment berubah.
3. Dengan backup governance tersedia, simulasi primary `governance.yml` hilang saat server offline; startup harus recover dari backup.
4. Jangan melakukan destructive test pada production live; lakukan pada copy/staging.
5. Test `/cve market supply ...` satu kali dan expiry event satu kali; tidak boleh ada duplicate listener behavior.
6. Test grant/revoke governance dan restart untuk memastikan state persistent.
7. Jalankan `/cve doctor`, `/cve status`, transaksi BUY/SELL/BULK, dan safety recovery regression.
