package com.gmail.nossr50.database;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.datatypes.MobHealthBarType;
import com.gmail.nossr50.datatypes.database.DatabaseType;
import com.gmail.nossr50.datatypes.database.PlayerStat;
import com.gmail.nossr50.datatypes.database.UpgradeType;
import com.gmail.nossr50.datatypes.player.*;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.runnables.database.UUIDUpdateAsyncTask;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.StringUtils;
import com.gmail.nossr50.util.experience.MMOExperienceBarManager;
import com.gmail.nossr50.util.skills.SkillUtils;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang.NullArgumentException;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

public final class FlatFileDatabaseManager implements DatabaseManager {
    private final HashMap<PrimarySkillType, List<PlayerStat>> playerStatHash = new HashMap<>();
    private final List<PlayerStat> powerLevels = new ArrayList<>();
    private long lastUpdate = 0;

    private final long UPDATE_WAIT_TIME = 600000L; // 10 minutes
    private final File usersFile;
    private static final Object fileWritingLock = new Object();

    protected FlatFileDatabaseManager() {
        usersFile = new File(mcMMO.getUsersFilePath());
        checkStructure();
        updateLeaderboards();

        if (mcMMO.getUpgradeManager().shouldUpgrade(UpgradeType.ADD_UUIDS)) {
            new UUIDUpdateAsyncTask(mcMMO.p, getStoredUsers()).start();
        }
    }

