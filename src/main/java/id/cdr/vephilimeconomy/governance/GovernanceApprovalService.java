package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable beta.3 approval queue for sensitive price/runtime-stock changes.
 *
 * The service intentionally persists EXECUTING before invoking ShopAdminService.
 * If the server dies in that window, startup blocks further approval execution
 * until an administrator explicitly resolves the evidence. This avoids replaying
 * a mutation that may already have committed before the crash.
 */
public final class GovernanceApprovalService {
    private static final int SCHEMA = 1;
    private static final String ADMIN_PERMISSION = "cdrvephilimeconomy.admin";
    private static final String GOVERNANCE_ADMIN_PERMISSION = "cdrvephilimeconomy.governance.admin";
    private static final String APPROVE_PERMISSION = "cdrvephilimeconomy.governance.approve";
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{6,32}");

    private final CdrVephilimEconomy plugin;
    private final GovernanceService governance;
    private final ShopAdminService shopAdmin;
    private final AdminAuditService audit;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final Map<String, ApprovalRequest> records = new LinkedHashMap<>();

    private boolean healthy;
    private boolean recoveryBlocked;
    private String healthDetail = "not loaded";

    public GovernanceApprovalService(CdrVephilimEconomy plugin, GovernanceService governance) {
        this.plugin = plugin;
        this.governance = governance;
        this.shopAdmin = plugin.shopAdminService();
        this.audit = plugin.adminAuditService();
        this.file = new File(plugin.getDataFolder(), "governance-approvals.yml");
        this.backupFile = new File(plugin.getDataFolder(), "governance-approvals.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "governance-approvals.yml.tmp");
    }

    public synchronized Result load() {
        try {
            if (!file.isFile()) {
                persist(Map.of());
            }
            Map<String, ApprovalRequest> loaded = readStrict(file);
            records.clear();
            records.putAll(loaded);
            healthy = true;
            refreshRecoveryState();
            if (!recoveryBlocked) {
                expireStale();
            }
            healthDetail = buildHealthDetail();
            return Result.ok("Approval storage loaded: " + healthDetail + ".");
        } catch (IOException exception) {
            healthy = false;
            recoveryBlocked = true;
            healthDetail = exception.getMessage();
            plugin.getLogger().severe("Governance approval fail-closed: " + healthDetail);
            return Result.fail("Approval storage load gagal: " + healthDetail);
        }
    }

    public synchronized Result reload() {
        return load();
    }

    public synchronized Result requestPrice(CommandSender sender, String shopId, String listingId,
                                            boolean buySide, double before, double target, String reason) {
        Result gate = requestGate(sender, shopId, GovernanceCapability.PRICE);
        if (!gate.success()) return gate;
        if (!Double.isFinite(before) || !Double.isFinite(target) || target < 0.0D) {
            return Result.fail("Harga approval tidak valid.");
        }
        GovernanceService.Assignment assignment = assignment(sender);
        if (assignment == null || assignment.role() == GovernanceRole.ROYAL_TREASURER) {
            return Result.fail("Approval request hanya diperlukan untuk Economy Staff/Manager.");
        }

        ApprovalRequest request = newRequest(sender, assignment, ActionType.PRICE,
                normalize(shopId), normalize(listingId), buySide ? "buy" : "sell",
                before, target, 0, reason);
        return enqueue(request);
    }

    public synchronized Result requestStock(CommandSender sender, String shopId, String listingId,
                                            ShopAdminService.StockOperation operation, int amount, String reason) {
        Result gate = requestGate(sender, shopId, GovernanceCapability.STOCK_RUNTIME);
        if (!gate.success()) return gate;
        if (operation == null || amount < 0) {
            return Result.fail("Runtime stock approval tidak valid.");
        }
        GovernanceService.Assignment assignment = assignment(sender);
        if (assignment == null || assignment.role() == GovernanceRole.ROYAL_TREASURER) {
            return Result.fail("Approval request hanya diperlukan untuk Economy Staff/Manager.");
        }

        ApprovalRequest request = newRequest(sender, assignment, ActionType.STOCK_RUNTIME,
                normalize(shopId), normalize(listingId), operation.name(),
                0.0D, 0.0D, amount, reason);
        return enqueue(request);
    }

    private Result requestGate(CommandSender sender, String shopId, GovernanceCapability capability) {
        if (!plugin.getConfig().getBoolean("governance.approval.enabled", true)) {
            return Result.fail("Sensitive-change approval sedang dinonaktifkan di config.yml.");
        }
        if (!healthy) return Result.fail("Approval storage tidak sehat: " + healthDetail);
        if (recoveryBlocked) return Result.fail("Approval execution diblokir karena ada EXECUTING evidence yang belum direkonsiliasi.");
        if (!(sender instanceof Player)) return Result.fail("Approval request role harus dibuat oleh player governance.");
        GovernanceService.Assignment assignment = assignment(sender);
        if (assignment == null || !assignment.role().allows(capability)) {
            return Result.fail("Governance assignment/capability tidak valid untuk approval request.");
        }
        String normalizedShop = normalize(shopId);
        if (!assignment.scopes().contains("*") && !assignment.scopes().contains(normalizedShop)) {
            return Result.fail("Shop " + normalizedShop + " di luar governance scope requester.");
        }
        int maxPending = Math.max(1, plugin.getConfig().getInt("governance.approval.max-pending-per-requester", 5));
        long pending = records.values().stream()
                .filter(value -> value.requesterUuid().equals(assignment.uuid()))
                .filter(value -> value.status() == ApprovalStatus.PENDING)
                .count();
        if (pending >= maxPending) {
            return Result.fail("Pending approval requester sudah mencapai limit " + maxPending + ".");
        }
        return Result.ok("request allowed");
    }

    private ApprovalRequest newRequest(CommandSender sender, GovernanceService.Assignment assignment,
                                       ActionType action, String shopId, String listingId, String parameter,
                                       double before, double target, int amount, String reason) {
        Instant now = Instant.now();
        long expiryMinutes = clamp(plugin.getConfig().getLong("governance.approval.expiry-minutes", 10L), 1L, 1440L);
        String id;
        do {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (records.containsKey(id));
        return new ApprovalRequest(id, assignment.uuid(), sender.getName(), assignment.role(), action,
                shopId, listingId, parameter, before, target, amount, now, now.plusSeconds(expiryMinutes * 60L),
                ApprovalStatus.PENDING, "", null, compact(reason));
    }

    private Result enqueue(ApprovalRequest request) {
        for (ApprovalRequest existing : records.values()) {
            if (existing.status() != ApprovalStatus.PENDING) continue;
            if (!existing.requesterUuid().equals(request.requesterUuid())) continue;
            if (existing.action() == request.action()
                    && existing.shopId().equals(request.shopId())
                    && existing.listingId().equals(request.listingId())
                    && existing.parameter().equalsIgnoreCase(request.parameter())) {
                return Result.fail("Sudah ada pending approval #" + existing.id() + " untuk target yang sama.");
            }
        }

        String detail = describeForAudit(request);
        try {
            audit.record(request.requesterName(), "GOV_APPROVAL_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Approval request dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        Map<String, ApprovalRequest> candidate = new LinkedHashMap<>(records);
        candidate.put(request.id(), request);
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
            healthDetail = buildHealthDetail();
            return Result.ok("Approval #" + request.id() + " dibuat untuk " + request.action()
                    + " shop=" + request.shopId() + "/" + request.listingId()
                    + ". Expires " + request.expiresAt() + ".");
        } catch (IOException exception) {
            failStorage("enqueue", exception);
            tryRecord(request.requesterName(), "GOV_APPROVAL_REQUEST_FAILED", detail + "; error=" + exception.getMessage());
            return Result.fail("Approval request gagal dipersist: " + exception.getMessage());
        }
    }

    public synchronized Result approve(CommandSender reviewer, String rawId) {
        expireStale();
        if (!healthy) return Result.fail("Approval storage tidak sehat: " + healthDetail);
        if (recoveryBlocked) return Result.fail("Approval execution diblokir sampai EXECUTING evidence direkonsiliasi.");
        ApprovalRequest request = pending(rawId);
        if (request == null) return Result.fail("Pending approval tidak ditemukan: " + rawId);
        Result authorization = reviewAuthorization(reviewer, request);
        if (!authorization.success()) return authorization;
        if (!requesterStillAuthorized(request)) {
            return terminalWithoutExecution(request, ApprovalStatus.FAILED, reviewer.getName(),
                    "Requester role/scope berubah sejak request dibuat.");
        }

        String auditDetail = describeForAudit(request) + "; reviewer=" + reviewer.getName();
        try {
            audit.record(reviewer.getName(), "GOV_APPROVAL_APPROVE_REQUEST", auditDetail);
        } catch (IOException exception) {
            return Result.fail("Approval dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        Instant now = Instant.now();
        ApprovalRequest executing = request.withDecision(ApprovalStatus.EXECUTING, reviewer.getName(), now,
                "execution started");
        Map<String, ApprovalRequest> candidate = new LinkedHashMap<>(records);
        candidate.put(request.id(), executing);
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
        } catch (IOException exception) {
            failStorage("mark executing", exception);
            return Result.fail("Tidak dapat mempersist EXECUTING evidence; mutation tidak dijalankan: " + exception.getMessage());
        }

        ShopAdminService.Result mutation = execute(executing, reviewer.getName());
        ApprovalStatus finalStatus = mutation.success() ? ApprovalStatus.APPROVED : ApprovalStatus.FAILED;
        ApprovalRequest finished = executing.withDecision(finalStatus, reviewer.getName(), Instant.now(), mutation.message());
        candidate = new LinkedHashMap<>(records);
        candidate.put(request.id(), finished);
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
            refreshRecoveryState();
            healthDetail = buildHealthDetail();
        } catch (IOException exception) {
            // Disk should still contain EXECUTING from the pre-execution write. Block replay.
            recoveryBlocked = true;
            healthy = false;
            healthDetail = "Final approval state gagal dipersist setelah mutation: " + exception.getMessage();
            plugin.getLogger().severe(healthDetail + ". Jangan replay approval #" + request.id()
                    + " sebelum rekonsiliasi manual.");
            return Result.fail("Mutation result=" + mutation.success() + " tetapi final approval evidence gagal ditulis. "
                    + "Approval subsystem dikunci; cek state shop/audit lalu recover #" + request.id() + ".");
        }

        String event = mutation.success() ? "GOV_APPROVAL_EXECUTE_SUCCESS" : "GOV_APPROVAL_EXECUTE_FAILED";
        tryRecord(reviewer.getName(), event, auditDetail + "; result=" + compact(mutation.message()));
        if (!mutation.success()) {
            return Result.fail("Approval #" + request.id() + " gagal dieksekusi: " + mutation.message());
        }
        return Result.ok("Approval #" + request.id() + " disetujui dan mutation berhasil: " + mutation.message());
    }

    private ShopAdminService.Result execute(ApprovalRequest request, String reviewer) {
        String actor = request.requesterName() + "[approval:" + request.id() + ",by:" + reviewer + "]";
        if (request.action() == ActionType.PRICE) {
            Optional<Shop> shop = plugin.findRuntimeShop(request.shopId());
            ShopListing listing = shop.map(value -> value.listings().get(request.listingId())).orElse(null);
            if (listing == null) return ShopAdminService.Result.fail("Listing runtime tidak ditemukan.");
            boolean buy = request.parameter().equalsIgnoreCase("buy");
            double current = buy ? listing.buyPrice() : listing.sellPrice();
            if (Math.abs(current - request.beforeValue()) > 0.0000001D) {
                return ShopAdminService.Result.fail("Approval stale: harga berubah sejak request. expected="
                        + request.beforeValue() + ", current=" + current + ".");
            }
            return shopAdmin.setPrice(actor, request.shopId(), request.listingId(), buy, request.targetValue());
        }

        ShopAdminService.StockOperation operation;
        try {
            operation = ShopAdminService.StockOperation.valueOf(request.parameter().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ShopAdminService.Result.fail("Operation stock approval invalid: " + request.parameter());
        }
        return shopAdmin.changeRuntimeStock(actor, request.shopId(), request.listingId(), operation, request.amount());
    }

    public synchronized Result reject(CommandSender reviewer, String rawId, String reason) {
        expireStale();
        if (!healthy) return Result.fail("Approval storage tidak sehat: " + healthDetail);
        if (recoveryBlocked) return Result.fail("Approval review diblokir sampai EXECUTING evidence direkonsiliasi.");
        ApprovalRequest request = pending(rawId);
        if (request == null) return Result.fail("Pending approval tidak ditemukan: " + rawId);
        Result authorization = reviewAuthorization(reviewer, request);
        if (!authorization.success()) return authorization;
        return terminalWithoutExecution(request, ApprovalStatus.REJECTED, reviewer.getName(),
                reason == null || reason.isBlank() ? "rejected" : reason);
    }

    public synchronized Result cancel(CommandSender sender, String rawId) {
        expireStale();
        if (!healthy) return Result.fail("Approval storage tidak sehat: " + healthDetail);
        if (recoveryBlocked) return Result.fail("Approval cancel diblokir sampai EXECUTING evidence direkonsiliasi.");
        ApprovalRequest request = pending(rawId);
        if (request == null) return Result.fail("Pending approval tidak ditemukan: " + rawId);
        boolean owner = sender instanceof Player player && request.requesterUuid().equals(player.getUniqueId());
        if (!owner && !governance.canAdminGovernance(sender)) {
            return Result.fail("Hanya requester atau governance admin yang dapat membatalkan request.");
        }
        return terminalWithoutExecution(request, ApprovalStatus.CANCELLED, sender.getName(), "cancelled by requester/admin");
    }

    private Result terminalWithoutExecution(ApprovalRequest request, ApprovalStatus status,
                                            String reviewer, String note) {
        ApprovalRequest finished = request.withDecision(status, reviewer, Instant.now(), compact(note));
        Map<String, ApprovalRequest> candidate = new LinkedHashMap<>(records);
        candidate.put(request.id(), finished);
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
            healthDetail = buildHealthDetail();
        } catch (IOException exception) {
            failStorage("terminal decision", exception);
            return Result.fail("Keputusan approval gagal dipersist: " + exception.getMessage());
        }
        tryRecord(reviewer, "GOV_APPROVAL_" + status.name(), describeForAudit(finished));
        return Result.ok("Approval #" + request.id() + " -> " + status + ".");
    }

    public synchronized Result recover(CommandSender sender, String rawId, boolean executed) {
        if (!governance.canAdminGovernance(sender)) {
            return Result.fail("Recovery approval membutuhkan governance admin/full admin.");
        }
        if (!healthy && !recoveryBlocked) {
            return Result.fail("Approval storage tidak dapat dibaca; perbaiki file lalu reload terlebih dahulu.");
        }
        String id = normalize(rawId);
        ApprovalRequest request = records.get(id);
        if (request == null || request.status() != ApprovalStatus.EXECUTING) {
            return Result.fail("EXECUTING approval evidence tidak ditemukan: " + rawId);
        }
        ApprovalStatus status = executed ? ApprovalStatus.RECOVERED_EXECUTED : ApprovalStatus.RECOVERED_NOT_EXECUTED;
        ApprovalRequest resolved = request.withDecision(status, sender.getName(), Instant.now(),
                "manual recovery declaration=" + (executed ? "executed" : "not-executed"));
        Map<String, ApprovalRequest> candidate = new LinkedHashMap<>(records);
        candidate.put(id, resolved);
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
            healthy = true;
            refreshRecoveryState();
            healthDetail = buildHealthDetail();
        } catch (IOException exception) {
            failStorage("recovery", exception);
            return Result.fail("Recovery evidence gagal dipersist: " + exception.getMessage());
        }
        tryRecord(sender.getName(), "GOV_APPROVAL_RECOVERY_RESOLVED", describeForAudit(resolved));
        return Result.ok("Approval #" + id + " recovery ditandai " + status
                + ". Pastikan deklarasi sesuai hasil rekonsiliasi shop/audit.");
    }

    private Result reviewAuthorization(CommandSender reviewer, ApprovalRequest request) {
        if (reviewer instanceof Player player && request.requesterUuid().equals(player.getUniqueId())) {
            return Result.fail("Anti-self-approval: requester tidak boleh approve/reject request sendiri. Gunakan cancel bila ingin membatalkan.");
        }
        if (reviewer.hasPermission(ADMIN_PERMISSION)
                || reviewer.hasPermission(GOVERNANCE_ADMIN_PERMISSION)
                || reviewer.hasPermission(APPROVE_PERMISSION)) {
            return Result.ok("operator reviewer");
        }
        if (!(reviewer instanceof Player player)) {
            return Result.fail("Reviewer tidak memiliki permission approval.");
        }
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) return Result.fail("Reviewer tidak memiliki governance assignment.");
        GovernanceService.Assignment assignment = optional.get();
        if (!scopeMatches(assignment, request.shopId())) {
            return Result.fail("Reviewer tidak memiliki scope untuk shop " + request.shopId() + ".");
        }
        if (assignment.role().ordinal() <= request.requesterRole().ordinal()) {
            return Result.fail("Reviewer harus memiliki role lebih tinggi dari requester " + request.requesterRole() + ".");
        }
        return Result.ok("hierarchy reviewer");
    }

    private boolean requesterStillAuthorized(ApprovalRequest request) {
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(request.requesterUuid());
        if (optional.isEmpty()) return false;
        GovernanceService.Assignment current = optional.get();
        if (current.role() != request.requesterRole()) return false;
        if (!scopeMatches(current, request.shopId())) return false;
        GovernanceCapability needed = request.action() == ActionType.PRICE
                ? GovernanceCapability.PRICE : GovernanceCapability.STOCK_RUNTIME;
        return current.role().allows(needed);
    }

    private static boolean scopeMatches(GovernanceService.Assignment assignment, String shopId) {
        return assignment.scopes().contains("*") || assignment.scopes().contains(normalize(shopId));
    }

    public synchronized boolean canView(CommandSender sender) {
        return governance.canViewGovernance(sender)
                || sender.hasPermission(APPROVE_PERMISSION)
                || sender.hasPermission(ADMIN_PERMISSION);
    }

    public synchronized List<String> listLines(CommandSender viewer) {
        expireStale();
        List<ApprovalRequest> visible = visible(viewer);
        visible.sort(Comparator.comparing(ApprovalRequest::createdAt).reversed());
        if (visible.isEmpty()) return List.of("§7Tidak ada approval yang dapat dilihat.");
        List<String> lines = new ArrayList<>();
        for (ApprovalRequest request : visible) {
            if (request.status() != ApprovalStatus.PENDING && request.status() != ApprovalStatus.EXECUTING) continue;
            lines.add("§e#" + request.id() + " §7| §f" + request.status()
                    + " §7| " + request.requesterName() + "(" + request.requesterRole() + ")"
                    + " §7| " + request.action() + " " + request.shopId() + "/" + request.listingId()
                    + " §7| expires=§f" + request.expiresAt());
        }
        if (lines.isEmpty()) return List.of("§7Tidak ada pending/executing approval yang dapat dilihat.");
        return lines;
    }

    public synchronized String describe(CommandSender viewer, String rawId) {
        expireStale();
        String id = normalize(rawId);
        ApprovalRequest request = records.get(id);
        if (request == null || !visible(viewer).contains(request)) {
            return "Approval tidak ditemukan atau tidak dapat dilihat: " + rawId;
        }
        return "#" + request.id() + " status=" + request.status()
                + ", requester=" + request.requesterName() + "(" + request.requesterRole() + ")"
                + ", action=" + request.action() + ", shop=" + request.shopId()
                + ", listing=" + request.listingId() + ", parameter=" + request.parameter()
                + ", before=" + request.beforeValue() + ", target=" + request.targetValue()
                + ", amount=" + request.amount() + ", created=" + request.createdAt()
                + ", expires=" + request.expiresAt() + ", reviewer=" + emptyDash(request.reviewer())
                + ", note=" + emptyDash(request.note());
    }

    public synchronized List<String> visibleIds(CommandSender viewer, boolean executingOnly) {
        expireStale();
        List<String> ids = new ArrayList<>();
        for (ApprovalRequest request : visible(viewer)) {
            if (executingOnly) {
                if (request.status() == ApprovalStatus.EXECUTING) ids.add(request.id());
            } else if (request.status() == ApprovalStatus.PENDING) {
                ids.add(request.id());
            }
        }
        return ids;
    }

    private List<ApprovalRequest> visible(CommandSender viewer) {
        boolean all = viewer.hasPermission(ADMIN_PERMISSION)
                || viewer.hasPermission(GOVERNANCE_ADMIN_PERMISSION)
                || viewer.hasPermission(APPROVE_PERMISSION);
        if (all) return new ArrayList<>(records.values());
        if (!(viewer instanceof Player player)) return List.of();
        Optional<GovernanceService.Assignment> reviewer = governance.assignmentFor(player.getUniqueId());
        List<ApprovalRequest> visible = new ArrayList<>();
        for (ApprovalRequest request : records.values()) {
            if (request.requesterUuid().equals(player.getUniqueId())) {
                visible.add(request);
                continue;
            }
            if (reviewer.isPresent() && scopeMatches(reviewer.get(), request.shopId())
                    && reviewer.get().role().ordinal() > request.requesterRole().ordinal()) {
                visible.add(request);
            }
        }
        return visible;
    }

    public synchronized String statusSummary() {
        long pending = records.values().stream().filter(value -> value.status() == ApprovalStatus.PENDING).count();
        long executing = records.values().stream().filter(value -> value.status() == ApprovalStatus.EXECUTING).count();
        return "healthy=" + healthy + ", recoveryBlocked=" + recoveryBlocked + ", records=" + records.size()
                + ", pending=" + pending + ", executing=" + executing + ", detail=" + healthDetail;
    }

    private ApprovalRequest pending(String rawId) {
        ApprovalRequest request = records.get(normalize(rawId));
        return request != null && request.status() == ApprovalStatus.PENDING ? request : null;
    }

    private GovernanceService.Assignment assignment(CommandSender sender) {
        if (!(sender instanceof Player player)) return null;
        return governance.assignmentFor(player.getUniqueId()).orElse(null);
    }

    private void expireStale() {
        if (!healthy || recoveryBlocked) return;
        Instant now = Instant.now();
        Map<String, ApprovalRequest> candidate = null;
        List<ApprovalRequest> expired = new ArrayList<>();
        for (ApprovalRequest request : records.values()) {
            if (request.status() == ApprovalStatus.PENDING && !request.expiresAt().isAfter(now)) {
                if (candidate == null) candidate = new LinkedHashMap<>(records);
                ApprovalRequest replacement = request.withDecision(ApprovalStatus.EXPIRED, "SYSTEM", now, "expired");
                candidate.put(request.id(), replacement);
                expired.add(replacement);
            }
        }
        if (candidate == null) return;
        try {
            persist(candidate);
            records.clear();
            records.putAll(candidate);
            for (ApprovalRequest request : expired) {
                tryRecord("SYSTEM", "GOV_APPROVAL_EXPIRED", describeForAudit(request));
            }
            healthDetail = buildHealthDetail();
        } catch (IOException exception) {
            failStorage("expire stale", exception);
        }
    }

    private void refreshRecoveryState() {
        recoveryBlocked = records.values().stream().anyMatch(value -> value.status() == ApprovalStatus.EXECUTING);
        if (recoveryBlocked) {
            plugin.getLogger().severe("Governance approval recovery required: ditemukan EXECUTING evidence. "
                    + "Jangan approve ulang sebelum /cve governance approval recover ... setelah rekonsiliasi manual.");
        }
    }

    private String buildHealthDetail() {
        long pending = records.values().stream().filter(value -> value.status() == ApprovalStatus.PENDING).count();
        long executing = records.values().stream().filter(value -> value.status() == ApprovalStatus.EXECUTING).count();
        return "schema=v" + SCHEMA + ", records=" + records.size() + ", pending=" + pending + ", executing=" + executing;
    }

    private Map<String, ApprovalRequest> readStrict(File source) throws IOException {
        YamlConfiguration yaml = loadStrict(source);
        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA) throw new IOException("governance-approvals.yml schema unsupported: " + schema);
        Map<String, ApprovalRequest> loaded = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("approvals");
        if (section == null) return loaded;
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            if (!SAFE_ID.matcher(id).matches()) throw new IOException("Approval id invalid: " + rawId);
            ConfigurationSection item = section.getConfigurationSection(rawId);
            if (item == null) throw new IOException("Approval section invalid: " + rawId);
            try {
                UUID requesterUuid = UUID.fromString(item.getString("requester-uuid", ""));
                String requesterName = item.getString("requester-name", "").trim();
                GovernanceRole requesterRole = GovernanceRole.valueOf(item.getString("requester-role", "").toUpperCase(Locale.ROOT));
                ActionType action = ActionType.valueOf(item.getString("action", "").toUpperCase(Locale.ROOT));
                String shopId = normalize(item.getString("shop", ""));
                String listingId = normalize(item.getString("listing", ""));
                String parameter = item.getString("parameter", "").trim();
                double before = item.getDouble("before", 0.0D);
                double target = item.getDouble("target", 0.0D);
                int amount = item.getInt("amount", 0);
                Instant created = Instant.parse(item.getString("created-at", ""));
                Instant expires = Instant.parse(item.getString("expires-at", ""));
                ApprovalStatus status = ApprovalStatus.valueOf(item.getString("status", "").toUpperCase(Locale.ROOT));
                String reviewer = item.getString("reviewer", "");
                String decidedRaw = item.getString("decided-at", "");
                Instant decided = decidedRaw == null || decidedRaw.isBlank() ? null : Instant.parse(decidedRaw);
                String note = item.getString("note", "");
                if (requesterName.isBlank() || parameter.isBlank() || amount < 0
                        || !Double.isFinite(before) || !Double.isFinite(target) || target < 0.0D
                        || !expires.isAfter(created)) {
                    throw new IllegalArgumentException("invalid approval fields");
                }
                loaded.put(id, new ApprovalRequest(id, requesterUuid, requesterName, requesterRole, action,
                        shopId, listingId, parameter, before, target, amount, created, expires, status,
                        reviewer == null ? "" : reviewer, decided, note == null ? "" : note));
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                throw new IOException("Approval record invalid #" + rawId + ": " + exception.getMessage(), exception);
            }
        }
        return loaded;
    }

    private void persist(Map<String, ApprovalRequest> source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
        ConfigurationSection approvals = yaml.createSection("approvals");
        for (ApprovalRequest request : source.values()) {
            ConfigurationSection item = approvals.createSection(request.id());
            item.set("requester-uuid", request.requesterUuid().toString());
            item.set("requester-name", request.requesterName());
            item.set("requester-role", request.requesterRole().name());
            item.set("action", request.action().name());
            item.set("shop", request.shopId());
            item.set("listing", request.listingId());
            item.set("parameter", request.parameter());
            item.set("before", request.beforeValue());
            item.set("target", request.targetValue());
            item.set("amount", request.amount());
            item.set("created-at", request.createdAt().toString());
            item.set("expires-at", request.expiresAt().toString());
            item.set("status", request.status().name());
            item.set("reviewer", request.reviewer());
            item.set("decided-at", request.decidedAt() == null ? "" : request.decidedAt().toString());
            item.set("note", request.note());
        }
        if (file.isFile()) Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        yaml.save(tempFile);
        readStrict(tempFile);
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    private void failStorage(String phase, IOException exception) {
        healthy = false;
        recoveryBlocked = true;
        healthDetail = phase + " failed: " + exception.getMessage();
        plugin.getLogger().severe("Governance approval storage fail-closed: " + healthDetail);
    }

    private void tryRecord(String actor, String action, String detail) {
        try {
            audit.record(actor, action, detail);
        } catch (IOException exception) {
            plugin.getLogger().severe("Gagal menulis approval admin audit: " + exception.getMessage());
        }
    }

    private static String describeForAudit(ApprovalRequest request) {
        return "id=" + request.id() + "; requester=" + request.requesterName()
                + "; role=" + request.requesterRole() + "; action=" + request.action()
                + "; shop=" + request.shopId() + "; listing=" + request.listingId()
                + "; parameter=" + request.parameter() + "; before=" + request.beforeValue()
                + "; target=" + request.targetValue() + "; amount=" + request.amount()
                + "; status=" + request.status() + "; expires=" + request.expiresAt()
                + "; note=" + compact(request.note());
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String compact(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    private static String emptyDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum ActionType {
        PRICE,
        STOCK_RUNTIME
    }

    public enum ApprovalStatus {
        PENDING,
        EXECUTING,
        APPROVED,
        REJECTED,
        CANCELLED,
        EXPIRED,
        FAILED,
        RECOVERED_EXECUTED,
        RECOVERED_NOT_EXECUTED
    }

    public record ApprovalRequest(
            String id,
            UUID requesterUuid,
            String requesterName,
            GovernanceRole requesterRole,
            ActionType action,
            String shopId,
            String listingId,
            String parameter,
            double beforeValue,
            double targetValue,
            int amount,
            Instant createdAt,
            Instant expiresAt,
            ApprovalStatus status,
            String reviewer,
            Instant decidedAt,
            String note
    ) {
        public ApprovalRequest withDecision(ApprovalStatus newStatus, String newReviewer, Instant when, String newNote) {
            return new ApprovalRequest(id, requesterUuid, requesterName, requesterRole, action, shopId, listingId,
                    parameter, beforeValue, targetValue, amount, createdAt, expiresAt, newStatus,
                    newReviewer == null ? "" : newReviewer, when, newNote == null ? "" : newNote);
        }
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
