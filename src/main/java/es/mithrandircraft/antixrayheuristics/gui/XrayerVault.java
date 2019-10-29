//--------------------------------------------------------------------
// Copyright © Dylan Calaf 2019 - AntiXrayHeuristics
//--------------------------------------------------------------------

package es.mithrandircraft.antixrayheuristics.gui;

import es.mithrandircraft.antixrayheuristics.PlaceholderManager;
import es.mithrandircraft.antixrayheuristics.callbacks.GetAllBaseXrayerDataCallback;
import es.mithrandircraft.antixrayheuristics.callbacks.GetXrayerBelongingsCallback;
import es.mithrandircraft.antixrayheuristics.files.LocaleManager;
import es.mithrandircraft.antixrayheuristics.math.MathFunctions;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class XrayerVault {

    private final es.mithrandircraft.antixrayheuristics.AntiXrayHeuristics mainClassAccess;

    private ArrayList<String> UUIDs = new ArrayList<String>(); //These 3 list's values are parallel, and represent xrayer information.
    private ArrayList<Integer> handledAmounts = new ArrayList<Integer>();
    private ArrayList<String> firstHandledTimes = new ArrayList<String>();

    private int pages; //How many pages the vault should have

    private HashMap<String, PlayerViewInfo> viewers = new HashMap<String, PlayerViewInfo>(); //Stores who's viewing the GUI and info on what's being looked at.

    private ItemStack separator = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    private ItemStack nextButton = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
    private ItemStack prevButton = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
    private ItemStack purgeButton = new ItemStack(Material.RED_STAINED_GLASS_PANE);
    private ItemStack refreshButton = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
    private ItemStack backButton = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
    private ItemStack purgePlayerButton = new ItemStack(Material.RED_STAINED_GLASS_PANE);
    private ItemStack absolvePlayerButton = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);

    public XrayerVault(es.mithrandircraft.antixrayheuristics.AntiXrayHeuristics main)
    {
        this.mainClassAccess = main;

        ItemMeta separator_meta = separator.getItemMeta();
        separator_meta.setDisplayName(" ");
        separator.setItemMeta(separator_meta);

        ItemMeta next_meta = nextButton.getItemMeta();
        next_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("NextButtonTitle")));
        nextButton.setItemMeta(next_meta);

        ItemMeta prev_meta = prevButton.getItemMeta();
        prev_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("BackButtonTitle")));
        prevButton.setItemMeta(prev_meta);

        ItemMeta purge_meta = purgeButton.getItemMeta();
        purge_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("PurgeButtonTitle")));
        purge_meta.setLore(LocaleManager.get().getStringList("PurgeButtonDesc"));
        purgeButton.setItemMeta(purge_meta);

        ItemMeta refresh_meta = refreshButton.getItemMeta();
        refresh_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("RefreshButtonTitle")));
        refresh_meta.setLore(LocaleManager.get().getStringList("RefreshButtonDesc"));
        refreshButton.setItemMeta(refresh_meta);

        ItemMeta back_meta = backButton.getItemMeta();
        back_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("GoBackButtonTitle")));
        backButton.setItemMeta(back_meta);

        ItemMeta purgeplayer_meta = purgePlayerButton.getItemMeta();
        purgeplayer_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("PurgePlayerButtonTitle")));
        purgeplayer_meta.setLore(LocaleManager.get().getStringList("PurgePlayerButtonDesc"));
        purgePlayerButton.setItemMeta(purgeplayer_meta);

        ItemMeta absolveplayer_meta = absolvePlayerButton.getItemMeta();
        absolveplayer_meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',LocaleManager.get().getString("AbsolvePlayerButtonTitle")));
        absolveplayer_meta.setLore(LocaleManager.get().getStringList("AbsolvePlayerButtonDesc"));
        absolvePlayerButton.setItemMeta(absolveplayer_meta);
    }

    public void PurgeAllXrayersAndRefreshVault() //Removes all xrayer data from both memory and xrayer vault, and refreshes vault after which sending everyone back to page 0 for safety
    {
        //Dump registered xrayers:
        Bukkit.getScheduler().runTaskAsynchronously(mainClassAccess, () -> mainClassAccess.mm.DeleteRegisteredXrayers());
        //refresh, just clear the vault lists since we did a global purge, and set pages to 1:
        ClearXrayerInfoLists();
        SetPages(1);
        //send viewers back to page 0:
        SendAllToPageZero();
    }

    public void XrayerDataRemover(String name, Boolean nameIsSolicitor) //Can clear the inspected xrayer that solicitor name is watching (or explicitly defined if nameIsSolicitor = false) from both persistent data storage and data loaded in RAM for vault, and refresh vault, sending everyone back to page 0 for safety
    {
        final String xrayerUUID;
        if(nameIsSolicitor) //Inputted name is the solicitor viewing xrayer through gui. We can get the xrayer's name since it's stored in this same vault
        {
            //purge player from memory:
            xrayerUUID = GetInspectedXrayer(name);
            Bukkit.getScheduler().runTaskAsynchronously(mainClassAccess, () -> mainClassAccess.mm.DeleteXrayer(xrayerUUID));
            //remove absolved uuid from being listed in vault:
            RemoveXrayerDataByUUIDFromList(xrayerUUID);
        }
        else //Should be the actual specific xrayer's name
        {
            //purge player from memory:
            xrayerUUID = Bukkit.getServer().getPlayer(name).getUniqueId().toString();
            Bukkit.getScheduler().runTaskAsynchronously(mainClassAccess, () -> mainClassAccess.mm.DeleteXrayer(xrayerUUID));
            //remove absolved uuid from being listed in vault:
            RemoveXrayerDataByUUIDFromList(xrayerUUID);
        }
        //recalculate pages length:
        CalculatePages();
        //send viewers back to page 0:
        SendAllToPageZero();
    }

    public void UpdateXrayerInfoLists(Player player, int page) //Updates Xrayer information arrays and also forces page open for player
    {
        ClearXrayerInfoLists(); //Clearing previous data

        Bukkit.getScheduler().runTaskAsynchronously(mainClassAccess, () -> mainClassAccess.mm.GetAllBaseXrayerData(new GetAllBaseXrayerDataCallback() {
            @Override
            public void onQueryDone(List<String> uuids, List<Integer> handledamounts, List<String> firsthandledtimes) {
                UUIDs.addAll(uuids);
                handledAmounts.addAll(handledamounts);
                firstHandledTimes.addAll(firsthandledtimes);

                OpenVault(player, page);
            }
        })); //This single async function fills up the 3 lists with xrayer information.
    }

    public void ClearXrayerInfoLists() //clears all xrayer information arraylists
    {
        UUIDs.clear();
        handledAmounts.clear();
        firstHandledTimes.clear();
    }

    private void CalculatePages() //Calculates pages considering the amount of registered xrayer uuid's, and that there can only be 27 results per page
    {
        pages = MathFunctions.Cut(45, UUIDs.size());
    }

    private void SendAllToPageZero() //Sends all viewers back to page 0:
    {
        for(Map.Entry<String, PlayerViewInfo> entry : viewers.entrySet())
        {
            OpenVault(Bukkit.getServer().getPlayer(entry.getKey()) ,0);
            Bukkit.getServer().getPlayer(entry.getKey()).sendMessage(LocaleManager.get().getString("MessagesPrefix") + " " + LocaleManager.get().getString("ForcedPageZero"));
        }
    }

    //Button format getter methods:
    public ItemStack GetNextButtonFormat(){ return nextButton; }
    public ItemStack GetPrevButtonFormat(){ return prevButton; }
    public ItemStack GetPurgeButtonFormat(){ return purgeButton; }
    public ItemStack GetRefreshButtonFormat(){ return refreshButton; }

    public ItemStack GetBackButtonFormat(){ return backButton; }
    public ItemStack GetPurgePlayerButtonFormat(){ return purgePlayerButton; }
    public ItemStack GetAbsolvePlayerButtonFormat(){ return absolvePlayerButton; }

    public void OpenVault(Player player, int page) //Opens xrayer vault in specified page (This shows player heads with info about the xrayer)
    {
        Inventory gui = Bukkit.createInventory(null, 54, "Xrayer Vault"); //Vault contents to display
        viewers.put(player.getName(), new PlayerViewInfo(page)); //Register player as gui viewer on a certain page (used as reference)

        //Fill up vault:
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        UUID currentUUID;
        int iteration = 0;
        ListIterator<String> iter = UUIDs.listIterator(page * 45);
        while (iter.hasNext() && !(iteration >= 45)) //Fills up the vault page with skulls containing xrayer data
        {
            currentUUID = UUID.fromString(iter.next());
            meta.setOwningPlayer(Bukkit.getServer().getOfflinePlayer(currentUUID)); //Assigns player to head owner
            meta.setDisplayName(Bukkit.getServer().getOfflinePlayer(currentUUID).getName()); //Head name editing
            meta.setLore(PlaceholderManager.SubstituteXrayerDataPlaceholders(LocaleManager.get().getStringList("PlayerHeadDesc"), String.valueOf(handledAmounts.get(iteration)), firstHandledTimes.get(iteration))); //Head lore editing
            skull.setItemMeta(meta);
            gui.setItem(iteration, skull);
            iteration++;
        }

        //Lower section separators:
        gui.setItem(46, separator);
        gui.setItem(47, separator);
        gui.setItem(49, separator);
        gui.setItem(51, separator);
        gui.setItem(52, separator);

        //Lower section vault/gui stuff:
        if(page + 1 < pages) gui.setItem(53, nextButton); else gui.setItem(53, separator);
        if(page - 1 > -1) gui.setItem(45, prevButton); else gui.setItem(45, separator);
        gui.setItem(48, purgeButton);
        gui.setItem(50, refreshButton);

        player.openInventory(gui);
    }

    public void OpenXrayerConfiscatedInventory(Player player, int xrayerUUIDIndex) //Opens an xrayer's confiscated inventory by xrayer name
    {
        viewers.get(player.getName()).xrayerInvUUID = UUIDs.get(xrayerUUIDIndex); //Update uuid of xrayer we're watching

        Bukkit.getScheduler().runTaskAsynchronously(mainClassAccess, () -> mainClassAccess.mm.GetXrayerBelongings(UUIDs.get(xrayerUUIDIndex), new GetXrayerBelongingsCallback()
        {
            @Override
            public void onQueryDone(ItemStack[] belongings)
            {
                Inventory inv = Bukkit.createInventory(null, 54, "Xrayer Vault"); //Vault contents to display

                //Fill up vault:
                for (int i = 0; i < 41; i++) //Fills up the vault page with confiscated xrayer's inventory
                {
                    inv.setItem(i, belongings[i]);
                }

                //Separation bar for mere decoration:
                for(int i = 45; i < 54; i++) { inv.setItem(i, separator); }
                inv.setItem(46, separator);
                inv.setItem(47, separator);
                inv.setItem(48, separator);
                inv.setItem(50, separator);
                inv.setItem(52, separator);

                //Lower section vault/gui stuff:
                inv.setItem(45, backButton);
                inv.setItem(51, purgePlayerButton);
                inv.setItem(53, absolvePlayerButton);

                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) skull.getItemMeta();
                meta.setOwningPlayer(Bukkit.getServer().getOfflinePlayer(UUID.fromString(UUIDs.get(xrayerUUIDIndex)))); //Assigns player to head owner
                meta.setDisplayName(Bukkit.getServer().getOfflinePlayer(UUID.fromString(UUIDs.get(xrayerUUIDIndex))).getName()); //Head name editing
                meta.setLore(Arrays.asList("Times handled: " + handledAmounts.get(xrayerUUIDIndex), "First detected: " + firstHandledTimes.get(xrayerUUIDIndex))); //Head lore editing
                skull.setItemMeta(meta);
                inv.setItem(49, skull);

                player.openInventory(inv);
            }
        }));
    }

    public int GetPage(String player) { return viewers.get(player).page; } //Returns page player is on
    public String GetInspectedXrayer(String player) { return viewers.get(player).xrayerInvUUID; } //Returns the uuid of the original owner of the inventory player is inspecting (if any)

    private void RemoveXrayerDataByUUIDFromList(String uuid) //Removes all xrayer data in xrayer vault by uuid
    {
        //Check if uuid exists:
        for (int i = 0; i < UUIDs.size(); i++) if(UUIDs.get(i).equals(uuid)) {
            //All xrayer data is in the same index, so we can use this index to delete all xrayer data in all parallel array lists
            UUIDs.remove(i);
            handledAmounts.remove(i);
            firstHandledTimes.remove(i);
            break;
        }
    }
    public void RemovePlayerAsViewer(String name) { viewers.remove(name); } //Removes a player and it's data from the viewers hashmap

    public boolean CheckIfNoViewers(){ return viewers.isEmpty(); }

    private void SetPages(int many) { pages = many; } //Forcefully sets how many pages the vault currently has
}
