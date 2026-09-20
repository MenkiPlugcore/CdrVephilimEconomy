# CdrVephilimEconomy 0.1.0-beta.3-RC1

RC1 membuka fase **Economy Staff & Governance** di atas frozen baseline `0.1.0-beta.2`.

Fokus RC1 adalah identitas staff ekonomi, scope per shop, role hierarchy, persistent governance storage, dan guardrail awal terhadap perubahan harga/stok. Core BUY/SELL dan transaction safety beta.1 tidak diubah.

## Roles

### ECONOMY_STAFF

Capability internal:
- view shop/diagnostic management;
- edit buy/sell price pada shop yang menjadi scope;
- add/remove runtime stock pada shop yang menjadi scope.

Guardrail default:
- perubahan harga maksimal 20% per operasi;
- runtime stock add/remove maksimal 128 item per operasi;
- runtime stock `SET` tidak diizinkan untuk role-only staff.

### ECONOMY_MANAGER

Capability internal:
- seluruh capability staff;
- bind/unbind NPC;
- enable/disable shop;
- edit display name/size;
- add/remove/move listing dan edit mode;
- edit initial/max stock config;
- edit manager metadata.

Guardrail default:
- perubahan harga maksimal 50% per operasi;
- runtime stock add/remove maksimal 1024 item per operasi;
- runtime stock `SET` tetap membutuhkan Royal Treasurer/admin atau permission beta.2 eksplisit.

### ROYAL_TREASURER

Memiliki seluruh capability governance termasuk create/delete shop. Scope tetap berlaku; scope `*` berarti global.

## Commands

```text
/cve governance status
/cve governance list
/cve governance who <player>
/cve governance grant <player> <ECONOMY_STAFF|ECONOMY_MANAGER|ROYAL_TREASURER> <shop|*>
/cve governance revoke <player> <shop|*|all>
/cve governance reload
```

Target player harus pernah join server sebelum assignment dibuat supaya UUID yang dipersist dapat dipercaya.

## Scope

Assignment disimpan berdasarkan UUID. Satu member memiliki satu role dan satu atau lebih scope.

Contoh:

```text
/cve governance grant Raka ECONOMY_STAFF blacksmith
/cve governance grant Raka ECONOMY_STAFF farmer
```

Raka dapat mengelola capability staff pada `blacksmith` dan `farmer`, tetapi tidak pada shop lain.

Mengubah role melalui `grant` mengganti role dan memulai scope set baru untuk mencegah scope luas dari role lama ikut terbawa tanpa sengaja.

## Persistence

Runtime file:

```text
governance.yml
governance.yml.bak
governance.yml.tmp
```

`governance.yml` memakai schema v1 dan ditulis dengan temporary validation + atomic replace bila filesystem mendukung. File invalid membuat role-based governance fail-closed sampai diperbaiki/reload.

Core economy tetap dapat dikelola oleh `cdrvephilimeconomy.admin` ketika governance storage rusak, sehingga owner tidak terkunci dari recovery.

## Permission Compatibility

Permission beta.2 tetap kompatibel dan menjadi explicit bypass terhadap role guardrail:

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.* (granular nodes existing)
```

Permission governance baru:

```text
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.admin
```

Role internal beta.3 dapat memberi capability shop tanpa harus memberikan seluruh permission granular LuckPerms. Jika staff sengaja diberi permission beta.2 langsung, permission tersebut dianggap operator override dan tidak dibatasi oleh role limit RC1.

## Audit

Grant/revoke memakai `AdminAuditService` yang sama dengan beta.2:

```text
GOVERNANCE_GRANT_REQUEST
GOVERNANCE_GRANT_SUCCESS
GOVERNANCE_GRANT_FAILED
GOVERNANCE_REVOKE_REQUEST
GOVERNANCE_REVOKE_SUCCESS
GOVERNANCE_REVOKE_FAILED
```

Dengan Discord administrative audit aktif, event governance mengikuti sink async yang sama tanpa menjadikan Discord dependency transaksi.

## Guardrail Limitations RC1

Limit harga dan runtime stock saat ini adalah **per operasi**, bukan rolling-window quota. Staff yang melakukan perubahan berkali-kali tetap meninggalkan audit trail, tetapi RC1 belum memiliki approval queue, daily quota, cooldown governance, atau two-person approval.

Hal tersebut sengaja menjadi target RC2/RC berikutnya agar foundation role/scope diuji terlebih dahulu sebelum approval state machine ditambahkan.

## Next

Setelah RC1 runtime QA, fase berikutnya diarahkan ke **sensitive-change approval**: request/approve/reject, threshold perubahan besar, expiry, anti-self-approval, dan durable approval evidence.
