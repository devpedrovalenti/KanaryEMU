package com.eu.habbo.messages.incoming.rooms.items;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionPostIt;
import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.rooms.FurnitureMovementError;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomRightLevels;
import com.eu.habbo.habbohotel.rooms.RoomTile;
import com.eu.habbo.habbohotel.rooms.RoomTileState;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.users.HabboItem;
import com.eu.habbo.habbohotel.wired.WiredHandler;
import com.eu.habbo.habbohotel.wired.WiredTriggerType;
import com.eu.habbo.messages.incoming.MessageHandler;
import com.eu.habbo.messages.outgoing.inventory.RemoveHabboItemComposer;
import com.eu.habbo.messages.outgoing.rooms.items.RoomBuilderResultComposer;
import com.eu.habbo.plugin.Event;
import com.eu.habbo.plugin.events.furniture.FurnitureToggleEvent;
import gnu.trove.set.hash.THashSet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative operations used by the reduced Room Builder UI. */
public class RoomBuilderEvent extends MessageHandler {
    public static final int MAX_GROUP_ITEMS = 200;
    public static final int MAX_PILE_ITEMS = 100;
    public static final int MAX_RESULT_ITEMS = 300;

    private static final int ACTION_INIT = 0;
    private static final int ACTION_SELECT_SAME = 2;
    private static final int ACTION_SET_PLACEMENT_HEIGHT = 4;
    private static final int ACTION_FREEZE_SELF = 5;
    private static final int ACTION_TOGGLE_TELEPORT_MODE = 6;
    private static final int ACTION_PICKUP_SAME = 7;
    private static final int ACTION_SET_SAME_STATE = 8;
    private static final int ACTION_SET_SAME_ROTATION = 9;
    private static final int ACTION_MOVE_PILE = 10;
    private static final int ACTION_COPY_PILE = 11;
    private static final int ACTION_CLEAR = 12;
    private static final int ACTION_SAVE = 13;
    private static final long HEIGHT_SETTING_TTL_MS = 30L * 60L * 1000L;

    private static final ConcurrentHashMap<Integer, PlacementHeight> PLACEMENT_HEIGHTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, FrozenAvatar> FROZEN_AVATARS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, BuilderTeleportMode> BUILDER_TELEPORT_MODES = new ConcurrentHashMap<>();

    private static final class PlacementHeight {
        private final int roomId;
        private final double height;
        private final boolean keep;
        private final long updatedAt;

