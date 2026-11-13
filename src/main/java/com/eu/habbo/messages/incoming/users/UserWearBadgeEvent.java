package com.eu.habbo.messages.incoming.users;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.users.HabboBadge;
import com.eu.habbo.habbohotel.users.inventory.BadgesComponent;
import com.eu.habbo.messages.incoming.MessageHandler;
import com.eu.habbo.messages.outgoing.users.UserBadgesComposer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class UserWearBadgeEvent extends MessageHandler {
    private static final int MAX_BADGE_SLOTS = 7;

    @Override
    public int getRatelimit() {
        return 250;
    }

    @Override
    public void handle() throws Exception {
        ArrayList<HabboBadge> updatedBadges = new ArrayList<>();
        ArrayList<Integer> usedSlots = new ArrayList<>();
        Map<HabboBadge, Integer> badgeSlots = new HashMap<>();
        for (int i = 0; i < MAX_BADGE_SLOTS; i++) {
            int slot = this.packet.readInt();
            if (slot < 1 || slot > MAX_BADGE_SLOTS)
                return;

            String badgeId = this.packet.readString();

            if (badgeId.isEmpty())
                continue;

            HabboBadge badge = this.client.getHabbo().getInventory().getBadgesComponent().getBadge(badgeId);
            if (badge != null && !updatedBadges.contains(badge) && !usedSlots.contains(slot)) {
                usedSlots.add(slot);
                updatedBadges.add(badge);
                badgeSlots.put(badge, slot);
            }
        }

        BadgesComponent.resetSlots(this.client.getHabbo());
        for (HabboBadge badge : updatedBadges) {
            badge.setSlot(badgeSlots.get(badge));
            badge.needsUpdate(true);
            Emulator.getThreading().run(badge);
        }

        if (this.client.getHabbo().getHabboInfo().getCurrentRoom() != null) {
            this.client.getHabbo().getHabboInfo().getCurrentRoom().sendComposer(new UserBadgesComposer(updatedBadges, this.client.getHabbo().getHabboInfo().getId()).compose());
        } else {
            this.client.sendResponse(new UserBadgesComposer(updatedBadges, this.client.getHabbo().getHabboInfo().getId()));
        }
    }
}
