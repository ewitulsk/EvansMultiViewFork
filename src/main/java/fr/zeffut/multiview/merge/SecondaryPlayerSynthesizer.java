package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

import com.mojang.authlib.GameProfile;

import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthesizes MC packets that make a secondary POV player visible as a regular
 * player entity in the primary POV's stream.
 *
 * <h2>What is synthesized</h2>
 * <ol>
 *   <li><b>PlayerInfoUpdate(ADD_PLAYER)</b> — registers the secondary's game profile
 *       in the client tab list so the client knows the skin/name when the entity spawns.</li>
 *   <li><b>ClientboundAddEntityPacket</b> — spawns a player entity at (0, 0, 0) with the
 *       secondary's UUID and a fresh global entity ID.  A subsequent TeleportEntity
 *       packet (synthesized from each PLAYER_POSITION EGO packet) will relocate it.</li>
 *   <li><b>ClientboundTeleportEntityPacket</b> (TELEPORT_ENTITY) — re-positions the fake
 *       player entity whenever the secondary source's PLAYER_POSITION packet fires.</li>
 * </ol>
 *
 * <h2>Fallback mode</h2>
 * If Minecraft's Bootstrap has not been initialised (unit test context, or codec
 * lookup failure), all synthesis methods return {@code null} silently so that the
 * caller can skip emission and preserve the previous behaviour (secondary invisible).
 *
 * @implNote Non thread-safe. Use one instance per merge run.
 */
public final class SecondaryPlayerSynthesizer {

    private static final Logger LOG = LoggerFactory.getLogger(SecondaryPlayerSynthesizer.class);

    /** True when MC codec lookup failed — all synthesis is disabled. */
    private final boolean fallbackMode;

    private final IdRemapper idRemapper;

    /** Numeric protocol IDs, resolved once at construction. */
    private final int idPlayerInfoUpdate;
    private final int idAddEntity;
    private final int idTeleportEntity;

    /**
     * Registry access that actually contains {@code minecraft:entity_type}.
     * {@code RegistryAccess.EMPTY} has no registries at all, so encoding or decoding
     * an {@code EntityType} holder through it throws "Missing registry". Mirroring
     * {@link EntityPacketRewriter}: after Bootstrap the built-in root registry holds
     * every vanilla registry; without Bootstrap (plain unit tests) we detect the
     * empty entity_type registry and fall back to EMPTY, preserving stub behaviour.
     */
    private static RegistryAccess registryAccess() {
        try {
            if (BuiltInRegistries.ENTITY_TYPE.size() > 0) {
                return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            }
        } catch (Throwable ignored) {
        }
        return RegistryAccess.EMPTY;
    }

