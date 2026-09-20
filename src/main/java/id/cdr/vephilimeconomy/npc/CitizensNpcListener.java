package id.cdr.vephilimeconomy.npc;

import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.transaction.RuntimeSafetyState;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class CitizensNpcListener implements Listener {
    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final ShopGuiService gui;
    private final RuntimeSafetyState safetyState;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public CitizensNpcListener(JavaPlugin plugin, ShopRegistry registry, ShopGuiService gui,
                               RuntimeSafetyState safetyState) {
        this.plugin = plugin;
        this.registry = registry;
        this.gui = gui;
        this.safetyState = safetyState;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        Shop shop = registry.findByNpcId(event.getNPC().getId()).orElse(null);
        if (shop == null || !shop.enabled()) {
            return;
        }

        if (safetyState.isStopped()) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String message = plugin.getConfig().getString("messages.safety-stop",
                    "<dark_red>Sistem ekonomi sedang dikunci sementara. Hubungi staff.</dark_red>");
            event.getClicker().sendMessage(miniMessage.deserialize(prefix + message));
            return;
        }

        gui.open(event.getClicker(), shop);
    }
}
