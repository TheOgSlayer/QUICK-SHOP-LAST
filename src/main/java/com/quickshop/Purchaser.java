package com.quickshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

/**
 * The hot path. Runs on every click, so: no inventory copies, no GUI refresh,
 * no cooldown, no saving. Just: space check -> balance check -> withdraw -> give.
 */
public final class Purchaser {
    private final QuickShopPlugin plugin;

    public Purchaser(QuickShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void buy(Player p, Product prod, int amount) {
        if (prod.usesShards()) buyWithShards(p, prod, amount);
        else buyWithMoney(p, prod, amount);
    }

    private void buyWithMoney(Player p, Product prod, int amount) {
        PlayerInventory inv = p.getInventory();
        if (!hasSpace(inv, prod, amount)) {
            p.sendActionBar(Texts.INVENTORY_FULL);
            return;
        }
        EconomyHook eco = plugin.economy();
        if (!eco.available()) {
            p.sendActionBar(Texts.NO_ECONOMY);
            return;
        }
        double total = prod.price * amount;
        double bal = eco.balance(p);
        if (bal < total) {
            p.sendActionBar(Texts.notEnough(prod.name, "money", "$" + Texts.num(total - bal)));
            return;
        }
        if (!eco.withdraw(p, total)) {
            p.sendActionBar(Texts.notEnough(prod.name, "money", "$" + Texts.num(total)));
            return;
        }
        ItemStack stack = prod.template.clone();
        stack.setAmount(amount);
        Map<Integer, ItemStack> left = inv.addItem(stack);
        if (!left.isEmpty()) { // should not happen after hasSpace(); never lose items
            for (ItemStack s : left.values()) p.getWorld().dropItem(p.getLocation(), s);
        }
    }

    private void buyWithShards(Player p, Product prod, int amount) {
        long cost = Math.round(prod.price * amount);
        DataManager data = plugin.data();
        long bal = data.getShards(p.getUniqueId());
        if (bal < cost) {
            p.sendActionBar(Texts.notEnough(prod.name, "shards", (cost - bal) + " shards"));
            return;
        }

        var cfg = plugin.getConfig();
        // Optional per-mob name override, e.g. golem -> iron_golem
        String mob = cfg.getString("shards.mob-names." + prod.id, prod.id);
        String cmd = cfg.getString("shards.spawner-command", "givespawner {player} {mob} {amount}")
                .replace("{player}", p.getName())
                .replace("{mob}", mob)
                .replace("{amount}", Integer.toString(amount));
        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        // 1) Does the command even exist? (checked BEFORE taking any shards)
        String root = cmd.split(" ")[0];
        if (Bukkit.getCommandMap().getCommand(root) == null) {
            fail(p, cmd, "the command '/" + root + "' is not registered - is the spawner plugin installed and loaded?");
            return;
        }

        // 2) Take shards, run the command, refund if it reports failure.
        data.takeShards(p.getUniqueId(), cost);
        boolean ok;
        String error = "the command returned false (wrong arguments? check the plugin's usage message in console)";
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception ex) {
            ok = false;
            error = "exception: " + ex;
        }
        if (!ok) {
            data.addShards(p.getUniqueId(), cost);
            fail(p, cmd, error);
        }
    }

    private void fail(Player p, String cmd, String reason) {
        p.sendMessage("Spawner purchase failed - you were not charged. Please tell an admin.");
        if (p.hasPermission("quickshop.shards.admin")) {
            p.sendMessage("[QuickShop admin] Ran: /" + cmd);
            p.sendMessage("[QuickShop admin] Reason: " + reason);
        }
        plugin.getLogger().warning("Spawner command failed: /" + cmd + " -> " + reason);
    }

    /** Walks the 36 storage slots via getItem() (no array copy). Returns as soon as space is found. */
    private boolean hasSpace(PlayerInventory inv, Product prod, int amount) {
        Material m = prod.template.getType();
        int max = prod.maxStack;
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return true; // amount <= maxStack, one empty slot is enough
            if (max > 1 && it.getType() == m && !it.hasItemMeta()) {
                free += max - it.getAmount();
                if (free >= amount) return true;
            }
        }
        return false;
    }
}
