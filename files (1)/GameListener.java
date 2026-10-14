package com.nationwar;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class GameListener implements Listener {
    private final NationWarPlugin plugin;
    private final NationManager m;
    private final Gui gui;

    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Long> statusCooldown = new HashMap<>();
    private final Map<UUID, Location> lastFree = new HashMap<>();
    private final Set<UUID> ejecting = new HashSet<>();
    private final Map<UUID, Location> pendingSpectate = new HashMap<>();
    private final Map<UUID, Location> spectating = new HashMap<>();

    public GameListener(NationWarPlugin plugin, NationManager m, Gui gui) {
        this.plugin = plugin;
        this.m = m;
        this.gui = gui;
    }

    public void startTasks() { Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 40L, 20L); }

    public void shutdown() {
        for (UUID u : new HashSet<>(spectating.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) restoreFromSpectator(p);
        }
    }

    private void notice(Player p, String mm) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        cooldown.put(p.getUniqueId(), now);
        Msg.send(p, mm);
    }

    private void openStatusDebounced(Player p) {
        long now = System.currentTimeMillis();
        Long last = statusCooldown.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        statusCooldown.put(p.getUniqueId(), now);
        gui.openStatus(p);
    }

    // ===================== 출입 제한 (블록 단위) =====================
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ() && f.getWorld() == t.getWorld()) return;
        Player p = e.getPlayer();
        Claim c = m.claimAt(t);
        if (c != null && !m.canEnter(p, c)) {
            e.setCancelled(true);
            Nation pn = m.nationOf(p.getUniqueId());
            Nation cn = m.nations.get(c.nation);
            if (pn != null && cn != null && m.atWar(pn, cn)) {
                notice(p, "<red>🚫 상대 시민이 모두 오프라인이라 침범할 수 없습니다.");
            } else {
                gui.tryOpenPass(p, c); // 통행증 발행 / 비켜가기 창
            }
            return;
        }
        if (c == null) lastFree.put(p.getUniqueId(), t.clone());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (ejecting.contains(e.getPlayer().getUniqueId())) return;
        Claim c = m.claimAt(e.getTo());
        if (c != null && !m.canEnter(e.getPlayer(), c)) {
            e.setCancelled(true);
            notice(e.getPlayer(), "<red>🚫 출입금지 영토로는 이동할 수 없습니다.");
        }
    }

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Claim c = m.claimAt(p.getLocation());
            if (c == null) lastFree.put(p.getUniqueId(), p.getLocation().clone());
            else if (!m.canEnter(p, c)) eject(p);
        }
    }

    private void eject(Player p) {
        Location to = lastFree.get(p.getUniqueId());
        if (to == null) to = p.getWorld().getSpawnLocation();
        Claim c = m.claimAt(to);
        if (c != null && !m.canEnter(p, c)) to = p.getWorld().getSpawnLocation();
        teleportSafe(p, to);
        notice(p, "<red>출입 권한이 없어 영토 밖으로 이동되었습니다.");
    }

    private void teleportSafe(Player p, Location to) {
        ejecting.add(p.getUniqueId());
        try {
            p.teleport(to);
        } finally {
            ejecting.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (m.claimAt(p.getLocation()) == null) lastFree.put(p.getUniqueId(), p.getLocation().clone());
        m.startKit(p);
        Msg.send(p, "<gray>Shift+우클릭(또는 Shift+F)으로 상태창을 열 수 있습니다.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        pendingSpectate.remove(p.getUniqueId());
        gui.onQuit(p.getUniqueId());
        if (spectating.containsKey(p.getUniqueId())) restoreFromSpectator(p);
    }

    // ===================== 채팅 입력 (통행료 / 건국 이름) =====================
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!gui.hasPrompt(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> gui.handleChat(p, text));
    }

    // ===================== 상태창: Shift+F (어디를 보고 있어도, 뭘 들고 있어도) =====================
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (!e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        openStatusDebounced(e.getPlayer());
    }

    // ===================== 상호작용 =====================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;

        // 1) 상태창: Shift + 우클릭 (뭘 들고 있어도, 땅/허공 상관없이)
        if (p.isSneaking() && right) {
            e.setCancelled(true);
            openStatusDebounced(p);
            return;
        }

        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        Block b = e.getClickedBlock();

        // 2) 땅 선택 금 괭이
        if (Items.isLandTool(it)) {
            e.setCancelled(true);
            if (a == Action.RIGHT_CLICK_BLOCK && b != null) {
                m.select(p, true, b);
            } else if (a == Action.LEFT_CLICK_BLOCK && b != null) {
                if (m.select(p, false, b)) handleSelection(p);
            }
            return;
        }

        // 3) 선전포고 깃발
        if (it != null && right && "war".equals(Items.flagType(it))) {
            e.setCancelled(true);
            boolean used = m.useWarFlag(p);
            if (used && plugin.getConfig().getBoolean("war.consume-flags", true)) it.setAmount(it.getAmount() - 1);
            return;
        }

        // 4) 땅 보호
        if (b == null) return;
        Claim c = m.claimAt(b);
        if (c != null && !m.canInteract(p, c)) {
            e.setCancelled(true);
            if (a != Action.PHYSICAL) notice(p, "<red>보호된 땅입니다. (통행 중에는 상호작용이 불가능합니다)");
        }
    }

    private void handleSelection(Player p) {
        Nation n = m.nationOf(p.getUniqueId());
        NationManager.Plan pl = m.plan(p, n == null);
        if (pl.error() != null) { Msg.send(p, "<red>" + pl.error()); return; }
        if (n == null) {
            Msg.send(p, "<green>선택 완료: " + String.format("%,d", pl.area()) + "블록 (가격 " + NationManager.fmt(pl.price())
                    + "). <white>상태창 > 땅 관리 > 건국</white> 에서 국가를 세우세요.");
        } else {
            gui.openLandPurchase(p, pl);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        Nation mag = m.magnetAt(b);
        if (Items.isMagnetPick(p.getInventory().getItemInMainHand()) && mag == null) {
            e.setCancelled(true);
            notice(p, "<red>자석석 곡괭이는 자석석만 캘 수 있습니다.");
            return;
        }
        if (mag != null) {
            e.setCancelled(true);
            String err = m.magnetBreak(p, mag);
            if (err != null) notice(p, "<red>" + err);
            return;
        }
        Claim c = m.claimAt(b);
        if (c != null && !m.canInteract(p, c)) {
            e.setCancelled(true);
            notice(p, "<red>보호된 땅입니다.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Claim c = m.claimAt(e.getBlock());
        if (c != null && !m.canInteract(e.getPlayer(), c)) {
            e.setCancelled(true);
            notice(e.getPlayer(), "<red>보호된 땅입니다.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Claim c = m.claimAt(e.getBlock());
        if (c != null && !m.canInteract(e.getPlayer(), c)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Claim c = m.claimAt(e.getBlock());
        if (c != null && !m.canInteract(e.getPlayer(), c)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (en instanceof Player) return;
        Claim c = m.claimAt(en.getLocation());
        if (c != null && !m.canInteract(e.getPlayer(), c)) {
            e.setCancelled(true);
            notice(e.getPlayer(), "<red>보호된 땅입니다.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Claim c = m.claimAt(e.getRightClicked().getLocation());
        if (c != null && !m.canInteract(e.getPlayer(), c)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        Claim c = m.claimAt(e.getEntity().getLocation());
        if (c != null && !m.canInteract(p, c)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { filterExplosion(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { filterExplosion(e.blockList()); }

    private void filterExplosion(List<Block> list) {
        boolean protect = plugin.getConfig().getBoolean("protection.protect-explosions", true);
        list.removeIf(b -> m.magnetAt(b) != null || (protect && m.claimAt(b) != null));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (m.magnetAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (m.magnetAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (m.magnetAt(e.getBlock()) != null) e.setCancelled(true);
    }

    // ===================== 피해 / PvP =====================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && gui.isInvulnerable(p.getUniqueId())) e.setCancelled(true);
    }

    private boolean pvpAllowed(Player a, Player v) {
        if (a.getUniqueId().equals(v.getUniqueId())) return true;
        Nation an = m.nationOf(a.getUniqueId()), vn = m.nationOf(v.getUniqueId());
        boolean war = m.atWar(an, vn);
        if (an != null && vn != null && !war && m.related(an, vn)) return false;
        for (Claim c : new Claim[]{m.claimAt(v.getLocation()), m.claimAt(a.getLocation())}) {
            if (c == null) continue;
            if (!war || !(c.nation.equals(an.id) || c.nation.equals(vn.id))) return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = null;
        Entity d = e.getDamager();
        if (d instanceof Player pl) attacker = pl;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null) return;

        Entity victim = e.getEntity();
        if (victim instanceof Player v) {
            if (!pvpAllowed(attacker, v)) {
                e.setCancelled(true);
                notice(attacker, "<red>이 곳에서는 PvP를 할 수 없습니다.");
            }
        } else if (!(victim instanceof Monster)) {
            Claim c = m.claimAt(victim.getLocation());
            if (c != null && !m.canInteract(attacker, c)) e.setCancelled(true);
        }
    }

    // ===================== 사망 / 리스폰 =====================
    private void overlordPenalty(PlayerDeathEvent e, Player v) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(i);   // 갑옷/왼손 제외, 빈 칸도 포함
        Collections.shuffle(slots);
        for (int i = 0; i < 10; i++) {
            int s = slots.get(i);
            ItemStack it = v.getInventory().getItem(s);
            if (it == null) continue;
            e.getDrops().remove(it);               // 떨어지지 않고 사라짐
            v.getInventory().setItem(s, null);
        }
        Msg.send(v, "<red>식민지 시민에게 처치당해 인벤토리 10칸의 아이템이 사라졌습니다.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || k.getUniqueId().equals(v.getUniqueId())) return;
        Nation vn = m.nationOf(v.getUniqueId());
        Nation kn = m.nationOf(k.getUniqueId());

        // 식민지로 만든 사람이 식민지 시민에게 죽으면 인벤토리 10칸 삭제
        if (kn != null && kn.overlordPlayer != null && kn.overlordPlayer.equals(v.getUniqueId())) overlordPenalty(e, v);

        if (vn == null) return;
        Claim here = m.claimAt(v.getLocation());
        boolean ownLand = here != null && here.nation.equals(vn.id);
        War w = kn == null ? null : m.warBetween(vn.id, kn.id);

        // 반란 성공: 반란 중인 식민지 시민이 종주국의 최고인(리더)을 처치
        boolean rebelWin = kn != null && kn.isColony() && kn.rebelling
                && vn.id.equals(kn.overlordNation) && v.getUniqueId().equals(vn.leader);

        if (w != null) {
            e.setKeepInventory(false);
            boolean victimDeclarer = w.declarer.equals(vn.id);
            boolean outsideOwner = m.ownsLand(v.getUniqueId()) && !ownLand && w.declarer.equals(kn.id);
            if (victimDeclarer || outsideOwner) {
                List<Claim> lands = m.claimsOf(v.getUniqueId());
                if (lands.isEmpty()) {
                    Claim cap = m.claims.get(vn.capitalClaim);
                    if (cap != null) lands = List.of(cap);
                }
                e.getDrops().add(Items.coordPaper(vn, lands));
            }
            if (m.ownsLand(v.getUniqueId())) pendingSpectate.put(v.getUniqueId(), v.getLocation().clone());
        } else {
            if (!ownLand) {
                e.setKeepInventory(true);
                e.getDrops().clear();
                e.setKeepLevel(true);
                e.setShouldDropExperience(false);
            }
            if (kn != null && !m.related(vn, kn) && here == null) {
                m.declareWar(kn, vn, k.getName() + " 님이 PvP로 처치", null);
            }
        }

        if (rebelWin) m.rebelSuccess(kn, vn);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Location death = pendingSpectate.remove(p.getUniqueId());
        if (death == null) return;
        Location land = m.landSpawn(p);
        final Location real = land != null ? land : e.getRespawnLocation();
        e.setRespawnLocation(death);
        spectating.put(p.getUniqueId(), real);
        int sec = plugin.getConfig().getInt("war.spectator-seconds", 3);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !spectating.containsKey(p.getUniqueId())) return;
            p.setGameMode(GameMode.SPECTATOR);
            Msg.send(p, "<red>땅 주인이 전사했습니다. " + sec + "초 후 자신의 땅에서 부활합니다.");
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && spectating.containsKey(p.getUniqueId())) restoreFromSpectator(p);
        }, sec * 20L + 1L);
    }

    private void restoreFromSpectator(Player p) {
        Location real = spectating.remove(p.getUniqueId());
        p.setGameMode(GameMode.SURVIVAL);
        if (real != null) teleportSafe(p, real);
    }

    // ===================== 모든 창: 아이템 가져가기 금지, 클릭하면 선택 =====================
    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<Player> act = h.actions.get(e.getSlot());
        if (act == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> act.accept(p));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof MenuHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuHolder h && h.onClose != null) h.onClose.run();
    }
}
