# Room Builder server protocol (reduced Nitro UI)

The tool uses the existing custom packet IDs; no message ID was added or reused.

| Direction | ID | Name | Payload |
|---|---:|---|---|
| Client → server | 4090 | `RoomBuilderEvent` | `int action`, then action fields below |
| Server → client | 4090 | `RoomBuilderResultComposer` | `bool allSucceeded`, `string message`, `short succeededCount`, `int[succeededCount] succeededIds`, `short failedCount`, `int[failedCount] failedIds` |
| Client → server | 3320 | `RoomUserWalkEvent` | `int x, int y`; while Builder teleport mode is enabled, the server intercepts this click and teleports the sender instead of walking |

All requests require a loaded current room and owner/room rights, guild construction rights, or `acc_moverotate`. Furniture operations additionally validate each object with the room's existing placement checks. IDs are room-scoped and re-resolved for every request. The maximum same-item group is 200; the maximum pile is 100; response ID arrays are capped at 300.

## Actions

All fields are big-endian in the normal emulator packet encoding. `bool` is one byte (`0` or `1`). Coordinates are room tile coordinates. Furniture rotations are the emulator's rotation values, not degrees.

| Action | Fields after `action` | Behavior |
|---:|---|---|
| 0 | — | Initialize/authorize the tool. |
| 2 | `int sourceItemId` | Return all floor furniture IDs with the same base item as the source. The client uses the response to select those items. |
| 4 | `int heightHundredths`, `bool keepHeight` | Set requested absolute placement Z (`100` = 1.00). Send `-1` for automatic height. One-shot height is consumed only after successful placement unless `keepHeight=true`. |
| 5 | `bool frozen` | Freeze/unfreeze only the sender's avatar. No Habbo ID is accepted. Walk requests are rejected while frozen; the prior walk state is restored on unfreeze, clear, or room exit. |
| 6 | `bool enabled` | Arm/disarm click-to-teleport for only the sender. When armed, the next ID 3320 room-click packet teleports the sender to the requested valid tile and is not processed as a walk. Mode remains armed until disabled, cleared, or room exit. |
| 7 | `int sourceItemId` | Collect all matching floor furniture, subject to per-item ownership and pickup rights. |
| 8 | `int sourceItemId, int targetState` | Set every matching item to this exact state index. State must be in `[0, stateCount)`. Plugin cancellation is preflighted for the whole group before any state changes. |
| 9 | `int sourceItemId, int direction` | Set every matching item to this exact permitted rotation value. Direction is validated against the furniture definition's available rotations. |
| 10 | `int sourceX, int sourceY, int destinationX, int destinationY` | Move the floor pile whose items originate at the source tile to the destination tile. Destination must be valid and pass footprint, collision, avatar, and height checks. |
| 11 | `int sourceX, int sourceY, int destinationX, int destinationY` | Copy the source pile using matching furniture from the caller's inventory. One inventory item per source pile item is required. No furniture is duplicated by the server. |
| 12 | — | Clear temporary Builder height, freeze, and teleport modes. It does not remove or move room furniture. |
| 13 | — | Persist the room's floor and wall furniture records through the emulator's existing item database update path. |

Example: select same item `42`: `[2,42]`; set all matching items to state 3: `[8,42,3]`; set all matching items to direction 4: `[9,42,4]`; move a pile from `(2,3)` to `(6,3)`: `[10,2,3,6,3]`; copy it there: `[11,2,3,6,3]`.

The reply's `succeededIds` lists processed furniture IDs (or selected IDs for action 2). `failedIds` lists furniture records that were rejected or could not be completed. For a whole-request validation error, both lists are empty unless the message identifies the affected source IDs. `allSucceeded=false` with both lists populated is an explicit partial result; the UI must show the message and failed IDs.

## Height, viewing, and existing native packets

The placement override is applied by the existing `RoomPlaceItemEvent` (received ID 1258). Its original payload remains unchanged; it uses the stored Builder height preference only while the sender still has construction rights, or normal automatic stack height otherwise. The client must not send an absolute height in the placement packet. Height is absolute Z, cannot be below the tile's floor height, and must keep the furniture's top within `Room.MAXIMUM_FURNI_HEIGHT`.

The room's normal floor item data already carries furniture Z and state/extradata for the “Ver altura” and “Ver estados” UI. No separate read request is needed. Automatic height clears the override and returns placement to the existing stack-height calculation.

Other existing packets remain unchanged for normal gameplay: rotate/move 248, wall move 168, floor state/click 99, stack-helper height 3839, floor-plan save 875. The Room Builder no longer intercepts or changes the normal rotate and toggle handlers. The native Floor Editor remains registered and is not part of the Builder-specific actions.

## Nitro React composer/parser/listener work

- Send ID 4090 with actions 0, 2, and 4–13 using the fields above; parse both success and failed ID arrays from the response composer also on ID 4090.
- For freeze, send only action 5 plus a boolean. For teleport, send action 6 plus a boolean; while armed, room clicks continue to send ID 3320 with the clicked coordinates. Do not send a target Habbo ID.
- Use action 2's returned IDs to highlight matching furniture. For group state and rotation, send one explicit action 8 or 9 with source ID and desired destination state/direction; do not send repeated native toggle/rotate packets for each member.
- For pile operations, send action 10 or 11 with source and destination coordinates. If copy fails for missing inventory items, display the server message/failed IDs.
- “Limpar” sends action 12. “Salvar” sends action 13 and waits for the result. Display partial/failure details from `failedIds`.
- “Ver altura” and “Ver estados” can use the floor item data already parsed by the room client.

Only modes and room item data are persisted. Height/freeze/teleport are session state. Group, pile, and state mutations are synchronized through the existing room update composers and item persistence path. Plugin/placement failures after batch preflight may produce an explicit partial result; the server never reports such a batch as fully successful.
