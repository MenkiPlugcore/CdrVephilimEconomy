package id.cdr.vephilimeconomy.pricing;

import id.cdr.vephilimeconomy.transaction.TransactionType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Read-only market statistics derived from the authoritative transaction audit.
 * RC3 intentionally does not create a second transaction ledger: successful
 * BUY/SELL rows in logs/audit.log remain the source of truth for volume stats.
 */
public final class MarketStatisticsService {
    private static final int MAX_SCAN_LINES = 100_000;

    private final File auditFile;

    public MarketStatisticsService(File dataFolder) {
        this.auditFile = new File(new File(dataFolder, "logs"), "audit.log");
    }

    public Report report(String rawShopId, String rawListingId, int hours) throws IOException {
        String shopId = normalize(rawShopId);
        String listingId = normalize(rawListingId);
        int safeHours = Math.max(1, Math.min(720, hours));
        Instant cutoff = Instant.now().minus(Duration.ofHours(safeHours));

        if (!auditFile.isFile()) {
            return Report.empty(shopId, listingId, safeHours, "audit.log belum tersedia");
        }

        Deque<String> tail = new ArrayDeque<>(MAX_SCAN_LINES);
        try (BufferedReader reader = new BufferedReader(new FileReader(auditFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == MAX_SCAN_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        }

        long buyTransactions = 0L;
        long sellTransactions = 0L;
        long buyUnits = 0L;
        long sellUnits = 0L;
        double buyValue = 0.0D;
        double sellValue = 0.0D;
        double minBuyUnit = Double.POSITIVE_INFINITY;
        double maxBuyUnit = Double.NEGATIVE_INFINITY;
        double minSellUnit = Double.POSITIVE_INFINITY;
        double maxSellUnit = Double.NEGATIVE_INFINITY;
        Instant first = null;
        Instant last = null;
        int malformed = 0;

        for (String line : tail) {
            Parsed row;
            try {
                row = parse(line);
            } catch (RuntimeException exception) {
                malformed++;
                continue;
            }
            if (row == null || !"SUCCESS".equalsIgnoreCase(row.status()) || row.timestamp().isBefore(cutoff)) {
                continue;
            }
            if (!shopId.isBlank() && !shopId.equals(normalize(row.shopId()))) {
                continue;
            }
            if (!listingId.isBlank() && !listingId.equals(normalize(row.listingId()))) {
                continue;
            }

            if (first == null || row.timestamp().isBefore(first)) first = row.timestamp();
            if (last == null || row.timestamp().isAfter(last)) last = row.timestamp();

            if (row.type() == TransactionType.BUY) {
                buyTransactions++;
                buyUnits += row.amount();
                buyValue += row.total();
                minBuyUnit = Math.min(minBuyUnit, row.unit());
                maxBuyUnit = Math.max(maxBuyUnit, row.unit());
            } else if (row.type() == TransactionType.SELL) {
                sellTransactions++;
                sellUnits += row.amount();
                sellValue += row.total();
                minSellUnit = Math.min(minSellUnit, row.unit());
                maxSellUnit = Math.max(maxSellUnit, row.unit());
            }
        }

        long transactions = buyTransactions + sellTransactions;
        return new Report(
                shopId,
                listingId,
                safeHours,
                transactions,
                buyTransactions,
                sellTransactions,
                buyUnits,
                sellUnits,
                buyValue,
                sellValue,
                buyUnits == 0L ? 0.0D : buyValue / buyUnits,
                sellUnits == 0L ? 0.0D : sellValue / sellUnits,
                buyTransactions == 0L ? 0.0D : minBuyUnit,
                buyTransactions == 0L ? 0.0D : maxBuyUnit,
                sellTransactions == 0L ? 0.0D : minSellUnit,
                sellTransactions == 0L ? 0.0D : maxSellUnit,
                first,
                last,
                tail.size() == MAX_SCAN_LINES,
                malformed,
                transactions == 0L ? "tidak ada transaksi sukses pada window" : "OK"
        );
    }

    public List<String> render(Report report) {
        List<String> lines = new ArrayList<>();
        String scope = report.shopId().isBlank() ? "ALL"
                : report.listingId().isBlank() ? report.shopId()
                : report.shopId() + "/" + report.listingId();
        lines.add("§6[CVE Market] §fscope=§e" + scope + " §7window=§f" + report.hours() + "h"
                + " §7tx=§f" + report.transactions());
        lines.add("§7BUY  tx=§f" + report.buyTransactions()
                + " §7units=§f" + report.buyUnits()
                + " §7value=§f" + round2(report.buyValue())
                + " §7avgUnit=§f" + round2(report.averageBuyUnit()));
        lines.add("§7SELL tx=§f" + report.sellTransactions()
                + " §7units=§f" + report.sellUnits()
                + " §7value=§f" + round2(report.sellValue())
                + " §7avgUnit=§f" + round2(report.averageSellUnit()));
        lines.add("§7Net stock flow (SELL-BUY units)=§f" + (report.sellUnits() - report.buyUnits())
                + " §7turnover=§f" + round2(report.buyValue() + report.sellValue()));
        if (report.transactions() > 0L) {
            lines.add("§7BUY range=§f" + round2(report.minBuyUnit()) + ".." + round2(report.maxBuyUnit())
                    + " §7SELL range=§f" + round2(report.minSellUnit()) + ".." + round2(report.maxSellUnit()));
            lines.add("§7first=§f" + report.firstAt() + " §7last=§f" + report.lastAt());
        }
        if (report.scanTruncated()) {
            lines.add("§e[WARN] Statistik hanya membaca " + MAX_SCAN_LINES + " baris audit terbaru.");
        }
        if (report.malformedRows() > 0) {
            lines.add("§e[WARN] audit rows yang tidak dapat diparse=" + report.malformedRows());
        }
        return List.copyOf(lines);
    }

    private static Parsed parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split(" \\| ");
        if (parts.length < 11) return null;

        Instant timestamp = Instant.parse(parts[0].trim());
        String shop = value(parts, "shop=");
        String listing = value(parts, "listing=");
        String typeRaw = value(parts, "type=");
        String amountRaw = value(parts, "amount=");
        String unitRaw = value(parts, "unit=");
        String totalRaw = value(parts, "total=");
        String status = value(parts, "status=");
        if (shop == null || listing == null || typeRaw == null || amountRaw == null
                || unitRaw == null || totalRaw == null || status == null) return null;

        TransactionType type = TransactionType.valueOf(typeRaw.toUpperCase(Locale.ROOT));
        int amount = Integer.parseInt(amountRaw);
        double unit = Double.parseDouble(unitRaw);
        double total = Double.parseDouble(totalRaw);
        if (amount <= 0 || !Double.isFinite(unit) || !Double.isFinite(total)) return null;
        return new Parsed(timestamp, shop, listing, type, amount, unit, total, status);
    }

    private static String value(String[] parts, String prefix) {
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) return trimmed.substring(prefix.length());
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private record Parsed(Instant timestamp, String shopId, String listingId,
                          TransactionType type, int amount, double unit, double total, String status) {
    }

    public record Report(
            String shopId,
            String listingId,
            int hours,
            long transactions,
            long buyTransactions,
            long sellTransactions,
            long buyUnits,
            long sellUnits,
            double buyValue,
            double sellValue,
            double averageBuyUnit,
            double averageSellUnit,
            double minBuyUnit,
            double maxBuyUnit,
            double minSellUnit,
            double maxSellUnit,
            Instant firstAt,
            Instant lastAt,
            boolean scanTruncated,
            int malformedRows,
            String detail
    ) {
        private static Report empty(String shop, String listing, int hours, String detail) {
            return new Report(shop, listing, hours, 0L, 0L, 0L, 0L, 0L,
                    0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                    null, null, false, 0, detail);
        }
    }
}
