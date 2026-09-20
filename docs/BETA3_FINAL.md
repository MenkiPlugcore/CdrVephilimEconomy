# CdrVephilimEconomy 0.1.0-beta.3 FINAL

`0.1.0-beta.3` menutup fase **Economy Staff & Governance** dan menjadi frozen baseline governance sebelum development `beta.4 — Controlled Dynamic Pricing`.

## Scope Final

beta.3 menambahkan governance di atas Core Economy beta.1 dan Shop Management beta.2 tanpa mengganti transaction engine yang sudah dibekukan.

### Role dan Scope

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Assignment disimpan berbasis UUID dan dapat dibatasi ke shop tertentu atau scope global `*`.

### Sensitive Change Approval

Perubahan role-only yang melewati guardrail tidak langsung dieksekusi. Request disimpan secara durable, memiliki expiry, dapat cancel/reject, dan tidak dapat di-self-approve.

Approval yang akan dieksekusi dipersist sebagai `EXECUTING` lebih dahulu. Jika server crash pada window tersebut, mutation tidak direplay otomatis; admin harus melakukan rekonsiliasi lalu memakai recovery command eksplisit.

### Rolling Quota dan Cooldown

Direct mutation kecil tetap dibatasi secara kumulatif supaya per-operation limit tidak dapat dibypass melalui banyak perubahan kecil.

Evidence quota:

```text
governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp
```

Quota reservation dipersist sebelum direct role mutation. Kegagalan ledger membuat direct role-only price/stock mutation fail-closed.

### Two-Person Extreme Approval

Extreme mutation menggunakan dua reviewer berbeda. Default policy menganggap kondisi berikut sebagai extreme:

- perubahan harga absolut >= 100%;
- perubahan harga dari base 0;
- runtime stock delta >= 4096;
- runtime stock `SET`.

Reviewer pertama hanya menyimpan durable first-review evidence. Reviewer kedua yang berbeda baru dapat meneruskan execution. Secara default minimal satu reviewer harus senior: Royal Treasurer dengan scope sesuai, governance admin, atau full admin.

First-review evidence diikat dengan SHA-256 fingerprint dari immutable request fields supaya evidence lama tidak dapat dipakai untuk request yang berubah.

## Persistence Contract

```text
governance.yml
governance.yml.bak
governance.yml.tmp

governance-approvals.yml
governance-approvals.yml.bak
governance-approvals.yml.tmp

governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp

governance-dual-approval.yml
governance-dual-approval.yml.bak
governance-dual-approval.yml.tmp
logs/governance-dual-approval-history.log
```

File governance yang corrupt/unwritable tidak boleh membuat role mutation lolos tanpa evidence. Layer terkait akan fail-closed sementara BUY/SELL core tetap dipisahkan dari governance failure sejauh transaction storage sendiri sehat.

## Command Contract

```text
/cve governance status
/cve governance list
/cve governance who <player>
/cve governance grant <player> <role> <shop|*>
/cve governance revoke <player> <shop|*|all>
/cve governance reload

/cve governance approval status
/cve governance approval list
/cve governance approval show <id>
/cve governance approval approve <id>
/cve governance approval reject <id> [reason]
/cve governance approval cancel <id>
/cve governance approval reload
/cve governance approval recover <id> <executed|not-executed> CONFIRM
```

## Permission Contract

```text
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.approve
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` tetap full override. Permission Shop Management beta.2 tetap backward compatible dan diperlakukan sebagai explicit operator override untuk capability terkait.

## Operational Checks

Setelah install/upgrade:

```text
/cve status
/cve doctor
/cve shop schema
/cve shop validate
/cve governance status
/cve governance approval status
```

Kondisi normal harus menunjukkan core runtime, shop schema, governance, approval, quota, dan dual-approval storage dalam keadaan sehat.

## Security Invariants

- requester tidak dapat approve/reject request sendiri;
- reviewer hierarchy dan scope divalidasi sebelum execution;
- extreme request membutuhkan dua reviewer berbeda;
- minimal satu reviewer senior diwajibkan bila policy default aktif;
- stale/mutated request tidak dapat menggunakan first-review fingerprint lama;
- approval execution memiliki durable `EXECUTING` evidence;
- rolling quota survive restart;
- direct role-only mutation tidak boleh berjalan jika quota evidence gagal dipersist;
- governance failure tidak boleh diam-diam menjadi permission escalation;
- beta.1 transaction safety lock dan pending transaction journal tetap source of truth untuk kegagalan transaksi kritis.

## Frozen Baseline

Mulai `0.1.0-beta.3` FINAL:

- fitur governance beta.3 dibekukan;
- bug fix governance dipisahkan dari fitur baru;
- dynamic pricing tidak dimasukkan ke patch beta.3;
- development fitur berikutnya dilakukan pada branch/fase beta.4.

## Next

Fase berikutnya: **beta.4 — Controlled Dynamic Pricing** dengan price floor/ceiling, supply-demand sensitivity, cooldown, anti-manipulation, statistik, dan integrasi governance untuk perubahan parameter pasar.
