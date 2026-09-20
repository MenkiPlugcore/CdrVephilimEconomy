# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, dan governance.

## Status

- **Current development:** `0.1.0-beta.3-RC2`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.3`

beta.3 RC1 membuka role/scope Economy Staff. RC2 menambahkan **sensitive-change approval** dengan hierarchy reviewer, expiry, anti-self-approval, durable execution evidence, dan fail-closed recovery.

## Core Economy

- Citizens NPC sebagai front-end transaksi.
- BUY / SELL / BUY_SELL per listing.
- Persistent real stock dan static pricing.
- Vault economy bridge.
- Transaction journal + persistent safety lock.
- Fail-closed recovery untuk stock/pending transaction.
- Local/Discord transaction audit.
- `/cve doctor`, `/cve safety status`, explicit recovery.

## Shop Management beta.2

```text
/cve shop list
/cve shop info <shop>
/cve shop schema
/cve shop validate
/cve shop create <id> [size] [display name]
/cve shop delete <id> CONFIRM
/cve shop name <shop> <display name>
/cve shop size <shop> <size>
/cve shop bind <shop> <npc-id|-1>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop manager <shop> <name|none>
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
/cve shop slot <shop> <listing> <slot>
/cve shop price <shop> <listing> <buy|sell> <value>
/cve shop initialstock <shop> <listing> <value>
/cve shop maxstock <shop> <listing> <value>
/cve shop stock <shop> <listing> <set|add|remove> <amount>
```

Shop management memakai `shops.yml` schema v2, candidate validation, backup, admin mutation journal, local administrative audit, dan optional Discord administrative audit.

## beta.3 — Economy Staff & Governance

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Assignment berbasis UUID dan dibatasi per shop scope atau `*`.

```text
/cve governance status
/cve governance list
/cve governance who <player>
/cve governance grant <player> <role> <shop|*>
/cve governance revoke <player> <shop|*|all>
/cve governance reload
```

`ECONOMY_STAFF` dapat view, edit price, dan add/remove runtime stock pada scope. `ECONOMY_MANAGER` menambah capability bind/toggle/layout/listing/stock config/manager metadata. `ROYAL_TREASURER` memiliki seluruh capability governance termasuk create/delete.

Permission beta.2 tetap backward compatible dan dianggap explicit operator override.

## RC2 Sensitive-Change Approval

Default role guardrail:

```yaml
governance:
  limits:
    economy-staff:
      max-price-change-percent: 20.0
      max-runtime-stock-delta: 128
    economy-manager:
      max-price-change-percent: 50.0
      max-runtime-stock-delta: 1024

  approval:
    enabled: true
    expiry-minutes: 10
    max-pending-per-requester: 5
```

Untuk role-only Staff/Manager:

- perubahan harga di dalam limit langsung dieksekusi;
- harga di atas limit menjadi approval request;
- perubahan harga dari base `0` menjadi approval request;
- ADD/REMOVE runtime stock di dalam limit langsung dieksekusi;
- delta stock di atas limit menjadi approval request;
- runtime stock `SET` selalu menjadi approval request.

Treasurer/admin/permission beta.2 eksplisit tetap dapat direct mutation.

## Approval Commands

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

Reviewer hierarchy:

- Manager dapat review request Staff pada scope yang sama.
- Treasurer dapat review request Staff/Manager pada scope yang sama.
- role peer/lower tidak dapat approve.
- requester tidak dapat approve/reject request sendiri.
- requester dapat cancel request sendiri.
- `cdrvephilimeconomy.governance.approve` memberi reviewer override tanpa hak grant/revoke.

## Durable Approval Recovery

Approval disimpan di:

```text
governance-approvals.yml
governance-approvals.yml.bak
governance-approvals.yml.tmp
```

Sebelum mutation approved dipanggil, state dipersist menjadi `EXECUTING`. Jika server crash pada window tersebut, subsystem approval masuk `recoveryBlocked=true` dan **tidak replay mutation otomatis**.

Admin harus memeriksa shop/stock/admin audit, lalu menandai hasil rekonsiliasi:

```text
/cve governance approval recover <id> executed CONFIRM
```

atau:

```text
/cve governance approval recover <id> not-executed CONFIRM
```

## Governance Persistence

```text
governance.yml
governance.yml.bak
governance.yml.tmp
```

Governance role storage dan approval storage sama-sama memakai strict YAML validation dan temporary + atomic replace bila filesystem mendukung.

## Permissions

Existing beta.2:

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.view
cdrvephilimeconomy.shop.create
cdrvephilimeconomy.shop.delete
cdrvephilimeconomy.shop.bind
cdrvephilimeconomy.shop.toggle
cdrvephilimeconomy.shop.edit
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.shop.price
cdrvephilimeconomy.shop.stock
cdrvephilimeconomy.shop.manager
```

beta.3:

```text
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.approve
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` mewarisi semuanya.

## Audit

Grant/revoke dan approval memakai `logs/admin-audit.log`. Jika Discord administrative audit aktif, event governance/approval juga dikirim async melalui sink yang sama.

Approval event utama:

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

## Integrasi

- Paper 1.21.11 / Java 21
- Citizens
- Vault + economy provider
- LuckPerms opsional untuk permission override
- Discord webhook opsional

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md)
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md)
- [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md)
- [`docs/BETA3_RC1.md`](docs/BETA3_RC1.md)
- [`docs/BETA3_RC2.md`](docs/BETA3_RC2.md)
- [`docs/BETA3_TEST_PLAN.md`](docs/BETA3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.3 Update

Setelah RC2 QA aman, update berikutnya diarahkan ke **rolling governance quota + cooldown** dan kemungkinan **two-person approval** untuk perubahan paling sensitif sebelum final regression/security pass beta.3.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
