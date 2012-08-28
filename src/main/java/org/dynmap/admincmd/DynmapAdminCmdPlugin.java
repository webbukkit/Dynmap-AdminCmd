package org.dynmap.admincmd;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.dynmap.markers.Marker;

import be.Balor.Player.ACPlayer;
import be.Balor.Player.PlayerManager;
import be.Balor.Tools.Warp;
import be.Balor.World.ACWorld;
import be.Balor.World.WorldManager;
import be.Balor.bukkit.AdminCmd.AdminCmd;

public class DynmapAdminCmdPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-AdminCmd] ";

    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    AdminCmd admincmd;
    boolean homes = true;
    boolean warps = true;
    
    FileConfiguration cfg;

    private class OurPlayerListener implements Listener, Runnable {
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPlayerJoin(PlayerJoinEvent event) {
            getServer().getScheduler().scheduleSyncDelayedTask(DynmapAdminCmdPlugin.this, this, 10);
        }
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            getServer().getScheduler().scheduleSyncDelayedTask(DynmapAdminCmdPlugin.this, this, 10);
        }
        public void run() {
            if((!stop) && homes) {
                homelayer.updateHomes();
            }
        }
    }

    private class Layer {
        MarkerSet set;
        MarkerIcon deficon;
        String labelfmt;
        Set<String> visible;
        Set<String> hidden;
        Map<String, Marker> markers = new HashMap<String, Marker>();
        
        public Layer(String id, FileConfiguration cfg, String deflabel, String deficon, String deflabelfmt) {
            set = markerapi.getMarkerSet("admincmd." + id);
            if(set == null)
                set = markerapi.createMarkerSet("admincmd."+id, cfg.getString("layer."+id+".name", deflabel), null, false);
            else
                set.setMarkerSetLabel(cfg.getString("layer."+id+".name", deflabel));
            if(set == null) {
                severe("Error creating " + deflabel + " marker set");
                return;
            }
            set.setLayerPriority(cfg.getInt("layer."+id+".layerprio", 10));
            set.setHideByDefault(cfg.getBoolean("layer."+id+".hidebydefault", false));
            int minzoom = cfg.getInt("layer."+id+".minzoom", 0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                set.setMinZoom(minzoom);
            String icon = cfg.getString("layer."+id+".deficon", deficon);
            this.deficon = markerapi.getMarkerIcon(icon);
            if(this.deficon == null) {
                info("Unable to load default icon '" + icon + "' - using default '"+deficon+"'");
                this.deficon = markerapi.getMarkerIcon(deficon);
            }
            labelfmt = cfg.getString("layer."+id+".labelfmt", deflabelfmt);
            List<String> lst = cfg.getStringList("layer."+id+".visiblemarkers");
            if(lst != null)
                visible = new HashSet<String>(lst);
            lst = cfg.getStringList("layer."+id+".hiddenmarkers");
            if(lst != null)
                hidden = new HashSet<String>(lst);

            OurPlayerListener lsnr = new OurPlayerListener();
            getServer().getPluginManager().registerEvents(lsnr, DynmapAdminCmdPlugin.this);
        }
        
        void cleanup() {
            if(set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            markers.clear();
        }
        
        boolean isVisible(String id, String wname) {
            if((visible != null) && (visible.isEmpty() == false)) {
                if((visible.contains(id) == false) && (visible.contains("world:" + wname) == false))
                    return false;
            }
            if((hidden != null) && (hidden.isEmpty() == false)) {
                if(hidden.contains(id) || hidden.contains("world:" + wname))
                    return false;
            }
            return true;
        }
        
        void updateHomes() {
            Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */

            /* Get all players */
            Set<ACPlayer> players = PlayerManager.getInstance().getOnlineACPlayers();
            /* For each player */
            for(ACPlayer pl : players) {
                Set<String> homes = pl.getHomeList();
                String pname = pl.getName();
                for(String name : homes) {
                    /* Get location */
                    Location loc = pl.getHome(name);
                    String wname = loc.getWorld().getName();
                    /* Skip if not visible */
                    if(isVisible(pname, wname) == false)
                        continue;
                    String id = wname + "/" + pl.getName() + "/" + name;

                    String label = labelfmt.replace("%name%", pname).replace("%homename%", name);
                    /* See if we already have marker */
                    Marker m = markers.remove(id);
                    if(m == null) { /* Not found?  Need new one */
                        m = set.createMarker(id, label, wname, loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                    }
                    else {  /* Else, update position if needed */
                        m.setLocation(wname, loc.getX(), loc.getY(), loc.getZ());
                        m.setLabel(label);
                        m.setMarkerIcon(deficon);
                    }
                    newmap.put(id, m);    /* Add to new map */
                }
            }
            /* Now, review old map - anything left is gone */
            for(Marker oldm : markers.values()) {
                oldm.deleteMarker();
            }
            /* And replace with new map */
            markers.clear();
            markers = newmap;
        }

        void updateWarps() {
            Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */

            /* Get all the warps */
            WorldManager wm = WorldManager.getInstance();
            Set<String> warps = wm.getAllWarpList();
            /* For each warp */
            for(String warp : warps) {
                String[] tok = warp.split(":");
                if(tok.length < 2) continue;
                /* Get world name */
                String wname = tok[0];
                /* Get name */
                String name = tok[1];
                ACWorld w = ACWorld.getWorld(wname);
                if(w == null) continue;
                Warp warpobj = w.getWarp(name);
                if(warpobj == null) continue;
                /* Get location */
                Location loc = warpobj.loc;
                /* Skip if not visible */
                if(isVisible(name, wname) == false)
                    continue;
                String id = wname + "/" + name;

                String label = labelfmt.replace("%name%", name);
                /* See if we already have marker */
                Marker m = markers.remove(id);
                if(m == null) { /* Not found?  Need new one */
                    m = set.createMarker(id, label, wname, loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                }
                else {  /* Else, update position if needed */
                    m.setLocation(wname, loc.getX(), loc.getY(), loc.getZ());
                    m.setLabel(label);
                    m.setMarkerIcon(deficon);
                }
                newmap.put(id, m);    /* Add to new map */
            }
            /* Now, review old map - anything left is gone */
            for(Marker oldm : markers.values()) {
                oldm.deleteMarker();
            }
            /* And replace with new map */
            markers.clear();
            markers = newmap;
        }
    }

    /* Homes layer settings */
    private Layer homelayer;
    
    /* Warps layer settings */
    private Layer warplayer;
    
    long updperiod;
    boolean stop;
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class MarkerUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateMarkers();
        }
    }
    
    /* Update mob population and position */
    private void updateMarkers() {
        if(homes) {
            homelayer.updateHomes();
        }
        if(warps) {
            warplayer.updateWarps();
        }
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), updperiod);
    }

    private class OurServerListener implements Listener {
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("AdminCmd")) {
                if(dynmap.isEnabled() && admincmd.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get AdminCmd */
        Plugin p = pm.getPlugin("AdminCmd");
        if(p == null) {
            severe("Cannot find AdminCmd!");
            return;
        }
        admincmd = (AdminCmd)p;
        
        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        

        /* If both enabled, activate */
        if(dynmap.isEnabled() && admincmd.isEnabled())
            activate();
        
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {
        }
    }

    private boolean reload = false;
    
    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading Dynmap marker API!");
            return;
        }
            
        /* Load configuration */
        if(reload) {
            this.reloadConfig();
            if(homelayer != null) {
                if(homelayer.set != null) {
                    homelayer.set.deleteMarkerSet();
                }
                homelayer = null;
            }
            if(warplayer != null) {
                if(warplayer.set != null) {
                    warplayer.set.deleteMarkerSet();
                }
                warplayer = null;
            }
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Check which is enabled */
        if(cfg.getBoolean("layer.homes.enable", true) == false)
            homes = false;
        if(cfg.getBoolean("layer.warps.enable", true) == false)
            warps = false;
        
        /* Now, add marker set for homes */
        if(homes)
            homelayer = new Layer("homes", cfg, "Homes", "house", "%name%(home)");
        /* Now, add marker set for warps */
        if(warps)
            warplayer = new Layer("warps", cfg, "Warps", "portal", "[%name%]");
        
        /* Set up update job - based on periond */
        double per = cfg.getDouble("update.period", 5.0);
        if(per < 2.0) per = 2.0;
        updperiod = (long)(per*20.0);
        stop = false;
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), 5*20);
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(homelayer != null) {
            homelayer.cleanup();
            homelayer = null;
        }
        if(warplayer != null) {
            warplayer.cleanup();
            warplayer = null;
        }
        stop = true;
    }

}
