# beta.4 RC3 Test Plan

Target: `0.1.0-beta.4-RC3`.

## 1. Baseline

1. Start server with existing beta.4 RC2 data.
2. Run `/cve status` and `/cve pricing status`.
3. Ensure BUY/SELL on one static listing still works.
4. Ensure dynamic listing still uses RC2 sampled multiplier and reversal guard.

## 2. Read-Only Pricing Commands

```text
/cve pricing status
/cve pricing show blacksmith iron_ingot
/cve pricing stats blacksmith iron_ingot 24
```

Expected:

- no file mutation;
- current policy and effective quote are shown;
- stats count only successful transaction audit rows;
- invalid hours outside 1-720 are rejected.

## 3. Statistics Accuracy

Perform known transactions on one listing, for example:

```text
BUY 1
BUY 16
SELL 1
```

Then run:

```text
/cve pricing stats blacksmith iron_ingot 24
```

Verify BUY transaction count, SELL transaction count, unit volume, turnover, and effective unit averages match `logs/audit.log`.

## 4. Economy Staff

Assign `ECONOMY_STAFF` to only `blacksmith`.

Expected:

- can view `blacksmith` pricing/statistics;
- cannot view an unrelated shop unless another permission grants it;
- cannot use `/cve pricing set`;
- cannot use global/stability mutation.

## 5. Economy Manager Scope

Assign `ECONOMY_MANAGER` to only `blacksmith`.

Test allowed mutation:

```text
/cve pricing set blacksmith iron_ingot sensitivity 0.75
```

when previous sensitivity is `0.50`.

Expected: succeeds, because delta is 0.25 and value is inside Manager range.

Test rejected mutation:

```text
/cve pricing set blacksmith iron_ingot sensitivity 2.0
```

Expected: rejected by Manager guardrail.

Also test an unrelated shop. Expected: scope denial.

## 6. Royal Treasurer

Royal Treasurer scoped to `blacksmith`:

- can change listing policy in `blacksmith` beyond Manager guardrail but still inside absolute engine bounds;
- cannot change global/stability unless assignment scope contains `*`.

Royal Treasurer with `*`:

```text
/cve pricing global on
/cve pricing stability quote-cooldown 45
/cve pricing stability min-stock-change 12
/cve pricing stability reversal-cooldown 20
```

Expected: succeeds and runtime reloads safely.

## 7. Explicit Permission Override

Grant:

```text
cdrvephilimeconomy.pricing.manage
```

Expected: actor can manage pricing without internal governance role and is not restricted by Manager delta guardrail. Absolute parser bounds still apply.

## 8. Candidate Validation

Attempt invalid values:

```text
/cve pricing set blacksmith iron_ingot target 2
/cve pricing set blacksmith iron_ingot sensitivity -1
/cve pricing set blacksmith iron_ingot min 0
/cve pricing stability quote-cooldown 0
```

Expected:

- mutation rejected;
- active runtime remains unchanged;
- `pricing.yml` remains valid;
- rejection evidence appears in admin audit where applicable.

## 9. Backup / Reload / Rollback

After a successful pricing mutation verify:

```text
pricing.yml
pricing.yml.admin.bak
```

`pricing.yml.admin.tmp` should not remain after a normal successful operation.

Check `logs/admin-audit.log` for REQUEST + SUCCESS evidence.

## 10. RC2 Regression

Repeat:

- quote cooldown;
- minimum stock delta resampling;
- restart continuity from `market-state.yml`;
- stale quote `PRICE_CHANGED` behavior;
- rapid BUY→SELL reversal guard;
- static fallback if dynamic market-state is intentionally made unhealthy on a test instance.

## Pass Criteria

RC3 passes when governed policy mutation, audit-backed statistics, RC2 dynamic market stability, and beta.1-beta.3 transaction/governance regressions all behave without economy duplication or silent pricing mutation.
