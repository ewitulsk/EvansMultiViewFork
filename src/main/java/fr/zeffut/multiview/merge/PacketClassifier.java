package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;

import java.util.function.IntFunction;

/**
 * Classifie un Action en Category. Pour GamePacket, délègue à une
 * IntFunction<Category> (la table de dispatch construite à runtime).
 *
 * @implNote Non thread-safe. Appelé depuis le pipeline single-threaded.
 */
public final class PacketClassifier {

    private final IntFunction<Category> gamePacketDispatch;

    public PacketClassifier(IntFunction<Category> gamePacketDispatch) {
        this.gamePacketDispatch = gamePacketDispatch;
    }

    public Category classify(Action action) {
        return switch (action) {
            case Action.NextTick n -> Category.TICK;
            case Action.ConfigurationPacket c -> Category.CONFIG;
            case Action.CreatePlayer c -> Category.LOCAL_PLAYER;
            case Action.MoveEntities m -> Category.ENTITY;
            case Action.CacheChunkRef ref -> Category.CACHE_REF;
            case Action.VoiceChat v -> Category.EGO;
            case Action.EncodedVoiceChat v -> Category.EGO;
            case Action.Unknown u -> {
                // AccuratePlayerPosition is Flashback-proprietary and encodes the local
                // player's position every tick. It must come from the primary source only;
                // secondary sources' positions would fight for the single local-player slot.
                if ("flashback:action/accurate_player_position_optional".equals(u.id())
                        || "flashback:action/accurate_player_position".equals(u.id())) {
                    yield Category.LOCAL_PLAYER;
                }
                // RealTimeClock records the wall-clock time for the editor's RTC overlay.
                // Emitting it per-source would write N slightly-different clocks into the
                // merged stream — keep the primary's only.
                if ("flashback:action/real_time_clock_optional".equals(u.id())) {
                    yield Category.LOCAL_PLAYER;
                }
                yield Category.PASSTHROUGH;
            }
            case Action.GamePacket gp -> gamePacketDispatch.apply(readPacketId(gp.bytes()));
        };
    }

    /** Reads the VarInt head of a payload (packet id). Package-private for MergeOrchestrator reuse. */
    static int readPacketId(byte[] payload) {
        int value = 0, shift = 0;
        for (int i = 0; i < 5 && i < payload.length; i++) {
            byte b = payload[i];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt trop long ou payload vide");
    }
}
