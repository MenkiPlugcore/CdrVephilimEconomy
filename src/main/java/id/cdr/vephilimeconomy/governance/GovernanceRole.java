package id.cdr.vephilimeconomy.governance;

import java.util.EnumSet;
import java.util.Set;

public enum GovernanceRole {
    ECONOMY_STAFF(EnumSet.of(
            GovernanceCapability.VIEW,
            GovernanceCapability.PRICE,
            GovernanceCapability.STOCK_RUNTIME
    )),

    ECONOMY_MANAGER(EnumSet.of(
            GovernanceCapability.VIEW,
            GovernanceCapability.BIND,
            GovernanceCapability.TOGGLE,
            GovernanceCapability.EDIT,
            GovernanceCapability.ITEM,
            GovernanceCapability.PRICE,
            GovernanceCapability.STOCK_CONFIG,
            GovernanceCapability.STOCK_RUNTIME,
            GovernanceCapability.MANAGER
    )),

    ROYAL_TREASURER(EnumSet.allOf(GovernanceCapability.class));

    private final Set<GovernanceCapability> capabilities;

    GovernanceRole(Set<GovernanceCapability> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
    }

    public boolean allows(GovernanceCapability capability) {
        return capabilities.contains(capability);
    }

    public Set<GovernanceCapability> capabilities() {
        return capabilities;
    }
}
