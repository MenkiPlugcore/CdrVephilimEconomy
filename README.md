# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus untuk **Vephilim Roleplay**.

Fokus utamanya adalah membuat perdagangan terasa sebagai bagian dari dunia roleplay kerajaan: player bertransaksi melalui NPC, bukan melalui command shop biasa.

## Prinsip Utama

- Player **tidak memiliki command shop**.
- Semua transaksi dilakukan melalui NPC Citizens.
- NPC hanya menjadi front-end/interaksi dunia; logika transaksi ditangani plugin.
- Sistem mendukung BUY, SELL, atau BUY + SELL per item.
- Shop memiliki stok nyata yang persisten.
- Harga masih bersifat statis agar mudah dibalance dan diaudit.
- Semua transaksi penting memiliki audit trail.
- Fokus utama development adalah keamanan transaksi, anti-dupe, fail-closed recovery, dan operasional admin yang terkontrol.

## Status

**Current development candidate: `0.1.0-beta.2-RC1`**  
**Frozen core baseline: `0.1.0-beta.1`**

beta.2 dibangun di atas core beta.1 tanpa mengubah transaction safety foundation. RC1 membuka command-driven shop management, granular permissions, transactional `shops.yml` mutation, dan administrative audit.

Core yang tetap dipertahankan:

- Citizens NPC-only shop.
- GUI BUY / SELL / BUY_SELL.
- Hot reload aman melalui `/cve reload`.
- Persistent stock + backup/recovery.
- Transaction lock, cooldown, per-player in-flight guard.
- Persistent safety circuit breaker dan recovery eksplisit.
- Write-ahead transaction journal untuk crash-window recovery.
- Local transaction audit + Discord audit async.
- `/cve status`, `/cve doctor`, dan `/cve safety ...`.

## beta.2 Shop Management

```text
/cve shop list
/cve shop info <shop>
/cve shop create <id> [size] [display name]
/cve shop delete <id> CONFIRM
/cve shop bind <shop> <npc-id|-1>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop manager <shop> <name|none>
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
/cve shop price <shop> <listing> <buy|sell> <value>
/cve shop initialstock <shop> <listing> <value>
/cve shop maxstock <shop> <listing> <value>
/cve shop stock <shop> <listing> <set|add|remove> <amount>
```

Setiap mutation config melewati candidate validation dan runtime apply tanpa restart. Snapshot pre-change disimpan ke `shops.yml.admin.bak`, sedangkan jejak perubahan administratif disimpan di `logs/admin-audit.log`.

## Granular Permissions

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.view
cdrvephilimeconomy.shop.create
cdrvephilimeconomy.shop.delete
cdrvephilimeconomy.shop.bind
cdrvephilimeconomy.shop.toggle
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.shop.price
cdrvephilimeconomy.shop.stock
cdrvephilimeconomy.shop.manager
```

Full `cdrvephilimeconomy.admin` mewarisi seluruh permission shop. Staff dapat diberi permission granular melalui LuckPerms tanpa full admin access.

## Core Admin Commands

```text
/cve reload
/cve status
/cve doctor
/cve safety status
/cve safety unlock CONFIRM
```

Tidak ada command shop untuk player.

## Integrasi

- Paper 1.21.11 / Java 21.
- Citizens.
- Vault + economy provider.
- Discord webhook opsional untuk transaction audit eksternal.
- LuckPerms dapat digunakan untuk permission admin/staff.

## Storage

Definisi shop berada di `shops.yml`. Stock runtime dipersist ke `stock.yml` dengan backup, temporary snapshot verification, dan marker initialization. Safety stop menggunakan `safety.lock`, sedangkan transaksi yang belum terbukti committed saat crash dicatat pada `pending-transactions/`.

beta.2 menambahkan `shops.yml.admin.bak` untuk snapshot sebelum admin config mutation dan `logs/admin-audit.log` untuk administrative change trail.

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md) — tahapan development.
- [`CHANGELOG.md`](CHANGELOG.md) — riwayat perubahan.
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md) — frozen core baseline beta.1.
- [`docs/BETA1_TEST_PLAN.md`](docs/BETA1_TEST_PLAN.md) — regression checklist core.
- [`docs/BETA2_ADMIN_COMMANDS.md`](docs/BETA2_ADMIN_COMMANDS.md) — command dan permission beta.2.
- [`docs/BETA2_TEST_PLAN.md`](docs/BETA2_TEST_PLAN.md) — QA shop management beta.2.
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md) — konsep ekonomi.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — arsitektur core.

## Next beta.2 hardening

Setelah RC1 runtime QA, beta.2 berikutnya menutup schema migration/versioning `shops.yml`, Discord administrative audit, serta ergonomi management lanjutan sebelum beta.2 difinalkan.

Plugin ini dikembangkan oleh **MenkiPlugcore** untuk project **Vephilim Roleplay**.
