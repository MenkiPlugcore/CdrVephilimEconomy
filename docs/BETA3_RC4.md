# CdrVephilimEconomy 0.1.0-beta.3-RC4

RC4 menutup governance beta.3 dengan **two-person approval untuk perubahan ekstrem** dan security hardening terhadap bypass approval/quota.

## Perubahan Utama

### 1. Two-person approval untuk extreme mutation

Approval RC2 tetap menjadi source of truth dan executor mutation. RC4 menambahkan durable first-review attestation di atas flow tersebut.

Default extreme policy:

```yaml
governance:
  approval:
    two-person:
      enabled: true
      price-change-percent-threshold: 100.0
      price-from-zero-requires-two: true
      stock-delta-threshold: 4096
      stock-set-requires-two: true
      require-at-least-one-senior-reviewer: true
```

Mutation yang memenuhi policy ini tidak langsung dieksekusi oleh reviewer pertama.

Flow:

```text
PENDING approval
  -> reviewer #1 valid
  -> durable first-review evidence
  -> reviewer #2 yang berbeda
  -> minimal satu reviewer senior
  -> GovernanceApprovalService normal
  -> EXECUTING evidence
  -> ShopAdminService mutation
  -> APPROVED / FAILED
```

Reviewer pertama dan kedua wajib identitas berbeda. Requester tetap tidak boleh menjadi reviewer request miliknya sendiri.

### 2. Senior reviewer requirement

Jika `require-at-least-one-senior-reviewer: true`, minimal satu dari dua reviewer harus salah satu berikut:

- `ROYAL_TREASURER` dengan scope shop yang sesuai;
- holder `cdrvephilimeconomy.governance.admin`;
- holder `cdrvephilimeconomy.admin`.

Permission `cdrvephilimeconomy.governance.approve` tetap dapat menjadi reviewer, tetapi permission tersebut sendirian tidak dihitung sebagai senior reviewer.

### 3. Durable first-review evidence

RC4 menambah:

```text
governance-dual-approval.yml
governance-dual-approval.yml.bak
governance-dual-approval.yml.tmp
logs/governance-dual-approval-history.log
```

First review menyimpan:

- approval ID;
- fingerprint SHA-256 immutable request fields;
- identity reviewer pertama;
- nama reviewer;
- apakah reviewer senior;
- timestamp.

Jika approval record berubah di antara review pertama dan kedua, fingerprint mismatch membatalkan first review lama dan review harus dimulai kembali. Ini mencegah approval evidence dipakai untuk target mutation yang sudah berubah.

Evidence first review dibersihkan setelah approval mencapai terminal state dan dipindahkan ke history log.

### 4. Fail-closed storage

Jika `governance-dual-approval.yml` corrupt/tidak writable:

- BUY/SELL player tidak dihentikan;
- single-review approval biasa tetap dapat berjalan;
- **extreme approval diblokir fail-closed**;
- admin harus memperbaiki storage dan menjalankan governance/approval reload.

### 5. RC3 quota runtime wiring fix

Audit RC4 menemukan bahwa kelas rolling quota RC3 sudah tersedia tetapi listener command belum benar-benar diregistrasikan oleh command runtime. RC4 memperbaiki wiring tersebut.

`GovernanceQuotaCommandListener` sekarang diregistrasikan ketika command governance dibuat. Bila quota ledger tidak sehat, listener tetap aktif sehingga direct role-only price/stock mutation diblokir fail-closed, bukan diam-diam bypass quota.

`/cve status`, `/cve doctor`, `/cve governance status`, dan `/cve governance who <player>` sekarang menampilkan health/usage quota secara nyata.

### 6. Interaction layer

Final beta.3 governance chain setelah RC4:

```text
Role/scope RC1
   ↓
per-operation threshold
   ├─ direct small mutation -> rolling quota/cooldown RC3
   └─ sensitive mutation -> approval RC2
                            ├─ normal sensitive -> 1 reviewer
                            └─ extreme -> 2 reviewer RC4
                                           ↓
                                    EXECUTING journal RC2
                                           ↓
                                     ShopAdminService
```

Full admin/Royal Treasurer/explicit beta.2 permissions tetap merupakan operator override sesuai desain governance.

## Audit Events

RC4 menambah event administrative audit:

```text
GOV_DUAL_APPROVAL_FIRST_REVIEW_REQUEST
GOV_DUAL_APPROVAL_FIRST_REVIEW_RECORDED
GOV_DUAL_APPROVAL_SECOND_REVIEW_READY
GOV_DUAL_APPROVAL_FIRST_REVIEW_CLEARED
```

Jika Discord administrative audit aktif, event tersebut mengikuti sink admin audit yang sama.

## Runtime Checks

Setelah upgrade RC4:

```text
/cve status
/cve governance status
/cve governance approval status
/cve governance who <staff>
```

Harus menunjukkan governance, approval, quota, dan dual-approval storage healthy.

## RC4 Exit Criteria

RC4 dianggap siap menuju beta.3 FINAL bila:

- quota RC3 benar-benar aktif saat runtime;
- direct Staff/Manager mutation tidak dapat spam melewati rolling quota;
- extreme approval tidak dapat dieksekusi reviewer pertama;
- reviewer yang sama tidak dapat menjadi reviewer kedua;
- minimal satu reviewer senior diwajibkan sesuai config;
- fingerprint mismatch menginvalidasi first review lama;
- first-review evidence survive restart;
- terminal approval membersihkan evidence ke history;
- storage corrupt fail-closed pada layer yang relevan;
- core BUY/SELL, beta.2 shop management, RC2 approval recovery, dan safety system tidak regression.

Jika seluruh regression ini aman, tahap berikutnya adalah final security pass dan `0.1.0-beta.3 FINAL`.
