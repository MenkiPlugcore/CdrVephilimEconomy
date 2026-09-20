package id.cdr.vephilimeconomy.npc;

import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class CitizensNpcListener implements Listener {
    private final ShopRegistry registry;
    private final ShopGuiService gui;

    public CitizensNpcListener(ShopRegistry registry, ShopGuiService gui) {
        this.registry = registry;
        this.gui = gui;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        Shop shop = registry.findByNpcId(event.getNPC().getId()).orElse(null);
        if (shop == null || !shop.enabled()) {
            return;
        }

        gui.open(event.getClicker(), shop);
    }
}
