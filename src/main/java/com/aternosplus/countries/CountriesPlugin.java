package com.aternosplus.countries;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class CountriesPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, String> openMenus = new HashMap<>();
    private final Set<UUID> countryChat = new HashSet<>();
    private static final String PREFIX = ChatColor.DARK_AQUA + "[Countries] " + ChatColor.GRAY;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { getDataFolder().mkdirs(); dataFile.createNewFile(); }
            catch (IOException e) { getLogger().severe("Could not create data.yml: " + e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("country")).setExecutor(this);
        Objects.requireNonNull(getCommand("country")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("war")).setExecutor(this);
        Objects.requireNonNull(getCommand("war")).setTabCompleter(this);
        getLogger().info("Countries enabled.");
    }

    @Override public void onDisable() { saveData(); }
    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save data: " + e.getMessage()); }
    }
    private String country(UUID id) { return data.getString("players." + id); }
    private String key(String a, String b) {
        List<String> pair = new ArrayList<>(List.of(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT)));
        Collections.sort(pair); return pair.get(0) + "|" + pair.get(1);
    }
    private boolean atWar(String a, String b) { return data.getBoolean("wars." + key(a,b), false); }
    private boolean allied(String a, String b) { return data.getBoolean("alliances." + key(a,b), false); }
    private boolean leader(Player p, String c) { return p.hasPermission("countries.admin") || p.getUniqueId().toString().equals(data.getString("leaders." + c.toLowerCase(Locale.ROOT))); }
    private void msg(CommandSender s, String m) { s.sendMessage(PREFIX + m); }
    private void announce(String c, String m) {
        for (Player p : Bukkit.getOnlinePlayers()) if (c.equalsIgnoreCase(country(p.getUniqueId()))) msg(p, m);
    }
    private List<String> countries() {
        return getConfig().getStringList("countries");
    }
    private String findCountry(String name) {
        return countries().stream().filter(c -> c.equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    private void openCountryMenu(Player p) {
        List<String> list = countries();
        int size = Math.min(54, Math.max(9, ((list.size()+8)/9)*9));
        Inventory inv = Bukkit.createInventory(null, size, ChatColor.DARK_AQUA + "Choose your country");
        for (int i=0; i<Math.min(list.size(),54); i++) {
            String c = list.get(i);
            ItemStack item = new ItemStack(Material.FILLED_MAP);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + c);
            meta.setLore(List.of(ChatColor.GRAY + "Click to join " + c));
            item.setItemMeta(meta); inv.setItem(i,item);
        }
        openMenus.put(p.getUniqueId(), "select");
        p.openInventory(inv);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (country(e.getPlayer().getUniqueId()) == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline() && country(e.getPlayer().getUniqueId()) == null) openCountryMenu(e.getPlayer());
            }, 20L);
        }
    }
    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !openMenus.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        if (!"select".equals(openMenus.get(p.getUniqueId()))) return;
        ItemStack item=e.getCurrentItem();
        if (item==null || !item.hasItemMeta() || item.getItemMeta().getDisplayName()==null) return;
        String selected=ChatColor.stripColor(item.getItemMeta().getDisplayName());
        String c=findCountry(selected);
        if (c==null || country(p.getUniqueId())!=null) return;
        data.set("players."+p.getUniqueId(),c);
        String leaderKey="leaders."+c.toLowerCase(Locale.ROOT);
        if (!data.contains(leaderKey)) data.set(leaderKey,p.getUniqueId().toString());
        saveData(); openMenus.remove(p.getUniqueId()); p.closeInventory();
        msg(p,"You joined " + ChatColor.AQUA + c + ChatColor.GRAY + ". You are its founder/leader if first.");
        announce(c, p.getName()+" joined your country.");
    }
    @EventHandler public void onMenuClose(InventoryCloseEvent e) {
        if (openMenus.containsKey(e.getPlayer().getUniqueId())) openMenus.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker=p;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p) attacker=p;
        if (attacker==null || attacker.equals(victim)) return;
        String a=country(attacker.getUniqueId()), v=country(victim.getUniqueId());
        if (a==null || v==null || a.equalsIgnoreCase(v) || !atWar(a,v)) {
            e.setCancelled(true);
            msg(attacker, a==null || v==null ? "Both players must select a country before PvP." :
                a.equalsIgnoreCase(v) ? "Friendly fire is disabled." : "You cannot attack " + v + " while your countries are not at war.");
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Player p=e.getPlayer();
        if (!countryChat.contains(p.getUniqueId())) return;
        String c=country(p.getUniqueId());
        if (c==null) { countryChat.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        String line=ChatColor.DARK_AQUA+"[C] "+ChatColor.AQUA+p.getName()+ChatColor.GRAY+": "+e.getMessage();
        for (Player online:Bukkit.getOnlinePlayers()) if (c.equalsIgnoreCase(country(online.getUniqueId()))) online.sendMessage(line);
    }

    @Override public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        boolean warCmd=command.getName().equalsIgnoreCase("war");
        if (warCmd) return war(s,args);
        if (args.length==0) {
            msg(s,"/country list | info | join | leave | chat | ally | neutral | enemy");
            msg(s,"/country invite <player> | kick <player> | leader <player>");
            return true;
        }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { msg(s,"Available: "+String.join(", ",countries())); return true; }
        if (!(s instanceof Player p)) { msg(s,"Players only."); return true; }
        String c=country(p.getUniqueId());
        switch(sub) {
            case "join" -> { if(c!=null) msg(p,"You already belong to "+c+"."); else openCountryMenu(p); }
            case "info" -> {
                if(args.length>1) c=findCountry(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                if(c==null) c=country(p.getUniqueId());
                if(c==null) { msg(p,"You are not in a country."); return true; }
                long members=data.getConfigurationSection("players")==null?0:data.getConfigurationSection("players").getKeys(false).stream().filter(id->c.equalsIgnoreCase(data.getString("players."+id))).count();
                msg(p,c+" | Members: "+members+" | Leader: "+Bukkit.getOfflinePlayer(UUID.fromString(data.getString("leaders."+c.toLowerCase(Locale.ROOT),p.getUniqueId().toString()))).getName());
            }
            case "leave" -> {
                if(c==null) { msg(p,"You are not in a country."); return true; }
                if(leader(p,c)) { msg(p,"Leaders cannot leave. Transfer leadership first."); return true; }
                data.set("players."+p.getUniqueId(),null); saveData(); msg(p,"You left "+c+".");
            }
            case "chat" -> {
                if(c==null) { msg(p,"Join a country first."); return true; }
                if(countryChat.remove(p.getUniqueId())) msg(p,"Country chat disabled.");
                else { countryChat.add(p.getUniqueId()); msg(p,"Country chat enabled. Use /country chat again to return to global chat."); }
            }
            case "invite" -> {
                if(c==null || !leader(p,c)) { msg(p,"Only your country's leader can invite players (admin bypass)."); return true; }
                if(args.length<2) { msg(p,"Usage: /country invite <player>"); return true; }
                Player target=Bukkit.getPlayerExact(args[1]);
                if(target==null) { msg(p,"That player is not online."); return true; }
                if(country(target.getUniqueId())!=null) { msg(p,"That player already has a country."); return true; }
                data.set("invites."+target.getUniqueId(),c); saveData();
                msg(target,"You were invited to "+c+". Use /country accept."); msg(p,"Invitation sent.");
            }
            case "accept" -> {
                String invite=data.getString("invites."+p.getUniqueId());
                if(invite==null || c!=null) { msg(p,"No valid invitation."); return true; }
                data.set("players."+p.getUniqueId(),invite); data.set("invites."+p.getUniqueId(),null); saveData(); msg(p,"You joined "+invite+".");
            }
            case "kick" -> {
                if(c==null || !leader(p,c) || args.length<2) { msg(p,"Usage: /country kick <player> (leader only)"); return true; }
                OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);
                if(!c.equalsIgnoreCase(country(target.getUniqueId()))) { msg(p,"That player is not in your country."); return true; }
                if(target.getUniqueId().equals(p.getUniqueId())) { msg(p,"You cannot kick yourself."); return true; }
                data.set("players."+target.getUniqueId(),null); saveData(); msg(p,"Player removed from your country.");
                if(target.isOnline()) msg(target.getPlayer(),"You were removed from "+c+".");
            }
            case "leader" -> {
                if(c==null || !leader(p,c) || args.length<2) { msg(p,"Usage: /country leader <player> (leader only)"); return true; }
                OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);
                if(!c.equalsIgnoreCase(country(target.getUniqueId()))) { msg(p,"That player must be in your country."); return true; }
                data.set("leaders."+c.toLowerCase(Locale.ROOT),target.getUniqueId().toString()); saveData(); announce(c,target.getName()+" is now the country leader.");
            }
            case "ally", "neutral", "enemy" -> {
                if(c==null || !leader(p,c) || args.length<2) { msg(p,"Usage: /country "+sub+" <country> (leader only)"); return true; }
                String other=findCountry(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                if(other==null || other.equalsIgnoreCase(c)) { msg(p,"Choose a different valid country."); return true; }
                data.set("alliances."+key(c,other),sub.equals("ally"));
                data.set("diplomacy."+key(c,other),sub.toUpperCase(Locale.ROOT)); saveData();
                msg(p,"Relationship with "+other+" set to "+sub+".");
                announce(other,"Diplomatic status with "+c+" changed to "+sub+".");
            }
            default -> msg(p,"Unknown subcommand. Use /country.");
        }
        return true;
    }

    private boolean war(CommandSender s,String[] args) {
        if(args.length==0) { msg(s,"/war declare <country> | accept <country> | peace <country> | list | info <country>"); return true; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("list")) {
            ConfigurationSection sec=data.getConfigurationSection("wars");
            if(sec==null || sec.getKeys(false).isEmpty()) msg(s,"There are currently no active wars.");
            else msg(s,"Active war pairs: "+sec.getKeys(false).stream().filter(k->sec.getBoolean(k)).collect(Collectors.joining(", ")));
            return true;
        }
        if(!(s instanceof Player p)) { msg(s,"Players only."); return true; }
        String own=country(p.getUniqueId());
        if(own==null) { msg(p,"Join a country first."); return true; }
        if(args.length<2) { msg(p,"Specify a country."); return true; }
        String other=findCountry(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
        if(other==null || own.equalsIgnoreCase(other)) { msg(p,"Choose a different valid country."); return true; }
        if(sub.equals("info")) { msg(p,own+" vs "+other+": "+(atWar(own,other)?"AT WAR":"peace")); return true; }
        if(!leader(p,own)) { msg(p,"Only your country's leader can manage wars."); return true; }
        String k=key(own,other);
        switch(sub) {
            case "declare" -> {
                if(atWar(own,other)) { msg(p,"You are already at war."); return true; }
                if(allied(own,other)) { msg(p,"End your alliance before declaring war."); return true; }
                data.set("warRequests."+k,own); saveData();
                announce(other,""+own+" has declared war. Their leader must use /war accept "+own+" to activate the war.");
                msg(p,"War declaration sent to "+other+". The other leader must accept it.");
            }
            case "accept" -> {
                String request=data.getString("warRequests."+k);
                if(request==null || !request.equalsIgnoreCase(other)) { msg(p,"No pending declaration from "+other+"."); return true; }
                data.set("wars."+k,true); data.set("warRequests."+k,null); saveData();
                announce(own,"War with "+other+" has begun!"); announce(other,"War with "+own+" has begun!");
            }
            case "peace", "surrender" -> {
                if(!atWar(own,other)) { msg(p,"You are not at war."); return true; }
                data.set("wars."+k,false); data.set("warRequests."+k,null); saveData();
                announce(own,"Peace has been made with "+other+"."); announce(other,"Peace has been made with "+own+".");
            }
            default -> msg(p,"Use /war declare, accept, peace, surrender, info or list.");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if(args.length==1) return (c.getName().equalsIgnoreCase("war")?List.of("declare","accept","peace","surrender","info","list"):List.of("list","info","join","leave","chat","invite","accept","kick","leader","ally","neutral","enemy")).stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length>=2 && (args[0].equalsIgnoreCase("ally") || args[0].equalsIgnoreCase("neutral") || args[0].equalsIgnoreCase("enemy") || c.getName().equalsIgnoreCase("war"))) return countries().stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[args.length-1].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }
}
