package org.bukkit.entity; import java.util.*; import org.bukkit.Location;
public interface Entity { UUID getUniqueId(); boolean isValid(); void remove(); Location getLocation(); EntityType getType(); List<Entity> getNearbyEntities(double x,double y,double z); }
