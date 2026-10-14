package com.nationwar;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class NationManager {
    private static final Pattern NAME = Pattern.compile("^[가-힣A-Za-z0-9_]{2,12}$");

    private final NationWarPlugin plugin;
    public final Map<UUID, Nation> nations = new LinkedHashMap<>();
    public final Map<UUID, Claim> claims = new LinkedHashMap<>();
    public final Map<String, War> wars = new HashMap<>();
    public final Map<UUID, Integer> toll = new HashMap<>();
    public final Map<UUID, Long> balances = new HashMap<>();
    public final Set<UUID> kitGiven = new HashSet<>();
    private final Map<UUID, UUID> memberIndex = new HashMap<>();
    private final Map<String, Nation> magnets = new HashMap<>();
    private final Map<String, List<Claim>> chunkIndex = new HashMap<>();
    private final Map<String, Long> passes = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<String, PeaceRequest> peaceRequests = new HashMap<>();
    private final Map<UUID, Sel> pos1 = new HashMap<>();
    private final Map<UUID, Sel> pos2 = new HashMap<>();
    private boolean dirty;

    private record Invite(UUID nation, long expires) {}
    private record PeaceRequest(UUID from, long expires) {}
    public record Sel(String world, int x, int z) {}
    public record Plan(String world, int x1, int z1, int x2, int z2, long area, long price, String error) {
        static Plan err(String e) { return new Plan(null, 0, 0, 0, 0, 0, 0, e); }
    }

    public NationManager(NationWarPlugin plugin) { this.plugin = plugin; }

    // ===================== 공통 =====================
    private int ci(String path, int def) { return plugin.getConfig().getInt(path, def); }

    public boolean requireOnline() { return plugin.getConfig().getBoolean("war.require-defender-online", true); }

    private void touch() { dirty = true; reindex(); }

    public boolean isDirty() { return dirty; }

    private void reindex() {
        memberIndex.clear();
        magnets.clear();
        chunkIndex.clear();
        for (Nation n : nations.values()) {
            for (UUID u : n.members) memberIndex.put(u, n.id);
            if (n.magnetWorld != null) magnets.put(magnetKey(n.magnetWorld, n.mx, n.my, n.mz), n);
        }
        for (Claim c : claims.values()) {
            for (int cx = c.x1 >> 4; cx <= (c.x2 >> 4); cx++)
                for (int cz = c.z1 >> 4; cz <= (c.z2 >> 4); cz++)
                    chunkIndex.computeIfAbsent(c.world + ":" + cx + ":" + cz, k -> new ArrayList<>()).add(c);
        }
    }

    private static String magnetKey(String w, int x, int y, int z) { return w + ":" + x + ":" + y + ":" + z; }

    public static String fmt(long v) { return String.format("%,d", v) + "원"; }

    public Nation nationOf(UUID player) {
        UUID id = memberIndex.get(player);
        return id == null ? null : nations.get(id);
    }

    public Nation byName(String name) {
        for (Nation n : nations.values()) if (n.name.equalsIgnoreCase(name)) return n;
        return null;
    }

    // ----- 땅 조회 (블록 좌표 기준, 높이 무관) -----
    public Claim claimAt(String world, int x, int z) {
        List<Claim> l = chunkIndex.get(world + ":" + (x >> 4) + ":" + (z >> 4));
        if (l == null) return null;
        for (Claim c : l) if (c.contains(x, z)) return c;
        return null;
    }

    public Claim claimAt(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return claimAt(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public Claim claimAt(Block b) { return claimAt(b.getWorld().getName(), b.getX(), b.getZ()); }

    public List<Claim> claimsInArea(String w, int x1, int z1, int x2, int z2) {
        Set<Claim> set = new LinkedHashSet<>();
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++)
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                List<Claim> l = chunkIndex.get(w + ":" + cx + ":" + cz);
                if (l != null) set.addAll(l);
            }
        List<Claim> res = new ArrayList<>();
        for (Claim c : set) if (c.intersects(x1, z1, x2, z2)) res.add(c);
        return res;
    }

    public Nation magnetAt(Block b) {
        if (b.getType() != Material.LODESTONE) return null;
        return magnets.get(magnetKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    public List<Claim> claimsOf(UUID owner) {
        List<Claim> l = new ArrayList<>();
        for (Claim c : claims.values()) if (c.owner.equals(owner)) l.add(c);
        return l;
    }

    public List<Claim> claimsOfNation(UUID nation) {
        List<Claim> l = new ArrayList<>();
        for (Claim c : claims.values()) if (c.nation.equals(nation)) l.add(c);
        return l;
    }

    public boolean ownsLand(UUID player) {
        for (Claim c : claims.values()) if (c.owner.equals(player)) return true;
        return false;
    }

    public boolean anyOnline(Nation n) {
        for (UUID u : n.members) if (Bukkit.getPlayer(u) != null) return true;
        return false;
    }

    public void broadcast(Nation n, String mm) {
        for (UUID u : n.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Msg.send(p, mm);
        }
    }

    private String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        String s = op.getName();
        return Msg.esc(s == null ? u.toString().substring(0, 8) : s);
    }

    public void giveItem(Player p, ItemStack it) {
        for (ItemStack r : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }

    // ===================== 돈 =====================
    public long balance(UUID u) { return balances.getOrDefault(u, 0L); }

    public void addBalance(UUID u, long a) { balances.merge(u, a, Long::sum); dirty = true; }

    public boolean charge(UUID u, long a) {
        if (a <= 0) return true;
        if (balance(u) < a) return false;
        balances.put(u, balance(u) - a);
        dirty = true;
        return true;
    }

    public long deposit(Player p) {
        ItemStack[] cs = p.getInventory().getStorageContents();
        long total = 0;
        for (int i = 0; i < cs.length; i++) {
            long v = Items.noteValue(cs[i]);
            if (v > 0) { total += v * cs[i].getAmount(); cs[i] = null; }
        }
        if (total > 0) {
            p.getInventory().setStorageContents(cs);
            addBalance(p.getUniqueId(), total);
        }
        return total;
    }

    public String withdraw(Player p, long amount) {
        if (!charge(p.getUniqueId(), amount)) return "잔액이 부족합니다.";
        giveItem(p, Items.moneyNote(amount));
        return null;
    }

    public void startKit(Player p) {
        if (!kitGiven.add(p.getUniqueId())) return;
        giveItem(p, Items.landTool());
        long start = ci("economy.start-balance", 0);
        if (start > 0) addBalance(p.getUniqueId(), start);
        dirty = true;
        Msg.send(p, "<green>땅 선택 괭이를 받았습니다. <gray>(상태창 > 땅 관리에서 언제든 다시 받을 수 있습니다)");
    }

    public boolean hasLandTool(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (Items.isLandTool(it)) return true;
        return false;
    }

    public String giveLandTool(Player p) {
        if (hasLandTool(p)) return "이미 땅 선택 괭이를 가지고 있습니다.";
        giveItem(p, Items.landTool());
        return null;
    }

    // ===================== 상점 (판매) =====================
    public Map<Material, Long> sellPrices() {
        Map<Material, Long> res = new LinkedHashMap<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("shop.sell");
        if (s == null) return res;
        for (String k : s.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat != null) res.put(mat, s.getLong(k));
        }
        return res;
    }

    public int countPlain(Player p, Material mat) {
        int c = 0;
        for (ItemStack i : p.getInventory().getStorageContents())
            if (i != null && i.getType() == mat && !i.hasItemMeta()) c += i.getAmount();
        return c;
    }

    /** 인벤토리의 해당 아이템을 모두 판매. @return 번 돈 (0이면 판매할 게 없음) */
    public long sellAll(Player p, Material mat) {
        Long price = sellPrices().get(mat);
        if (price == null) return 0;
        ItemStack[] cs = p.getInventory().getStorageContents();
        long count = 0;
        for (int i = 0; i < cs.length; i++) {
            ItemStack s = cs[i];
            if (s != null && s.getType() == mat && !s.hasItemMeta()) { count += s.getAmount(); cs[i] = null; }
        }
        if (count <= 0) return 0;
        p.getInventory().setStorageContents(cs);
        long earned = count * price;
        addBalance(p.getUniqueId(), earned);
        return earned;
    }

    // ===================== 구역 =====================
    public boolean inRegion(String world, int x, int z) {
        var c = plugin.getConfig();
        if (!world.equals(c.getString("region.world", "world"))) return false;
        int half = c.getInt("region.size-blocks", 10000) / 2;
        int ox = c.getInt("region.center-x", 0), oz = c.getInt("region.center-z", 0);
        return x >= ox - half && x < ox + half && z >= oz - half && z < oz + half;
    }

    // ===================== 전쟁/관계 =====================
    public War warBetween(UUID a, UUID b) { return wars.get(War.key(a, b)); }

    public boolean atWar(Nation a, Nation b) {
        return a != null && b != null && !a.id.equals(b.id) && wars.containsKey(War.key(a.id, b.id));
    }

    public boolean related(Nation a, Nation b) {
        if (a == null || b == null) return false;
        if (a.id.equals(b.id)) return true;
        if (atWar(a, b)) return false;
        return a.id.equals(b.overlordNation) || b.id.equals(a.overlordNation);
    }

    private boolean bypass(Player p) { return p.hasPermission("nationwar.bypass"); }

    public boolean canEnter(Player p, Claim c) {
        if (c == null || bypass(p)) return true;
        Nation cn = nations.get(c.nation);
        if (cn == null) return true;
        Nation pn = nationOf(p.getUniqueId());
        if (pn != null) {
            if (related(pn, cn)) return true;
            if (atWar(pn, cn)) return !requireOnline() || anyOnline(cn);
        }
        return hasPass(p.getUniqueId(), cn.id);
    }

    public boolean canInteract(Player p, Claim c) {
        if (c == null || bypass(p)) return true;
        Nation cn = nations.get(c.nation);
        if (cn == null) return true;
        Nation pn = nationOf(p.getUniqueId());
        if (pn == null) return false;
        if (pn.id.equals(cn.id)) return true;
        if (atWar(pn, cn)) return !requireOnline() || anyOnline(cn);
        return false;
    }

    private String pk(UUID p, UUID n) { return p + ":" + n; }

    public boolean hasPass(UUID p, UUID n) {
        Long e = passes.get(pk(p, n));
        if (e == null) return false;
        if (e < System.currentTimeMillis()) { passes.remove(pk(p, n)); return false; }
        return true;
    }

    public long tollOf(Claim c) { return toll.getOrDefault(c.owner, ci("pass.default-toll", 0)); }

    public String buyPass(Player p, Claim c) {
        Nation tn = nations.get(c.nation);
        if (tn == null) return "주인 없는 땅입니다.";
        Nation pn = nationOf(p.getUniqueId());
        if (pn != null && (atWar(pn, tn) || related(pn, tn))) return "통행증이 필요 없는 관계입니다.";
        if (hasPass(p.getUniqueId(), tn.id)) return "이미 통행증이 있습니다.";
        long fee = tollOf(c);
        if (!charge(p.getUniqueId(), fee)) return "잔액이 부족합니다. (통행료 " + fmt(fee) + ")";
        if (fee > 0) addBalance(c.owner, fee);
        int sec = ci("pass.duration-seconds", 120);
        passes.put(pk(p.getUniqueId(), tn.id), System.currentTimeMillis() + sec * 1000L);
        Msg.send(p, "<green>" + Msg.esc(tn.name) + " 통행증 발행 (" + sec + "초, 통행료 " + fmt(fee) + "). 통행 중에는 상호작용/PvP가 불가능합니다.");
        broadcast(tn, "<yellow>[통행] " + Msg.esc(p.getName()) + " 님이 국가 영토를 통행합니다. (통행료 " + fmt(fee) + ")");
        return null;
    }

    public boolean declareWar(Nation atk, Nation def, String reason, Player glowPlayer) {
        if (atk.id.equals(def.id) || wars.containsKey(War.key(atk.id, def.id))) return false;
        wars.put(War.key(atk.id, def.id), new War(atk.id, def.id, System.currentTimeMillis()));
        touch();
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<red>⚔ <yellow>" + Msg.esc(atk.name) + "</yellow> 이(가) <yellow>"
                + Msg.esc(def.name) + "</yellow> 에게 선전포고했습니다! <gray>(" + Msg.esc(reason) + ")")));
        if (glowPlayer != null) {
            int ticks = ci("war.declarer-glow-seconds", 10) * 20;
            glowPlayer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, false));
        }
        return true;
    }

    public void endWar(Nation a, Nation b) {
        wars.remove(War.key(a.id, b.id));
        peaceRequests.remove(War.key(a.id, b.id));
        if (b.id.equals(a.overlordNation)) a.rebelling = false;
        if (a.id.equals(b.overlordNation)) b.rebelling = false;
        touch();
    }

    // ----- 선전포고 깃발 -----
    public boolean hasWarFlag(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            if ("war".equals(Items.flagType(it))) return true;
            if (it.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof ShulkerBox sb) {
                for (ItemStack s : sb.getInventory().getContents())
                    if (s != null && "war".equals(Items.flagType(s))) return true;
            }
        }
        return false;
    }

    public String giveWarFlag(Player p) {
        if (hasWarFlag(p)) return "이미 선전포고 깃발을 가지고 있습니다. (셜커 상자 포함)";
        giveItem(p, Items.warFlag());
        return null;
    }

    private Claim nearestForeign(Player p, Nation pn) {
        Location l = p.getLocation();
        double range = plugin.getConfig().getDouble("war.flag-range", 24);
        int r = (int) Math.ceil(range);
        Claim best = null;
        double bd = Double.MAX_VALUE;
        for (Claim c : claimsInArea(l.getWorld().getName(), l.getBlockX() - r, l.getBlockZ() - r, l.getBlockX() + r, l.getBlockZ() + r)) {
            if (c.nation.equals(pn.id)) continue;
            double d = c.distanceTo(l.getX(), l.getZ());
            if (d <= range && d < bd) { bd = d; best = c; }
        }
        return best;
    }

    public boolean useWarFlag(Player p) {
        Nation pn = nationOf(p.getUniqueId());
        if (pn == null || claimsOfNation(pn.id).isEmpty()) {
            Msg.send(p, "<red>땅을 가진 국가에 소속되어야 선전포고할 수 있습니다.");
            return false;
        }
        Claim t = nearestForeign(p, pn);
        if (t == null) { Msg.send(p, "<red>근처에 다른 국가의 땅이 없습니다."); return false; }
        Nation tn = nations.get(t.nation);
        if (tn == null) return false;
        if (atWar(pn, tn)) { Msg.send(p, "<red>이미 전쟁 중입니다."); return false; }
        if (related(pn, tn)) { Msg.send(p, "<red>종주국/식민지 관계입니다. 식민지는 상태창의 '혁명'을 사용하세요."); return false; }
        if (requireOnline() && !anyOnline(tn)) { Msg.send(p, "<red>상대 시민이 모두 오프라인이라 선전포고할 수 없습니다."); return false; }
        return declareWar(pn, tn, p.getName() + " 님이 선전포고 깃발 사용", p);
    }

    public String peace(Player p, String targetName) {
        Nation n = nationOf(p.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        if (!n.leader.equals(p.getUniqueId())) return "땅 주인(리더)만 휴전을 제안/수락할 수 있습니다.";
        Nation t = byName(targetName);
        if (t == null) return "그런 국가가 없습니다.";
        if (!atWar(n, t)) return "해당 국가와 전쟁 중이 아닙니다.";
        String key = War.key(n.id, t.id);
        PeaceRequest r = peaceRequests.get(key);
        long now = System.currentTimeMillis();
        if (r != null && r.from().equals(t.id) && r.expires() > now) {
            endWar(n, t);
            Bukkit.broadcast(Msg.c(Msg.PREFIX + "<green>" + Msg.esc(n.name) + " 와(과) " + Msg.esc(t.name) + " 의 전쟁이 휴전으로 종료되었습니다."));
            return null;
        }
        peaceRequests.put(key, new PeaceRequest(n.id, now + ci("war.peace-request-expire-seconds", 120) * 1000L));
        broadcast(t, "<yellow>" + Msg.esc(n.name) + "</yellow> 이(가) 휴전을 제안했습니다. 리더가 <green>/국가 휴전 " + Msg.esc(n.name) + "</green> 로 수락할 수 있습니다.");
        Msg.send(p, "<green>휴전을 제안했습니다.");
        return null;
    }

    // ===================== 땅 선택 / 구매 (블록 단위) =====================
    /** @return 두 번째 지점이 정상 지정되었으면 true */
    public boolean select(Player p, boolean first, Block b) {
        Sel s = new Sel(b.getWorld().getName(), b.getX(), b.getZ());
        if (first) {
            pos1.put(p.getUniqueId(), s);
            pos2.remove(p.getUniqueId());
            Msg.send(p, "<green>첫 번째 지점: (" + s.x() + ", " + s.z() + ") <gray>- 이제 다른 블록을 좌클릭하세요. (같은 블록이면 1칸)");
            return false;
        }
        if (!pos1.containsKey(p.getUniqueId())) {
            Msg.send(p, "<red>먼저 블록을 우클릭해서 첫 번째 지점을 지정하세요.");
            return false;
        }
        pos2.put(p.getUniqueId(), s);
        Msg.send(p, "<green>두 번째 지점: (" + s.x() + ", " + s.z() + ")");
        return true;
    }

    public Plan plan(Player p, boolean founding) {
        Sel a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
        if (a == null || b == null) return Plan.err("땅 선택 괭이로 블록 우클릭(첫 지점) → 다른 블록 좌클릭(두 번째 지점)을 먼저 하세요.");
        if (!a.world().equals(b.world())) return Plan.err("두 지점이 서로 다른 월드입니다.");
        Nation n = nationOf(p.getUniqueId());
        if (founding && n != null) return Plan.err("이미 국가에 소속되어 있습니다.");
        if (!founding && n == null) return Plan.err("국가가 없습니다. 상태창 > 땅 관리 > 건국 으로 먼저 건국하세요.");

        int x1 = Math.min(a.x(), b.x()), x2 = Math.max(a.x(), b.x());
        int z1 = Math.min(a.z(), b.z()), z2 = Math.max(a.z(), b.z());
        long area = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
        long maxA = plugin.getConfig().getLong("claims.max-area-per-purchase", 10000);
        if (area > maxA) return Plan.err("한 번에 최대 " + maxA + "블록까지 살 수 있습니다. (선택: " + area + "블록)");
        if (!inRegion(a.world(), x1, z1) || !inRegion(a.world(), x2, z2))
            return Plan.err("선택한 범위가 플레이 구역(스폰 기준 1만x1만) 밖입니다.");
        if (!claimsInArea(a.world(), x1, z1, x2, z2).isEmpty()) return Plan.err("이미 누군가 소유한 땅이 포함되어 있습니다.");
        if (!founding) {
            long total = area;
            for (Claim c : claimsOfNation(n.id)) total += c.area();
            if (total > plugin.getConfig().getLong("claims.max-area-per-nation", 250000)) return Plan.err("국가 최대 면적을 초과합니다.");
            if (plugin.getConfig().getBoolean("claims.require-near", true)) {
                int maxGap = ci("claims.max-gap-chunks", 3) * 16;
                boolean near = false;
                for (Claim c : claimsOfNation(n.id)) {
                    if (c.world.equals(a.world()) && c.gapTo(x1, z1, x2, z2) <= maxGap) { near = true; break; }
                }
                if (!near) return Plan.err("기존 땅에서 최대 " + ci("claims.max-gap-chunks", 3) + "청크(" + maxGap + "블록) 이내만 구매할 수 있습니다.");
            }
        }
        long price = area * ci("economy.price-per-block", 10) + (founding ? ci("economy.nation-create-price", 0) : 0);
        return new Plan(a.world(), x1, z1, x2, z2, area, price, null);
    }

    private void clearSelection(UUID u) { pos1.remove(u); pos2.remove(u); }

    public String checkName(String name) {
        if (!NAME.matcher(name).matches()) return "국가 이름은 한글/영문/숫자/_ 2~12자만 가능합니다.";
        if (byName(name) != null) return "이미 존재하는 국가 이름입니다.";
        return null;
    }

    public String createNation(Player p, String name) {
        if (nationOf(p.getUniqueId()) != null) return "이미 국가에 소속되어 있습니다.";
        String nameErr = checkName(name);
        if (nameErr != null) return nameErr;
        Plan pl = plan(p, true);
        if (pl.error() != null) return pl.error();
        World w = Bukkit.getWorld(pl.world());
        if (w == null) return "월드를 찾을 수 없습니다.";
        if (!charge(p.getUniqueId(), pl.price())) return "잔액이 부족합니다. (필요: " + fmt(pl.price()) + ")";
        Nation n = new Nation(UUID.randomUUID(), name, p.getUniqueId());
        nations.put(n.id, n);
        Claim c = new Claim(UUID.randomUUID(), pl.world(), pl.x1(), pl.z1(), pl.x2(), pl.z2(), p.getUniqueId(), n.id, false);
        claims.put(c.id, c);
        n.capitalClaim = c.id;
        placeMagnet(n, w, c);
        clearSelection(p.getUniqueId());
        touch();
        return null;
    }

    public String buyLand(Player p) {
        Plan pl = plan(p, false);
        if (pl.error() != null) return pl.error();
        Nation n = nationOf(p.getUniqueId());
        if (!charge(p.getUniqueId(), pl.price())) return "잔액이 부족합니다. (필요: " + fmt(pl.price()) + ")";
        Claim c = new Claim(UUID.randomUUID(), pl.world(), pl.x1(), pl.z1(), pl.x2(), pl.z2(), p.getUniqueId(), n.id, false);
        claims.put(c.id, c);
        clearSelection(p.getUniqueId());
        touch();
        return null;
    }

    private void placeMagnet(Nation n, World w, Claim c) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int[] pos = null;
        for (int t = 0; t < 30 && pos == null; t++) {
            int x = r.nextInt(c.x1, c.x2 + 1), z = r.nextInt(c.z1, c.z2 + 1);
            int y = w.getHighestBlockYAt(x, z);
            if (w.getBlockAt(x, y, z).isLiquid()) continue;
            pos = new int[]{x, y + 1, z};
        }
        if (pos == null) {
            int x = c.centerX(), z = c.centerZ();
            pos = new int[]{x, w.getHighestBlockYAt(x, z) + 1, z};
        }
        w.getBlockAt(pos[0], pos[1], pos[2]).setType(Material.LODESTONE);
        n.magnetWorld = w.getName();
        n.mx = pos[0];
        n.my = pos[1];
        n.mz = pos[2];
    }

    private void removeMagnet(Nation n) {
        if (n.magnetWorld == null) return;
        World w = Bukkit.getWorld(n.magnetWorld);
        if (w != null) {
            Block b = w.getBlockAt(n.mx, n.my, n.mz);
            if (b.getType() == Material.LODESTONE) b.setType(Material.AIR);
        }
        n.magnetWorld = null;
    }

    public String unclaim(Player p) {
        Nation n = nationOf(p.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        Claim c = claimAt(p.getLocation());
        if (c == null || !c.nation.equals(n.id)) return "우리 국가의 땅이 아닙니다.";
        if (!c.owner.equals(p.getUniqueId()) && !n.leader.equals(p.getUniqueId())) return "땅 주인 또는 리더만 포기할 수 있습니다.";
        if (c.id.equals(n.capitalClaim)) return "수도(자석석이 있는 땅)는 포기할 수 없습니다. (/국가 해체)";
        claims.remove(c.id);
        touch();
        return null;
    }

    /** 0 = 무료, 1~10000 */
    public String setToll(Player p, int amount) {
        if (amount < 0 || amount > 10_000) return "통행료는 0원(무료) 또는 1원~10000원만 가능합니다.";
        if (!ownsLand(p.getUniqueId())) return "땅 주인만 통행료를 설정할 수 있습니다.";
        toll.put(p.getUniqueId(), amount);
        dirty = true;
        return null;
    }

    // ===================== 멤버 관리 =====================
    public String invite(Player leader, Player target) {
        Nation n = nationOf(leader.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        if (!n.leader.equals(leader.getUniqueId())) return "땅 주인(리더)만 초대할 수 있습니다.";
        if (nationOf(target.getUniqueId()) != null) return "상대는 이미 국가에 소속되어 있습니다.";
        invites.put(target.getUniqueId(), new Invite(n.id, System.currentTimeMillis() + 60_000));
        Msg.send(target, "<yellow>" + Msg.esc(n.name) + "</yellow> 국가에서 초대했습니다. <green>/국가 수락</green> (60초)");
        return null;
    }

    public String accept(Player p) {
        Invite i = invites.remove(p.getUniqueId());
        if (i == null || i.expires() < System.currentTimeMillis()) return "받은 초대가 없거나 만료되었습니다.";
        if (nationOf(p.getUniqueId()) != null) return "이미 국가에 소속되어 있습니다.";
        Nation n = nations.get(i.nation());
        if (n == null) return "해당 국가가 더 이상 존재하지 않습니다.";
        n.members.add(p.getUniqueId());
        touch();
        broadcast(n, "<green>" + Msg.esc(p.getName()) + "</green> 님이 국가의 시민이 되었습니다.");
        return null;
    }

    private void abandonLand(UUID player) {
        for (Claim c : new ArrayList<>(claimsOf(player))) claims.remove(c.id);
    }

    public String leave(Player p) {
        Nation n = nationOf(p.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        if (n.leader.equals(p.getUniqueId())) return "땅 주인(리더)은 탈퇴할 수 없습니다. (/국가 해체)";
        if (n.isColony()) return "식민지 상태에서는 국가를 떠날 수 없습니다. 상태창의 '혁명'을 사용하세요.";
        abandonLand(p.getUniqueId());
        n.members.remove(p.getUniqueId());
        touch();
        broadcast(n, "<yellow>" + Msg.esc(p.getName()) + "</yellow> 님이 국가를 떠났습니다. (소유한 땅은 모두 포기됨)");
        return null;
    }

    public void leaveFree(UUID u) {
        Nation n = nationOf(u);
        if (n == null || n.leader.equals(u)) return;
        abandonLand(u);
        n.members.remove(u);
        touch();
    }

    public String kick(Player leader, String targetName) {
        Nation n = nationOf(leader.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        if (!n.leader.equals(leader.getUniqueId())) return "땅 주인(리더)만 추방할 수 있습니다.";
        UUID target = null;
        for (UUID u : n.members) {
            String nm = Bukkit.getOfflinePlayer(u).getName();
            if (nm != null && nm.equalsIgnoreCase(targetName)) target = u;
        }
        if (target == null) return "그런 시민이 없습니다.";
        if (target.equals(n.leader)) return "리더는 추방할 수 없습니다.";
        abandonLand(target);
        n.members.remove(target);
        touch();
        broadcast(n, "<red>" + Msg.esc(targetName) + "</red> 님이 추방되었습니다.");
        return null;
    }

    public String disband(Player p) {
        Nation n = nationOf(p.getUniqueId());
        if (n == null) return "국가에 소속되어 있지 않습니다.";
        if (!n.leader.equals(p.getUniqueId())) return "땅 주인(리더)만 해체할 수 있습니다.";
        if (n.isColony()) return "식민지 상태에서는 국가를 해체할 수 없습니다.";
        for (War w : wars.values()) if (w.involves(n.id)) return "전쟁 중에는 국가를 해체할 수 없습니다.";
        dissolve(n);
        return null;
    }

    public String adminDisband(String name) {
        Nation n = byName(name);
        if (n == null) return "그런 국가가 없습니다.";
        dissolve(n);
        return null;
    }

    private void dissolve(Nation n) {
        for (Claim c : claimsOfNation(n.id)) claims.remove(c.id);
        removeMagnet(n);
        for (Nation o : nations.values()) {
            if (n.id.equals(o.overlordNation)) { o.overlordNation = null; o.overlordPlayer = null; o.rebelling = false; }
        }
        wars.entrySet().removeIf(e -> e.getValue().involves(n.id));
        peaceRequests.clear();
        nations.remove(n.id);
        touch();
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<gray>국가 <yellow>" + Msg.esc(n.name) + "</yellow> 이(가) 해체되었습니다."));
    }

    // ===================== 정복(자석석) =====================
    public String magnetBreak(Player p, Nation vn) {
        Nation bn = nationOf(p.getUniqueId());
        if (bn == null) return "국가에 소속되어야 정복할 수 있습니다.";
        if (bn.id.equals(vn.id)) return "자신의 자석석은 캘 수 없습니다.";
        if (!atWar(bn, vn)) return "전쟁 중인 국가의 자석석만 캘 수 있습니다.";
        if (requireOnline() && !anyOnline(vn)) return "상대 시민이 모두 오프라인이라 정복할 수 없습니다.";
        World w = Bukkit.getWorld(vn.magnetWorld);
        if (w != null) {
            Firework fw = w.spawn(new Location(w, vn.mx + 0.5, vn.my + 1, vn.mz + 0.5), Firework.class);
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.RED, Color.YELLOW).trail(true).build());
            fw.setFireworkMeta(fm);
            fw.detonate();
        }
        plugin.gui().openConquest(p, bn.id, vn.id);
        return null;
    }

    private Nation[] validateConquest(Player p, UUID bnId, UUID vnId) {
        Nation bn = nationOf(p.getUniqueId());
        Nation vn = nations.get(vnId);
        if (bn == null || vn == null || !bn.id.equals(bnId)) { Msg.send(p, "<red>정복 상태가 유효하지 않습니다."); return null; }
        if (!atWar(bn, vn)) { Msg.send(p, "<red>더 이상 전쟁 상태가 아닙니다."); return null; }
        if (requireOnline() && !anyOnline(vn)) { Msg.send(p, "<red>상대 시민이 모두 오프라인입니다."); return null; }
        return new Nation[]{bn, vn};
    }

    public void chooseColony(Player p, UUID bnId, UUID vnId) {
        Nation[] r = validateConquest(p, bnId, vnId);
        if (r == null) return;
        Nation bn = r[0], vn = r[1];
        vn.overlordNation = bn.id;
        vn.overlordPlayer = p.getUniqueId();
        vn.lockUntil = System.currentTimeMillis() + ci("war.conquest-lock-minutes", 10) * 60_000L;
        vn.rebelling = false;
        endWar(bn, vn);
        long taken = 0;
        for (UUID u : vn.members) {
            long b = balance(u);
            if (b > 0) { balances.put(u, 0L); taken += b; }
        }
        if (taken > 0) addBalance(p.getUniqueId(), taken);
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<gold>👑 " + Msg.esc(vn.name) + " 이(가) " + Msg.esc(p.getName()) + " 님의 식민지가 되었습니다!"));
        Msg.send(p, "<green>식민지 시민들의 돈 " + fmt(taken) + " 을(를) 빼앗았습니다.");
        broadcast(vn, "<red>국가가 식민지가 되어 모든 돈을 빼앗겼습니다. 상태창의 '혁명'으로 반란을 일으킬 수 있습니다.");
    }

    public void chooseAnnex(Player p, UUID bnId, UUID vnId) {
        Nation[] r = validateConquest(p, bnId, vnId);
        if (r == null) return;
        annexFinalize(r[0], r[1], p);
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<gold>👑 " + Msg.esc(r[1].name) + " 의 땅이 " + Msg.esc(p.getName()) + " 님에게 병합되었습니다!"));
    }

    private void annexFinalize(Nation bn, Nation vn, Player conqueror) {
        removeMagnet(vn);
        for (Claim c : claimsOfNation(vn.id)) { c.nation = bn.id; c.owner = conqueror.getUniqueId(); c.annexed = true; }
        List<UUID> moved = new ArrayList<>(vn.members);
        bn.members.addAll(vn.members);
        for (Nation o : nations.values()) {
            if (!vn.id.equals(o.overlordNation)) continue;
            if (o.id.equals(bn.id)) { o.overlordNation = null; o.overlordPlayer = null; o.rebelling = false; }
            else { o.overlordNation = bn.id; o.overlordPlayer = conqueror.getUniqueId(); }
        }
        wars.entrySet().removeIf(e -> e.getValue().involves(vn.id));
        peaceRequests.clear();
        nations.remove(vn.id);
        touch();
        for (UUID u : moved) {
            Player pl = Bukkit.getPlayer(u);
            if (pl != null && !pl.getUniqueId().equals(conqueror.getUniqueId())) plugin.gui().openAnnexChoice(pl, bn);
        }
    }

    public List<Nation> colonies(UUID overlordPlayer) {
        List<Nation> l = new ArrayList<>();
        for (Nation n : nations.values()) if (overlordPlayer.equals(n.overlordPlayer)) l.add(n);
        return l;
    }

    private String colonyCheck(Player p, Nation col) {
        if (!p.getUniqueId().equals(col.overlordPlayer)) return "당신의 식민지가 아닙니다.";
        long left = col.lockUntil - System.currentTimeMillis();
        if (left > 0) return "아직 변경할 수 없습니다. (" + (left / 1000) + "초 남음)";
        return null;
    }

    public String annexColony(Player p, Nation col) {
        String err = colonyCheck(p, col);
        if (err != null) return err;
        Nation bn = nationOf(p.getUniqueId());
        if (bn == null) return "국가에 소속되어 있지 않습니다.";
        annexFinalize(bn, col, p);
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<gold>👑 식민지 " + Msg.esc(col.name) + " 이(가) " + Msg.esc(p.getName()) + " 님의 땅으로 병합되었습니다!"));
        return null;
    }

    public String releaseColony(Player p, Nation col) {
        String err = colonyCheck(p, col);
        if (err != null) return err;
        Nation bn = nations.get(col.overlordNation);
        col.overlordNation = null;
        col.overlordPlayer = null;
        col.rebelling = false;
        if (bn != null) endWar(bn, col);
        touch();
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<green>🕊 " + Msg.esc(col.name) + " 이(가) 독립국가가 되었습니다!"));
        return null;
    }

    // ===================== 혁명 =====================
    public String startRebellion(Player p) {
        Nation n = nationOf(p.getUniqueId());
        if (n == null || !n.isColony()) return "식민지 시민만 혁명을 일으킬 수 있습니다.";
        if (n.rebelling) return "이미 반란이 진행 중입니다.";
        Nation o = nations.get(n.overlordNation);
        if (o == null) { n.overlordNation = null; n.overlordPlayer = null; touch(); return "종주국이 사라져 독립되었습니다."; }
        if (requireOnline() && !anyOnline(o)) return "종주국 시민이 모두 오프라인입니다.";
        n.rebelling = true;
        declareWar(n, o, "식민지 반란! 종주국의 최고인을 처치하면 독립", null);
        broadcast(n, "<red><bold>반란이 시작됩니다!</bold> <gray>종주국의 최고인을 처치하면 독립할 수 있습니다.");
        for (UUID u : n.members) {
            Player pl = Bukkit.getPlayer(u);
            if (pl == null) continue;
            List<ItemStack> kit = Items.rebelKit();
            PlayerInventory inv = pl.getInventory();
            if (inv.getHelmet() == null) inv.setHelmet(kit.get(0)); else giveItem(pl, kit.get(0));
            if (inv.getChestplate() == null) inv.setChestplate(kit.get(1)); else giveItem(pl, kit.get(1));
            if (inv.getLeggings() == null) inv.setLeggings(kit.get(2)); else giveItem(pl, kit.get(2));
            if (inv.getBoots() == null) inv.setBoots(kit.get(3)); else giveItem(pl, kit.get(3));
            giveItem(pl, kit.get(4));
        }
        return null;
    }

    /** 반란 성공: 종주국의 최고인(리더)을 처치 -> 독립 */
    public void rebelSuccess(Nation col, Nation over) {
        col.overlordNation = null;
        col.overlordPlayer = null;
        col.rebelling = false;
        endWar(col, over);
        Bukkit.broadcast(Msg.c(Msg.PREFIX + "<green>🏴 반란 성공! " + Msg.esc(col.name) + " 이(가) 종주국 " + Msg.esc(over.name) + " 의 최고인을 처치하고 독립했습니다!"));
    }

    // ===================== 리스폰 위치 =====================
    public Location landSpawn(Player p) {
        Claim c = null;
        for (Claim x : claimsOf(p.getUniqueId())) { c = x; break; }
        Nation n = nationOf(p.getUniqueId());
        if (c == null && n != null) c = claims.get(n.capitalClaim);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.world);
        if (w == null) return null;
        int x = c.centerX(), z = c.centerZ();
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    // ===================== 표시용 =====================
    public List<String> landLines(UUID u) {
        List<String> lines = new ArrayList<>();
        List<Claim> own = new ArrayList<>(), ann = new ArrayList<>();
        for (Claim c : claimsOf(u)) (c.annexed ? ann : own).add(c);
        long area = 0;
        for (Claim c : own) area += c.area();
        lines.add("<gray>내 땅: <white>" + own.size() + "곳 / " + String.format("%,d", area) + "블록");
        int k = 0;
        for (Claim c : own) {
            if (k++ >= 10) { lines.add("<gray>..."); break; }
            lines.add("<white>" + c.coord());
        }
        if (!ann.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Claim c : ann) { if (sb.length() > 0) sb.append(", "); sb.append(c.coord()); }
            lines.add("<gold>병합한 땅: <white>" + sb);
        }
        return lines;
    }

    public void sendInfo(Player to, Nation n) {
        Msg.send(to, "<yellow><bold>" + Msg.esc(n.name));
        to.sendMessage(Msg.c("<gray>최고인(땅 주인): <white>" + nameOf(n.leader)));
        StringBuilder sb = new StringBuilder();
        for (UUID u : n.members) sb.append(nameOf(u)).append(" ");
        to.sendMessage(Msg.c("<gray>시민(" + n.members.size() + "): <white>" + sb));
        long area = 0;
        for (Claim c : claimsOfNation(n.id)) area += c.area();
        to.sendMessage(Msg.c("<gray>땅: <white>" + String.format("%,d", area) + "블록"));
        if (n.isColony()) {
            Nation o = nations.get(n.overlordNation);
            to.sendMessage(Msg.c("<gray>상태: <red>식민지 <gray>(종주국 " + (o == null ? "?" : Msg.esc(o.name)) + ")" + (n.rebelling ? " <red>반란 중" : "")));
        }
        StringBuilder ws = new StringBuilder();
        for (War w : wars.values()) {
            if (!w.involves(n.id)) continue;
            Nation o = nations.get(w.other(n.id));
            if (o != null) ws.append(Msg.esc(o.name)).append(" ");
        }
        if (ws.length() > 0) to.sendMessage(Msg.c("<gray>전쟁 중: <red>" + ws));
    }

    // ===================== 저장/불러오기 =====================
    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Nation n : nations.values()) {
            String b = "nations." + n.id;
            y.set(b + ".name", n.name);
            y.set(b + ".leader", n.leader.toString());
            y.set(b + ".capital-claim", n.capitalClaim == null ? null : n.capitalClaim.toString());
            y.set(b + ".members", n.members.stream().map(UUID::toString).toList());
            y.set(b + ".overlord-nation", n.overlordNation == null ? null : n.overlordNation.toString());
            y.set(b + ".overlord-player", n.overlordPlayer == null ? null : n.overlordPlayer.toString());
            y.set(b + ".lock-until", n.lockUntil);
            y.set(b + ".rebelling", n.rebelling);
            y.set(b + ".magnet.world", n.magnetWorld);
            y.set(b + ".magnet.x", n.mx);
            y.set(b + ".magnet.y", n.my);
            y.set(b + ".magnet.z", n.mz);
        }
        for (Claim c : claims.values()) {
            String b = "claims." + c.id;
            y.set(b + ".world", c.world);
            y.set(b + ".x1", c.x1);
            y.set(b + ".z1", c.z1);
            y.set(b + ".x2", c.x2);
            y.set(b + ".z2", c.z2);
            y.set(b + ".owner", c.owner.toString());
            y.set(b + ".nation", c.nation.toString());
            y.set(b + ".annexed", c.annexed);
        }
        int i = 0;
        for (War w : wars.values()) {
            String b = "wars." + (i++);
            y.set(b + ".declarer", w.declarer.toString());
            y.set(b + ".target", w.target.toString());
            y.set(b + ".since", w.since);
        }
        for (var e : toll.entrySet()) y.set("toll." + e.getKey(), e.getValue());
        for (var e : balances.entrySet()) y.set("balance." + e.getKey(), e.getValue());
        y.set("kit-given", kitGiven.stream().map(UUID::toString).toList());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(new File(plugin.getDataFolder(), "data.yml"));
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("data.yml 저장 실패: " + ex.getMessage());
        }
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "data.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection ns = y.getConfigurationSection("nations");
        if (ns != null) for (String k : ns.getKeys(false)) {
            ConfigurationSection s = ns.getConfigurationSection(k);
            if (s == null) continue;
            Nation n = new Nation(UUID.fromString(k), s.getString("name", "?"), UUID.fromString(s.getString("leader")));
            String cap = s.getString("capital-claim");
            if (cap != null) n.capitalClaim = UUID.fromString(cap);
            for (String m : s.getStringList("members")) n.members.add(UUID.fromString(m));
            String on = s.getString("overlord-nation");
            if (on != null) n.overlordNation = UUID.fromString(on);
            String op = s.getString("overlord-player");
            if (op != null) n.overlordPlayer = UUID.fromString(op);
            n.lockUntil = s.getLong("lock-until");
            n.rebelling = s.getBoolean("rebelling");
            n.magnetWorld = s.getString("magnet.world");
            n.mx = s.getInt("magnet.x");
            n.my = s.getInt("magnet.y");
            n.mz = s.getInt("magnet.z");
            nations.put(n.id, n);
        }
        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs != null) for (String k : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(k);
            if (s == null) continue;
            Claim c = new Claim(UUID.fromString(k), s.getString("world"), s.getInt("x1"), s.getInt("z1"), s.getInt("x2"), s.getInt("z2"),
                    UUID.fromString(s.getString("owner")), UUID.fromString(s.getString("nation")), s.getBoolean("annexed"));
            claims.put(c.id, c);
        }
        ConfigurationSection ws = y.getConfigurationSection("wars");
        if (ws != null) for (String k : ws.getKeys(false)) {
            ConfigurationSection s = ws.getConfigurationSection(k);
            if (s == null) continue;
            UUID a = UUID.fromString(s.getString("declarer"));
            UUID b = UUID.fromString(s.getString("target"));
            wars.put(War.key(a, b), new War(a, b, s.getLong("since")));
        }
        ConfigurationSection ts = y.getConfigurationSection("toll");
        if (ts != null) for (String k : ts.getKeys(false)) toll.put(UUID.fromString(k), ts.getInt(k));
        ConfigurationSection bs = y.getConfigurationSection("balance");
        if (bs != null) for (String k : bs.getKeys(false)) balances.put(UUID.fromString(k), bs.getLong(k));
        for (String k : y.getStringList("kit-given")) kitGiven.add(UUID.fromString(k));
        reindex();
    }
}
