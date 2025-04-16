package io.josemmo.bukkit.plugin.utils;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import io.josemmo.bukkit.plugin.YamipaPlugin;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.land.LandWorld;
import me.angeschossen.lands.api.player.LandPlayer;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.anjocaido.groupmanager.GroupManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Callable;

public class Permissions {
    private static final Logger LOGGER = Logger.getLogger();
    private static @Nullable LuckPerms luckPerms;
    private static @Nullable GroupManager groupManager;
    private static @Nullable WorldGuard worldGuard;
    private static @Nullable GriefPrevention griefPrevention;
    private static @Nullable TownyAPI townyApi;
    private static @Nullable LandsIntegration landsApi;

    static {
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (NoClassDefFoundError | IllegalStateException __) {
            // LuckPerms is not installed
        }

        try {
            groupManager = (GroupManager) YamipaPlugin.getInstance().getServer().getPluginManager().getPlugin("GroupManager");
        } catch (NoClassDefFoundError __) {
            // GroupManager is not installed
        }

        try {
            worldGuard = WorldGuard.getInstance();
        } catch (NoClassDefFoundError __) {
            // WorldGuard is not installed
        }

        try {
            griefPrevention = GriefPrevention.instance;
        } catch (NoClassDefFoundError __) {
            // GriefPrevention is not installed
        }

        try {
            townyApi = TownyAPI.getInstance();
        } catch (NoClassDefFoundError __) {
            // Towny is not installed
        }

        try {
            landsApi = LandsIntegration.of(YamipaPlugin.getInstance());
        } catch (NoClassDefFoundError __) {
            // Lands is not installed
        }
    }

    /**
     * Get player variable
     *
     * @param variable Variable name (key)
     * @param player   Player instance
     * @return Variable value or NULL if not found
     */
    public static @Nullable String getVariable(@NotNull String variable, @NotNull Player player) {
        if (luckPerms != null) {
            return luckPerms.getPlayerAdapter(Player.class).getUser(player).getCachedData().getMetaData()
                .getMetaValue(variable);
        }

        if (groupManager != null) {
            String rawValue = groupManager.getWorldsHolder().getWorldPermissions(player)
                .getPermissionString(player.getName(), variable);
            return rawValue.isEmpty() ? null : rawValue;
        }

        return null;
    }

    /**
     * Can build at this block
     *
     * @param player   Player instance
     * @param location Block location
     * @return Whether player can build or not
     */
    public static boolean canBuild(@NotNull Player player, @NotNull Location location) {
        return queryWorldGuard(player, location, true)
            && queryGriefPrevention(player, location, true)
            && queryTowny(player, location, true)
            && queryLands(player, location, true);
    }

    /**
     * Can destroy this block
     *
     * @param player   Player instance
     * @param location Block location
     * @return Whether player can destroy or not
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean canDestroy(@NotNull Player player, @NotNull Location location) {
        return queryWorldGuard(player, location, false)
            && queryGriefPrevention(player, location, false)
            && queryTowny(player, location, false)
            && queryLands(player, location, false);
    }

    private static boolean queryWorldGuard(@NotNull Player player, @NotNull Location location, boolean isBuild) {
        if (worldGuard == null) {
            return true;
        }
        WorldGuardPlatform platform = worldGuard.getPlatform();
        LocalPlayer wrappedPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

        // Grant if bypass permission is enabled
        boolean hasBypass = platform.getSessionManager().hasBypass(
            wrappedPlayer,
            BukkitAdapter.adapt(Objects.requireNonNull(location.getWorld()))
        );
        if (hasBypass) {
            return true;
        }

        // Check permission (note "BUILD" flag must always be present)
        StateFlag flag = isBuild ? Flags.BLOCK_PLACE : Flags.BLOCK_BREAK;
        return platform.getRegionContainer().createQuery()
            .testState(BukkitAdapter.adapt(location), wrappedPlayer, Flags.BUILD, flag);
    }

    private static boolean queryGriefPrevention(@NotNull Player player, @NotNull Location location, boolean isBuild) {
        if (griefPrevention == null) {
            return true;
        }
        YamipaPlugin plugin = YamipaPlugin.getInstance();

        // Build callable depending on permission to check
        Callable<Boolean> canEditCallable = isBuild ?
            () -> griefPrevention.allowBuild(player, location) == null :
            () -> griefPrevention.allowBreak(player, location.getBlock(), location) == null;

        // Check permission from primary thread
        try {
            return Bukkit.isPrimaryThread() ?
                canEditCallable.call() :
                Bukkit.getScheduler().callSyncMethod(plugin, canEditCallable).get();
        } catch (Exception e) {
            LOGGER.severe("Failed to get player permissions from GriefPrevention", e);
            return false;
        }
    }

    private static boolean queryTowny(@NotNull Player player, @NotNull Location location, boolean isBuild) {
        if (townyApi == null) {
            return true;
        }
        Material material = location.getBlock().getType();
        TownyPermission.ActionType type = isBuild ? TownyPermission.ActionType.BUILD : TownyPermission.ActionType.DESTROY;
        return PlayerCacheUtil.getCachePermission(player, location, material, type);
    }

    private static boolean queryLands(@NotNull Player player, @NotNull Location location, boolean isBuild) {
        if (landsApi == null) {
            return true;
        }
        LandWorld landWorld = landsApi.getWorld(Objects.requireNonNull(location.getWorld()));
        if (landWorld == null) {
            return true;
        }
        LandPlayer landPlayer = Objects.requireNonNull(landsApi.getLandPlayer(player.getUniqueId()));
        RoleFlag flag = isBuild ?
            me.angeschossen.lands.api.flags.type.Flags.BLOCK_PLACE :
            me.angeschossen.lands.api.flags.type.Flags.BLOCK_BREAK;
        return landWorld.hasRoleFlag(landPlayer, location, flag, null, false);
    }
}
