# CdrVephilimEconomy 0.1.0-beta.3-RC2

RC2 memperluas **Economy Staff & Governance** dengan durable sensitive-change approval di atas role/scope foundation RC1.

## Tujuan

Perubahan sensitif dari role-only `ECONOMY_STAFF` dan `ECONOMY_MANAGER` tidak lagi sekadar ditolak ketika melewati guardrail. Plugin membuat approval request yang harus direview oleh role lebih tinggi atau operator approval.

Core BUY/SELL, transaction safety, stock persistence, dan Shop Management beta.2 tidak diubah.

## Sensitive Changes

### Harga

- Staff: perubahan <=20% default langsung jalan.
- Staff: perubahan >20% menjadi approval request.
- Manager: perubahan <=50% default langsung jalan.
- Manager: perubahan >50% menjadi approval request.
- Perubahan dari harga dasar `0` oleh Staff/Manager menjadi approval request.
- Treasurer/admin/explicit `cdrvephilimeconomy.shop.price` tetap direct.

Harga request menyimpan harga awal. Saat approve, harga runtime harus masih sama dengan harga awal request. Jika sudah berubah, request dianggap stale dan mutation tidak dijalankan.

### Runtime Stock

- Staff ADD/REMOVE <=128 default langsung jalan.
- Staff ADD/REMOVE >128 menjadi approval request.
- Manager ADD/REMOVE <=1024 default langsung jalan.
- Manager ADD/REMOVE >1024 menjadi approval request.
- Runtime stock `SET` oleh Staff/Manager selalu menjadi approval request.
- Treasurer/admin/explicit `cdrvephilimeconomy.shop.stock` tetap direct.

Stock ADD/REMOVE dieksekusi terhadap stock runtime saat approval dilakukan dan tetap melewati bounds validation ShopAdminService.

## Reviewer Hierarchy

- `ECONOMY_MANAGER` dapat review request `ECONOMY_STAFF` pada shop scope yang sama.
- `ROYAL_TREASURER` dapat review request Staff/Manager pada scope yang sama.
- Reviewer dengan role yang sama atau lebih rendah ditolak.
- `cdrvephilimeconomy.governance.approve` menjadi operator reviewer override tanpa memberi hak grant/revoke governance.
- `cdrvephilimeconomy.governance.admin` dan full admin juga dapat review.

## Anti-Self-Approval

Requester tidak dapat approve atau reject request miliknya sendiri.

Requester yang ingin membatalkan memakai:

```text
/cve governance approval cancel <id>
```

## Commands

```text
/cve governance approval status
/cve governance approval list
/cve governance approval show <id>
/cve governance approval approve <id>
/cve governance approval reject <id> [reason]
/cve governance approval cancel <id>
/cve governance approval reload
/cve governance approval recover <id> <executed|not-executed> CONFIRM
```

## Persistence

```text
governance-approvals.yml
governance-approvals.yml.bak
governance-approvals.yml.tmp
```

Schema approval saat ini: v1.

Setiap request menyimpan:

- approval ID;
- UUID/nama requester;
- role requester saat request dibuat;
- action type;
- shop/listing;
- side harga atau stock operation;
- before/target/amount;
- created/expires timestamp;
- status;
- reviewer/decision timestamp;
- note/reason.

Penulisan memakai temporary validation + atomic move bila filesystem mendukung.

## Approval State Machine

Normal flow:

```text
PENDING
  -> APPROVED
  -> REJECTED
  -> CANCELLED
  -> EXPIRED
  -> FAILED
```

Sebelum mutation benar-benar dieksekusi, plugin wajib mempersist:

```text
EXECUTING
```

baru memanggil `ShopAdminService`.

Ini penting untuk crash safety. Kalau server mati setelah mutation mungkin sudah committed tetapi sebelum status final approval tersimpan, disk masih memiliki `EXECUTING` evidence. Startup berikutnya tidak melakukan replay otomatis.

## Crash Recovery

Jika ada `EXECUTING` evidence saat startup/reload:

```text
recoveryBlocked=true
```

Approval request/review baru diblokir fail-closed sampai admin merekonsiliasi shop/stock/admin audit.

Setelah menentukan hasil sebenarnya:

```text
/cve governance approval recover <id> executed CONFIRM
```

atau:

```text
/cve governance approval recover <id> not-executed CONFIRM
```

Recovery hanya mendeklarasikan hasil rekonsiliasi; command ini **tidak replay mutation**.

Status terminal recovery:

```text
RECOVERED_EXECUTED
RECOVERED_NOT_EXECUTED
```

## Expiry & Queue Guard

Default:

```yaml
governance:
  approval:
    enabled: true
    expiry-minutes: 10
    max-pending-per-requester: 5
```

- expiry di-clamp 1-1440 menit;
- pending expired menjadi `EXPIRED` ketika subsystem approval diakses;
- request target yang sama dari requester yang sama tidak boleh diduplikasi selama masih PENDING;
- requester dibatasi jumlah pending request untuk mencegah queue spam.

## Audit

Approval memakai `AdminAuditService` beta.2, sehingga local admin audit tetap source of truth dan Discord administrative audit tetap async/optional.

Event utama:

```text
GOV_APPROVAL_REQUEST
GOV_APPROVAL_APPROVE_REQUEST
GOV_APPROVAL_EXECUTE_SUCCESS
GOV_APPROVAL_EXECUTE_FAILED
GOV_APPROVAL_REJECTED
GOV_APPROVAL_CANCELLED
GOV_APPROVAL_EXPIRED
GOV_APPROVAL_RECOVERY_RESOLVED
```

## Permission Baru

```text
cdrvephilimeconomy.governance.approve
```

Permission ini hanya untuk review approval; tidak otomatis memberi grant/revoke role governance.

## Next

RC berikutnya diarahkan ke **rolling governance quota + cooldown** dan, bila perlu, **two-person approval** untuk perubahan paling sensitif sebelum beta.3 final security/regression pass.
