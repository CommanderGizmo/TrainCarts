package com.bergerkiller.bukkit.tc.storage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import net.minecraft.server.WorldServer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;

import com.bergerkiller.bukkit.common.config.DataReader;
import com.bergerkiller.bukkit.common.config.DataWriter;
import com.bergerkiller.bukkit.common.utils.StreamUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.tc.MinecartGroup;
import com.bergerkiller.bukkit.tc.MinecartMember;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.TrainProperties;

public class OfflineGroupManager {
	private static boolean chunkLoadReq = false;
	private static boolean ignoreChunkLoad = false;
	private static Set<String> containedTrains = new HashSet<String>();
	private static HashSet<UUID> hiddenMinecarts = new HashSet<UUID>();
	private static Map<UUID, OfflineGroupManager> managers = new HashMap<UUID, OfflineGroupManager>();
	public static OfflineGroupManager get(UUID uuid) {
		OfflineGroupManager rval = managers.get(uuid);
		if (rval == null) {
			rval = new OfflineGroupManager();
			managers.put(uuid, rval);
		}
		return rval;
	}
	public static OfflineGroupManager get(World world) {
		return get(world.getUID());
	}		
	public static void loadChunk(Chunk chunk) {
		chunkLoadReq = true;
		if (ignoreChunkLoad) return;
		synchronized (managers) {
			OfflineGroupManager man = managers.get(chunk.getWorld().getUID());
			if (man != null) {
				if (man.groupmap.isEmpty()) {
					managers.remove(chunk.getWorld().getUID());
				} else {
					Set<OfflineGroup> groups = man.groupmap.remove(chunk);
					if (groups != null) {
						for (OfflineGroup group : groups) {
							group.chunkCounter++;
							if (group.testFullyLoaded()) {
								//a participant to be restored
								if (group.updateLoadedChunks(chunk.getWorld())) {
									man.groupmap.remove(group);
									containedTrains.remove(group.name);
									restoreGroup(group, chunk.getWorld());
								} else {
									//add it again
									man.groupmap.add(group);
								}
							}
						}
					}
				}
			}
		}
	}
	public static void unloadChunk(Chunk chunk) {
		synchronized (managers) {
			OfflineGroupManager man = managers.get(chunk.getWorld().getUID());
			if (man != null) {
				if (man.groupmap.isEmpty()) {
					managers.remove(chunk.getWorld().getUID());
				} else {
					Set<OfflineGroup> groupset = man.groupmap.get(chunk);
					if (groupset != null) {
						for (OfflineGroup group : groupset) {
							group.chunkCounter--;
						}
					}
				}
			}
		}
	}

	private OfflineGroupMap groupmap = new OfflineGroupMap();

	public static void refresh() {
		for (WorldServer world : WorldUtil.getWorlds()) {
			refresh(world.getWorld());
		}
	}
	public static void refresh(World world) {
		synchronized (managers) {
			OfflineGroupManager man = managers.get(world.getUID());
			if (man != null) {
				if (man.groupmap.isEmpty()) {
					managers.remove(world.getUID());
				} else {
					ignoreChunkLoad = true;
					man.refreshGroups(world);
					ignoreChunkLoad = false;
				}
			}
		}
	}

