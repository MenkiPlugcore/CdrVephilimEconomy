package id.cdr.vephilimeconomy.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

/** Soft runtime bridge to CdrBounty. No compile-time dependency is required. */
public final class WantedPriceBridge {
    private static volatile Object provider;
    private static volatile Method multiplierMethod;
    private static volatile Method totalMethod;
    private static volatile boolean resolved;

    private WantedPriceBridge() {}

    public static double applyBuySurcharge(UUID playerId, double discountedPrice) {
        if (!Double.isFinite(discountedPrice) || discountedPrice <= 0.0D) return discountedPrice;
        double multiplier = multiplier(playerId);
        double adjusted = discountedPrice * multiplier;
        return Math.max(0.01D, Math.round(adjusted * 100.0D) / 100.0D);
    }

    public static double multiplier(UUID playerId) {
        if (playerId == null) return 1.0D;
        resolveIfNeeded();
        Object current = provider;
        Method method = multiplierMethod;
        if (current == null || method == null) return 1.0D;
        try {
            Object result = method.invoke(current, playerId);
            if (result instanceof Number number) {
                double value = number.doubleValue();
                return Double.isFinite(value) && value >= 1.0D ? value : 1.0D;
            }
        } catch (ReflectiveOperationException ignored) {
            clear();
        }
        return 1.0D;
    }

    public static BigDecimal activeBountyTotal(UUID playerId) {
        if (playerId == null) return BigDecimal.ZERO;
        resolveIfNeeded();
        Object current = provider;
        Method method = totalMethod;
        if (current == null || method == null) return BigDecimal.ZERO;
        try {
            Object result = method.invoke(current, playerId);
            return result instanceof BigDecimal decimal ? decimal : BigDecimal.ZERO;
        } catch (ReflectiveOperationException ignored) {
            clear();
            return BigDecimal.ZERO;
        }
    }

    public static boolean available() {
        resolveIfNeeded();
        return provider != null;
    }

    public static synchronized void clear() {
        provider = null;
        multiplierMethod = null;
        totalMethod = null;
        resolved = false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static synchronized void resolveIfNeeded() {
        if (resolved && provider != null) return;
        resolved = true;
        try {
            Plugin bounty = Bukkit.getPluginManager().getPlugin("CdrBounty");
            if (bounty == null || !bounty.isEnabled()) return;
            Class<?> apiClass = Class.forName(
                    "store.cadera.cdrbounty.api.CdrBountyShopApi",
                    true,
                    bounty.getClass().getClassLoader());
            RegisteredServiceProvider registration = Bukkit.getServicesManager().getRegistration((Class) apiClass);
            if (registration == null || registration.getProvider() == null) return;
            Object found = registration.getProvider();
            provider = found;
            multiplierMethod = apiClass.getMethod("shopBuyMultiplier", UUID.class);
            totalMethod = apiClass.getMethod("activeBountyTotal", UUID.class);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            provider = null;
            multiplierMethod = null;
            totalMethod = null;
        }
    }
}