    public SecondaryPlayerSynthesizer(IdRemapper idRemapper) {
        this.idRemapper = idRemapper;

        boolean fb;
        int piuId = -1, aeId = -1, teId = -1;
        try {
            piuId = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE);
            aeId  = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_ADD_ENTITY);
            teId  = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY);
            fb = false;
        } catch (Throwable t) {
            LOG.warn("SecondaryPlayerSynthesizer: codec init failed ("
                    + t.getClass().getSimpleName() + ") — secondary player synthesis disabled.");
            fb = true;
        }
        this.fallbackMode     = fb;
        this.idPlayerInfoUpdate = piuId;
        this.idAddEntity        = aeId;
        this.idTeleportEntity   = teId;
    }

    /**
     * Returns true when synthesis is available (Minecraft codecs reachable at runtime).
     * If false, all synthesis methods return null.
     */
    public boolean isAvailable() {
        return !fallbackMode;
    }

    // -------------------------------------------------------------------------
    // CreateLocalPlayer payload parsing
    // -------------------------------------------------------------------------

    /**
     * Extracts the UUID from a {@code CreateLocalPlayer} payload.
     * The payload starts with the UUID as two big-endian longs (16 bytes total).
     *
     * @param createPlayerBytes raw payload of {@link fr.zeffut.multiview.format.Action.CreatePlayer}
     * @return UUID, or {@code null} if the payload is too short
     */
    public static UUID extractUuid(byte[] createPlayerBytes) {
        if (createPlayerBytes == null || createPlayerBytes.length < 16) return null;
        ByteBuf buf = Unpooled.wrappedBuffer(createPlayerBytes);
        long msb = buf.readLong();
        long lsb = buf.readLong();
        return new UUID(msb, lsb);
    }

    /**
     * Attempts to extract the {@link GameProfile} (UUID + username + skin properties)
     * from a {@code CreateLocalPlayer} payload.
     *
     * <h3>Payload layout (Flashback 0.39.4 / MC 1.21.11)</h3>
     * <pre>
     *   UUID         : 2 × long  (16 bytes)
     *   x, y, z      : 3 × double (24 bytes)
     *   yaw, pitch, headYaw : 3 × float (12 bytes)
     *   EntityType   : via EntityType.STREAM_CODEC (RegistryFriendlyByteBuf codec)
     *   GameProfile  : via ByteBufCodecs.GAME_PROFILE (ByteBuf codec)
     *   …remainder…
     * </pre>
     *
     * Parsing the EntityType codec requires Bootstrap to be initialised.  If it
     * fails, we fall back to constructing a profile with just the UUID and a
     * placeholder name.
     *
     * @param createPlayerBytes raw CreatePlayer payload
     * @return parsed {@link GameProfile}, or a stub profile on failure
     */
    public static GameProfile extractGameProfile(byte[] createPlayerBytes) {
        UUID uuid = extractUuid(createPlayerBytes);
        if (uuid == null) {
            return new GameProfile(UUID.randomUUID(), "Player-unknown");
        }

        // Try to parse full GameProfile from payload
        try {
            ByteBuf raw = Unpooled.wrappedBuffer(createPlayerBytes);
            // Skip UUID (16 bytes = 2 longs)
            raw.skipBytes(16);
            // Skip x, y, z (3 doubles = 24 bytes)
            raw.skipBytes(24);
            // Skip yaw, pitch, headYaw (3 floats = 12 bytes)
            raw.skipBytes(12);

            // Skip EntityType encoded via EntityType.STREAM_CODEC (RegistryFriendlyByteBuf)
            // EntityType is encoded as a VarInt registry ID
            RegistryFriendlyByteBuf regBuf = new RegistryFriendlyByteBuf(raw, registryAccess());
            EntityType.STREAM_CODEC.decode(regBuf);

            // Now decode GameProfile via ByteBufCodecs.GAME_PROFILE
            // GameProfile codec: readUUID + readString (VarInt-prefixed UTF-8) + properties
            GameProfile profile = ByteBufCodecs.GAME_PROFILE.decode(raw);
            if (profile != null && profile.name() != null && !profile.name().isEmpty()) {
                return profile;
            }
        } catch (Throwable t) {
            LOG.debug("SecondaryPlayerSynthesizer: could not extract GameProfile from CreatePlayer payload: "
                    + t.getMessage() + " — using placeholder name");
        }

        // Fallback: use UUID + placeholder name derived from UUID prefix
        String name = "Player-" + uuid.toString().substring(0, 8);
        return new GameProfile(uuid, name);
    }

    // -------------------------------------------------------------------------
    // Packet synthesis
    // -------------------------------------------------------------------------

    /**
     * Synthesizes a {@code PlayerInfoUpdate(ADD_PLAYER)} packet that registers the
     * secondary player in the client's tab list.
     *
     * <h3>Wire format (manual encoding)</h3>
     * The public constructors of {@code ClientboundPlayerInfoUpdatePacket} only accept
     * {@code ServerPlayerEntity} objects, which are unavailable in the merge
     * pipeline.  We therefore encode the wire format directly:
     * <pre>
     *   byte  actions EnumSet   (1 byte: bit 0 = ADD_PLAYER set → 0x01)
     *   VarInt entry count      (1 → 0x01)
     *   UUID                    (2 longs = 16 bytes)
     *   GameProfile             (via ByteBufCodecs.GAME_PROFILE)
     * </pre>
     *
     * @param profile game profile (UUID + name); may be a stub placeholder
     * @return raw payload bytes (VarInt packetId + body), or {@code null} if synthesis failed
     */
    public byte[] synthesizePlayerInfoUpdate(GameProfile profile) {
        if (fallbackMode) return null;
        try {
            ByteBuf body = Unpooled.buffer(64);
            FriendlyByteBuf pbuf = new FriendlyByteBuf(body);

            // Actions EnumSet: ADD_PLAYER has ordinal 0 → bit 0 set → single byte 0x01
            // writeEnumSet writes the enum as a fixed-size BitSet based on enum class size.
            // ClientboundPlayerInfoUpdatePacket.Action has 8 values → 1 byte.
            pbuf.writeEnumSet(java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                    ClientboundPlayerInfoUpdatePacket.Action.class);

            // 1 entry
            pbuf.writeVarInt(1);

            // Entry UUID (16 bytes)
            pbuf.writeLong(profile.id().getMostSignificantBits());
            pbuf.writeLong(profile.id().getLeastSignificantBits());

            // ADD_PLAYER action data: GameProfile via ByteBufCodecs.GAME_PROFILE
            ByteBufCodecs.GAME_PROFILE.encode(body, profile);

            return prependPacketId(idPlayerInfoUpdate, body);
        } catch (Throwable t) {
            LOG.warn("SecondaryPlayerSynthesizer: PlayerInfoUpdate synthesis failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Synthesizes an {@code ClientboundAddEntityPacket} (ADD_ENTITY) that spawns a PLAYER
     * entity for the secondary source at (0, 0, 0).
     *
     * <p>A fresh global entity ID is assigned via {@link IdRemapper#assign(int, int)}
     * using {@code localId=-1} as a sentinel (guaranteed unique per sourceIdx since
     * real entity IDs are non-negative).
     *
     * @param sourceIdx source index — used to derive a unique sentinel key in IdRemapper
     * @param uuid      the secondary player's UUID (from CreateLocalPlayer)
     * @return raw payload bytes, or {@code null} on failure
     */
    private EntityType<?> playerEntityType() throws ReflectiveOperationException {
        try {
            return (EntityType<?>) EntityType.class.getField("PLAYER").get(null);
        } catch (NoSuchFieldException absentIn26_2) {
            Class<?> types = Class.forName("net.minecraft.world.entity.EntityTypes");
            return (EntityType<?>) types.getField("PLAYER").get(null);
        }
    }

    public byte[] synthesizeAddEntity(int sourceIdx, UUID uuid) {
        if (fallbackMode) return null;
        try {
            // Assign a fresh global ID using sentinel localId = -1
            int globalId = idRemapper.assign(sourceIdx, -1);

            ClientboundAddEntityPacket packet = new ClientboundAddEntityPacket(
                    globalId,
                    uuid,
                    0.0, 0.0, 0.0,   // initial position — corrected by first TELEPORT_ENTITY
                    0f, 0f,           // yaw, pitch
                    playerEntityType(),
                    0,                // entityData
                    Vec3.ZERO,       // velocity
                    0.0               // headYaw
            );

            ByteBuf body = Unpooled.buffer(64);
            RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(body, registryAccess());
            ClientboundAddEntityPacket.STREAM_CODEC.encode(registryBuf, packet);

            return prependPacketId(idAddEntity, body);
        } catch (Throwable t) {
            LOG.warn("SecondaryPlayerSynthesizer: AddEntity synthesis failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Returns the fake global entity ID that was assigned for the given source.
     * Must be called <b>after</b> {@link #synthesizeAddEntity(int, UUID)}.
     *
     * @param sourceIdx source index
     * @return global entity ID, or -1 if not yet assigned
     */
    public int getFakeEntityId(int sourceIdx) {
        if (!idRemapper.contains(sourceIdx, -1)) return -1;
        return idRemapper.remap(sourceIdx, -1);
    }

    /**
     * Synthesizes a {@code ClientboundTeleportEntityPacket} (TELEPORT_ENTITY) that moves
     * the fake player entity to the given absolute position.
     *
     * <p>An empty relatives set makes every component absolute. On 26.2 this method
     * encoded an {@code EntityPositionSync} body under the TELEPORT_ENTITY id, which
     * missed the relatives-set field and failed to decode; on 26.3
     * {@code ClientboundEntityPositionSyncPacket} moved to a {@code PositionPath}
     * record anyway, so the real teleport packet is used here.
     *
     * @param entityId global entity ID of the fake player (from {@link #getFakeEntityId})
     * @param x        absolute X position
     * @param y        absolute Y position
     * @param z        absolute Z position
     * @param yaw      yaw in degrees
     * @param pitch    pitch in degrees
     * @return raw payload bytes, or {@code null} on failure
     */
    public byte[] synthesizeTeleport(int entityId, double x, double y, double z,
                                     float yaw, float pitch) {
        if (fallbackMode || entityId < 0) return null;
        try {
            PositionMoveRotation pos = new PositionMoveRotation(
                    new Vec3(x, y, z),
                    Vec3.ZERO,   // deltaMovement
                    yaw,
                    pitch
            );

            ClientboundTeleportEntityPacket packet = ClientboundTeleportEntityPacket.teleport(
                    entityId,
                    pos,
                    java.util.Set.of(),   // relatives — empty = absolute
                    false                 // onGround
            );

            ByteBuf body = Unpooled.buffer(32);
            FriendlyByteBuf pbuf = new FriendlyByteBuf(body);
            ClientboundTeleportEntityPacket.STREAM_CODEC.encode(pbuf, packet);

            return prependPacketId(idTeleportEntity, body);
        } catch (Throwable t) {
            LOG.warn("SecondaryPlayerSynthesizer: TeleportEntity synthesis failed: " + t.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // accurate_player_position_optional helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code entityId} that prefixes a Flashback
     * {@code accurate_player_position_optional} action payload.
     *
     * <p>Payload layout (cf. {@code FlashbackAccurateEntityPosition} stream codec):
     * <pre>
     *   VarInt entityId
     *   VarInt count
     *   count × (double x, double y, double z, float yaw, float pitch)
     * </pre>
     *
     * @param payload raw action payload
     * @return the entity id, or {@code -1} if the payload is empty / malformed
     */
    public static int readAccuratePositionEntityId(byte[] payload) {
        if (payload == null || payload.length == 0) return -1;
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            return VarInts.readVarInt(buf);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Rewrites the leading VarInt {@code entityId} in an
     * {@code accurate_player_position_optional} action payload, preserving all
     * subsequent bytes (position / angle list).
     *
     * @param payload     original action payload (VarInt entityId + VarInt count + data)
     * @param newEntityId replacement entity id
     * @return rewritten payload, or the original reference if decoding failed
     */
    public static byte[] rewriteAccuratePositionEntityId(byte[] payload, int newEntityId) {
        if (payload == null || payload.length == 0) return payload;
        try {
            ByteBuf in = Unpooled.wrappedBuffer(payload);
            int originalStart = in.readerIndex();
            VarInts.readVarInt(in); // skip old entityId
            int afterOldId = in.readerIndex();

            ByteBuf out = Unpooled.buffer(payload.length + 5);
            VarInts.writeVarInt(out, newEntityId);
            int remaining = payload.length - (afterOldId - originalStart);
            if (remaining > 0) {
                out.writeBytes(payload, afterOldId, remaining);
            }
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } catch (Throwable t) {
            LOG.warn("SecondaryPlayerSynthesizer: rewriteAccuratePositionEntityId failed: "
                    + t.getMessage());
            return payload;
        }
    }

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    /**
     * Prepends a VarInt {@code packetId} to {@code body} and returns the combined
     * byte array.  {@code body}'s reader index is assumed to be at position 0;
     * {@code readableBytes()} is used as the body length.
     */
    private static byte[] prependPacketId(int packetId, ByteBuf body) {
        ByteBuf header = Unpooled.buffer(5);
        VarInts.writeVarInt(header, packetId);

        int totalLen = header.readableBytes() + body.readableBytes();
        byte[] result = new byte[totalLen];
        header.readBytes(result, 0, header.readableBytes());
        int headerLen = header.writerIndex();
        body.readBytes(result, headerLen, body.readableBytes());
        return result;
    }
}
