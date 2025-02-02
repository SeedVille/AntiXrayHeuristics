//--------------------------------------------------------------------
// Copyright © Dylan Calaf Latham 2019-2021 AntiXrayHeuristics
//--------------------------------------------------------------------

package es.mithrandircraft.antixrayheuristics;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

public final class AntiXrayHeuristics extends JavaPlugin implements Listener {

    //----------
    //PROPERTIES
    //----------

    //Self plugin reference
    private static AntiXrayHeuristics plugin;

    //API
    private APIAntiXrayHeuristics api;

    //Captured spigot version:
    protected SpigotVersion spigotVersion;

    //Mining sessions HashMap <Name, MiningSession>:
    protected HashMap<String, MiningSession> sessions = new HashMap<String, MiningSession>();

    //Persistent memory storage manager:
    protected MemoryManager mm = new MemoryManager(this);

    //Hardcoded heuristics:

    private final float suspicionLevelThreshold = 100f; //Suspicion Threshold value above which we consider a player as Xraying.

    private final int mainRunnableFrequency = 200; //(ticks)15s - Time in ticks at which suspicion decrease runnable is executed.

    protected final float maxSuspicionDecreaseProportion = -10f;
    protected final float minSuspicionDecreaseProportion = -0.1f;
    protected final float absoluteMinimumSuspicionDecrease = -3.0f; //Players mining below certain speeds should at least have this suspicion level applied, else fp's emerge

    protected final int maxAccountableMillisecondDeltaForThirtyMinedBlocks = 20000; //Directly proportional to "minSuspicionDecreaseProportion"
    protected final int minAccountableMillisecondDeltaForThirtyMinedBlocks = 0; //Directly proportional to "maxSuspicionDecreaseProportion"

    private final int suspicionStreakZeroThreshold = 20; //Ammount of consecutive times after which a player is considered as no longer mining.

    private static final Set<Material> RELEVANT_BASES = Set.of(Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE);

    //Precalculated heuristics:

    private int nonOreStreakDecreaseAmount; //Mined blocks streak decrease from all sessions every time mainRunnableFrequency is reached.

    private int usualEncounterThreshold; //Threshold of mined non-ore blocks after which we consider the player is definetly mining legit

    private float extraDiamondWeight; //A higher weight value applied to MiningSessions on diamond encounter if suspicion is higher than usual
    private float extraEmeraldWeight; //A higher weight value applied to MiningSessions on emerald encounter if suspicion is higher than usual
    private float extraAncientDebrisWeight; //A higher weight value applied to MiningSessions on ancient debris encounter if suspicion is higher than usual

    //GUI:
    protected XrayerVault vault;


    //-------
    //METHODS
    //-------

    //Get AXH main plugin class
    public static AntiXrayHeuristics GetPlugin() {
        return plugin;
    }

    //Get AXH API
    public APIAntiXrayHeuristics GetAPI() {
        return api;
    }

