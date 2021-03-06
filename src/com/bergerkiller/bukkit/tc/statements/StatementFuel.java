package com.bergerkiller.bukkit.tc.statements;

import com.bergerkiller.bukkit.tc.MinecartMember;

public class StatementFuel extends Statement {

	@Override
	public boolean match(String text) {
		return text.equals("coal") || text.equals("fuel")  || text.equals("fueled");
	}
	
	@Override
	public boolean handle(MinecartMember member, String text) {
		return member.hasFuel();
	}

	@Override
	public boolean matchArray(String text) {
		return false;
	}
}
