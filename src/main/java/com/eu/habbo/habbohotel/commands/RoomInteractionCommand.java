package com.eu.habbo.habbohotel.commands;

import com.eu.habbo.habbohotel.gameclients.GameClient;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomChatMessageBubbles;
import com.eu.habbo.habbohotel.users.Habbo;
import java.util.Locale;

public class RoomInteractionCommand extends Command {

  public RoomInteractionCommand() {
    super(null, new String[]{
        "beijar", "kiss", "abracar", "abraçar", "hug", "socar", "soco", "punch", "chutar",
        "kick"
    });
  }

  @Override
  public boolean handle(GameClient gameClient, String[] params) {
    Habbo actor = gameClient.getHabbo();
    Room room = actor.getHabboInfo().getCurrentRoom();

    if (room == null || !actor.getRoomUnit().isInRoom()) {
      return true;
    }

    if (params.length < 2) {
      actor.whisper("Use :<acao> <usuario>.", RoomChatMessageBubbles.ALERT);
      return true;
    }

    Habbo target = room.getHabbo(params[1]);
    if (target == null) {
      actor.whisper("Esse usuario nao esta nesta sala.", RoomChatMessageBubbles.ALERT);
      return true;
    }

    if (target == actor) {
      actor.whisper("Voce nao pode usar essa acao em si mesmo.", RoomChatMessageBubbles.ALERT);
      return true;
    }

    String action;
    switch (params[0].toLowerCase(Locale.ROOT)) {
      case "beijar":
      case "kiss":
        action = "beija";
        break;
      case "abracar":
      case "abraçar":
      case "hug":
        action = "abraça";
        break;
      case "socar":
      case "soco":
      case "punch":
        action = "dá um soco em";
        break;
      case "chutar":
      case "kick":
        action = "dá um chute em";
        break;
      default:
        return false;
    }

    actor.talk(actor.getHabboInfo().getUsername() + " " + action + " "
        + target.getHabboInfo().getUsername() + ".", RoomChatMessageBubbles.NORMAL);
    return true;
  }
}
