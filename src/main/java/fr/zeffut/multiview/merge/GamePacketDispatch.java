package fr.zeffut.multiview.merge;

import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Builds the {@code Map<Integer, Category>} dispatch table for clientbound
 * GamePackets by introspecting the MC 1.21.11 Yarn API at runtime.
 *
 * <h2>Strategy A — full introspection via GameProtocols.S2C</h2>
 * <p>
 * Uses {@link GameProtocols#S2C}{@code .buildUnbound().forEachPacketType(...)},
 * which yields each registered S2C packet type with its numeric protocol ID.
 * Packet types are matched against {@link GamePacketTypes} constants to assign
 * the correct {@link Category}.
 * </p>
 *
 * <h2>Fallback (Strategy C)</h2>
 * <p>
 * If introspection fails at runtime for any reason, a PASSTHROUGH fallback is
 * returned and a warning is emitted in the {@link MergeReport}.
 * </p>
 */
public final class GamePacketDispatch {

    private GamePacketDispatch() {}

    /**
     * Build the dispatch table.
     *
     * @param report merge report — receives a warning if the fallback is used
     * @return an {@link IntFunction} from packetId → {@link Category}
     */
    public static IntFunction<Category> buildOrFallback(MergeReport report) {
        try {
            return build();
        } catch (Throwable e) {
            report.warn("GamePacketDispatch: introspection failed (" + e
                    + "), all GamePackets → PASSTHROUGH. Merge will be degraded.");
            return pid -> {
                report.stats.passthroughPackets.merge("packetId=" + pid, 1, Integer::sum);
                return Category.PASSTHROUGH;
            };
        }
    }

    /**
     * Build the dispatch table without a fallback. Throws on failure.
     * Package-private for unit testing.
     */
    static IntFunction<Category> build() {
        // Build the category lookup: PacketType → Category
        Map<PacketType<?>, Category> byType = buildCategoryByType();

        // Iterate all S2C play packet types with their numeric IDs
        Map<Integer, Category> table = new HashMap<>();
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            Category cat = byType.getOrDefault(type, Category.PASSTHROUGH);
            table.put(id, cat);
        });

        return pid -> table.getOrDefault(pid, Category.PASSTHROUGH);
    }

    /**
     * Returns the numeric protocol ID for a given {@link PacketType}, or -1 if
     * not found. Used by {@link MinecraftPacketIdProvider}.
     */
    static int findId(PacketType<?> target) {
        int[] result = {-1};
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (type == target) result[0] = id;
        });
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Category mapping
    // -------------------------------------------------------------------------

    private static Map<PacketType<?>, Category> buildCategoryByType() {
        Map<PacketType<?>, Category> m = new HashMap<>();

        // --- WORLD packets ---
        // ChunkDataS2CPacket (LEVEL_CHUNK_WITH_LIGHT)
        m.put(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, Category.WORLD);
        // UnloadChunkS2CPacket (FORGET_LEVEL_CHUNK)
        m.put(GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK, Category.WORLD);
        // ClientboundBlockUpdatePacket (BLOCK_UPDATE)
        m.put(GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE, Category.WORLD);
        // ClientboundSectionBlocksUpdatePacket (SECTION_BLOCKS_UPDATE)
        m.put(GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE, Category.WORLD);
        // LightUpdateS2CPacket (LIGHT_UPDATE)
        m.put(GamePacketTypes.CLIENTBOUND_LIGHT_UPDATE, Category.WORLD);
        // BlockEntityUpdateS2CPacket (BLOCK_ENTITY_DATA)
        m.put(GamePacketTypes.CLIENTBOUND_BLOCK_ENTITY_DATA, Category.WORLD);
        // BlockEventS2CPacket (BLOCK_EVENT)
        m.put(GamePacketTypes.CLIENTBOUND_BLOCK_EVENT, Category.WORLD);
        // ChunkBiomeDataS2CPacket (CHUNKS_BIOMES)
        m.put(GamePacketTypes.CLIENTBOUND_CHUNKS_BIOMES, Category.WORLD);
        // ClientboundAddTransientBlockPacket (ADD_TRANSIENT_BLOCK) — new in 26.3.
        // Transient (ghost) block state at a position: world state like BLOCK_UPDATE,
        // routed through WorldPacketRewriter's passthrough (no LWW — transient blocks
        // are per-viewer and idempotent when re-applied).
        m.put(GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK, Category.WORLD);

        // --- ENTITY packets ---
        // ClientboundAddEntityPacket (ADD_ENTITY)
        m.put(GamePacketTypes.CLIENTBOUND_ADD_ENTITY, Category.ENTITY);
        // EntitiesDestroyS2CPacket (REMOVE_ENTITIES)
        m.put(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES, Category.ENTITY);
        // ClientboundMoveEntityPacket.MoveRelative (MOVE_ENTITY_POS)
        m.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS, Category.ENTITY);
        // ClientboundMoveEntityPacket.Rotate (MOVE_ENTITY_ROT)
        m.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT, Category.ENTITY);
        // ClientboundMoveEntityPacket.RotateAndMoveRelative (MOVE_ENTITY_POS_ROT)
        m.put(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT, Category.ENTITY);
        // ClientboundEntityPositionSyncPacket (TELEPORT_ENTITY)
        m.put(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY, Category.ENTITY);
        // EntityPositionSyncS2CPacket (ENTITY_POSITION_SYNC)
        m.put(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC, Category.ENTITY);
        // EntityTrackerUpdateS2CPacket (SET_ENTITY_DATA)
        m.put(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA, Category.ENTITY);
        // EntityStatusS2CPacket (ENTITY_EVENT)
        m.put(GamePacketTypes.CLIENTBOUND_ENTITY_EVENT, Category.ENTITY);
        // EntityVelocityUpdateS2CPacket (SET_ENTITY_MOTION)
        m.put(GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION, Category.ENTITY);
        // EntityAnimationS2CPacket (ANIMATE)
        m.put(GamePacketTypes.CLIENTBOUND_ANIMATE, Category.ENTITY);
        // EntitySetHeadYawS2CPacket (ROTATE_HEAD)
        m.put(GamePacketTypes.CLIENTBOUND_ROTATE_HEAD, Category.ENTITY);
        // EntityEquipmentUpdateS2CPacket (SET_EQUIPMENT)
        m.put(GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT, Category.ENTITY);
        // EntityAttachS2CPacket (SET_ENTITY_LINK)
        m.put(GamePacketTypes.CLIENTBOUND_SET_ENTITY_LINK, Category.ENTITY);
        // EntityPassengersSetS2CPacket (SET_PASSENGERS)
        m.put(GamePacketTypes.CLIENTBOUND_SET_PASSENGERS, Category.ENTITY);
        // EntityAttributesS2CPacket (UPDATE_ATTRIBUTES)
        m.put(GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES, Category.ENTITY);
        // EntityStatusEffectS2CPacket (UPDATE_MOB_EFFECT)
        m.put(GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT, Category.ENTITY);
        // RemoveEntityStatusEffectS2CPacket (REMOVE_MOB_EFFECT)
        m.put(GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT, Category.ENTITY);
        // EntityDamageS2CPacket (DAMAGE_EVENT)
        m.put(GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT, Category.ENTITY);
        // ItemPickupAnimationS2CPacket (TAKE_ITEM_ENTITY)
        m.put(GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY, Category.ENTITY);
        // ClientboundSwingAnimationPacket (SWING_ANIMATION) — new in 26.3.
        // First field is VarInt entityId → rewriteSingleEntityId remaps it.
        m.put(GamePacketTypes.CLIENTBOUND_SWING_ANIMATION, Category.ENTITY);

        // --- EGO packets ---
        // HealthUpdateS2CPacket (SET_HEALTH)
        m.put(GamePacketTypes.CLIENTBOUND_SET_HEALTH, Category.EGO);
        // ExperienceBarUpdateS2CPacket (SET_EXPERIENCE)
        m.put(GamePacketTypes.CLIENTBOUND_SET_EXPERIENCE, Category.EGO);
        // InventoryS2CPacket (CONTAINER_SET_CONTENT)
        m.put(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT, Category.EGO);
        // ScreenHandlerSlotUpdateS2CPacket (CONTAINER_SET_SLOT)
        m.put(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT, Category.EGO);
        // OpenScreenS2CPacket (OPEN_SCREEN)
        m.put(GamePacketTypes.CLIENTBOUND_OPEN_SCREEN, Category.EGO);
        // CloseScreenS2CPacket (CONTAINER_CLOSE_S2C)
        m.put(GamePacketTypes.CLIENTBOUND_CONTAINER_CLOSE, Category.EGO);
        // UpdateSelectedSlotS2CPacket (SET_CARRIED_ITEM_S2C)
        m.put(GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT, Category.EGO);
        // PlayerAbilitiesS2CPacket (PLAYER_ABILITIES_S2C)
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES, Category.EGO);
        // ScreenHandlerPropertyUpdateS2CPacket (CONTAINER_SET_DATA)
        m.put(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_DATA, Category.EGO);
        // SetCursorItemS2CPacket (SET_CURSOR_ITEM)
        m.put(GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM, Category.EGO);
        // SetPlayerInventoryS2CPacket (SET_PLAYER_INVENTORY)
        m.put(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, Category.EGO);
        // ClientboundPlayerPositionPacket (PLAYER_POSITION)
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_POSITION, Category.EGO);
        // DamageTiltS2CPacket (HURT_ANIMATION) and DeathMessageS2CPacket (PLAYER_COMBAT_KILL)
        // both start with `VarInt entityId` — routed to ENTITY so EntityPacketRewriter's
        // rewriteSingleEntityId remaps the id to the global namespace. Previously EGO routing
        // left local ids unremapped, so the hurt animation / death message targeted an entity
        // that no longer existed in the merge.
        m.put(GamePacketTypes.CLIENTBOUND_HURT_ANIMATION, Category.ENTITY);
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL, Category.ENTITY);
        // PlayerRespawnS2CPacket (RESPAWN) — dimension change, MUST follow primary only.
        // Otherwise secondary's dim change drags the replay client into their dimension,
        // causing primary's subsequent chunk packets to be applied to the wrong dimension
        // and silently dropped by Flashback → missing chunks.
        m.put(GamePacketTypes.CLIENTBOUND_RESPAWN, Category.EGO);
        // GameJoinS2CPacket (LOGIN) — initial world setup, keep primary only for same reason.
        m.put(GamePacketTypes.CLIENTBOUND_LOGIN, Category.EGO);
        // ChunkRenderDistanceCenterS2CPacket (SET_CHUNK_CACHE_CENTER)
        // — tells the client where to center chunk loading. Must follow the primary
        // POV only; secondary sources' values would fight over which chunks load.
        m.put(GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER, Category.EGO);
        // ChunkLoadDistanceS2CPacket (SET_CHUNK_CACHE_RADIUS)
        m.put(GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS, Category.EGO);
        // SimulationDistanceS2CPacket
        m.put(GamePacketTypes.CLIENTBOUND_SET_SIMULATION_DISTANCE, Category.EGO);
        // ClientboundPostEffectsPacket (POST_EFFECTS) — new in 26.3, registered on the
        // common protocol but bundled into the play-phase clientbound set. Post effects
        // are local-player screen state (like health/experience) — primary POV only.
        m.put(net.minecraft.network.protocol.common.CommonPacketTypes.CLIENTBOUND_POST_EFFECTS,
                Category.EGO);

        // --- GLOBAL packets ---
        // ClientboundPlayerInfoUpdatePacket (PLAYER_INFO_UPDATE, PLAYER_INFO_REMOVE)
        // Must be GLOBAL so GlobalDeduper deduplicates identical entries across sources
        // instead of emitting one copy per source (which causes player list flickering).
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE, Category.GLOBAL);
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE, Category.GLOBAL);
        // WorldTimeUpdateS2CPacket (SET_TIME)
        m.put(GamePacketTypes.CLIENTBOUND_SET_TIME, Category.GLOBAL);
        // ClientboundSystemChatPacket (SYSTEM_CHAT)
        m.put(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT, Category.GLOBAL);
        // ChatMessageS2CPacket (PLAYER_CHAT)
        m.put(GamePacketTypes.CLIENTBOUND_PLAYER_CHAT, Category.GLOBAL);
        // ProfilelessChatMessageS2CPacket (DISGUISED_CHAT)
        m.put(GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT, Category.GLOBAL);
        // ExplosionS2CPacket (EXPLODE)
        m.put(GamePacketTypes.CLIENTBOUND_EXPLODE, Category.GLOBAL);
        // PlaySoundS2CPacket (SOUND)
        m.put(GamePacketTypes.CLIENTBOUND_SOUND, Category.GLOBAL);
        // PlaySoundFromEntityS2CPacket (SOUND_ENTITY)
        m.put(GamePacketTypes.CLIENTBOUND_SOUND_ENTITY, Category.GLOBAL);
        // StopSoundS2CPacket (STOP_SOUND)
        m.put(GamePacketTypes.CLIENTBOUND_STOP_SOUND, Category.GLOBAL);
        // ParticleS2CPacket (LEVEL_PARTICLES)
        m.put(GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES, Category.GLOBAL);
        // WorldEventS2CPacket (LEVEL_EVENT)
        m.put(GamePacketTypes.CLIENTBOUND_LEVEL_EVENT, Category.GLOBAL);
        // GameStateChangeS2CPacket (GAME_EVENT)
        m.put(GamePacketTypes.CLIENTBOUND_GAME_EVENT, Category.GLOBAL);

        return m;
    }
}
