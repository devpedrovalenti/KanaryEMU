package com.eu.habbo.messages.outgoing.rooms.items;

import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

import java.util.List;

public class RoomBuilderResultComposer extends MessageComposer {
    private final boolean success;
    private final String error;
    private final List<Integer> succeededIds;
    private final List<Integer> failedIds;

    public RoomBuilderResultComposer(boolean success, String error, List<Integer> itemIds) {
        this(success, error, success ? itemIds : new java.util.ArrayList<>(),
                success ? new java.util.ArrayList<>() : itemIds);
    }

    public RoomBuilderResultComposer(boolean success, String error, List<Integer> succeededIds, List<Integer> failedIds) {
        this.success = success;
        this.error = error;
        this.succeededIds = succeededIds;
        this.failedIds = failedIds;
    }

    @Override
    protected ServerMessage composeInternal() {
        this.response.init(Outgoing.RoomBuilderResultComposer);
        this.response.appendBoolean(this.success);
        this.response.appendString(this.error);
        this.response.appendShort(this.succeededIds.size());
        for (int id : this.succeededIds) this.response.appendInt(id);
        this.response.appendShort(this.failedIds.size());
        for (int id : this.failedIds) this.response.appendInt(id);
        return this.response;
    }
}
