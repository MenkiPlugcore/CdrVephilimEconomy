# CdrVephilimEconomy — 0.1.0-beta.1 FINAL

Tanggal finalisasi: 20 September 2026.

`0.1.0-beta.1` adalah frozen baseline pertama untuk core NPC economy Vephilim Roleplay. Build ini menutup rangkaian RC1 sampai RC7 dan menjadi fondasi yang harus dipertahankan saat development `beta.2` dimulai.

## Status

- Version: `0.1.0-beta.1`
- Platform target: Paper 1.21.11 / Java 21
- Player flow: NPC-only melalui Citizens
- Economy bridge: Vault
- Pricing: static
- Storage stock: persistent YAML + backup/recovery
- Runtime reload: `/cve reload`
- Diagnostics: `/cve status`, `/cve doctor`
- Recovery: persistent safety stop + explicit admin unlock
- Crash recovery evidence: write-ahead pending transaction journal

## Runtime Validation yang Sudah Dilaporkan

Pengujian runtime pada Blacksmith Vephilim telah mengonfirmasi jalur utama berikut berjalan:

- BUY 1 item.
- BUY bulk 16.
- SELL 1 item.
- SELL bulk 16.
- GUI NPC dapat dibuka dari Citizens binding.
- Perubahan shop/config dapat diterapkan dengan safe reload tanpa restart server.
- Mode listing tetap menjadi sumber kebenaran arah transaksi; perubahan harga saja tidak mengubah SELL menjadi BUY_SELL.

RC7 kemudian dipakai sebagai final regression/anti-dupe hardening dan dilaporkan aman untuk ditutup menjadi beta.1 final.

## Frozen Core Contract

Mulai build ini, fase berikutnya tidak boleh mengubah asumsi inti berikut tanpa migration/security review:

1. Player tidak memiliki command shop umum.
2. Semua transaksi player tetap melewati NPC binding dan `TransactionService`.
3. Stock runtime tidak boleh di-reset dari `initial-stock` setelah storage pernah diinisialisasi.
4. Mutation economy/inventory/stock harus berada dalam guarded transaction flow.
5. Crash window harus meninggalkan durable pending evidence.
6. Kegagalan yang membuat consistency tidak dapat dibuktikan harus fail-closed melalui persistent safety stop.
7. `/cve reload` tidak boleh menjadi bypass untuk safety state.
8. Discord webhook tidak boleh menjadi dependency commit transaksi.
9. Perubahan administratif beta.2 wajib memiliki persistence dan audit yang setara dengan transaction core.

## File Runtime Penting

```text
config.yml
shops.yml
stock.yml
stock.yml.bak
stock.yml.initialized
safety.lock
safety.lock.last
safety-history.log
pending-transactions/
transaction-recovery/
transaction-recovery.log
logs/audit.log
```

Beberapa file hanya muncul ketika kondisi terkait terjadi.

## Admin Commands beta.1

```text
/cve reload
/cve status
/cve doctor
/cve safety status
/cve safety unlock CONFIRM
```

Permission:

```text
cdrvephilimeconomy.admin
```

## Recovery Rule

Jika safety stop aktif atau pending transaction ditemukan:

- jangan menghapus `safety.lock` atau pending journal secara manual sebagai recovery normal;
- cek transaction ID, audit log, saldo player, inventory player, dan stock listing;
- pastikan storage sehat;
- gunakan `/cve doctor`;
- setelah rekonsiliasi selesai, gunakan `/cve safety unlock CONFIRM`.

Recovery akan mempertahankan evidence agar investigasi tetap dapat dilakukan setelah ekonomi dibuka kembali.

## Scope yang Sengaja Belum Masuk

Fitur berikut bukan bagian beta.1:

- admin CRUD shop yang lengkap;
- granular staff permission;
- Economy Staff / Royal Treasurer governance;
- dynamic pricing;
- RP market events;
- auction house;
- player market otomatis;
- bank kompleks.

Semua itu masuk fase sesudah core beta.1.

## Next Development Phase

`beta.2 — Shop Management`

Target awal:

```text
/cve shop list
/cve shop info <shop>
/cve shop create <id>
/cve shop delete <id>
/cve shop bind <shop> <npc-id>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop additem <shop>
/cve shop removeitem <shop> <listing>
/cve shop price <shop> <listing> <buy|sell> <harga>
/cve shop stock <shop> <listing> <set|add|remove> <jumlah>
```

Implementasi beta.2 harus mempertahankan safety guarantees beta.1, terutama stock consistency, audit trail, fail-closed persistence, dan crash evidence.