	public void refreshGroups(World world) {
		chunkLoadReq = false;
		try {
			Iterator<OfflineGroup> iter = this.groupmap.values().iterator();
			while (iter.hasNext()) {
				OfflineGroup wg = iter.next();
				if (checkChunks(wg, world)) {
					containedTrains.remove(wg.name);
					this.groupmap.remove(wg, true);
					iter.remove();
					restoreGroup(wg, world);
				}
			}
			if (chunkLoadReq) {
				this.refreshGroups(world);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private static boolean checkChunks(OfflineGroup g, World world) {
		if (g.updateLoadedChunks(world)) {
		} else if (TrainProperties.get(g.name).keepChunksLoaded) {
			if (TrainCarts.keepChunksLoadedOnlyWhenMoving) {
				boolean ismoving = false;
				for (OfflineMember wm : g.members) {
					if (Math.abs(wm.motX) > 0.001) {
						ismoving = true;
						break;
					}
					if (Math.abs(wm.motZ) > 0.001) {
						ismoving = true;
						break;
					}
				}
				if (!ismoving) return false;
			}
			//load nearby chunks
			for (OfflineMember wm : g.members) {
				WorldUtil.loadChunks(world, wm.cx, wm.cz, 2);
			}
		} else {
			return false;
		}
		return true;
	}
	private static void restoreGroup(OfflineGroup g, World world) {
		for (OfflineMember wm : g.members) {
			hiddenMinecarts.remove(wm.entityUID);
		}
		MinecartGroup.create(g.name, g.getMinecarts(world));
	}


	/*
	 * Train removal
	 */
	public static int destroyAll(World world) {
		synchronized (managers) {
			OfflineGroupManager man = managers.remove(world.getUID());
			if (man != null) {
				for (OfflineGroup wg : man.groupmap) {
					containedTrains.remove(wg.name);
					for (OfflineMember wm : wg.members) {
						hiddenMinecarts.remove(wm.entityUID);
					}
				}
			}
		}
		int count = 0;
		for (MinecartGroup g : MinecartGroup.getGroups()) {
			if (g.getWorld() == world) {
				if (!g.isEmpty()) count++;
				g.destroy();
			}
		}
		destroyMinecarts(world);
		return count;
	}
	public static int destroyAll() {
		int count = 0;
		synchronized (managers) {
			managers.clear();
			containedTrains.clear();
			hiddenMinecarts.clear();
		}
		for (MinecartGroup g : MinecartGroup.getGroups()) {
			if (!g.isEmpty()) count++;
			g.destroy();
		}
		for (World world : Bukkit.getServer().getWorlds()) {
			destroyMinecarts(world);
		}
		TrainProperties.clearAll();
		return count;
	}
	public static void destroyMinecarts(World world) {
		for (Entity e : world.getEntities()) {
			if (!e.isDead()) {
				if (e instanceof Minecart) e.remove();
			}
		}
	}

	private static void deinit() {
		managers.clear();
		hiddenMinecarts.clear();
		containedTrains.clear();
	}

	/**
	 * Loads the buffered groups from file
	 * @param filename - The groupdata file to read from
	 */
	public static void init(String filename) {
		synchronized (managers) {
			deinit();
			new DataReader(filename) {
				public void read(DataInputStream stream) throws IOException {
					int totalgroups = 0;
					int totalmembers = 0;
					int worldcount = stream.readInt();
					for (int i = 0; i < worldcount; i++) {
						UUID worldUID = StreamUtil.readUUID(stream);
						int groupcount = stream.readInt();
						OfflineGroupManager man = get(worldUID);
						for (int j = 0; j < groupcount; j++) {
							OfflineGroup wg = OfflineGroup.readFrom(stream);
							for (OfflineMember wm : wg.members) hiddenMinecarts.add(wm.entityUID);
							man.groupmap.add(wg);
						    containedTrains.add(wg.name);
							totalmembers += wg.members.length;
						}

						totalgroups += groupcount;
					}
					String msg = totalgroups + " Train";
					if (totalgroups == 1) msg += " has"; else msg += "s have";
					msg += " been loaded in " + worldcount + " world";
					if (worldcount != 1) msg += "s";
					msg += ". (" + totalmembers + " Minecart";
					if (totalmembers != 1) msg += "s";
					msg += ")";
					TrainCarts.plugin.log(Level.INFO, msg);
				}
			}.read();
		}
	}

	/**
	 * Saves the buffered groups to file
	 * @param filename - The groupdata file to write to
	 */
	public static void deinit(String filename) {
		synchronized (managers) {
			new DataWriter(filename) {
				public void write(DataOutputStream stream) throws IOException {
					//clear empty worlds
					Iterator<OfflineGroupManager> iter = managers.values().iterator();
					while (iter.hasNext()) {
						if (iter.next().groupmap.isEmpty()) {
							iter.remove();
						}
					}

					//Write it
					stream.writeInt(managers.size());
					for (Map.Entry<UUID, OfflineGroupManager> entry : managers.entrySet()) {
						StreamUtil.writeUUID(stream, entry.getKey());

						stream.writeInt(entry.getValue().groupmap.size());
						for (OfflineGroup wg : entry.getValue().groupmap) wg.writeTo(stream);
					}
				}
			}.write();
			deinit();
		}
	}

	/**
	 * Buffers the group and unlinks the members
	 * @param group - The group to buffer
	 */
	public static void hideGroup(MinecartGroup group) {
		if (group == null || !group.isValid()) return;
		World world = group.getWorld();
		if (world == null) return;
		synchronized (managers) {
			for (MinecartMember mm : group) {
				hiddenMinecarts.add(mm.uniqueId);
			}
			//==== add =====
			OfflineGroup wg = new OfflineGroup(group);
			wg.updateLoadedChunks(world);
			get(world).groupmap.add(wg);
			containedTrains.add(wg.name);
			//==============
			group.unload();
		}
	}
	public static void hideGroup(Object member) {
		MinecartMember mm = MinecartMember.get(member);
		if (mm != null && !mm.dead) hideGroup(mm.getGroup());
	}

	/**
	 * Check if this minecart is in a buffered group
	 * Used to check if a minecart can be linked
	 * @param m - The minecart to check
	 */
	public static boolean wasInGroup(Entity minecartentity) {
		return wasInGroup(minecartentity.getUniqueId());
	}
	public static boolean wasInGroup(UUID minecartUniqueID) {
		return hiddenMinecarts.contains(minecartUniqueID);
	}

	public static boolean contains(String trainname) {
		if (MinecartGroup.get(trainname) != null) {
			return true;
		}
		return containedTrains.contains(trainname);
	}
	public static void rename(String oldtrainname, String newtrainname) {
		MinecartGroup.rename(oldtrainname, newtrainname);
		synchronized (managers) {
			for (OfflineGroupManager man : managers.values()) {
				for (OfflineGroup group : man.groupmap) {
					if (group.name.equals(oldtrainname)) {
						group.name = newtrainname;
						containedTrains.remove(oldtrainname);
						containedTrains.add(newtrainname);
						return;
					}
				}
			}
		}
	}

}