        private PlacementHeight(int roomId, double height, boolean keep) {
            this.roomId = roomId;
            this.height = height;
            this.keep = keep;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private static final class FrozenAvatar {
        private final int roomId;
        private final boolean couldWalk;

        private FrozenAvatar(int roomId, boolean couldWalk) {
            this.roomId = roomId;
            this.couldWalk = couldWalk;
        }
    }

    private static final class BuilderTeleportMode {
        private final int roomId;

        private BuilderTeleportMode(int roomId) {
            this.roomId = roomId;
        }
    }

    public static Double getPlacementHeight(int userId, int roomId) {
        PlacementHeight setting = PLACEMENT_HEIGHTS.get(userId);
        if (setting == null) return null;
        if (setting.roomId != roomId || System.currentTimeMillis() - setting.updatedAt > HEIGHT_SETTING_TTL_MS) {
            PLACEMENT_HEIGHTS.remove(userId, setting);
            return null;
        }
        return setting.height;
    }

    public static void finishPlacementHeight(int userId, int roomId) {
        PlacementHeight setting = PLACEMENT_HEIGHTS.get(userId);
        if (setting != null && setting.roomId == roomId && !setting.keep) {
            PLACEMENT_HEIGHTS.remove(userId, setting);
        }
    }

    public static boolean isBuilderFrozen(int userId, int roomId) {
        FrozenAvatar frozen = FROZEN_AVATARS.get(userId);
        if (frozen == null) return false;
        if (frozen.roomId != roomId) {
            FROZEN_AVATARS.remove(userId, frozen);
            return false;
        }
        return true;
    }

    public static boolean isBuilderTeleportMode(int userId, int roomId) {
        BuilderTeleportMode mode = BUILDER_TELEPORT_MODES.get(userId);
        if (mode == null) return false;
        if (mode.roomId != roomId) {
            BUILDER_TELEPORT_MODES.remove(userId, mode);
            return false;
        }
        return true;
    }

    public static boolean hasBuilderRights(Room room, Habbo habbo) {
        return room != null && room.isLoaded() && habbo != null && (room.isOwner(habbo)
                || room.hasRights(habbo)
                || room.getGuildRightLevel(habbo).isEqualOrGreaterThan(RoomRightLevels.GUILD_RIGHTS)
                || habbo.hasPermission(Permission.ACC_MOVEROTATE));
    }

    public static void disableBuilderTeleportMode(int userId, int roomId, RoomUnit unit) {
        BuilderTeleportMode mode = BUILDER_TELEPORT_MODES.get(userId);
        if (mode != null && mode.roomId == roomId) BUILDER_TELEPORT_MODES.remove(userId, mode);
    }

    public static void clearUserState(int userId, int roomId, RoomUnit unit) {
        PlacementHeight height = PLACEMENT_HEIGHTS.get(userId);
        if (height != null && height.roomId == roomId) PLACEMENT_HEIGHTS.remove(userId, height);
        FrozenAvatar frozen = FROZEN_AVATARS.get(userId);
        if (frozen != null && frozen.roomId == roomId) {
            FROZEN_AVATARS.remove(userId, frozen);
            if (unit != null) unit.setCanWalk(frozen.couldWalk);
        }
        disableBuilderTeleportMode(userId, roomId, unit);
    }

    @Override
    public int getRatelimit() {
        return 50;
    }

    @Override
    public void handle() throws Exception {
        int action = this.packet.readInt();
        Habbo actor = this.client.getHabbo();
        Room room = actor == null ? null : actor.getHabboInfo().getCurrentRoom();
        if (room == null || !room.isLoaded()) {
            fail("The room is not active.", new ArrayList<>());
            return;
        }
        if (!hasBuilderRights(room, actor)) {
            fail("You do not have building rights in this room.", new ArrayList<>());
            return;
        }

        switch (action) {
            case ACTION_INIT:
                succeed("", new ArrayList<>());
                return;
            case ACTION_SELECT_SAME:
                selectSame(room, this.packet.readInt());
                return;
            case ACTION_SET_PLACEMENT_HEIGHT:
                setPlacementHeight(room);
                return;
            case ACTION_FREEZE_SELF:
                freezeSelf(room, this.packet.readBoolean());
                return;
            case ACTION_TOGGLE_TELEPORT_MODE:
                toggleTeleportMode(room, this.packet.readBoolean());
                return;
            case ACTION_PICKUP_SAME:
                pickupSame(room, this.packet.readInt());
                return;
            case ACTION_SET_SAME_STATE:
                setSameState(room, this.packet.readInt(), this.packet.readInt());
                return;
            case ACTION_SET_SAME_ROTATION:
                rotateSame(room, this.packet.readInt(), this.packet.readInt());
                return;
            case ACTION_MOVE_PILE:
                processPile(room, this.packet.readInt(), this.packet.readInt(),
                        this.packet.readInt(), this.packet.readInt(), false);
                return;
            case ACTION_COPY_PILE:
                processPile(room, this.packet.readInt(), this.packet.readInt(),
                        this.packet.readInt(), this.packet.readInt(), true);
                return;
            case ACTION_CLEAR:
                clearModes(room);
                return;
            case ACTION_SAVE:
                saveConstruction(room);
                return;
            default:
                fail("Unknown room-builder action.", new ArrayList<>());
        }
    }

    private void selectSame(Room room, int sourceId) {
        HabboItem source = room.getHabboItem(sourceId);
        if (source == null || !isFloorItem(room, source)) {
            fail("The selected furniture no longer belongs to this room.", new ArrayList<>());
            return;
        }
        List<HabboItem> matching = sameFurniture(room, source);
        if (matching.size() > MAX_GROUP_ITEMS) {
            fail("There are more matching items than the limit of " + MAX_GROUP_ITEMS + ".", new ArrayList<>());
            return;
        }
        succeed("", ids(matching));
    }

    private void setPlacementHeight(Room room) {
        int heightHundredths = this.packet.readInt();
        boolean keep = this.packet.readBoolean();
        int userId = this.client.getHabbo().getHabboInfo().getId();
        if (heightHundredths == -1) {
            PLACEMENT_HEIGHTS.remove(userId);
            succeed("Automatic height enabled.", new ArrayList<>());
            return;
        }
        if (heightHundredths < 0 || heightHundredths > Room.MAXIMUM_FURNI_HEIGHT * 100) {
            fail("Requested height is outside the allowed range.", new ArrayList<>());
            return;
        }
        PLACEMENT_HEIGHTS.put(userId, new PlacementHeight(room.getId(), heightHundredths / 100.0D, keep));
        succeed("Placement height saved.", new ArrayList<>());
    }

    private void freezeSelf(Room room, boolean frozen) {
        Habbo actor = this.client.getHabbo();
        RoomUnit unit = actor.getRoomUnit();
        int userId = actor.getHabboInfo().getId();
        if (unit == null || !unit.isInRoom()) {
            fail("Your avatar is no longer in this room.", new ArrayList<>());
            return;
        }
        if (frozen) {
            FrozenAvatar previous = FROZEN_AVATARS.get(userId);
            if (previous == null || previous.roomId != room.getId()) {
                FROZEN_AVATARS.put(userId, new FrozenAvatar(room.getId(), unit.canWalk()));
            }
            unit.setCanWalk(false);
            unit.getPath().clear();
        } else {
            FrozenAvatar previous = FROZEN_AVATARS.get(userId);
            if (previous != null && previous.roomId == room.getId()) {
                FROZEN_AVATARS.remove(userId, previous);
                unit.setCanWalk(previous.couldWalk);
            }
        }
        succeed("", new ArrayList<>());
    }

    private void toggleTeleportMode(Room room, boolean enabled) {
        Habbo actor = this.client.getHabbo();
        RoomUnit unit = actor.getRoomUnit();
        int userId = actor.getHabboInfo().getId();
        if (unit == null || !unit.isInRoom()) {
            fail("Your avatar is no longer in this room.", new ArrayList<>());
            return;
        }
        if (enabled) {
            if (actor.getHabboInfo().getRiding() != null) {
                fail("Teleport mode is unavailable while riding a pet.", new ArrayList<>());
                return;
            }
            BUILDER_TELEPORT_MODES.putIfAbsent(userId, new BuilderTeleportMode(room.getId()));
            succeed("Click a room tile to teleport.", new ArrayList<>());
        } else {
            disableBuilderTeleportMode(userId, room.getId(), unit);
            succeed("Teleport mode disabled.", new ArrayList<>());
        }
    }

    private void pickupSame(Room room, int sourceId) {
        HabboItem source = room.getHabboItem(sourceId);
        if (source == null || !isFloorItem(room, source)) {
            fail("The selected furniture no longer belongs to this room.", new ArrayList<>());
            return;
        }
        List<HabboItem> matching = sameFurniture(room, source);
        if (matching.isEmpty() || matching.size() > MAX_GROUP_ITEMS) {
            fail("Matching furniture count is outside the allowed range.", new ArrayList<>());
            return;
        }
        for (HabboItem item : matching) {
            if (item instanceof InteractionPostIt || !canPickUp(room, item, this.client.getHabbo())) {
                fail("You do not have pickup rights for every matching item.", ids(matching));
                return;
            }
        }
        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (HabboItem item : matching) {
            try {
                if (item.getUserId() == this.client.getHabbo().getHabboInfo().getId()) {
                    room.pickUpItem(item, this.client.getHabbo());
                } else {
                    if (room.hasOwnerPermission(this.client.getHabbo())) {
                        item.setUserId(this.client.getHabbo().getHabboInfo().getId());
                    }
                    room.ejectUserItem(item);
                }
                if (room.getHabboItem(item.getId()) == null) succeeded.add(item.getId());
                else failed.add(item.getId());
            } catch (Exception e) {
                failed.add(item.getId());
            }
        }
        result(succeeded, failed, "Collected " + succeeded.size() + " of " + matching.size() + " matching items.");
    }

    private void setSameState(Room room, int sourceId, int targetState) {
        HabboItem source = room.getHabboItem(sourceId);
        if (source == null || !isFloorItem(room, source)) {
            fail("The selected furniture no longer belongs to this room.", new ArrayList<>());
            return;
        }
        int stateCount = source.getBaseItem().getStateCount();
        List<HabboItem> matching = sameFurniture(room, source);
        if (stateCount <= 0 || targetState < 0 || targetState >= stateCount || matching.size() > MAX_GROUP_ITEMS) {
            fail("The requested state is not available for this furniture.", ids(matching));
            return;
        }
        for (HabboItem item : matching) {
            Event event = new FurnitureToggleEvent(item, this.client.getHabbo(), targetState);
            Emulator.getPluginManager().fireEvent(event);
            if (event.isCancelled()) {
                fail("The state change was cancelled; no furniture was changed.", ids(matching));
                return;
            }
        }

        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        THashSet<RoomTile> updatedTiles = new THashSet<>();
        for (HabboItem item : matching) {
            try {
                String nextState = Integer.toString(targetState);
                if (!nextState.equals(item.getExtradata())) {
                    item.setExtradata(nextState);
                    item.needsUpdate(true);
                    room.updateItemState(item);
                    RoomTile tile = room.getLayout().getTile(item.getX(), item.getY());
                    if (tile != null) updatedTiles.addAll(room.getLayout().getTilesAt(tile,
                            item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation()));
                    Emulator.getThreading().run(item);
                    WiredHandler.handle(WiredTriggerType.STATE_CHANGED, this.client.getHabbo().getRoomUnit(), room, new Object[]{item});
                }
                succeeded.add(item.getId());
            } catch (Exception e) {
                failed.add(item.getId());
            }
        }
        if (!updatedTiles.isEmpty()) room.updateTiles(updatedTiles);
        result(succeeded, failed, "Applied state " + targetState + " to " + succeeded.size() + " of " + matching.size() + " matching items.");
    }

    private void rotateSame(Room room, int sourceId, int direction) {
        HabboItem source = room.getHabboItem(sourceId);
        if (source == null || !isFloorItem(room, source)) {
            fail("The selected furniture no longer belongs to this room.", new ArrayList<>());
            return;
        }
        List<HabboItem> matching = sameFurniture(room, source);
        if (matching.isEmpty() || matching.size() > MAX_GROUP_ITEMS) {
            fail("Matching furniture count is outside the allowed range.", new ArrayList<>());
            return;
        }
        for (HabboItem item : matching) {
            if (!isAllowedDirection(item, direction)) {
                fail("Direction " + direction + " is not available for this furniture.", ids(matching));
                return;
            }
            RoomTile tile = room.getLayout().getTile(item.getX(), item.getY());
            FurnitureMovementError error = room.canPlaceFurnitureAt(item, this.client.getHabbo(), tile, direction);
            if (error != FurnitureMovementError.NONE) {
                fail("The group cannot be rotated: " + error.errorCode, ids(matching));
                return;
            }
        }
        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (HabboItem item : matching) {
            try {
                RoomTile tile = room.getLayout().getTile(item.getX(), item.getY());
                FurnitureMovementError error = room.moveFurniTo(item, tile, direction, this.client.getHabbo());
                if (error == FurnitureMovementError.NONE) succeeded.add(item.getId());
                else failed.add(item.getId());
            } catch (Exception e) {
                failed.add(item.getId());
            }
        }
        result(succeeded, failed, "Rotated " + succeeded.size() + " of " + matching.size() + " matching items to direction " + direction + ".");
    }

    private void processPile(Room room, int sourceX, int sourceY, int destinationX, int destinationY, boolean copy) {
        RoomTile sourceTile = room.getLayout().getTile((short) sourceX, (short) sourceY);
        RoomTile destinationTile = room.getLayout().getTile((short) destinationX, (short) destinationY);
        if (sourceTile == null || destinationTile == null || sourceTile.state == RoomTileState.INVALID
                || destinationTile.state == RoomTileState.INVALID || (sourceX == destinationX && sourceY == destinationY)) {
            fail("Source and destination must be different valid room tiles.", new ArrayList<>());
            return;
        }
        List<HabboItem> sourcePile = pileAt(room, sourceX, sourceY);
        if (sourcePile.isEmpty() || sourcePile.size() > MAX_PILE_ITEMS) {
            fail("The source pile is empty or exceeds the limit of " + MAX_PILE_ITEMS + " items.", new ArrayList<>());
            return;
        }
        if (copy && room.itemCount() + sourcePile.size() > Room.MAXIMUM_FURNI) {
            fail("Copying this pile would exceed the room furniture limit.", ids(sourcePile));
            return;
        }
        if (!validatePileDestination(room, sourcePile, sourceX, sourceY, destinationX, destinationY)) {
            fail("The destination has a collision, invalid tile, insufficient stack space, or avatar.", ids(sourcePile));
            return;
        }

        if (copy) copyPile(room, sourcePile, sourceX, sourceY, destinationX, destinationY);
        else movePile(room, sourcePile, sourceX, sourceY, destinationX, destinationY);
    }

    private boolean validatePileDestination(Room room, List<HabboItem> pile, int sourceX, int sourceY,
                                            int destinationX, int destinationY) {
        if (!room.getItemsAt(destinationX, destinationY).isEmpty()) return false;
        double resultingTop = room.getLayout().getHeightAtSquare(destinationX, destinationY);
        for (HabboItem item : pile) {
            RoomTile target = room.getLayout().getTile((short) destinationX, (short) destinationY);
            if (!room.getLayout().fitsOnMap(target, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation())) return false;
            if (room.canPlaceFurnitureAt(item, this.client.getHabbo(), target, item.getRotation()) != FurnitureMovementError.NONE) return false;
            THashSet<RoomTile> footprint = room.getLayout().getTilesAt(target,
                    item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation());
            for (RoomTile tile : footprint) {
                if (!room.getItemsAt(tile).isEmpty()) return false;
            }
            if (room.furnitureFitsAt(target, item, item.getRotation(), true) != FurnitureMovementError.NONE) return false;
            double height = Item.getCurrentHeight(item);
            resultingTop += height;
            if (resultingTop > Room.MAXIMUM_FURNI_HEIGHT) return false;
        }
        // Do not allow the destination footprint to overlap the source pile during a move.
        if (sourceX == destinationX && sourceY == destinationY) return false;
        return true;
    }

    private void movePile(Room room, List<HabboItem> pile, int sourceX, int sourceY, int destinationX, int destinationY) {
        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        RoomTile destination = room.getLayout().getTile((short) destinationX, (short) destinationY);
        for (HabboItem item : pile) {
            try {
                FurnitureMovementError error = room.moveFurniTo(item, destination, item.getRotation(), this.client.getHabbo());
                if (error == FurnitureMovementError.NONE) succeeded.add(item.getId());
                else failed.add(item.getId());
            } catch (Exception e) {
                failed.add(item.getId());
            }
        }
        result(succeeded, failed, "Moved " + succeeded.size() + " of " + pile.size() + " pile items.");
    }

    private void copyPile(Room room, List<HabboItem> sourcePile, int sourceX, int sourceY, int destinationX, int destinationY) {
        Map<Integer, List<HabboItem>> inventoryByBaseId = new HashMap<>();
        for (HabboItem inventoryItem : this.client.getHabbo().getInventory().getItemsComponent().getItemsAsValueCollection()) {
            if (inventoryItem.getRoomId() == 0) {
                inventoryByBaseId.computeIfAbsent(inventoryItem.getBaseItem().getId(), key -> new ArrayList<>()).add(inventoryItem);
            }
        }
        for (List<HabboItem> items : inventoryByBaseId.values()) items.sort(Comparator.comparingInt(HabboItem::getId));
        List<HabboItem> copies = new ArrayList<>(sourcePile.size());
        for (HabboItem source : sourcePile) {
            List<HabboItem> candidates = inventoryByBaseId.get(source.getBaseItem().getId());
            if (candidates == null || candidates.isEmpty()) {
                fail("You need one matching furniture item in your inventory for each copied pile item.", ids(sourcePile));
                return;
            }
            copies.add(candidates.remove(0));
        }

        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        RoomTile destination = room.getLayout().getTile((short) destinationX, (short) destinationY);
        for (int i = 0; i < sourcePile.size(); i++) {
            HabboItem source = sourcePile.get(i);
            HabboItem copy = copies.get(i);
            int rotation = source.getRotation();
            FurnitureMovementError error;
            try {
                if (!isAllowedDirection(copy, rotation)
                        || room.canPlaceFurnitureAt(copy, this.client.getHabbo(), destination, rotation) != FurnitureMovementError.NONE) {
                    failed.add(copy.getId());
                    continue;
                }
                double sourceFloor = room.getLayout().getHeightAtSquare(sourceX, sourceY);
                double destinationFloor = room.getLayout().getHeightAtSquare(destinationX, destinationY);
                double height = destinationFloor + Math.max(0, source.getZ() - sourceFloor);
                error = room.placeFloorFurniAt(copy, destination, rotation, this.client.getHabbo(), height);
                if (error != FurnitureMovementError.NONE) {
                    failed.add(copy.getId());
                    continue;
                }
                this.client.sendResponse(new RemoveHabboItemComposer(copy.getGiftAdjustedId()));
                this.client.getHabbo().getInventory().getItemsComponent().removeHabboItem(copy.getId());
                copy.setFromGift(false);
                succeeded.add(copy.getId());
            } catch (Exception e) {
                failed.add(copy.getId());
            }
        }
        result(succeeded, failed, "Copied " + succeeded.size() + " of " + sourcePile.size() + " pile items.");
    }

    private void clearModes(Room room) {
        int userId = this.client.getHabbo().getHabboInfo().getId();
        PLACEMENT_HEIGHTS.remove(userId);
        FrozenAvatar frozen = FROZEN_AVATARS.get(userId);
        if (frozen != null && frozen.roomId == room.getId()) {
            FROZEN_AVATARS.remove(userId, frozen);
            if (this.client.getHabbo().getRoomUnit() != null) this.client.getHabbo().getRoomUnit().setCanWalk(frozen.couldWalk);
        }
        disableBuilderTeleportMode(userId, room.getId(), this.client.getHabbo().getRoomUnit());
        succeed("Temporary Room Builder modes and values cleared.", new ArrayList<>());
    }

    private void saveConstruction(Room room) {
        List<HabboItem> items = new ArrayList<>();
        items.addAll(room.getFloorItems());
        items.addAll(room.getWallItems());
        for (HabboItem item : items) {
            if (room.getHabboItem(item.getId()) != item || item.getRoomId() != room.getId()) {
                fail("A furniture item changed rooms while the construction was being saved.", ids(items));
                return;
            }
        }

        String updateSql = "UPDATE items SET user_id = ?, room_id = ?, wall_pos = ?, x = ?, y = ?, z = ?, rot = ?, extra_data = ?, limited_data = ? WHERE id = ?";
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                for (HabboItem item : items) {
                    statement.setInt(1, item.getUserId());
                    statement.setInt(2, item.getRoomId());
                    statement.setString(3, item.getWallPosition());
                    statement.setInt(4, item.getX());
                    statement.setInt(5, item.getY());
                    statement.setDouble(6, Math.max(-9999, Math.min(9999,
                            Math.round(item.getZ() * 1000000D) / 1000000D)));
                    statement.setInt(7, item.getRotation());
                    statement.setString(8, item instanceof com.eu.habbo.habbohotel.items.interactions.InteractionGuildGate
                            ? "" : item.getDatabaseExtraData());
                    statement.setString(9, item.getLimitedStack() + ":" + item.getLimitedSells());
                    statement.setInt(10, item.getId());
                    statement.addBatch();
                }

                int[] updated = statement.executeBatch();
                if (updated.length != items.size()) throw new java.sql.SQLException("Incomplete room furniture batch.");
                for (int count : updated) {
                    if (count == Statement.EXECUTE_FAILED || count == 0) {
                        throw new java.sql.SQLException("One or more room furniture records were not updated.");
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
            for (HabboItem item : items) item.needsUpdate(false);
            succeed("Saved " + items.size() + " room furniture records.", new ArrayList<>());
        } catch (Exception e) {
            List<Integer> failed = ids(items);
            if (failed.size() > MAX_RESULT_ITEMS) failed = new ArrayList<>(failed.subList(0, MAX_RESULT_ITEMS));
            fail("Could not persist the construction; the database transaction was rolled back.", failed);
        }
    }

    private List<HabboItem> sameFurniture(Room room, HabboItem source) {
        List<HabboItem> matching = new ArrayList<>();
        for (HabboItem item : room.getFloorItems()) {
            if (item.getBaseItem().getId() == source.getBaseItem().getId()) matching.add(item);
        }
        matching.sort(Comparator.comparingInt(HabboItem::getId));
        return matching;
    }

    private List<HabboItem> pileAt(Room room, int x, int y) {
        List<HabboItem> pile = new ArrayList<>();
        for (HabboItem item : room.getFloorItems()) {
            if (item.getX() == x && item.getY() == y) pile.add(item);
        }
        pile.sort(Comparator.comparingDouble(HabboItem::getZ).thenComparingInt(HabboItem::getId));
        return pile;
    }

    private boolean isAllowedDirection(HabboItem item, int direction) {
        int rotations = item.getMaximumRotations();
        if (rotations <= 0 || direction < 0 || direction >= 8) return false;
        if (rotations >= 8) return true;
        if (8 % rotations != 0) return false;
        int directionStep = 8 / rotations;
        return direction % directionStep == 0;
    }

    private boolean isFloorItem(Room room, HabboItem item) {
        return room.getHabboItem(item.getId()) == item && room.getFloorItems().contains(item);
    }

    private boolean canPickUp(Room room, HabboItem item, Habbo actor) {
        if (item.getUserId() == actor.getHabboInfo().getId()) return true;
        if (!room.hasRights(actor)) return false;
        return item.getUserId() != room.getOwnerId() || room.isOwner(actor);
    }

    private List<Integer> ids(List<HabboItem> items) {
        List<Integer> ids = new ArrayList<>(items.size());
        for (HabboItem item : items) ids.add(item.getId());
        return ids;
    }

    private void result(List<Integer> succeededIds, List<Integer> failedIds, String message) {
        boolean success = failedIds.isEmpty();
        this.client.sendResponse(new RoomBuilderResultComposer(success, message, succeededIds, failedIds));
    }

    private void succeed(String message, List<Integer> ids) {
        this.client.sendResponse(new RoomBuilderResultComposer(true, message, ids, new ArrayList<>()));
    }

    private void fail(String message, List<Integer> failedIds) {
        this.client.sendResponse(new RoomBuilderResultComposer(false, message, new ArrayList<>(), failedIds));
    }
}
