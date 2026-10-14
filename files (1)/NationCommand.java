package com.nationwar;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class NationCommand implements CommandExecutor, TabCompleter {
    /** 일반 유저에게 허용 여부를 설정할 수 있는 하위 명령어 */
    static final List<String> SUBS = List.of("건국", "땅포기", "초대", "수락", "추방", "해체", "정보", "목록", "전쟁", "휴전", "잔액", "상태", "규칙");

    private final NationWarPlugin plugin;
    private final NationManager m;

    public NationCommand(NationWarPlugin plugin, NationManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private void done(Player p, String err, String ok) {
        if (err != null) Msg.send(p, "<red>" + err);
        else if (ok != null) Msg.send(p, "<green>" + ok);
    }

    private void help(Player p) {
        String[] lines = {
                "상태창: Shift+우클릭 (또는 Shift+F) - 은행/상점/땅 관리/통행료/선전포고 깃발",
                "땅 구매: 금 괭이로 블록 우클릭 → 다른 블록 좌클릭 (1칸당 " + plugin.getConfig().getInt("economy.price-per-block", 10) + "원)",
                "/국가 건국 <이름> - 선택한 땅으로 건국 (상태창 > 땅 관리 > 건국 도 가능)",
                "/국가 땅포기 / 초대 <플레이어> / 수락 / 추방 <이름> / 해체",
                "/국가 정보 [국가] / 목록 / 전쟁 / 휴전 <국가> / 잔액 / 상태 / 규칙",
                "※ 관리자가 허용한 명령어만 일반 유저가 사용할 수 있습니다."
        };
        Msg.send(p, "<yellow>== 국가전쟁 도움말 ==");
        for (String l : lines) p.sendMessage(Msg.c("<gray>" + l));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (args.length == 0 || args[0].equals("도움말")) { help(p); return true; }

        boolean admin = p.hasPermission("nationwar.admin");
        if (SUBS.contains(args[0]) && !admin && !plugin.commandAllowed(args[0])) {
            Msg.send(p, "<red>관리자만 사용할 수 있는 명령어입니다. <gray>(상태창은 Shift+우클릭 / Shift+F)");
            return true;
        }

        switch (args[0]) {
            case "건국" -> {
                if (args.length < 2) Msg.send(p, "<gray>사용법: <white>/국가 건국 <이름>");
                else done(p, m.createNation(p, args[1]), "건국했습니다! 수도에 자석석이 생성되었습니다.");
            }
            case "땅포기" -> done(p, m.unclaim(p), "땅을 포기했습니다.");
            case "초대" -> {
                if (args.length < 2) { Msg.send(p, "<gray>사용법: <white>/국가 초대 <플레이어>"); break; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) Msg.send(p, "<red>접속 중인 플레이어가 아닙니다.");
                else done(p, m.invite(p, t), "초대를 보냈습니다.");
            }
            case "수락" -> done(p, m.accept(p), "국가의 시민이 되었습니다.");
            case "추방" -> {
                if (args.length < 2) Msg.send(p, "<gray>사용법: <white>/국가 추방 <이름>");
                else done(p, m.kick(p, args[1]), "추방했습니다.");
            }
            case "해체" -> done(p, m.disband(p), null);
            case "정보" -> {
                Nation n = args.length >= 2 ? m.byName(args[1]) : m.nationOf(p.getUniqueId());
                if (n == null) Msg.send(p, "<red>국가를 찾을 수 없습니다.");
                else m.sendInfo(p, n);
            }
            case "목록" -> {
                Msg.send(p, "<yellow>== 국가 목록 ==");
                for (Nation n : m.nations.values()) {
                    long area = 0;
                    for (Claim c : m.claimsOfNation(n.id)) area += c.area();
                    p.sendMessage(Msg.c("<gray>- <white>" + Msg.esc(n.name) + " <gray>(시민 " + n.members.size()
                            + ", 땅 " + String.format("%,d", area) + "블록)"));
                }
            }
            case "전쟁" -> {
                Msg.send(p, "<yellow>== 진행 중인 전쟁 ==");
                for (War w : m.wars.values()) {
                    Nation a = m.nations.get(w.declarer), b = m.nations.get(w.target);
                    if (a != null && b != null)
                        p.sendMessage(Msg.c("<red>⚔ <white>" + Msg.esc(a.name) + " <gray>→ <white>" + Msg.esc(b.name)));
                }
            }
            case "휴전" -> {
                if (args.length < 2) Msg.send(p, "<gray>사용법: <white>/국가 휴전 <국가>");
                else done(p, m.peace(p, args[1]), null);
            }
            case "잔액" -> Msg.send(p, "<gold>내 잔액: <white>" + NationManager.fmt(m.balance(p.getUniqueId())));
            case "상태" -> plugin.gui().openStatus(p);
            case "규칙" -> {
                Msg.send(p, "<yellow>== 서버 규칙 ==");
                int i = 1;
                for (String r : plugin.getConfig().getStringList("rules"))
                    p.sendMessage(Msg.c("<gray>" + (i++) + ". <white>" + Msg.esc(r)));
            }
            default -> help(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("nationwar.admin");
        if (a.length == 1) {
            for (String k : SUBS) if (k.startsWith(a[0]) && (admin || plugin.commandAllowed(k))) out.add(k);
            if ("도움말".startsWith(a[0])) out.add("도움말");
        } else if (a.length == 2 && (admin || plugin.commandAllowed(a[0]))) {
            switch (a[0]) {
                case "초대", "추방" -> Bukkit.getOnlinePlayers().forEach(p -> {
                    if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
                });
                case "정보", "휴전" -> m.nations.values().forEach(n -> {
                    if (n.name.toLowerCase().startsWith(a[1].toLowerCase())) out.add(n.name);
                });
                default -> { }
            }
        }
        return out;
    }
}

final class AdminCommand implements CommandExecutor, TabCompleter {
    private final NationWarPlugin plugin;
    private final NationManager m;

    AdminCommand(NationWarPlugin plugin, NationManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private void listCommands(CommandSender s) {
        Msg.send(s, "<yellow>== 일반 유저 명령어 허용 상태 ==");
        for (String k : NationCommand.SUBS)
            s.sendMessage(Msg.c("<gray>/국가 " + k + " : " + (plugin.commandAllowed(k) ? "<green>허용" : "<red>관리자 전용")));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("nationwar.admin")) { Msg.send(s, "<red>권한이 없습니다."); return true; }
        if (a.length == 0) {
            Msg.send(s, "<gray>/국가관리 <리로드 | 구역설정 [크기] | 돈지급 <플레이어> <금액> | 해체 <국가> | 명령어 <이름|전체> <허용|금지> | 명령어목록>");
            return true;
        }
        switch (a[0]) {
            case "리로드" -> {
                plugin.reloadConfig();
                plugin.applyBorder();
                Msg.send(s, "<green>설정을 다시 불러왔습니다.");
            }
            case "구역설정" -> {
                var c = plugin.getConfig();
                String wn = c.getString("region.world", "");
                org.bukkit.World w = wn.isEmpty() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(wn);
                if (w == null) { Msg.send(s, "<red>월드를 찾을 수 없습니다."); break; }
                c.set("region.world", w.getName());
                c.set("region.center-x", w.getSpawnLocation().getBlockX());
                c.set("region.center-z", w.getSpawnLocation().getBlockZ());
                if (a.length >= 2) {
                    try { c.set("region.size-blocks", Integer.parseInt(a[1])); }
                    catch (NumberFormatException ex) { Msg.send(s, "<red>크기는 숫자(블록)로 입력하세요."); break; }
                }
                plugin.saveConfig();
                plugin.applyBorder();
                Msg.send(s, "<green>스폰을 중심으로 플레이 구역을 설정했습니다.");
            }
            case "돈지급" -> {
                if (a.length < 3) { Msg.send(s, "<red>/국가관리 돈지급 <플레이어> <금액>"); break; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                if (!op.isOnline() && !op.hasPlayedBefore()) { Msg.send(s, "<red>알 수 없는 플레이어입니다."); break; }
                try {
                    long amt = Long.parseLong(a[2]);
                    m.addBalance(op.getUniqueId(), amt);
                    Msg.send(s, "<green>" + a[1] + " 에게 " + NationManager.fmt(amt) + " 지급했습니다.");
                } catch (NumberFormatException ex) {
                    Msg.send(s, "<red>금액은 숫자로 입력하세요.");
                }
            }
            case "해체" -> {
                if (a.length < 2) { Msg.send(s, "<red>/국가관리 해체 <국가>"); break; }
                String err = m.adminDisband(a[1]);
                Msg.send(s, err == null ? "<green>해체했습니다." : "<red>" + err);
            }
            case "명령어목록" -> listCommands(s);
            case "명령어" -> {
                if (a.length < 3 || !(a[2].equals("허용") || a[2].equals("금지"))) {
                    Msg.send(s, "<red>/국가관리 명령어 <" + String.join("|", NationCommand.SUBS) + "|전체> <허용|금지>");
                    break;
                }
                boolean allow = a[2].equals("허용");
                if (a[1].equals("전체")) {
                    for (String k : NationCommand.SUBS) plugin.getConfig().set("player-commands." + k, allow);
                    plugin.saveConfig();
                } else if (NationCommand.SUBS.contains(a[1])) {
                    plugin.setCommandAllowed(a[1], allow);
                } else {
                    Msg.send(s, "<red>알 수 없는 명령어입니다. (/국가관리 명령어목록)");
                    break;
                }
                Msg.send(s, "<green>" + a[1] + " → " + (allow ? "일반 유저 허용" : "관리자 전용"));
            }
            default -> Msg.send(s, "<red>알 수 없는 하위 명령어");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("nationwar.admin")) return out;
        if (a.length == 1) {
            for (String k : List.of("리로드", "구역설정", "돈지급", "해체", "명령어", "명령어목록")) if (k.startsWith(a[0])) out.add(k);
        } else if (a.length == 2 && a[0].equals("해체")) {
            m.nations.values().forEach(n -> out.add(n.name));
        } else if (a.length == 2 && a[0].equals("돈지급")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 2 && a[0].equals("명령어")) {
            out.addAll(NationCommand.SUBS);
            out.add("전체");
        } else if (a.length == 3 && a[0].equals("명령어")) {
            out.add("허용");
            out.add("금지");
        }
        return out;
    }
}