    @Override
    public void onEnable() {

        //Static self reference init:
        plugin = this;

        //API init:
        api = new APIAntiXrayHeuristicsImpl(this);

        //Spigot version capture:
        spigotVersion = new SpigotVersion();

        //Config load:
        getConfig().options().copyDefaults();
        saveDefaultConfig();

        //Register serializable object (used for complex MaterialWeights config serialization)
        ConfigurationSerialization.registerClass(BlockWeightInfo.class);

        //Locale load:
        LocaleManager.setup(getName());
        LocaleManager.get().options().copyDefaults(true);
        LocaleManager.save();

        //Material weights load:
        WeightsCard.setup(getName());
        WeightsCard.get().options().copyDefaults(true);
        WeightsCard.save();

        //Vault GUI object initialize (version specific through NMS):
        vault = new XrayerVault(this);

        //Commands:
        getCommand("AXH").setExecutor(new CommandAXH(this));
        //Tab completer:
        getCommand("AXH").setTabCompleter(new CommandAXHAutoCompleter());

        //Sql connection?:
        if (getConfig().getString("StorageType").equals("MYSQL")) {
            mm.InitializeDataSource();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    mm.SQLCreateTableIfNotExists();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });
        }

        //Create json file if not exists?:
        else if (getConfig().getString("StorageType").equals("JSON")) {
            mm.JSONFileCreateIfNotExists();
        }

        //Event registring:
        getServer().getPluginManager().registerEvents(new EventBlockBreak(this), this);
        getServer().getPluginManager().registerEvents(new EventBlockPlace(this), this);
        getServer().getPluginManager().registerEvents(new EventClick(this), this);
        getServer().getPluginManager().registerEvents(new EventItemDrag(), this);
        getServer().getPluginManager().registerEvents(new EventInventoryClose(this), this);
        getServer().getPluginManager().registerEvents(new EventPlayerChangedWorld(this), this);

        //Runnables:
        MainRunnable();

        //Precalculations:
        nonOreStreakDecreaseAmount = -((int) Math.ceil((float) getConfig().getInt("MinimumBlocksMinedToNextVein") / 4f)); //Calculates bock streak reduction ammount on Runnable

        usualEncounterThreshold = getConfig().getInt("MinimumBlocksMinedToNextVein") * 4; //Calculates how many blocks till we should find diamond and/or emerald average

        extraDiamondWeight = getConfig().getLong("DiamondWeight") * 1.5f;
        extraEmeraldWeight = getConfig().getLong("EmeraldWeight") * 1.5f;
        extraAncientDebrisWeight = getConfig().getLong("AncientDebrisWeight") * 1.5f;
    }

    @Override
    public void onDisable() {
        if (getConfig().getString("StorageType").equals("MYSQL")) mm.CloseDataSource();
    }

    //Performs plugin updates at scheduled time
    private void MainRunnable() {
        new BukkitRunnable() {
            @Override
            public void run() {
                //Task: sessions HashMap update, Player suspicion decrease:
                Set sessionsKeySet = sessions.keySet();
                Iterator sessionsIterator = sessionsKeySet.iterator();
                while (sessionsIterator.hasNext()) {
                    String key = (String) sessionsIterator.next();
                    //Time reduces suspicion and non-ore streaks:
                    sessions.get(key).SelfSuspicionReducer(); //Less suspicion according to the session's own "suspicionDecreaseAmount"
                    sessions.get(key).minedNonOreBlocksStreak += nonOreStreakDecreaseAmount; //Less streak

                    //Clamps:
                    if (sessions.get(key).GetSuspicionLevel() < 0) {
                        sessions.get(key).SetSuspicionLevel(0); //Suspicion min 0
                        sessions.get(key).foundAtZeroSuspicionStreak++;
                        if (sessions.get(key).foundAtZeroSuspicionStreak >= suspicionStreakZeroThreshold)
                            sessions.remove(sessions.get(key)); //Remove MiningSession for inactivity
                    } else sessions.get(key).foundAtZeroSuspicionStreak = 0; //Reset streak
                    if (sessions.get(key).minedNonOreBlocksStreak < 0)
                        sessions.get(key).minedNonOreBlocksStreak = 0; //Non ore mined blocks streak min 0
                }
            }
        }.runTaskTimer(this, mainRunnableFrequency, mainRunnableFrequency);
    }

    //Trail algorithm updater
    private void UpdateTrail(BlockBreakEvent ev, MiningSession s) {
        if (s.GetLastBlockCoordsStoreCounter() == 3) //Every 4 mined blocks
        {
            s.SetMinedBlocksTrailArrayPos(s.GetNextCoordsStorePos(), ev.getBlock().getLocation()); //Store player block destruction coordinates in MiningSession IntVector3 Array
        }

        s.CycleBlockCoordsStoreCounter();
        s.CycleNextCoordsStorePos();
    }

    //Trail algorithm analysis
    private float GetWeightFromAnalyzingTrail(BlockBreakEvent ev, MiningSession s, float mineralWeight) {
        int unalignedMinedBlocksTimesDetected = 0; //Keeps track of how many times a block was detected as outside relative mined ore block height and or X || Z tunnel axises.
        int iteratedBlockCoordSlots = 0; //Keeps track of how many stored blocks we've iterated that weren't null. This is useful for pondering weights according to distance.

        for (int i = 0; i < 10; i++) {
            if (s.GetMinedBlocksTrailArrayPos(i) != null) //Check for a possible empty traced block slot, if so skip, else analyze:
            {
                //Z, X, Y check: Check if the block coordinates we're iterating are outside "3x3 horizontal Z and X axis tunnels" from mined ore. (You can imagine this as a cross with mined ore in center)
                //Relative altitude check:
                if (s.GetMinedBlocksTrailArrayPos(i).GetY() < ev.getBlock().getLocation().getY() - 2 || s.GetMinedBlocksTrailArrayPos(i).GetY() > ev.getBlock().getLocation().getY() + 2) {
                    //Mined block is outside Y axis width
                    unalignedMinedBlocksTimesDetected++; //If trailed block wasn't in an axis, we'll add an unalignment point.
                }
                //Relative X axis separation check:
                if (s.GetMinedBlocksTrailArrayPos(i).GetZ() < ev.getBlock().getLocation().getZ() - 2 || s.GetMinedBlocksTrailArrayPos(i).GetZ() > ev.getBlock().getLocation().getZ() + 2) {
                    //Relative Z axis separation check:
                    if (s.GetMinedBlocksTrailArrayPos(i).GetX() < ev.getBlock().getLocation().getX() - 2 || s.GetMinedBlocksTrailArrayPos(i).GetX() > ev.getBlock().getLocation().getX() + 2) {
                        //Mined block is ALSO outside X axis width
                        unalignedMinedBlocksTimesDetected++; //If trailed block wasn't in an axis, we'll add an unalignment point.
                    }
                }

                iteratedBlockCoordSlots++; //Slot had IntVector3 content, and we did two separate axis checks on it. Iteration complete.
            }
        }

        //Check how many unalignedMinedBlocksTimesDetected we encountered. Apply extra weight for mined ore vein.
        float fractionReducerValue = iteratedBlockCoordSlots - unalignedMinedBlocksTimesDetected / 2; //This value will reduce the additional OreWeight applied

        //If enough unaligned coordinates are detected (more than half the axises checked), assign smaller reduction value.
        if (unalignedMinedBlocksTimesDetected / 2 > iteratedBlockCoordSlots / 2)
            fractionReducerValue = fractionReducerValue / 3;

        if (fractionReducerValue < 1) fractionReducerValue = 1; //Min clamp to 1.

        //Reset all array positions to null:
        s.ResetBlocksTrailArray();

        return mineralWeight + (mineralWeight / fractionReducerValue); //Return final weight based on analysis
    }

    private boolean CheckGoldBiome(BlockBreakEvent ev) //Returns true if biome has incremented chances of gold
    {
        if (ev.getPlayer().getLocation().getBlock().getBiome() == Biome.BADLANDS
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.ERODED_BADLANDS) {
            return true;
        } else return false;
    }

    private boolean CheckEmeraldBiome(BlockBreakEvent ev) //Returns true if biome has incremented chances of emerald
    {
        if (ev.getPlayer().getLocation().getBlock().getBiome() == Biome.WINDSWEPT_HILLS
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.WINDSWEPT_GRAVELLY_HILLS
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.WINDSWEPT_FOREST
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.STONY_PEAKS
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.FROZEN_PEAKS
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.GROVE
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.SNOWY_SLOPES
            || ev.getPlayer().getLocation().getBlock().getBiome() == Biome.JAGGED_PEAKS) {
            return true;
        } else return false;
    }

    //Attempts at updating the mining session for a player who broke a block, with just a few arguments. If this fails, the function returns false, else returns true
    private boolean UpdateMiningSession(BlockBreakEvent ev, Material m) {
        MiningSession s = sessions.get(ev.getPlayer().getName());
        if (s == null) return false; //Return update unsuccessful
        else {
            //MiningSession PROPERTY UPDATES:

            //Relevant non-ores mining triggers
            // These are right on top of the state machine because they're very common:
            if (RELEVANT_BASES.contains(m)) {
                s.UpdateTimeAccountingProperties(ev.getPlayer()); //This method updates some speed/time propeties and may influence suspicion decrease rates
                s.minedNonOreBlocksStreak++;
                UpdateTrail(ev, s); //We mined a non-ore, so we update our trail
            }
            //Relevant ores mining triggers:
            else if (m == Material.COAL_ORE || m == Material.DEEPSLATE_COAL_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                //Check that it's not the same block ore material as the last mined block's. If it is, it will execute "||" statement which will verify the distance from last same mined block material to new mined block is not less than configured vein size:
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    //Check if enough non-ore blocks have been previously mined in order to account for this ore (exposed ores fp prevention):
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        //We got to an ore over threshold, so we analyze our non-ores mined trail and get weight based on that:
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("CoalWeight")));
                        s.minedNonOreBlocksStreak = 0; //Resets previously mined blocks counter
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.REDSTONE_ORE || m == Material.DEEPSLATE_REDSTONE_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("RedstoneWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.IRON_ORE || m == Material.DEEPSLATE_IRON_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("IronWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.GOLD_ORE || m == Material.DEEPSLATE_GOLD_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        //Weight according to biome frequency:
                        if (CheckGoldBiome(ev))
                            s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("GoldWeight")) / getConfig().getLong("FinalGoldWeightDivisionReducer"));
                        else s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("GoldWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.LAPIS_ORE || m == Material.DEEPSLATE_LAPIS_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("LapisWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        if (s.minedNonOreBlocksStreak > usualEncounterThreshold)
                            s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("DiamondWeight"))); //Updates suspicion level normally.
                        else
                            s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, extraDiamondWeight)); //Updates suspicion level with extra suspicion since the ore was quite close to last mined ore.

                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        if (s.minedNonOreBlocksStreak > usualEncounterThreshold) {
                            //Weight according to biome frequency:
                            if (CheckEmeraldBiome(ev))
                                s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("EmeraldWeight")) / getConfig().getLong("FinalEmeraldWeightDivisionReducer"));
                            else
                                s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("EmeraldWeight")));
                        } else {
                            //Weight according to biome frequency:
                            if (CheckEmeraldBiome(ev))
                                s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, extraEmeraldWeight) / getConfig().getLong("FinalEmeraldWeightDivisionReducer"));
                            else s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, extraEmeraldWeight));
                        }

                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.COPPER_ORE || m == Material.DEEPSLATE_COPPER_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("CopperWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.NETHER_QUARTZ_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("QuartzWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.NETHER_GOLD_ORE) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("NetherGoldWeight")));
                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else if (m == Material.ANCIENT_DEBRIS) {
                s.UpdateTimeAccountingProperties(ev.getPlayer());
                if (s.GetLastMinedOre() != m || s.GetLastMinedOreLocation().distance(ev.getBlock().getLocation()) > getConfig().getInt("ConsiderAdjacentWithinDistance"))
                    if (s.minedNonOreBlocksStreak > getConfig().getInt("MinimumBlocksMinedToNextVein")) {
                        if (s.minedNonOreBlocksStreak > usualEncounterThreshold)
                            s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, getConfig().getLong("AncientDebrisWeight"))); //Updates suspicion level normally.
                        else
                            s.AddSuspicionLevel(GetWeightFromAnalyzingTrail(ev, s, extraAncientDebrisWeight)); //Updates suspicion level with extra suspicion since the ore was quite close to last mined ore.

                        s.minedNonOreBlocksStreak = 0;
                    }
                s.SetLastMinedOreData(m, ev.getBlock().getLocation());
            } else {
                //Any other block during mining session
                s.minedNonOreBlocksStreak++;
                UpdateTrail(ev, s); //We mined a non-ore, so we update our trail
            }

            //Property clamping:
            if (s.GetSuspicionLevel() < 0f) s.SetSuspicionLevel(0f);

            //Behaviour analysis and handling:
            if (s.GetSuspicionLevel() > suspicionLevelThreshold) {
                XrayerHandler.HandleXrayer(ev.getPlayer().getName());
            }

            return true; //Return update successful
        }
    }

    //Returns block if relevant, returns Material.AIR if irrelevant
    private Material RelevantBlockCheck(BlockBreakEvent e) {
        Material type = e.getBlock().getType();
        if (RELEVANT_BASES.contains(type)) return type;
        switch (type) {
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE:
                if (getConfig().getLong("CoalWeight") != 0f) return type;
            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE:
                if (getConfig().getLong("RedstoneWeight") != 0f) return type;
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE:
                if (getConfig().getLong("IronWeight") != 0f) return type;
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE:
                if (getConfig().getLong("GoldWeight") != 0f) return type;
            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE:
                if (getConfig().getLong("LapisWeight") != 0f) return type;
            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
                if (getConfig().getLong("DiamondWeight") != 0f) return type;
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
                if (getConfig().getLong("EmeraldWeight") != 0f) return type;
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                if (getConfig().getLong("CopperWeight") != 0f) return type;
            case NETHER_QUARTZ_ORE:
                if (getConfig().getLong("QuartzWeight") != 0f) return type;
            case NETHER_GOLD_ORE:
                if (getConfig().getLong("NetherGoldWeight") != 0f) return type;
            case ANCIENT_DEBRIS:
                if (getConfig().getLong("AncientDebrisWeight") != 0f) return type;
        }
        return Material.AIR;
    }

    //Inspects the blockbreak event further for actions
    protected void BBEventAnalyzer(BlockBreakEvent ev) {
        if (!ev.getPlayer().hasPermission("AXH.Ignore")) {
            //Check if the block is relevant:
            Material m = RelevantBlockCheck(ev);
            if (m != Material.AIR) { //Attempt at updating player mining session:
                if (!UpdateMiningSession(ev, m)) { //Let's asume the player doesn't have a MiningSession entry. Then is the block consequently a first stone or first netherrack?
                    if (RELEVANT_BASES.contains(m)) {
                        sessions.put(ev.getPlayer().getName(), new MiningSession(this)); //Adds new entry to sessions HashMap for player
                    }
                }
            }
        }
    }
}