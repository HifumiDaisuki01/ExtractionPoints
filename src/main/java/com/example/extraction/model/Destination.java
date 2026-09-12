package com.example.extraction.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 撤离目标点（撤离后传送到的安全区）。
 */
public class Destination {

    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public Destination(String worldName, double x, double y, double z, float yaw, float pitch) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static Destination fromConfig(ConfigurationSection sec) {
        if (sec == null) return null;
        String world = sec.getString("world", "world");
        double x = sec.getDouble("x");
        double y = sec.getDouble("y");
        double z = sec.getDouble("z");
        float yaw = (float) sec.getDouble("yaw", 0.0);
        float pitch = (float) sec.getDouble("pitch", 0.0);
        return new Destination(world, x, y, z, yaw, pitch);
    }

    /**
     * 解析为 Bukkit Location。世界未加载时返回 null。
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public String toString() {
        return String.format("%s [%.1f, %.1f, %.1f]", worldName, x, y, z);
    }
}
