package com.bergerkiller.bukkit.tc.signactions;

import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import com.bergerkiller.bukkit.sl.API.Variables;
import com.bergerkiller.bukkit.tc.MinecartGroup;
import com.bergerkiller.bukkit.tc.MinecartMember;
import com.bergerkiller.bukkit.tc.Permission;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;

public class SignActionAnnounce extends SignAction {

	public static void sendMessage(SignActionEvent info, MinecartGroup group) {
		String msg = getMessage(info);
		for (MinecartMember member : group) {
			if (!member.hasPlayerPassenger()) return;
			Player player = (Player) member.getPassenger();
			sendMessage(msg, player);
		}
	}
	public static void sendMessage(SignActionEvent info, MinecartMember member) {
		if (!member.hasPlayerPassenger()) return;
		Player player = (Player) member.getPassenger();
		sendMessage(getMessage(info), player);
	}
	public static String getMessage(SignActionEvent info) {
		return getMessage(info.getLine(2) + info.getLine(3));
	}
	public static String getMessage(String msg) {
		return Util.replaceColors(TrainCarts.messageShortcuts.replace(msg));
	}
	public static void sendMessage(String msg, Player player) {
		if (TrainCarts.SignLinkEnabled) {
			int startindex, endindex;
			while ((startindex = msg.indexOf('%')) != -1 && (endindex = msg.indexOf('%', startindex + 1)) != -1) {
				String varname = msg.substring(startindex + 1, endindex);
				String value = varname.isEmpty() ? "%" : Variables.get(varname).get(player.getName());
				msg = msg.substring(0, startindex) + value + msg.substring(endindex + 1);
			}
		}
		player.sendMessage(msg);
	}

	@Override
	public void execute(SignActionEvent info) {
		if (!info.isType("announce")) return;
		if (info.isTrainSign() && info.isAction(SignActionType.GROUP_ENTER, SignActionType.REDSTONE_ON)) {
			if (!info.hasRailedMember() || !info.isPowered()) return;
			sendMessage(info, info.getGroup());
		} else if (info.isCartSign() && info.isAction(SignActionType.MEMBER_ENTER, SignActionType.REDSTONE_ON)) {
			if (!info.hasRailedMember() || !info.isPowered()) return;
			sendMessage(info, info.getMember());
		} else if (info.isRCSign() && info.isAction(SignActionType.REDSTONE_ON)) {
			MinecartGroup group = info.getRCTrainGroup();
			if (group != null) {
				sendMessage(info, group);
			}
		}		
	}

	@Override
	public boolean canSupportRC() {
		return true;
	}

	@Override
	public boolean build(SignChangeEvent event, String type, SignActionMode mode) {
		if (mode != SignActionMode.NONE) {
			if (type.startsWith("announce")) {
				if (mode == SignActionMode.RCTRAIN) {
					return handleBuild(event, Permission.BUILD_ANNOUNCER, "announcer", "remotely send a message to all the players in the train");
				} else {
					return handleBuild(event, Permission.BUILD_ANNOUNCER, "announcer", "send a message to players in a train");
				}
			}
		}
		return false;
	}
}