    public void purgePowerlessUsers() {
        int purgedUsers = 0;

        mcMMO.p.getLogger().info("Purging powerless users...");

        BufferedReader in = null;
        FileWriter out = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        // This code is O(n) instead of O(n²)
        synchronized (fileWritingLock) {
            try {
                in = new BufferedReader(new FileReader(usersFilePath));
                StringBuilder writer = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    String[] character = line.split(":");
                    Map<PrimarySkillType, Integer> skills = getSkillMapFromLine(character);

                    boolean powerless = true;
                    for (int skill : skills.values()) {
                        if (skill != 0) {
                            powerless = false;
                            break;
                        }
                    }

                    // If they're still around, rewrite them to the file.
                    if (!powerless) {
                        writer.append(line).append("\r\n");
                    }
                    else {
                        purgedUsers++;
                    }
                }

                // Write the new file
                out = new FileWriter(usersFilePath);
                out.write(writer.toString());
            }
            catch (IOException e) {
                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        mcMMO.p.getLogger().info("Purged " + purgedUsers + " users from the database.");
    }

    public void purgeOldUsers() {
        int removedPlayers = 0;
        long currentTime = System.currentTimeMillis();

        mcMMO.p.getLogger().info("Purging old users...");

        BufferedReader in = null;
        FileWriter out = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        // This code is O(n) instead of O(n²)
        synchronized (fileWritingLock) {
            try {
                in = new BufferedReader(new FileReader(usersFilePath));
                StringBuilder writer = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    String[] character = line.split(":");
                    String name = character[FlatFileMappings.USERNAME];
                    long lastPlayed = 0;
                    boolean rewrite = false;
                    try {
                        lastPlayed = Long.parseLong(character[37]) * Misc.TIME_CONVERSION_FACTOR;
                    }
                    catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                    if (lastPlayed == 0) {
                        OfflinePlayer player = mcMMO.p.getServer().getOfflinePlayer(name);
                        lastPlayed = player.getLastPlayed();
                        rewrite = true;
                    }

                    if (currentTime - lastPlayed > PURGE_TIME) {
                        removedPlayers++;
                    }
                    else {
                        if (rewrite) {
                            // Rewrite their data with a valid time
                            character[37] = Long.toString(lastPlayed);
                            String newLine = org.apache.commons.lang.StringUtils.join(character, ":");
                            writer.append(newLine).append("\r\n");
                        }
                        else {
                            writer.append(line).append("\r\n");
                        }
                    }
                }

                // Write the new file
                out = new FileWriter(usersFilePath);
                out.write(writer.toString());
            }
            catch (IOException e) {
                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        mcMMO.p.getLogger().info("Purged " + removedPlayers + " users from the database.");
    }

    public boolean removeUser(String playerName, UUID uuid) {
        //NOTE: UUID is unused for FlatFile for this interface implementation
        boolean worked = false;

        BufferedReader in = null;
        FileWriter out = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        synchronized (fileWritingLock) {
            try {
                in = new BufferedReader(new FileReader(usersFilePath));
                StringBuilder writer = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    // Write out the same file but when we get to the player we want to remove, we skip his line.
                    if (!worked && line.split(":")[FlatFileMappings.USERNAME].equalsIgnoreCase(playerName)) {
                        mcMMO.p.getLogger().info("User found, removing...");
                        worked = true;
                        continue; // Skip the player
                    }

                    writer.append(line).append("\r\n");
                }

                out = new FileWriter(usersFilePath); // Write out the new file
                out.write(writer.toString());
            }
            catch (Exception e) {
                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        Misc.profileCleanup(playerName);

        return worked;
    }

    @Override
    public void removeCache(UUID uuid) {
        //Not used in FlatFile
    }

    public boolean saveUser(@NotNull MMODataSnapshot dataSnapshot) {
        String playerName = dataSnapshot.getPlayerName();
        UUID uuid = dataSnapshot.getPlayerUUID();

        BufferedReader in = null;
        FileWriter out = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        synchronized (fileWritingLock) {
            try {
                // Open the file
                in = new BufferedReader(new FileReader(usersFilePath));
                StringBuilder writer = new StringBuilder();
                String line;

                boolean wroteUser = false;
                // While not at the end of the file
                while ((line = in.readLine()) != null) {
                    // Read the line in and copy it to the output if it's not the player we want to edit
                    String[] character = line.split(":");
                    if (!character[FlatFileMappings.UUID_INDEX].equalsIgnoreCase(uuid.toString()) && !character[FlatFileMappings.USERNAME].equalsIgnoreCase(playerName)) {
                        writer.append(line).append("\r\n");
                    }
                    else {
                        // Otherwise write the new player information
                        writeUserToLine(dataSnapshot, playerName, uuid, writer);
                        wroteUser = true;
                    }
                }

                /*
                 * If we couldn't find the user in the DB we need to add him
                 */
                if(!wroteUser)
                {
                    writeUserToLine(dataSnapshot, playerName, uuid, writer);
                }

                // Write the new file
                out = new FileWriter(usersFilePath);
                out.write(writer.toString());
                return true;
            }
            catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    private void writeUserToLine(MMODataSnapshot mmoDataSnapshot, String playerName, UUID uuid, StringBuilder writer) {
        ImmutableMap<PrimarySkillType, Integer> primarySkillLevelMap = mmoDataSnapshot.getSkillLevelValues();
        ImmutableMap<PrimarySkillType, Float> primarySkillExperienceValueMap = mmoDataSnapshot.getSkillExperienceValues();

        writer.append(playerName).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.MINING)).append(":");
        writer.append(":");
        writer.append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.MINING)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.WOODCUTTING)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.WOODCUTTING)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.REPAIR)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.UNARMED)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.HERBALISM)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.EXCAVATION)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.ARCHERY)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.SWORDS)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.AXES)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.ACROBATICS)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.REPAIR)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.UNARMED)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.HERBALISM)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.EXCAVATION)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.ARCHERY)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.SWORDS)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.AXES)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.ACROBATICS)).append(":");
        writer.append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.TAMING)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.TAMING)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.BERSERK)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.GIGA_DRILL_BREAKER)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.TREE_FELLER)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.GREEN_TERRA)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.SERRATED_STRIKES)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.SKULL_SPLITTER)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.SUPER_BREAKER)).append(":");
        writer.append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.FISHING)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.FISHING)).append(":");
        writer.append((int) mmoDataSnapshot.getAbilityDATS(SuperAbilityType.BLAST_MINING)).append(":");
        writer.append(System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR).append(":");

        MobHealthBarType mobHealthbarType = mmoDataSnapshot.getMobHealthBarType();
        writer.append(mobHealthbarType.toString()).append(":");

        writer.append(primarySkillLevelMap.get(PrimarySkillType.ALCHEMY)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.ALCHEMY)).append(":");
        writer.append(uuid != null ? uuid.toString() : "NULL").append(":");
        writer.append(mmoDataSnapshot.getScoreboardTipsShown()).append(":");
        writer.append(mmoDataSnapshot.getUniqueData(UniqueDataType.CHIMAERA_WING_DATS)).append(":");

        /*
            public static int SKILLS_TRIDENTS = 44;
            public static int EXP_TRIDENTS = 45;
            public static int SKILLS_CROSSBOWS = 46;
            public static int EXP_CROSSBOWS = 47;
            public static int BARSTATE_ACROBATICS = 48;
            public static int BARSTATE_ALCHEMY = 49;
            public static int BARSTATE_ARCHERY = 50;
            public static int BARSTATE_AXES = 51;
            public static int BARSTATE_EXCAVATION = 52;
            public static int BARSTATE_FISHING = 53;
            public static int BARSTATE_HERBALISM = 54;
            public static int BARSTATE_MINING = 55;
            public static int BARSTATE_REPAIR = 56;
            public static int BARSTATE_SALVAGE = 57;
            public static int BARSTATE_SMELTING = 58;
            public static int BARSTATE_SWORDS = 59;
            public static int BARSTATE_TAMING = 60;
            public static int BARSTATE_UNARMED = 61;
            public static int BARSTATE_WOODCUTTING = 62;
            public static int BARSTATE_TRIDENTS = 63;
            public static int BARSTATE_CROSSBOWS = 64;
         */

        writer.append(primarySkillLevelMap.get(PrimarySkillType.TRIDENTS)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.TRIDENTS)).append(":");
        writer.append(primarySkillLevelMap.get(PrimarySkillType.CROSSBOWS)).append(":");
        writer.append(primarySkillExperienceValueMap.get(PrimarySkillType.CROSSBOWS)).append(":");

        //XPBar States
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.ACROBATICS).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.ALCHEMY).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.ARCHERY).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.AXES).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.EXCAVATION).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.FISHING).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.HERBALISM).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.MINING).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.REPAIR).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.SALVAGE).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.SMELTING).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.SWORDS).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.TAMING).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.UNARMED).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.WOODCUTTING).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.TRIDENTS).toString()).append(":");
        writer.append(mmoDataSnapshot.getBarStateMap().get(PrimarySkillType.CROSSBOWS).toString()).append(":");

        writer.append(0).append(":"); //archery super 1 cd
        writer.append(0).append(":"); //xbow super 1 cd
        writer.append(0).append(":"); //tridents super 1 cd
        writer.append(0).append(":"); //chatspy toggle
        writer.append(0).append(":"); //leaderboard ignored

        writer.append("\r\n");
    }

    public List<PlayerStat> readLeaderboard(PrimarySkillType skill, int pageNumber, int statsPerPage) {
        updateLeaderboards();
        List<PlayerStat> statsList = skill == null ? powerLevels : playerStatHash.get(skill);
        int fromIndex = (Math.max(pageNumber, 1) - 1) * statsPerPage;

        return statsList.subList(Math.min(fromIndex, statsList.size()), Math.min(fromIndex + statsPerPage, statsList.size()));
    }

    public Map<PrimarySkillType, Integer> readRank(String playerName) {
        updateLeaderboards();

        Map<PrimarySkillType, Integer> skills = new HashMap<>();

        for (PrimarySkillType skill : PrimarySkillType.NON_CHILD_SKILLS) {
            skills.put(skill, getPlayerRank(playerName, playerStatHash.get(skill)));
        }

        skills.put(null, getPlayerRank(playerName, powerLevels));

        return skills;
    }

    public void newUser(String playerName, UUID uuid) {
        BufferedWriter out = null;
        synchronized (fileWritingLock) {
            try {
                // Open the file to write the player
                out = new BufferedWriter(new FileWriter(mcMMO.getUsersFilePath(), true));

                String startingLevel = AdvancedConfig.getInstance().getStartingLevel() + ":";

                // Add the player to the end
                out.append(playerName).append(":");
                out.append(startingLevel); // Mining
                out.append(":");
                out.append(":");
                out.append("0:"); // Xp
                out.append(startingLevel); // Woodcutting
                out.append("0:"); // WoodCuttingXp
                out.append(startingLevel); // Repair
                out.append(startingLevel); // Unarmed
                out.append(startingLevel); // Herbalism
                out.append(startingLevel); // Excavation
                out.append(startingLevel); // Archery
                out.append(startingLevel); // Swords
                out.append(startingLevel); // Axes
                out.append(startingLevel); // Acrobatics
                out.append("0:"); // RepairXp
                out.append("0:"); // UnarmedXp
                out.append("0:"); // HerbalismXp
                out.append("0:"); // ExcavationXp
                out.append("0:"); // ArcheryXp
                out.append("0:"); // SwordsXp
                out.append("0:"); // AxesXp
                out.append("0:"); // AcrobaticsXp
                out.append(":");
                out.append(startingLevel); // Taming
                out.append("0:"); // TamingXp
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append("0:"); // DATS
                out.append(":");
                out.append(startingLevel); // Fishing
                out.append("0:"); // FishingXp
                out.append("0:"); // Blast Mining
                out.append(String.valueOf(System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR)).append(":"); // LastLogin
                out.append(Config.getInstance().getMobHealthbarDefault().toString()).append(":"); // Mob Healthbar HUD
                out.append(startingLevel); // Alchemy
                out.append("0:"); // AlchemyXp
                out.append(uuid != null ? uuid.toString() : "NULL").append(":"); // UUID
                out.append("0:"); // Scoreboard tips shown
                out.append("0:"); // Chimaera Wing Dats

                out.append("0:"); // Tridents Skill Level
                out.append("0:"); // Tridents XP
                out.append("0:"); // Crossbow Skill Level
                out.append("0:"); // Crossbow XP Level

                //Barstates for the 15 currently existing skills by ordinal value
                out.append("NORMAL:"); // Acrobatics
                out.append("NORMAL:"); // Alchemy
                out.append("NORMAL:"); // Archery
                out.append("NORMAL:"); // Axes
                out.append("NORMAL:"); // Excavation
                out.append("NORMAL:"); // Fishing
                out.append("NORMAL:"); // Herbalism
                out.append("NORMAL:"); // Mining
                out.append("NORMAL:"); // Repair
                out.append("DISABLED:"); // Salvage
                out.append("DISABLED:"); // Smelting
                out.append("NORMAL:"); // Swords
                out.append("NORMAL:"); // Taming
                out.append("NORMAL:"); // Unarmed
                out.append("NORMAL:"); // Woodcutting
                out.append("NORMAL:"); // Tridents
                out.append("NORMAL:"); // Crossbows

                //2.2.000+
                out.append("0:"); // arch super 1
                out.append("0:"); //xbow super 1
                out.append("0:"); //tridents super 1
                out.append("0:"); //chatspy toggle
                out.append("0:"); //leaderboard ignored toggle


                // Add more in the same format as the line above

                out.newLine();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    public PlayerProfile loadPlayerProfile(UUID uuid) {
        return loadPlayerProfile("", uuid, false);
    }

    public @Nullable PlayerProfile loadPlayerProfile(@NotNull String playerName, @Nullable UUID uuid, boolean createNewPlayer) {
        BufferedReader bufferedReader = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        synchronized (fileWritingLock) {
            try {
                // Open the user file
                bufferedReader = new BufferedReader(new FileReader(usersFilePath));
                String currentLine;

                while ((currentLine = bufferedReader.readLine()) != null) {
                    // Find if the currentLine contains the player we want.
                    String[] stringDataArray = currentLine.split(":");

                    // Compare names because we don't have a valid uuid for that player even
                    // if input uuid is not null
                    if (stringDataArray[FlatFileMappings.UUID_INDEX].equalsIgnoreCase("NULL")) {
                        if (!stringDataArray[FlatFileMappings.USERNAME].equalsIgnoreCase(playerName)) {
                            continue;
                        }
                    }
                    // If input uuid is not null then we should compare uuids
                    else if ((uuid != null && !stringDataArray[FlatFileMappings.UUID_INDEX].equalsIgnoreCase(uuid.toString())) || (uuid == null && !stringDataArray[FlatFileMappings.USERNAME].equalsIgnoreCase(playerName))) {
                        continue;
                    }

                    // Update playerName bufferedReader database after name change
                    if (!stringDataArray[FlatFileMappings.USERNAME].equalsIgnoreCase(playerName)) {
                        mcMMO.p.getLogger().info("Name change detected: " + stringDataArray[FlatFileMappings.USERNAME] + " => " + playerName);
                        stringDataArray[FlatFileMappings.USERNAME] = playerName;
                    }

                    return loadFromLine(stringDataArray);
                }

                // Didn't find the player, createNewPlayer a new one
                if (createNewPlayer) {
                    if(uuid != null) {
                        newUser(playerName, uuid);
                        PersistentPlayerDataBuilder persistentPlayerDataBuilder = new PersistentPlayerDataBuilder();
                        return new PlayerProfile(persistentPlayerDataBuilder.buildNewPlayerData(uuid, playerName));
                    } else {
                        throw new NullArgumentException("playerUUID");
                    }

                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                // I have no idea why it's necessary to inline tryClose() here, but it removes
                // a resource leak warning, and I'm trusting the compiler on this one.
                if (bufferedReader != null) {
                    try {
                        bufferedReader.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        return null;
    }

    public void convertUsers(DatabaseManager destination) {
        BufferedReader in = null;
        String usersFilePath = mcMMO.getUsersFilePath();
        int convertedUsers = 0;
        long startMillis = System.currentTimeMillis();

        synchronized (fileWritingLock) {
            try {
                // Open the user file
                in = new BufferedReader(new FileReader(usersFilePath));
                String line;

                while ((line = in.readLine()) != null) {
                    String[] stringDataSplit = line.split(":");

                    try {
                        PlayerProfile playerProfile = loadFromLine(stringDataSplit);
                        if(playerProfile == null)
                            continue;

                        PersistentPlayerData persistentPlayerData = playerProfile.getPersistentPlayerData();
                        destination.saveUser(mcMMO.getUserManager().createPlayerDataSnapshot(persistentPlayerData));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    convertedUsers++;
                    Misc.printProgress(convertedUsers, progressInterval, startMillis);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

//    public boolean saveUserUUID(String userName, UUID uuid) {
//        boolean worked = false;
//
//        int i = 0;
//        BufferedReader in = null;
//        FileWriter out = null;
//        String usersFilePath = mcMMO.getUsersFilePath();
//
//        synchronized (fileWritingLock) {
//            try {
//                in = new BufferedReader(new FileReader(usersFilePath));
//                StringBuilder writer = new StringBuilder();
//                String line;
//
//                while ((line = in.readLine()) != null) {
//                    String[] character = line.split(":");
//                    if (!worked && character[USERNAME].equalsIgnoreCase(userName)) {
//                        if (character.length < 42) {
//                            mcMMO.p.getLogger().severe("Could not update UUID for " + userName + "!");
//                            mcMMO.p.getLogger().severe("Database entry is invalid.");
//                            continue;
//                        }
//
//                        line = line.replace(character[UUID_INDEX], uuid.toString());
//                        worked = true;
//                    }
//
//                    i++;
//                    writer.append(line).append("\r\n");
//                }
//
//                out = new FileWriter(usersFilePath); // Write out the new file
//                out.write(writer.toString());
//            }
//            catch (Exception e) {
//                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
//            }
//            finally {
//                mcMMO.p.getLogger().info(i + " entries written while saving UUID for " + userName);
//                if (in != null) {
//                    try {
//                        in.close();
//                    }
//                    catch (IOException e) {
//                        // Ignore
//                    }
//                }
//                if (out != null) {
//                    try {
//                        out.close();
//                    }
//                    catch (IOException e) {
//                        // Ignore
//                    }
//                }
//            }
//        }
//
//        return worked;
//    }
//
//    public boolean saveUserUUIDs(Map<String, UUID> fetchedUUIDs) {
//        BufferedReader in = null;
//        FileWriter out = null;
//        String usersFilePath = mcMMO.getUsersFilePath();
//        int i = 0;
//
//        synchronized (fileWritingLock) {
//            try {
//                in = new BufferedReader(new FileReader(usersFilePath));
//                StringBuilder writer = new StringBuilder();
//                String line;
//
//                while (((line = in.readLine()) != null)) {
//                    String[] character = line.split(":");
//                    if (!fetchedUUIDs.isEmpty() && fetchedUUIDs.containsKey(character[USERNAME])) {
//                        if (character.length < 42) {
//                            mcMMO.p.getLogger().severe("Could not update UUID for " + character[USERNAME] + "!");
//                            mcMMO.p.getLogger().severe("Database entry is invalid.");
//                            continue;
//                        }
//
//                        character[UUID_INDEX] = fetchedUUIDs.remove(character[USERNAME]).toString();
//                        line = org.apache.commons.lang.StringUtils.join(character, ":") + ":";
//                    }
//
//                    i++;
//                    writer.append(line).append("\r\n");
//                }
//
//                out = new FileWriter(usersFilePath); // Write out the new file
//                out.write(writer.toString());
//            }
//            catch (Exception e) {
//                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
//            }
//            finally {
//                mcMMO.p.getLogger().info(i + " entries written while saving UUID batch");
//                if (in != null) {
//                    try {
//                        in.close();
//                    }
//                    catch (IOException e) {
//                        // Ignore
//                    }
//                }
//                if (out != null) {
//                    try {
//                        out.close();
//                    }
//                    catch (IOException e) {
//                        // Ignore
//                    }
//                }
//            }
//        }
//
//        return true;
//    }

    public List<String> getStoredUsers() {
        ArrayList<String> users = new ArrayList<>();
        BufferedReader in = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        synchronized (fileWritingLock) {
            try {
                // Open the user file
                in = new BufferedReader(new FileReader(usersFilePath));
                String line;

                while ((line = in.readLine()) != null) {
                    String[] character = line.split(":");
                    users.add(character[FlatFileMappings.USERNAME]);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
        return users;
    }

    /**
     * Update the leader boards.
     */
    private void updateLeaderboards() {
        // Only update FFS leaderboards every 10 minutes.. this puts a lot of strain on the server (depending on the size of the database) and should not be done frequently
        if (System.currentTimeMillis() < lastUpdate + UPDATE_WAIT_TIME) {
            return;
        }

        String usersFilePath = mcMMO.getUsersFilePath();
        lastUpdate = System.currentTimeMillis(); // Log when the last update was run
        powerLevels.clear(); // Clear old values from the power levels

        // Initialize lists
        List<PlayerStat> mining = new ArrayList<>();
        List<PlayerStat> woodcutting = new ArrayList<>();
        List<PlayerStat> herbalism = new ArrayList<>();
        List<PlayerStat> excavation = new ArrayList<>();
        List<PlayerStat> acrobatics = new ArrayList<>();
        List<PlayerStat> repair = new ArrayList<>();
        List<PlayerStat> swords = new ArrayList<>();
        List<PlayerStat> axes = new ArrayList<>();
        List<PlayerStat> archery = new ArrayList<>();
        List<PlayerStat> unarmed = new ArrayList<>();
        List<PlayerStat> taming = new ArrayList<>();
        List<PlayerStat> fishing = new ArrayList<>();
        List<PlayerStat> alchemy = new ArrayList<>();

        BufferedReader in = null;
        String playerName = null;
        // Read from the FlatFile database and fill our arrays with information
        synchronized (fileWritingLock) {
            try {
                in = new BufferedReader(new FileReader(usersFilePath));
                String line;

                while ((line = in.readLine()) != null) {
                    String[] data = line.split(":");
                    playerName = data[FlatFileMappings.USERNAME];
                    int powerLevel = 0;

                    Map<PrimarySkillType, Integer> skills = getSkillMapFromLine(data);

                    powerLevel += putStat(acrobatics, playerName, skills.get(PrimarySkillType.ACROBATICS));
                    powerLevel += putStat(alchemy, playerName, skills.get(PrimarySkillType.ALCHEMY));
                    powerLevel += putStat(archery, playerName, skills.get(PrimarySkillType.ARCHERY));
                    powerLevel += putStat(axes, playerName, skills.get(PrimarySkillType.AXES));
                    powerLevel += putStat(excavation, playerName, skills.get(PrimarySkillType.EXCAVATION));
                    powerLevel += putStat(fishing, playerName, skills.get(PrimarySkillType.FISHING));
                    powerLevel += putStat(herbalism, playerName, skills.get(PrimarySkillType.HERBALISM));
                    powerLevel += putStat(mining, playerName, skills.get(PrimarySkillType.MINING));
                    powerLevel += putStat(repair, playerName, skills.get(PrimarySkillType.REPAIR));
                    powerLevel += putStat(swords, playerName, skills.get(PrimarySkillType.SWORDS));
                    powerLevel += putStat(taming, playerName, skills.get(PrimarySkillType.TAMING));
                    powerLevel += putStat(unarmed, playerName, skills.get(PrimarySkillType.UNARMED));
                    powerLevel += putStat(woodcutting, playerName, skills.get(PrimarySkillType.WOODCUTTING));
                    powerLevel += putStat(woodcutting, playerName, skills.get(PrimarySkillType.CROSSBOWS));
                    powerLevel += putStat(woodcutting, playerName, skills.get(PrimarySkillType.TRIDENTS));

                    putStat(powerLevels, playerName, powerLevel);
                }
            }
            catch (Exception e) {
                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " during user " + playerName + " (Are you sure you formatted it correctly?) " + e.toString());
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        SkillComparator c = new SkillComparator();

        mining.sort(c);
        woodcutting.sort(c);
        repair.sort(c);
        unarmed.sort(c);
        herbalism.sort(c);
        excavation.sort(c);
        archery.sort(c);
        swords.sort(c);
        axes.sort(c);
        acrobatics.sort(c);
        taming.sort(c);
        fishing.sort(c);
        alchemy.sort(c);
        powerLevels.sort(c);

        playerStatHash.put(PrimarySkillType.MINING, mining);
        playerStatHash.put(PrimarySkillType.WOODCUTTING, woodcutting);
        playerStatHash.put(PrimarySkillType.REPAIR, repair);
        playerStatHash.put(PrimarySkillType.UNARMED, unarmed);
        playerStatHash.put(PrimarySkillType.HERBALISM, herbalism);
        playerStatHash.put(PrimarySkillType.EXCAVATION, excavation);
        playerStatHash.put(PrimarySkillType.ARCHERY, archery);
        playerStatHash.put(PrimarySkillType.SWORDS, swords);
        playerStatHash.put(PrimarySkillType.AXES, axes);
        playerStatHash.put(PrimarySkillType.ACROBATICS, acrobatics);
        playerStatHash.put(PrimarySkillType.TAMING, taming);
        playerStatHash.put(PrimarySkillType.FISHING, fishing);
        playerStatHash.put(PrimarySkillType.ALCHEMY, alchemy);
    }

    /**
     * Checks that the file is present and valid
     */
    private void checkStructure() {
        if (usersFile.exists()) {
            BufferedReader in = null;
            FileWriter out = null;
            String usersFilePath = mcMMO.getUsersFilePath();

            synchronized (fileWritingLock) {
                try {
                    in = new BufferedReader(new FileReader(usersFilePath));
                    StringBuilder writer = new StringBuilder();
                    String line;
                    HashSet<String> usernames = new HashSet<>();
                    HashSet<String> players = new HashSet<>();

                    while ((line = in.readLine()) != null) {
                        // Remove empty lines from the file
                        if (line.isEmpty()) {
                            continue;
                        }

                        // Length checks depend on last stringDataArray being ':'
                        if (line.charAt(line.length() - 1) != ':') {
                            line = line.concat(":");
                        }
                        boolean updated = false;
                        String[] stringDataArray = line.split(":");
                        int originalLength = stringDataArray.length;

                        // Prevent the same username from being present multiple times
                        if (!usernames.add(stringDataArray[FlatFileMappings.USERNAME])) {
                            stringDataArray[FlatFileMappings.USERNAME] = "_INVALID_OLD_USERNAME_'";
                            updated = true;
                            if (stringDataArray.length < FlatFileMappings.UUID_INDEX + 1 || stringDataArray[FlatFileMappings.UUID_INDEX].equals("NULL")) {
                                continue;
                            }
                        }


                        if (stringDataArray.length < 33) {
                            // Before Version 1.0 - Drop
                            mcMMO.p.getLogger().warning("Dropping malformed or before version 1.0 line from database - " + line);
                            continue;
                        }

                        String oldVersion = null;

                        if (stringDataArray.length > 33 && !stringDataArray[33].isEmpty()) {
                            // Removal of Spout Support
                            // Version 1.4.07-dev2
                            // commit 7bac0e2ca5143bce84dc160617fed97f0b1cb968
                            stringDataArray[33] = "";
                            oldVersion = "1.4.07";
                            updated = true;
                        }

                        if (stringDataArray.length <= 33) {
                            // Introduction of HUDType
                            // Version 1.1.06
                            // commit 78f79213cdd7190cd11ae54526f3b4ea42078e8a
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "";
                            oldVersion = "1.1.06";
                            updated = true;
                        }

                        if (stringDataArray.length <= 35) {
                            // Introduction of Fishing
                            // Version 1.2.00
                            // commit a814b57311bc7734661109f0e77fc8bab3a0bd29
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 2);
                            stringDataArray[stringDataArray.length - 1] = "0";
                            stringDataArray[stringDataArray.length - 2] = "0";
                            if (oldVersion == null) {
                                oldVersion = "1.2.00";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 36) {
                            // Introduction of Blast Mining cooldowns
                            // Version 1.3.00-dev
                            // commit fadbaf429d6b4764b8f1ad0efaa524a090e82ef5
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "0";
                            if (oldVersion == null) {
                                oldVersion = "1.3.00";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 37) {
                            // Making old-purge work with flatfile
                            // Version 1.4.00-dev
                            // commmit 3f6c07ba6aaf44e388cc3b882cac3d8f51d0ac28
                            // XXX Cannot create an OfflinePlayer at startup, use 0 and fix in purge
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "0";
                            if (oldVersion == null) {
                                oldVersion = "1.4.00";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 38) {
                            // Addition of mob healthbars
                            // Version 1.4.06
                            // commit da29185b7dc7e0d992754bba555576d48fa08aa6
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = Config.getInstance().getMobHealthbarDefault().toString();
                            if (oldVersion == null) {
                                oldVersion = "1.4.06";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 39) {
                            // Addition of Alchemy
                            // Version 1.4.08
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 2);
                            stringDataArray[stringDataArray.length - 1] = "0";
                            stringDataArray[stringDataArray.length - 2] = "0";
                            if (oldVersion == null) {
                                oldVersion = "1.4.08";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 41) {
                            // Addition of UUIDs
                            // Version 1.5.01
                            // Add a value because otherwise it gets removed
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "NULL";
                            if (oldVersion == null) {
                                oldVersion = "1.5.01";
                            }
                            updated = true;
                        }
                        if (stringDataArray.length <= 42) {
                            // Addition of scoreboard tips auto disable
                            // Version 1.5.02
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "0";

                            if (oldVersion == null) {
                                oldVersion = "1.5.02";
                            }
                            updated = true;
                        }

                        if(stringDataArray.length <= 43) {
                            // Addition of Chimaera wing DATS
                            stringDataArray = Arrays.copyOf(stringDataArray, stringDataArray.length + 1);
                            stringDataArray[stringDataArray.length - 1] = "0";

                            if (oldVersion == null) {
                                oldVersion = "2.1.133";
                            }
                            updated = true;
                        }

                        if(stringDataArray.length <= FlatFileMappings.LENGTH_OF_SPLIT_DATA_ARRAY) {

                            if (oldVersion == null) {
                                oldVersion = "2.1.134";
                            }

                            stringDataArray = Arrays.copyOf(stringDataArray, FlatFileMappings.LENGTH_OF_SPLIT_DATA_ARRAY); // new array size

                            /*
                                public static int SKILLS_TRIDENTS = 44;
                                public static int EXP_TRIDENTS = 45;
                                public static int SKILLS_CROSSBOWS = 46;
                                public static int EXP_CROSSBOWS = 47;
                                public static int BARSTATE_ACROBATICS = 48;
                                public static int BARSTATE_ALCHEMY = 49;
                                public static int BARSTATE_ARCHERY = 50;
                                public static int BARSTATE_AXES = 51;
                                public static int BARSTATE_EXCAVATION = 52;
                                public static int BARSTATE_FISHING = 53;
                                public static int BARSTATE_HERBALISM = 54;
                                public static int BARSTATE_MINING = 55;
                                public static int BARSTATE_REPAIR = 56;
                                public static int BARSTATE_SALVAGE = 57;
                                public static int BARSTATE_SMELTING = 58;
                                public static int BARSTATE_SWORDS = 59;
                                public static int BARSTATE_TAMING = 60;
                                public static int BARSTATE_UNARMED = 61;
                                public static int BARSTATE_WOODCUTTING = 62;
                                public static int BARSTATE_TRIDENTS = 63;
                                public static int BARSTATE_CROSSBOWS = 64;
                             */

                            stringDataArray[FlatFileMappings.SKILLS_TRIDENTS] = "0"; //trident skill lvl
                            stringDataArray[FlatFileMappings.EXP_TRIDENTS] = "0"; //trident xp value
                            stringDataArray[FlatFileMappings.SKILLS_CROSSBOWS] = "0"; //xbow skill lvl
                            stringDataArray[FlatFileMappings.EXP_CROSSBOWS] = "0"; //xbow xp lvl

                            //Barstates 48-64
                            stringDataArray[FlatFileMappings.BARSTATE_ACROBATICS] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_ALCHEMY] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_ARCHERY] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_AXES] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_EXCAVATION] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_FISHING] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_HERBALISM] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_MINING] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_REPAIR] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_SALVAGE] = "DISABLED"; //Child skills
                            stringDataArray[FlatFileMappings.BARSTATE_SMELTING] = "DISABLED"; //Child skills
                            stringDataArray[FlatFileMappings.BARSTATE_SWORDS] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_TAMING] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_UNARMED] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_WOODCUTTING] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_TRIDENTS] = "NORMAL";
                            stringDataArray[FlatFileMappings.BARSTATE_CROSSBOWS] = "NORMAL";

                            stringDataArray[FlatFileMappings.COOLDOWN_ARCHERY_SUPER_1] = "0";
                            stringDataArray[FlatFileMappings.COOLDOWN_CROSSBOWS_SUPER_1] = "0";
                            stringDataArray[FlatFileMappings.COOLDOWN_TRIDENTS_SUPER_1] = "0";

                            stringDataArray[FlatFileMappings.CHATSPY_TOGGLE] = "0";
                            stringDataArray[FlatFileMappings.LEADERBOARD_IGNORED] = "0";

                            //This part is a bit odd because lastlogin already has a place in the index but it was unused
                            stringDataArray[FlatFileMappings.LAST_LOGIN] = "0";

                            updated = true;
                        }

                        //TODO: If new skills are added this needs to be rewritten
                        if (Config.getInstance().getTruncateSkills()) {
                            for (PrimarySkillType skill : PrimarySkillType.NON_CHILD_SKILLS) {
                                int index = getSkillIndex(skill);
                                if (index >= stringDataArray.length) {
                                    continue;
                                }
                                int cap = Config.getInstance().getLevelCap(skill);
                                if (Integer.parseInt(stringDataArray[index]) > cap) {
                                    mcMMO.p.getLogger().warning("Truncating " + skill.getName() + " to configured max level for player " + stringDataArray[FlatFileMappings.USERNAME]);
                                    stringDataArray[index] = cap + "";
                                    updated = true;
                                }
                            }
                        }

                        boolean corrupted = false;

                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of date
                        //TODO: Update this corruption code, its super out of dated

                        for (int i = 0; i < stringDataArray.length; i++) {
                            //Sigh... this code
                            if (stringDataArray[i].isEmpty() && !(i == 2 || i == 3 || i == 23 || i == 33 || i == 41)) {
                                mcMMO.p.getLogger().info("Player data at index "+i+" appears to be empty, possible corruption of data has occurred.");
                                corrupted = true;
                                if (i == 37) {
                                    stringDataArray[i] = String.valueOf(System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR);
                                }
                                else if (i == 38) {
                                    stringDataArray[i] = Config.getInstance().getMobHealthbarDefault().toString();
                                }
                                else {
                                    stringDataArray[i] = "0";
                                }
                            }

                            if (StringUtils.isInt(stringDataArray[i]) && i == 38) {
                                corrupted = true;
                                stringDataArray[i] = Config.getInstance().getMobHealthbarDefault().toString();
                            }

                            if (!StringUtils.isInt(stringDataArray[i]) && !(i == 0 || i == 2 || i == 3 || i == 23 || i == 33 || i == 38 || i == 41)) {
                                corrupted = true;
                                stringDataArray[i] = "0";
                            }
                        }

                        if (corrupted) {
                            mcMMO.p.getLogger().info("Updating corrupted database line for player " + stringDataArray[FlatFileMappings.USERNAME]);
                        }

                        if (oldVersion != null) {
                            mcMMO.p.getLogger().info("Updating database line from before version " + oldVersion + " for player " + stringDataArray[FlatFileMappings.USERNAME]);
                        }

                        updated |= corrupted;
                        updated |= oldVersion != null;

                        if (Config.getInstance().getTruncateSkills()) {
                            Map<PrimarySkillType, Integer> skills = getSkillMapFromLine(stringDataArray);
                            for (PrimarySkillType skill : PrimarySkillType.NON_CHILD_SKILLS) {
                                int cap = Config.getInstance().getLevelCap(skill);
                                if (skills.get(skill) > cap) {
                                    updated = true;
                                }
                            }
                        }

                        if (updated) {
                            line = org.apache.commons.lang.StringUtils.join(stringDataArray, ":") + ":";
                        }

                        // Prevent the same player from being present multiple times
                        if (stringDataArray.length == originalLength //If the length changed then the schema was expanded
                                && (!stringDataArray[FlatFileMappings.UUID_INDEX].isEmpty()
                                && !stringDataArray[FlatFileMappings.UUID_INDEX].equals("NULL")
                                && !players.add(stringDataArray[FlatFileMappings.UUID_INDEX]))) {
                            continue;
                        }

                        writer.append(line).append("\r\n");
                    }

                    // Write the new file
                    out = new FileWriter(usersFilePath);
                    out.write(writer.toString());
                }
                catch (IOException e) {
                    mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
                }
                finally {
                    if (in != null) {
                        try {
                            in.close();
                        }
                        catch (IOException e) {
                            // Ignore
                        }
                    }
                    if (out != null) {
                        try {
                            out.close();
                        }
                        catch (IOException e) {
                            // Ignore
                        }
                    }
                }
            }

            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.ADD_FISHING);
            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.ADD_BLAST_MINING_COOLDOWN);
            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.ADD_SQL_INDEXES);
            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.ADD_MOB_HEALTHBARS);
//            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.DROP_SQL_PARTY_NAMES);
            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.DROP_SPOUT);
            mcMMO.getUpgradeManager().setUpgradeCompleted(UpgradeType.ADD_ALCHEMY);
            return;
        }

        usersFile.getParentFile().mkdir();

        try {
            mcMMO.p.getLogger().info("Creating mcmmo.users file...");
            new File(mcMMO.getUsersFilePath()).createNewFile();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Integer getPlayerRank(String playerName, List<PlayerStat> statsList) {
        if (statsList == null) {
            return null;
        }

        int currentPos = 1;

        for (PlayerStat stat : statsList) {
            if (stat.name.equalsIgnoreCase(playerName)) {
                return currentPos;
            }

            currentPos++;
        }

        return null;
    }

    private int putStat(List<PlayerStat> statList, String playerName, int statValue) {
        statList.add(new PlayerStat(playerName, statValue));
        return statValue;
    }

    private static class SkillComparator implements Comparator<PlayerStat> {
        @Override
        public int compare(PlayerStat o1, PlayerStat o2) {
            return (o2.statVal - o1.statVal);
        }
    }

    private @Nullable PlayerProfile loadFromLine(String[] subString) {
        PersistentPlayerDataBuilder playerDataBuilder = new PersistentPlayerDataBuilder();

        EnumMap<PrimarySkillType, Integer>   skillLevelMap     = getSkillMapFromLine(subString);      // Skill levels
        EnumMap<PrimarySkillType, Float>     skillExperienceValueMap   = new EnumMap<PrimarySkillType, Float>(PrimarySkillType.class);     // Skill & XP
        EnumMap<SuperAbilityType, Integer> skillAbilityDeactivationTimeStamp = new EnumMap<SuperAbilityType, Integer>(SuperAbilityType.class); // Ability & Cooldown
        EnumMap<UniqueDataType, Integer> uniquePlayerDataMap = new EnumMap<UniqueDataType, Integer>(UniqueDataType.class);
        EnumMap<PrimarySkillType, MMOExperienceBarManager.BarState> xpBarStateMap = new EnumMap<PrimarySkillType, MMOExperienceBarManager.BarState>(PrimarySkillType.class);
        MobHealthBarType mobHealthbarType;
        int scoreboardTipsShown;

        skillExperienceValueMap.put(PrimarySkillType.TAMING, (float) Integer.parseInt(subString[FlatFileMappings.EXP_TAMING]));
        skillExperienceValueMap.put(PrimarySkillType.MINING, (float) Integer.parseInt(subString[FlatFileMappings.EXP_MINING]));
        skillExperienceValueMap.put(PrimarySkillType.REPAIR, (float) Integer.parseInt(subString[FlatFileMappings.EXP_REPAIR]));
        skillExperienceValueMap.put(PrimarySkillType.WOODCUTTING, (float) Integer.parseInt(subString[FlatFileMappings.EXP_WOODCUTTING]));
        skillExperienceValueMap.put(PrimarySkillType.UNARMED, (float) Integer.parseInt(subString[FlatFileMappings.EXP_UNARMED]));
        skillExperienceValueMap.put(PrimarySkillType.HERBALISM, (float) Integer.parseInt(subString[FlatFileMappings.EXP_HERBALISM]));
        skillExperienceValueMap.put(PrimarySkillType.EXCAVATION, (float) Integer.parseInt(subString[FlatFileMappings.EXP_EXCAVATION]));
        skillExperienceValueMap.put(PrimarySkillType.ARCHERY, (float) Integer.parseInt(subString[FlatFileMappings.EXP_ARCHERY]));
        skillExperienceValueMap.put(PrimarySkillType.SWORDS, (float) Integer.parseInt(subString[FlatFileMappings.EXP_SWORDS]));
        skillExperienceValueMap.put(PrimarySkillType.AXES, (float) Integer.parseInt(subString[FlatFileMappings.EXP_AXES]));
        skillExperienceValueMap.put(PrimarySkillType.ACROBATICS, (float) Integer.parseInt(subString[FlatFileMappings.EXP_ACROBATICS]));
        skillExperienceValueMap.put(PrimarySkillType.FISHING, (float) Integer.parseInt(subString[FlatFileMappings.EXP_FISHING]));
        skillExperienceValueMap.put(PrimarySkillType.ALCHEMY, (float) Integer.parseInt(subString[FlatFileMappings.EXP_ALCHEMY]));
        skillExperienceValueMap.put(PrimarySkillType.TRIDENTS, (float) Integer.parseInt(subString[FlatFileMappings.EXP_TRIDENTS]));
        skillExperienceValueMap.put(PrimarySkillType.CROSSBOWS, (float) Integer.parseInt(subString[FlatFileMappings.EXP_CROSSBOWS]));

        //Set Skill XP

        // Taming - Unused
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.SUPER_BREAKER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_SUPER_BREAKER]));
        // Repair - Unused
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.TREE_FELLER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_TREE_FELLER]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.BERSERK, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_BERSERK]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.GREEN_TERRA, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_GREEN_TERRA]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.GIGA_DRILL_BREAKER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_GIGA_DRILL_BREAKER]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.SERRATED_STRIKES, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_SERRATED_STRIKES]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.SKULL_SPLITTER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_SKULL_SPLITTER]));
        // Acrobatics - Unused
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.BLAST_MINING, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_BLAST_MINING]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.ARCHERY_SUPER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_ARCHERY_SUPER_1]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.SUPER_SHOTGUN, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_CROSSBOWS_SUPER_1]));
        skillAbilityDeactivationTimeStamp.put(SuperAbilityType.TRIDENT_SUPER, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_TRIDENTS_SUPER_1]));



        try {
            mobHealthbarType = MobHealthBarType.valueOf(subString[FlatFileMappings.HEALTHBAR]);
        }
        catch (Exception e) {
            mobHealthbarType = Config.getInstance().getMobHealthbarDefault();
        }


        UUID playerUUID;

        try {
            playerUUID = UUID.fromString(subString[FlatFileMappings.UUID_INDEX]);
        }
        catch (Exception e) {
            mcMMO.p.getLogger().severe("UUID not found for data entry, skipping entry");
            return null;
        }


        try {
            scoreboardTipsShown = Integer.parseInt(subString[FlatFileMappings.SCOREBOARD_TIPS]);
        }
        catch (Exception e) {
            scoreboardTipsShown = 0;
        }


        try {
            uniquePlayerDataMap.put(UniqueDataType.CHIMAERA_WING_DATS, Integer.valueOf(subString[FlatFileMappings.COOLDOWN_CHIMAERA_WING]));
        }
        catch (Exception e) {
            uniquePlayerDataMap.put(UniqueDataType.CHIMAERA_WING_DATS, 0);
        }


        try {
            xpBarStateMap.put(PrimarySkillType.ACROBATICS, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_ACROBATICS]));
            xpBarStateMap.put(PrimarySkillType.ALCHEMY, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_ALCHEMY]));
            xpBarStateMap.put(PrimarySkillType.ARCHERY, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_ARCHERY]));
            xpBarStateMap.put(PrimarySkillType.AXES, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_AXES]));
            xpBarStateMap.put(PrimarySkillType.EXCAVATION, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_EXCAVATION]));
            xpBarStateMap.put(PrimarySkillType.FISHING, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_FISHING]));
            xpBarStateMap.put(PrimarySkillType.HERBALISM, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_HERBALISM]));
            xpBarStateMap.put(PrimarySkillType.MINING, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_MINING]));
            xpBarStateMap.put(PrimarySkillType.REPAIR, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_REPAIR]));
            xpBarStateMap.put(PrimarySkillType.SALVAGE, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_SALVAGE]));
            xpBarStateMap.put(PrimarySkillType.SMELTING, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_SMELTING]));
            xpBarStateMap.put(PrimarySkillType.SWORDS, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_SWORDS]));
            xpBarStateMap.put(PrimarySkillType.TAMING, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_TAMING]));
            xpBarStateMap.put(PrimarySkillType.UNARMED, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_UNARMED]));
            xpBarStateMap.put(PrimarySkillType.WOODCUTTING, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_WOODCUTTING]));
            xpBarStateMap.put(PrimarySkillType.TRIDENTS, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_TRIDENTS]));
            xpBarStateMap.put(PrimarySkillType.CROSSBOWS, SkillUtils.asBarState(subString[FlatFileMappings.BARSTATE_CROSSBOWS]));

        } catch (Exception e) {
            xpBarStateMap = MMOExperienceBarManager.generateDefaultBarStateMap();
        }

        //Build Player Data
        playerDataBuilder.setSkillLevelValues(skillLevelMap)
            .setSkillExperienceValues(skillExperienceValueMap)
            .setAbilityDeactivationTimestamps(skillAbilityDeactivationTimeStamp)
            .setMobHealthBarType(mobHealthbarType)
            .setPlayerUUID(playerUUID)
            .setScoreboardTipsShown(scoreboardTipsShown)
            .setUniquePlayerData(uniquePlayerDataMap)
            .setBarStateMap(xpBarStateMap);

        PersistentPlayerData persistentPlayerData;

        try {
            persistentPlayerData = playerDataBuilder.build();
        } catch (Exception e) {
            mcMMO.p.getLogger().severe("Critical failure when trying to construct persistent player data!");
            e.printStackTrace();
            return null;
        }


