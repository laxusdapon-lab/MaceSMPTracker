package org.bukkit.event.entity; import org.bukkit.event.Event; import org.bukkit.entity.Entity;
public class EntityRemoveEvent extends Event { public enum Cause { DEATH, DESPAWN, DROP, ENTER_BLOCK, EXPLODE, HIT, MERGE, OUT_OF_WORLD, PICKUP, PLAYER_QUIT, PLUGIN, TRANSFORMATION, UNLOAD }
 public Cause getCause(){return null;} public Entity getEntity(){return null;} }
