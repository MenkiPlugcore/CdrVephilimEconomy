package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RC4 two-person approval guard for very high-impact governance mutations.
 *
 * The existing GovernanceApprovalService remains the mutation executor and source of
 * truth for request status. This service stores an independent durable first-review
 * attestation. A second distinct reviewer is required before an extreme approval is
 * allowed to reach the normal approval executor.
 */
public final class GovernanceDualApprovalService {
    private static final int SCHEMA = 1;
    private static final String ADMIN_PERMISSION = "cdrvephilimeconomy.admin";
    private static final String GOVERNANCE_ADMIN_PERMISSION = "cdrvephilimeconomy.governance.admin";
    private static final String APPROVE_PERMISSION = "cdrvephilimeconomy.governance.approve";

    private final CdrVephilimEconomy plugin;
    private final GovernanceService governance;
    private final AdminAuditService audit;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final File historyFile;
    private final File approvalFile;
    private final Map<String, FirstReview> firstReviews = new LinkedHashMap<>();

    private boolean healthy;
    private String healthDetail = "not loaded";

    public GovernanceDualApprovalService(CdrVephilimEconomy plugin, GovernanceService governance) {
        this.plugin = plugin;
        this.governance = governance;
        this.audit = plugin.adminAuditService();
        this.file = new File(plugin.getDataFolder(), "governance-dual-approval.yml");
        this.backupFile = new File(plugin.getDataFolder(), "governance-dual-approval.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "governance-dual-approval.yml.tmp");
        this.historyFile = new File(plugin.getDataFolder(), "logs/governance-dual-approval-history.log");
        this.approvalFile = new File(plugin.getDataFolder(), "governance-approvals.yml");
    }

    public synchronized Result load() {
        try {
            if (!file.isFile()) {
                persist(Map.of());
            }
            Map<String, FirstReview> loaded = readStrict(file);
            firstReviews.clear();
            firstReviews.putAll(loaded);
            healthy = true;
            pruneTerminalEvidence();
            healthDetail = "schema=v" + SCHEMA + ", firstReviews=" + firstReviews.size();
            return Result.ok("Dual approval guard loaded: " + healthDetail + ".");
        } catch (IOException exception) {
            healthy = false;
            healthDetail = exception.getMessage();
            plugin.getLogger().severe("Dual approval guard fail-closed: " + healthDetail);
            return Result.fail("Dual approval guard load gagal: " + healthDetail);
        }
    }

    public synchronized Result reload() {
        return load();
    }

    public synchronized Gate preApprove(CommandSender reviewer, String rawId) {
        if (!plugin.getConfig().getBoolean("governance.approval.two-person.enabled", true)) {
            return Gate.allow("two-person approval disabled");
        }

        String id = normalize(rawId);
        ApprovalSnapshot request;
        try {
            request = readApproval(id);
        } catch (IOException exception) {
            return Gate.block("Two-person guard tidak dapat membaca approval evidence: " + exception.getMessage());
        }
        if (request == null) {
            return Gate.allow("approval tidak ditemukan; serahkan validasi ke approval executor");
        }
        if (!request.status().equals("PENDING")) {
            cleanupIfPresent(id, "status=" + request.status());
            return Gate.allow("approval bukan PENDING; serahkan validasi ke approval executor");
        }
        if (!isExtreme(request)) {
            cleanupIfPresent(id, "request tidak lagi extreme");
            return Gate.allow("single-review approval");
        }
        if (!healthy) {
            return Gate.block("Two-person approval storage tidak sehat; extreme approval diblokir fail-closed: " + healthDetail);
        }
        if (!request.expiresAt().isAfter(Instant.now())) {
            return Gate.allow("request sudah expired; approval executor akan menutup request");
        }

        Authorization authorization = authorizeReviewer(reviewer, request);
        if (!authorization.allowed()) {
            return Gate.block(authorization.message());
        }

        String fingerprint = fingerprint(request);
        FirstReview first = firstReviews.get(id);
        if (first != null && !first.fingerprint().equals(fingerprint)) {
            Result invalidated = removeFirstReview(id, "fingerprint changed before second review");
            if (!invalidated.success()) {
                return Gate.block(invalidated.message());
            }
            first = null;
        }

        ReviewerIdentity identity = reviewerIdentity(reviewer, request);
        if (first == null) {
            String detail = auditDetail(request) + "; firstReviewer=" + reviewer.getName()
                    + "; senior=" + authorization.senior();
            try {
                audit.record(reviewer.getName(), "GOV_DUAL_APPROVAL_FIRST_REVIEW_REQUEST", detail);
            } catch (IOException exception) {
                return Gate.block("First review dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
            }

            FirstReview recorded = new FirstReview(id, fingerprint, identity.key(), reviewer.getName(),
                    authorization.senior(), Instant.now());
            Map<String, FirstReview> candidate = new LinkedHashMap<>(firstReviews);
            candidate.put(id, recorded);
            try {
                persist(candidate);
                firstReviews.clear();
                firstReviews.putAll(candidate);
                healthDetail = "schema=v" + SCHEMA + ", firstReviews=" + firstReviews.size();
            } catch (IOException exception) {
                healthy = false;
                healthDetail = "first review persistence failed: " + exception.getMessage();
                return Gate.block("First review gagal dipersist; extreme approval diblokir fail-closed.");
            }
            tryRecord(reviewer.getName(), "GOV_DUAL_APPROVAL_FIRST_REVIEW_RECORDED", detail);
            return Gate.firstRecorded("Review pertama untuk approval #" + id + " tercatat oleh "
                    + reviewer.getName() + ". Dibutuhkan reviewer kedua yang berbeda sebelum mutation dijalankan.");
        }

        if (first.reviewerKey().equals(identity.key())) {
            return Gate.block("Two-person approval membutuhkan reviewer kedua yang berbeda. Review pertama sudah oleh "
                    + first.reviewerName() + ".");
        }

        boolean requireSenior = plugin.getConfig().getBoolean(
                "governance.approval.two-person.require-at-least-one-senior-reviewer", true);
        if (requireSenior && !first.senior() && !authorization.senior()) {
            return Gate.block("Extreme approval membutuhkan minimal satu senior reviewer (Royal Treasurer/governance admin/full admin). "
                    + "Review pertama oleh " + first.reviewerName() + " belum memenuhi syarat senior.");
        }

        try {
            audit.record(reviewer.getName(), "GOV_DUAL_APPROVAL_SECOND_REVIEW_READY",
                    auditDetail(request) + "; firstReviewer=" + first.reviewerName()
                            + "; secondReviewer=" + reviewer.getName());
        } catch (IOException exception) {
            return Gate.block("Second review dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }
        return Gate.allow("review kedua valid; approval executor boleh melanjutkan");
    }

    public synchronized void afterApprovalAttempt(String rawId) {
        String id = normalize(rawId);
        if (!firstReviews.containsKey(id)) return;
        try {
            ApprovalSnapshot snapshot = readApproval(id);
            if (snapshot == null || !snapshot.status().equals("PENDING")) {
                removeFirstReview(id, snapshot == null ? "approval removed" : "terminal status=" + snapshot.status());
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Tidak dapat cleanup dual approval #" + id + ": " + exception.getMessage());
        }
    }

    public synchronized void afterTerminalAction(String rawId) {
        afterApprovalAttempt(rawId);
    }

    public synchronized String describe(String rawId) {
        String id = normalize(rawId);
        FirstReview first = firstReviews.get(id);
        if (first == null) return "dualReview=none";
        return "dualReview=WAITING_SECOND, firstReviewer=" + first.reviewerName()
                + ", senior=" + first.senior() + ", at=" + first.at();
    }

    public synchronized String statusSummary() {
        return "healthy=" + healthy + ", firstReviews=" + firstReviews.size() + ", detail=" + healthDetail;
    }

    private boolean isExtreme(ApprovalSnapshot request) {
        if (request.action().equals("PRICE")) {
            if (request.beforeValue() <= 0.0D && Double.compare(request.beforeValue(), request.targetValue()) != 0) {
                return plugin.getConfig().getBoolean(
                        "governance.approval.two-person.price-from-zero-requires-two", true);
            }
            if (request.beforeValue() <= 0.0D) return false;
            double percent = Math.abs(request.targetValue() - request.beforeValue()) / request.beforeValue() * 100.0D;
            double threshold = sanitizeThreshold(plugin.getConfig().getDouble(
                    "governance.approval.two-person.price-change-percent-threshold", 100.0D));
            return percent + 0.0000001D >= threshold;
        }

        if (!request.action().equals("STOCK_RUNTIME")) return false;
        if (request.parameter().equalsIgnoreCase("SET")) {
            return plugin.getConfig().getBoolean(
                    "governance.approval.two-person.stock-set-requires-two", true);
        }
        long threshold = Math.max(1L, plugin.getConfig().getLong(
                "governance.approval.two-person.stock-delta-threshold", 4096L));
        return request.amount() >= threshold;
    }

    private Authorization authorizeReviewer(CommandSender reviewer, ApprovalSnapshot request) {
        if (reviewer instanceof Player player && request.requesterUuid().equals(player.getUniqueId())) {
            return Authorization.denied("Anti-self-approval: requester tidak boleh menjadi reviewer approval sendiri.");
        }

        boolean admin = reviewer.hasPermission(ADMIN_PERMISSION);
        boolean governanceAdmin = reviewer.hasPermission(GOVERNANCE_ADMIN_PERMISSION);
        if (admin || governanceAdmin) {
            return Authorization.allowed(true, "admin reviewer");
        }

        if (reviewer.hasPermission(APPROVE_PERMISSION)) {
            return Authorization.allowed(isTreasurerWithScope(reviewer, request.shopId()), "approval permission reviewer");
        }

        if (!(reviewer instanceof Player player)) {
            return Authorization.denied("Reviewer tidak memiliki permission approval.");
        }
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) {
            return Authorization.denied("Reviewer tidak memiliki governance assignment.");
        }
        GovernanceService.Assignment assignment = optional.get();
        if (!scopeMatches(assignment, request.shopId())) {
            return Authorization.denied("Reviewer tidak memiliki scope untuk shop " + request.shopId() + ".");
        }
        if (assignment.role().ordinal() <= request.requesterRole().ordinal()) {
            return Authorization.denied("Reviewer harus memiliki role lebih tinggi dari requester "
                    + request.requesterRole() + ".");
        }
        return Authorization.allowed(assignment.role() == GovernanceRole.ROYAL_TREASURER,
                "hierarchy reviewer");
    }

    private boolean isTreasurerWithScope(CommandSender reviewer, String shopId) {
        if (!(reviewer instanceof Player player)) return false;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        return optional.filter(value -> value.role() == GovernanceRole.ROYAL_TREASURER)
                .filter(value -> scopeMatches(value, shopId)).isPresent();
    }

    private ReviewerIdentity reviewerIdentity(CommandSender reviewer, ApprovalSnapshot request) {
        if (reviewer instanceof Player player) {
            return new ReviewerIdentity("uuid:" + player.getUniqueId(), reviewer.getName());
        }
        return new ReviewerIdentity("sender:" + reviewer.getClass().getName().toLowerCase(Locale.ROOT)
                + ":" + reviewer.getName().toLowerCase(Locale.ROOT), reviewer.getName());
    }

    private void pruneTerminalEvidence() throws IOException {
        if (firstReviews.isEmpty()) return;
        Map<String, FirstReview> candidate = new LinkedHashMap<>(firstReviews);
        boolean changed = false;
        for (String id : firstReviews.keySet()) {
            ApprovalSnapshot snapshot = readApproval(id);
            if (snapshot == null || !snapshot.status().equals("PENDING")) {
                FirstReview removed = candidate.remove(id);
                if (removed != null) {
                    appendHistory(removed, snapshot == null ? "approval missing" : "status=" + snapshot.status());
                    changed = true;
                }
            }
        }
        if (changed) {
            persist(candidate);
            firstReviews.clear();
            firstReviews.putAll(candidate);
        }
    }

    private void cleanupIfPresent(String id, String reason) {
        if (!firstReviews.containsKey(id)) return;
        Result result = removeFirstReview(id, reason);
        if (!result.success()) {
            plugin.getLogger().warning(result.message());
        }
    }

    private Result removeFirstReview(String id, String reason) {
        FirstReview old = firstReviews.get(id);
        if (old == null) return Result.ok("no first review");
        Map<String, FirstReview> candidate = new LinkedHashMap<>(firstReviews);
        candidate.remove(id);
        try {
            persist(candidate);
            appendHistory(old, reason);
            firstReviews.clear();
            firstReviews.putAll(candidate);
            healthDetail = "schema=v" + SCHEMA + ", firstReviews=" + firstReviews.size();
            tryRecord("SYSTEM", "GOV_DUAL_APPROVAL_FIRST_REVIEW_CLEARED",
                    "approval=" + id + "; reviewer=" + old.reviewerName() + "; reason=" + compact(reason));
            return Result.ok("first review cleared");
        } catch (IOException exception) {
            healthy = false;
            healthDetail = "cleanup failed: " + exception.getMessage();
            return Result.fail("Dual approval cleanup gagal: " + exception.getMessage());
        }
    }

    private ApprovalSnapshot readApproval(String id) throws IOException {
        if (!approvalFile.isFile()) return null;
        YamlConfiguration yaml = loadStrict(approvalFile);
        ConfigurationSection item = yaml.getConfigurationSection("approvals." + id);
        if (item == null) return null;
        try {
            UUID requesterUuid = UUID.fromString(item.getString("requester-uuid", ""));
            GovernanceRole requesterRole = GovernanceRole.valueOf(
                    item.getString("requester-role", "").toUpperCase(Locale.ROOT));
            String action = item.getString("action", "").trim().toUpperCase(Locale.ROOT);
            String shop = normalize(item.getString("shop", ""));
            String listing = normalize(item.getString("listing", ""));
            String parameter = item.getString("parameter", "").trim().toUpperCase(Locale.ROOT);
            double before = item.getDouble("before", Double.NaN);
            double target = item.getDouble("target", Double.NaN);
            int amount = item.getInt("amount", -1);
            String status = item.getString("status", "").trim().toUpperCase(Locale.ROOT);
            Instant createdAt = Instant.parse(item.getString("created-at", ""));
            Instant expiresAt = Instant.parse(item.getString("expires-at", ""));
            if (action.isBlank() || shop.isBlank() || listing.isBlank() || parameter.isBlank()
                    || status.isBlank() || !Double.isFinite(before) || !Double.isFinite(target)
                    || amount < 0 || !expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("invalid approval fields");
            }
            return new ApprovalSnapshot(id, requesterUuid, requesterRole, action, shop, listing,
                    parameter, before, target, amount, status, createdAt, expiresAt);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new IOException("Approval record invalid #" + id + ": " + exception.getMessage(), exception);
        }
    }

    private String fingerprint(ApprovalSnapshot request) {
        String raw = request.requesterUuid() + "|" + request.requesterRole() + "|" + request.action()
                + "|" + request.shopId() + "|" + request.listingId() + "|" + request.parameter()
                + "|" + request.beforeValue() + "|" + request.targetValue() + "|" + request.amount()
                + "|" + request.createdAt() + "|" + request.expiresAt();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Map<String, FirstReview> readStrict(File source) throws IOException {
        YamlConfiguration yaml = loadStrict(source);
        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA) {
            throw new IOException("governance-dual-approval.yml schema tidak didukung: " + schema);
        }
        Map<String, FirstReview> loaded = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("reviews");
        if (section == null) return loaded;
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            ConfigurationSection item = section.getConfigurationSection(rawId);
            if (item == null) throw new IOException("Dual approval section invalid: " + rawId);
            try {
                String fingerprint = item.getString("fingerprint", "").trim();
                String reviewerKey = item.getString("reviewer-key", "").trim();
                String reviewerName = item.getString("reviewer-name", "").trim();
                boolean senior = item.getBoolean("senior", false);
                Instant at = Instant.parse(item.getString("at", ""));
                if (id.isBlank() || fingerprint.length() != 64 || reviewerKey.isBlank() || reviewerName.isBlank()) {
                    throw new IllegalArgumentException("invalid first review fields");
                }
                loaded.put(id, new FirstReview(id, fingerprint, reviewerKey, reviewerName, senior, at));
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                throw new IOException("Dual approval record invalid #" + rawId + ": " + exception.getMessage(), exception);
            }
        }
        return loaded;
    }

    private void persist(Map<String, FirstReview> source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
        ConfigurationSection section = yaml.createSection("reviews");
        for (FirstReview review : source.values()) {
            ConfigurationSection item = section.createSection(review.approvalId());
            item.set("fingerprint", review.fingerprint());
            item.set("reviewer-key", review.reviewerKey());
            item.set("reviewer-name", review.reviewerName());
            item.set("senior", review.senior());
            item.set("at", review.at().toString());
        }
        if (file.isFile()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        yaml.save(tempFile);
        readStrict(tempFile);
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    private YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private void appendHistory(FirstReview review, String reason) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Tidak dapat membuat folder logs untuk dual approval history.");
        }
        String line = Instant.now() + " approval=" + review.approvalId()
                + " firstReviewer=" + review.reviewerName() + " senior=" + review.senior()
                + " reviewedAt=" + review.at() + " reason=" + compact(reason) + System.lineSeparator();
        Files.writeString(historyFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void tryRecord(String actor, String action, String detail) {
        try {
            audit.record(actor, action, detail);
        } catch (IOException exception) {
            plugin.getLogger().warning("Gagal menulis dual approval admin audit: " + exception.getMessage());
        }
    }

    private static boolean scopeMatches(GovernanceService.Assignment assignment, String shopId) {
        return assignment.scopes().contains("*") || assignment.scopes().contains(normalize(shopId));
    }

    private static String auditDetail(ApprovalSnapshot request) {
        return "approval=" + request.id() + "; requester=" + request.requesterUuid()
                + "; requesterRole=" + request.requesterRole() + "; action=" + request.action()
                + "; shop=" + request.shopId() + "; listing=" + request.listingId()
                + "; parameter=" + request.parameter() + "; before=" + request.beforeValue()
                + "; target=" + request.targetValue() + "; amount=" + request.amount();
    }

    private static double sanitizeThreshold(double value) {
        if (!Double.isFinite(value)) return Double.MAX_VALUE;
        return Math.max(0.0D, value);
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String compact(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 220 ? value : value.substring(0, 217) + "...";
    }

    public record Gate(GateDecision decision, String message) {
        public static Gate allow(String message) {
            return new Gate(GateDecision.ALLOW_EXECUTION, message);
        }

        public static Gate firstRecorded(String message) {
            return new Gate(GateDecision.FIRST_REVIEW_RECORDED, message);
        }

        public static Gate block(String message) {
            return new Gate(GateDecision.BLOCKED, message);
        }
    }

    public enum GateDecision {
        ALLOW_EXECUTION,
        FIRST_REVIEW_RECORDED,
        BLOCKED
    }

    private record Authorization(boolean allowed, boolean senior, String message) {
        static Authorization allowed(boolean senior, String message) {
            return new Authorization(true, senior, message);
        }

        static Authorization denied(String message) {
            return new Authorization(false, false, message);
        }
    }

    private record ReviewerIdentity(String key, String name) {
    }

    private record FirstReview(
            String approvalId,
            String fingerprint,
            String reviewerKey,
            String reviewerName,
            boolean senior,
            Instant at
    ) {
    }

    private record ApprovalSnapshot(
            String id,
            UUID requesterUuid,
            GovernanceRole requesterRole,
            String action,
            String shopId,
            String listingId,
            String parameter,
            double beforeValue,
            double targetValue,
            int amount,
            String status,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message == null || message.isBlank() ? "unknown error" : message);
        }
    }
}