//        return new PlayerProfile(subString[USERNAME], playerUUID, skillLevelMap, skillExperienceValueMap, skillAbilityDeactivationTimeStamp, mobHealthbarType, scoreboardTipsShown, uniquePlayerDataMap, xpBarStateMap);
        return new PlayerProfile(persistentPlayerData);
    }

    private EnumMap<PrimarySkillType, Integer> getSkillMapFromLine(String[] stringDataArray) {
        EnumMap<PrimarySkillType, Integer> skillLevelsMap = new EnumMap<>(PrimarySkillType.class);   // Skill & Level

        skillLevelsMap.put(PrimarySkillType.TAMING, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_TAMING]));
        skillLevelsMap.put(PrimarySkillType.MINING, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_MINING]));
        skillLevelsMap.put(PrimarySkillType.REPAIR, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_REPAIR]));
        skillLevelsMap.put(PrimarySkillType.WOODCUTTING, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_WOODCUTTING]));
        skillLevelsMap.put(PrimarySkillType.UNARMED, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_UNARMED]));
        skillLevelsMap.put(PrimarySkillType.HERBALISM, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_HERBALISM]));
        skillLevelsMap.put(PrimarySkillType.EXCAVATION, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_EXCAVATION]));
        skillLevelsMap.put(PrimarySkillType.ARCHERY, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_ARCHERY]));
        skillLevelsMap.put(PrimarySkillType.SWORDS, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_SWORDS]));
        skillLevelsMap.put(PrimarySkillType.AXES, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_AXES]));
        skillLevelsMap.put(PrimarySkillType.ACROBATICS, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_ACROBATICS]));
        skillLevelsMap.put(PrimarySkillType.FISHING, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_FISHING]));
        skillLevelsMap.put(PrimarySkillType.ALCHEMY, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_ALCHEMY]));
        skillLevelsMap.put(PrimarySkillType.TRIDENTS, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_TRIDENTS]));
        skillLevelsMap.put(PrimarySkillType.CROSSBOWS, Integer.valueOf(stringDataArray[FlatFileMappings.SKILLS_CROSSBOWS]));

        return skillLevelsMap;
    }

    public DatabaseType getDatabaseType() {
        return DatabaseType.FLATFILE;
    }

    private int getSkillIndex(PrimarySkillType primarySkillType) {
        switch (primarySkillType) {
            case ACROBATICS:
                return FlatFileMappings.SKILLS_ACROBATICS;
            case ALCHEMY:
                return FlatFileMappings.SKILLS_ALCHEMY;
            case ARCHERY:
                return FlatFileMappings.SKILLS_ARCHERY;
            case AXES:
                return FlatFileMappings.SKILLS_AXES;
            case EXCAVATION:
                return FlatFileMappings.SKILLS_EXCAVATION;
            case FISHING:
                return FlatFileMappings.SKILLS_FISHING;
            case HERBALISM:
                return FlatFileMappings.SKILLS_HERBALISM;
            case MINING:
                return FlatFileMappings.SKILLS_MINING;
            case REPAIR:
                return FlatFileMappings.SKILLS_REPAIR;
            case SWORDS:
                return FlatFileMappings.SKILLS_SWORDS;
            case TAMING:
                return FlatFileMappings.SKILLS_TAMING;
            case UNARMED:
                return FlatFileMappings.SKILLS_UNARMED;
            case WOODCUTTING:
                return FlatFileMappings.SKILLS_WOODCUTTING;
            case TRIDENTS:
                return FlatFileMappings.SKILLS_TRIDENTS;
            case CROSSBOWS:
                return FlatFileMappings.SKILLS_CROSSBOWS;
            default:
                throw new RuntimeException("Primary Skills only");

        }
    }

    @Override
    public void onDisable() { }

    public void resetMobHealthSettings() {
        BufferedReader in = null;
        FileWriter out = null;
        String usersFilePath = mcMMO.getUsersFilePath();

        synchronized (fileWritingLock) {
            try {
                in = new BufferedReader(new FileReader(usersFilePath));
                StringBuilder writer = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    // Remove empty lines from the file
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] character = line.split(":");
                    
                    character[FlatFileMappings.HEALTHBAR] = Config.getInstance().getMobHealthbarDefault().toString();
                    
                    line = org.apache.commons.lang.StringUtils.join(character, ":") + ":";

                    writer.append(line).append("\r\n");
                }

                // Write the new file
                out = new FileWriter(usersFilePath);
                out.write(writer.toString());
            }
            catch (IOException e) {
                mcMMO.p.getLogger().severe("Exception while reading " + usersFilePath + " (Are you sure you formatted it correctly?)" + e.toString());
            }
            finally {
                if (in != null) {
                    try {
                        in.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    }
                    catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }
}