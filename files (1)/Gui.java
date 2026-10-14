package com.nationwar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * 모든 창(GUI). 상자 창과 같지만 아이템은 가져갈 수 없고, 아이템(블록)을 클릭하면 그 항목이 선택된다.
 * 좌클릭/우클릭 구분은 없다.
 */
public final class Gui {
    private final NationWarPlugin plugin;
    private final NationManager m;
    private final Set<UUID> invulnerable = new HashSet<>();
    private final Map<UUID, BiFunction<Player, String, Boolean>> prompts = new HashMap<>();
    private final Map<UUID, Long> passCooldown = new HashMap<>();

    public Gui(NationWarPlugin plugin, NationManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    public boolean isInvulnerable(UUID u) { return invulnerable.contains(u); }

    public boolean hasPrompt(UUID u) { return prompts.containsKey(u); }

    public void onQuit(UUID u) {
        invulnerable.remove(u);
        prompts.remove(u);
    }

    private void result(Player p, String err, String ok) {
        if (err != null) Msg.send(p, "<red>" + err);
        else if (ok != null) Msg.send(p, "<green>" + ok);
    }

    private void back(MenuHolder h, int slot) {
        h.set(slot, Items.icon(Material.ARROW, "<yellow>뒤로가기"), this::openStatus);
    }

    private void pane(MenuHolder h, int from, int to) {
        for (int i = from; i <= to; i++) h.set(i, Items.icon(Material.GRAY_STAINED_GLASS_PANE, " "), null);
    }

    // ===================== 채팅 입력 =====================
    /** handler가 true를 반환하면 입력 종료, false면 다시 입력 받음 */
    private void startPrompt(Player p, List<String> redLines, BiFunction<Player, String, Boolean> handler) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), handler);
        for (String l : redLines) p.sendMessage(Msg.c("<red>" + l));
    }

    public void handleChat(Player p, String text) {
        BiFunction<Player, String, Boolean> fn = prompts.get(p.getUniqueId());
        if (fn == null) return;
        if (fn.apply(p, text.trim())) prompts.remove(p.getUniqueId());
    }

    // ===================== 상태창 (Shift + 우클릭 / Shift + F) =====================
    public void openStatus(Player p) {
        Nation n = m.nationOf(p.getUniqueId());
        MenuHolder h = new MenuHolder("<dark_green>상태창", 3);
        if (n != null && n.isColony()) {
            h.set(10, Items.icon(Material.GRASS_BLOCK, "<red>혁명",
                    "<gray>식민지 시민은 땅 관리를 할 수 없습니다.", "<red>클릭: 반란을 시작합니다!"), pl -> {
                pl.closeInventory();
                String err = m.startRebellion(pl);
                if (err != null) Msg.send(pl, "<red>" + err);
            });
        } else {
            h.set(10, Items.icon(Material.GRASS_BLOCK, "<green>땅 관리",
                    "<gray>내 땅 좌표, 통행료, 금 괭이, 건국, 탈퇴"), this::openLand);
        }
        h.set(12, Items.icon(Material.GOLD_INGOT, "<gold>은행",
                "<gray>잔액: <white>" + NationManager.fmt(m.balance(p.getUniqueId())), "<gray>입금 / 출금"), this::openBank);
        h.set(14, Items.icon(Material.CHEST, "<aqua>상점", "<gray>상품 구매 / 상품 판매"), pl -> openShop(pl, false));
        h.set(16, Items.icon(Material.RED_BANNER, "<red>선전포고 깃발 받기",
                "<gray>인벤토리(셜커 상자 포함)에 없을 때 1개 지급"), pl -> {
            String err = m.giveWarFlag(pl);
            result(pl, err, "선전포고 깃발을 받았습니다.");
        });
        p.openInventory(h.inv);
    }

    // ===================== 땅 관리 =====================
    public void openLand(Player p) {
        UUID u = p.getUniqueId();
        Nation n = m.nationOf(u);
        MenuHolder h = new MenuHolder("<dark_green>땅 관리", 3);
        h.set(10, Items.icon(Material.MAP, "<green>내 땅 좌표", m.landLines(u)), null);
        h.set(11, Items.icon(Material.OAK_SIGN, "<gold>통행료 설정",
                "<gray>현재 통행료: <white>" + NationManager.fmt(m.toll.getOrDefault(u, plugin.getConfig().getInt("pass.default-toll", 0))),
                "<gray>0원 = 무료, 1원 ~ 10000원", "<yellow>클릭: 채팅으로 입력"), pl -> {
            if (!m.ownsLand(pl.getUniqueId())) { Msg.send(pl, "<red>땅 주인만 통행료를 설정할 수 있습니다."); return; }
            startTollPrompt(pl);
        });
        h.set(12, Items.icon(Material.GOLDEN_HOE, "<gold>금 괭이 받기", "<gray>인벤토리에 없을 때 지급"), pl ->
                result(pl, m.giveLandTool(pl), "땅 선택 괭이를 받았습니다."));
        if (n == null) {
            h.set(14, Items.icon(Material.WHITE_BANNER, "<aqua>건국",
                    "<gray>금 괭이로 고른 범위로 국가를 세웁니다.", "<gray>(블록 우클릭 → 다른 블록 좌클릭 후 사용)"), pl -> {
                NationManager.Plan pln = m.plan(pl, true);
                if (pln.error() != null) { Msg.send(pl, "<red>" + pln.error()); return; }
                startFoundPrompt(pl, pln);
            });
        }
        if (!m.colonies(u).isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (Nation col : m.colonies(u)) lore.add("<white>" + Msg.esc(col.name) + " <gray>(시민 " + col.members.size() + "명)");
            lore.add("<yellow>클릭: 식민지 관리");
            h.set(13, Items.icon(Material.RED_BANNER, "<red>식민지 땅", lore), this::openColonies);
        }
        h.set(16, Items.icon(Material.BARRIER, "<red>국가 탈퇴", "<gray>소유한 땅을 모두 버리고 떠납니다."), pl ->
                openConfirm(pl, "<dark_red>국가 탈퇴", "<gray>정말 탈퇴하시겠습니까? 땅은 모두 사라집니다.", () ->
                        result(pl, m.leave(pl), "국가를 떠났습니다.")));
        back(h, 22);
        p.openInventory(h.inv);
    }

    private void startTollPrompt(Player p) {
        startPrompt(p, List.of(
                "통행료를 채팅으로 입력하세요.",
                "숫자만 입력하세요. (0 = 무료, 1원 ~ 10000원)",
                "취소하려면 '취소' 라고 입력하세요."), (pl, text) -> {
            if (text.equals("취소")) { pl.sendMessage(Msg.c("<red>통행료 설정이 취소되었습니다.")); return true; }
            long v;
            try {
                v = Long.parseLong(text);
            } catch (NumberFormatException ex) {
                pl.sendMessage(Msg.c("<red>숫자만 입력하세요. (0 = 무료, 1~10000, 취소하려면 '취소')"));
                return false;
            }
            if (v < 0 || v > 10_000) {
                pl.sendMessage(Msg.c("<red>통행료는 0원(무료) 또는 1원~10000원까지만 가능합니다. 10000원을 넘을 수 없습니다. 다시 입력하세요."));
                return false;
            }
            String err = m.setToll(pl, (int) v);
            if (err != null) pl.sendMessage(Msg.c("<red>" + err));
            else pl.sendMessage(Msg.c(v == 0 ? "<red>통행료를 무료(0원)로 설정했습니다." : "<red>통행료를 " + NationManager.fmt(v) + " 으로 설정했습니다."));
            return true;
        });
    }

    private void startFoundPrompt(Player p, NationManager.Plan pln) {
        startPrompt(p, List.of(
                "국가 이름을 채팅으로 입력하세요. (한글/영문/숫자/_ 2~12자)",
                "선택한 땅: " + String.format("%,d", pln.area()) + "블록 / 가격 " + NationManager.fmt(pln.price()),
                "취소하려면 '취소' 라고 입력하세요."), (pl, text) -> {
            if (text.equals("취소")) { pl.sendMessage(Msg.c("<red>건국이 취소되었습니다.")); return true; }
            String nameErr = m.checkName(text);
            if (nameErr != null) { pl.sendMessage(Msg.c("<red>" + nameErr + " 다시 입력하세요.")); return false; }
            String err = m.createNation(pl, text);
            if (err != null) pl.sendMessage(Msg.c("<red>" + err));
            else Msg.send(pl, "<green>건국했습니다! 수도에 자석석이 생성되었습니다.");
            return true;
        });
    }

    // ===================== 은행 =====================
    public void openBank(Player p) {
        MenuHolder h = new MenuHolder("<gold>은행", 3);
        h.set(4, Items.icon(Material.GOLD_INGOT, "<gold>은행", "<gray>잔액: <white>" + NationManager.fmt(m.balance(p.getUniqueId()))), null);
        h.set(11, Items.icon(Material.HOPPER, "<green>전액 입금", "<gray>인벤토리의 종이 수표를 모두 입금합니다."), pl -> {
            long v = m.deposit(pl);
            result(pl, v <= 0 ? "입금할 수표가 없습니다." : null, "수표 " + NationManager.fmt(v) + " 을(를) 입금했습니다.");
            openBank(pl);
        });
        long[] amounts = {1, 10, 100, 1000, 10000};
        for (int i = 0; i < amounts.length; i++) {
            final long a = amounts[i];
            h.set(13 + i, Items.icon(Material.PAPER, "<yellow>" + NationManager.fmt(a) + " 출금", "<gray>종이 수표로 출금합니다."), pl -> {
                result(pl, m.withdraw(pl, a), NationManager.fmt(a) + " 수표를 출금했습니다.");
                openBank(pl);
            });
        }
        back(h, 22);
        p.openInventory(h.inv);
    }

    // ===================== 상점 (위쪽: 상품 구매 | 상품 판매) =====================
    public void openShop(Player p, boolean sellTab) {
        MenuHolder h = new MenuHolder("<aqua>상점", 6);
        pane(h, 0, 8);
        h.set(2, Items.icon(Material.CHEST, "<green>상품 구매", sellTab ? "<gray>클릭: 구매 탭 보기" : "<yellow>▶ 현재 보기"), pl -> openShop(pl, false));
        h.set(4, Items.icon(Material.GOLD_INGOT, "<gold>잔액", "<white>" + NationManager.fmt(m.balance(p.getUniqueId()))), null);
        h.set(6, Items.icon(Material.GOLD_BLOCK, "<gold>상품 판매", sellTab ? "<yellow>▶ 현재 보기" : "<gray>클릭: 판매 탭 보기"), pl -> openShop(pl, true));

        if (!sellTab) {
            long price = plugin.getConfig().getLong("shop.magnet-pickaxe-price", 2000);
            h.set(10, Items.icon(Material.IRON_PICKAXE, "<gold>자석석 곡괭이",
                    "<gray>자석석을 약 5초만에 캡니다.", "<gray>자석석 외에는 캘 수 없습니다.", "<yellow>가격: " + NationManager.fmt(price), "<green>클릭: 구매"), pl -> {
                if (!m.charge(pl.getUniqueId(), price)) { Msg.send(pl, "<red>잔액이 부족합니다."); return; }
                m.giveItem(pl, Items.magnetPick());
                Msg.send(pl, "<green>자석석 곡괭이를 구매했습니다.");
                openShop(pl, false);
            });
        } else {
            int slot = 9;
            for (Map.Entry<Material, Long> e : m.sellPrices().entrySet()) {
                if (slot >= 54) break;
                final Material mat = e.getKey();
                final long price = e.getValue();
                int have = m.countPlain(p, mat);
                h.set(slot++, Items.sellIcon(mat, List.of(
                        "<yellow>판매가: " + NationManager.fmt(price) + " (1개)",
                        "<gray>보유: " + have + "개",
                        "<green>클릭: 모두 판매" + (have > 0 ? " (" + NationManager.fmt(price * have) + ")" : ""))), pl -> {
                    long earned = m.sellAll(pl, mat);
                    if (earned <= 0) Msg.send(pl, "<red>판매할 아이템이 없습니다.");
                    else Msg.send(pl, "<green>" + NationManager.fmt(earned) + " 을(를) 받았습니다.");
                    openShop(pl, true);
                });
            }
        }
        p.openInventory(h.inv);
    }

    // ===================== 출입 시도: 통행증 창 =====================
    public void tryOpenPass(Player p, Claim c) {
        long now = System.currentTimeMillis();
        if (passCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) return;
        passCooldown.put(p.getUniqueId(), now + 1500);
        Bukkit.getScheduler().runTask(plugin, () -> openPass(p, c));
    }

    private void openPass(Player p, Claim c) {
        Nation tn = m.nations.get(c.nation);
        if (tn == null || !p.isOnline()) return;
        long fee = m.tollOf(c);
        MenuHolder h = new MenuHolder("<dark_red>출입금지: " + Msg.esc(tn.name), 1);
        h.set(2, Items.icon(Material.LIME_WOOL, "<green>통행증 발행",
                "<gray>통행료: <white>" + NationManager.fmt(fee),
                "<gray>유효시간: " + plugin.getConfig().getInt("pass.duration-seconds", 120) + "초",
                "<gray>통행 중에는 상호작용/PvP 불가"), pl -> {
            pl.closeInventory();
            String err = m.buyPass(pl, c);
            if (err != null) Msg.send(pl, "<red>" + err);
        });
        h.set(6, Items.icon(Material.RED_WOOL, "<yellow>비켜가기", "<gray>들어가지 않고 돌아갑니다."), Player::closeInventory);
        h.onClose = () -> passCooldown.put(p.getUniqueId(), System.currentTimeMillis() + 3000);
        p.openInventory(h.inv);
    }

    // ===================== 정복 선택: 왼쪽 초록 양털(병합) / 오른쪽 빨간 양털(식민지) =====================
    public void openConquest(Player p, UUID bnId, UUID vnId) {
        Nation vn = m.nations.get(vnId);
        if (vn == null) return;
        MenuHolder h = new MenuHolder("<dark_red>정복 방식 선택: " + Msg.esc(vn.name), 1);
        int min = plugin.getConfig().getInt("war.conquest-lock-minutes", 10);
        h.set(2, Items.icon(Material.LIME_WOOL, "<green>병합",
                "<gray>상대 땅이 내 땅이 됩니다.", "<gray>상대 시민은 머무르거나 떠날 수 있습니다.", "<red>식민지로 변경 불가"), pl -> {
            pl.closeInventory();
            m.chooseAnnex(pl, bnId, vnId);
        });
        h.set(6, Items.icon(Material.RED_WOOL, "<red>식민지",
                "<gray>상대 땅은 '식민지 땅'으로 따로 분류됩니다.", "<gray>시민들의 돈을 모두 빼앗습니다.", "<red>선택 후 " + min + "분간 변경 불가"), pl -> {
            pl.closeInventory();
            m.chooseColony(pl, bnId, vnId);
        });
        invulnerable.add(p.getUniqueId());
        h.onClose = () -> invulnerable.remove(p.getUniqueId()); // 창이 켜져 있는 동안 무적
        p.openInventory(h.inv);
    }

    // ===================== 병합된 시민: 머무르기 / 떠나기 =====================
    public void openAnnexChoice(Player p, Nation bn) {
        MenuHolder h = new MenuHolder("<dark_red>정복당했습니다", 1);
        h.set(2, Items.icon(Material.LIME_WOOL, "<green>머무르기", "<gray>" + Msg.esc(bn.name) + " 땅의 시민이 됩니다."), pl -> {
            pl.closeInventory();
            Msg.send(pl, "<yellow>" + Msg.esc(bn.name) + " 의 시민이 되었습니다.");
        });
        h.set(6, Items.icon(Material.RED_WOOL, "<red>떠나기", "<gray>이 땅을 떠납니다."), pl -> {
            pl.closeInventory();
            m.leaveFree(pl.getUniqueId());
            Msg.send(pl, "<yellow>땅을 떠났습니다.");
        });
        p.openInventory(h.inv);
    }

    // ===================== 식민지 관리 =====================
    public void openColonies(Player p) {
        MenuHolder h = new MenuHolder("<dark_red>식민지 땅", 3);
        int slot = 0;
        for (Nation col : m.colonies(p.getUniqueId())) {
            if (slot >= 18) break;
            List<String> lore = new ArrayList<>();
            long area = 0;
            for (Claim c : m.claimsOfNation(col.id)) area += c.area();
            lore.add("<gray>시민 " + col.members.size() + "명 / 땅 " + String.format("%,d", area) + "블록");
            int k = 0;
            for (Claim c : m.claimsOfNation(col.id)) {
                if (k++ >= 6) { lore.add("<gray>..."); break; }
                lore.add("<white>" + c.coord());
            }
            long left = col.lockUntil - System.currentTimeMillis();
            lore.add(left > 0 ? "<red>변경 불가: " + (left / 1000) + "초 남음" : "<green>변경 가능");
            lore.add("<yellow>클릭: 식민지 변경");
            h.set(slot++, Items.icon(Material.WHITE_BANNER, "<gold>" + Msg.esc(col.name), lore), pl -> openColonyChange(pl, col));
        }
        h.set(22, Items.icon(Material.ARROW, "<yellow>뒤로가기"), this::openLand);
        p.openInventory(h.inv);
    }

    /** 식민지 변경: 왼쪽 초록 양털(병합) / 오른쪽 빨간 양털(독립국가) */
    public void openColonyChange(Player p, Nation col) {
        MenuHolder h = new MenuHolder("<dark_red>식민지 변경: " + Msg.esc(col.name), 1);
        long left = col.lockUntil - System.currentTimeMillis();
        String lock = left > 0 ? "<red>변경 불가: " + (left / 1000) + "초 남음" : "<green>변경 가능";
        h.set(2, Items.icon(Material.LIME_WOOL, "<green>병합", "<gray>식민지가 내 땅이 됩니다.", lock), pl -> {
            result(pl, m.annexColony(pl, col), "병합되었습니다.");
            pl.closeInventory();
        });
        h.set(4, Items.icon(Material.WHITE_BANNER, "<gold>" + Msg.esc(col.name), lock), null);
        h.set(6, Items.icon(Material.RED_WOOL, "<red>독립국가", "<gray>식민지가 독립국가가 됩니다.", lock), pl -> {
            result(pl, m.releaseColony(pl, col), "독립국가가 되었습니다.");
            pl.closeInventory();
        });
        p.openInventory(h.inv);
    }

    // ===================== 확인 창 =====================
    public void openConfirm(Player p, String title, String desc, Runnable yes) {
        MenuHolder h = new MenuHolder(title, 1);
        h.set(2, Items.icon(Material.LIME_WOOL, "<green>확인", desc), pl -> {
            pl.closeInventory();
            yes.run();
        });
        h.set(6, Items.icon(Material.RED_WOOL, "<red>취소"), Player::closeInventory);
        p.openInventory(h.inv);
    }

    public void openLandPurchase(Player p, NationManager.Plan plan) {
        openConfirm(p, "<dark_green>땅 구매 확인",
                "<gray>" + String.format("%,d", plan.area()) + "블록 / 가격 " + NationManager.fmt(plan.price()), () ->
                        result(p, m.buyLand(p), "땅을 구매했습니다!"));
    }
}
