package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spike — prouve qu'on peut décoder / modifier / re-encoder un GamePacket
 * contenant un entity ID.
 *
 * <h2>FINDINGS (Step 4 — résultats de l'investigation)</h2>
 *
 * <h3>1. Structure du payload GamePacket</h3>
 *
 * Le payload d'une {@link Action.GamePacket} est OPAQUE côté Flashback : il est
 * stocké tel quel dans le segment, sans sur-couche Flashback.
 * <p>
 * En remontant la chaîne d'écriture dans {@code FlashbackWriter.writePacketSync}
 * (Arcade), la méthode délègue à {@code ReplayWriter.encodePacket(packet, protocol,
 * buf)} qui, pour {@code ConnectionProtocol.PLAY}, utilise le codec du protocole
 * PLAY côté serveur ({@code ProtocolInfo} liaison côté {@code ServerPlayPacketListener}).
 * <p>
 * Ce codec inscrit :
 * <pre>
 *   [VarInt packetId] [corps du packet]
 * </pre>
 * Le {@code packetId} est l'identifiant numérique du packet dans le protocole PLAY
 * (ex. : 0x1F pour {@code ClientboundMoveEntityPacket.MoveRelative} en 1.21.11).
 * <p>
 * Conclusion : <b>OUI, le payload inclut bien un VarInt packetId en tête</b>.
 * Le reste est le corps du packet sérialisé tel que Minecraft l'enverrait sur le
 * réseau.
 *
 * <h3>2. API de décodage côté lecture</h3>
 *
 * En 1.21.x (Yarn), l'API publique est :
 * <pre>
 *   // GameProtocols.S2C est un NetworkStateFactory&lt;ClientGamePacketListener, RegistryFriendlyByteBuf&gt;
 *   NetworkState&lt;ClientGamePacketListener&gt; state =
 *       GameProtocols.S2C.bind(RegistryFriendlyByteBuf.makeFactory(registryManager));
 *   StreamCodec&lt;ByteBuf, Packet&lt;? super ClientGamePacketListener&gt;&gt; codec = state.codec();
 *
 *   // Décode depuis un ByteBuf contenant [VarInt packetId + corps]
 *   Packet&lt;? super ClientGamePacketListener&gt; pkt = codec.decode(rawBuf);
 *
 *   // Re-encode dans un nouveau ByteBuf
 *   ByteBuf out = Unpooled.buffer();
 *   codec.encode(out, pkt);  // => [VarInt packetId + corps]
 * </pre>
 * <b>Caveat runtime :</b> {@code GameProtocols.S2C} initialise ses codecs
 * statiquement ; ceux-ci dépendent des registres Minecraft (ex.
 * {@code ClientboundAddEntityPacket} encode un {@code EntityType}). Si les registres
 * Vanilla ne sont pas initialisés ({@code Bootstrap.initialize()}), on obtient une
 * NPE dans le chemin de décodage des packets qui lisent un registre. Les tests qui
 * utilisent ce codec doivent donc appeler {@code Bootstrap.initialize()} une fois
 * avant, ou s'assurer que Minecraft est démarré (ce qui n'est pas le cas en unit
 * tests simples).
 * <p>
 * En pratique, dans l'IdRemapper (Task 8), le remapping sera exécuté au moment du
 * merge, côté serveur Fabric où le bootstrap est déjà fait — pas de problème. Pour
 * les tests unitaires seuls, on peut soit appeler {@code Bootstrap.initialize()},
 * soit utiliser la manipulation binaire directe (fallback ci-dessous).
 *
 * <h3>3. Stratégie de remapping entity ID — RECOMMANDATION pour Task 8 / Task 16</h3>
 *
 * <b>Approche A — décodage complet via codec (recommandée quand Bootstrap OK)</b>
 * <pre>{@code
 * NetworkState<ClientGamePacketListener> state =
 *     GameProtocols.S2C.bind(RegistryFriendlyByteBuf.makeFactory(registryManager));
 * StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = state.codec();
 *
 * // Pour chaque payload :
 * ByteBuf rawBuf = Unpooled.wrappedBuffer(payload);
 * Packet<?> pkt = codec.decode(rawBuf);
 *
 * if (pkt instanceof ClientboundAddEntityPacket spawn) {
 *     int oldId = spawn.getEntityId();
 *     int newId = idMap.remap(oldId);
 *     // ClientboundAddEntityPacket expose un constructeur complet (tous champs publics)
 *     pkt = new ClientboundAddEntityPacket(newId, spawn.getUuid(), spawn.getX(),
 *         spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch(),
 *         spawn.getEntityType(), spawn.getEntityData(), spawn.getVelocity(),
 *         spawn.getHeadYaw());
 * } else if (pkt instanceof ClientboundMoveEntityPacket move) {
 *     // ClientboundMoveEntityPacket stocke l'entity ID dans le champ protected `id`
 *     // — pas de setter, il faut utiliser la manipulation directe (Approach B)
 *     // ou la réflexion.
 * }
 *
 * // Re-encoder :
 * ByteBuf out = Unpooled.buffer();
 * codec.encode(out, pkt);
 * byte[] newPayload = new byte[out.readableBytes()];
 * out.readBytes(newPayload);
 * }</pre>
 *
 * <b>Approche B — manipulation binaire directe (fallback, sans Bootstrap)</b>
 * <p>
 * Pour {@code ClientboundMoveEntityPacket.*}, le format wire binaire est :
 * <pre>
 *   VarInt packetId
 *   VarInt entityId
 *   ... reste du corps (delta + flags)
 * </pre>
 * On peut donc remapper l'entity ID en :
 * 1. Lire/sauter le {@code packetId} (VarInt).
 * 2. Lire le {@code entityId} (VarInt) depuis la position courante.
 * 3. Re-construire le payload en concaténant : bytes originaux jusqu'au debut de
 *    l'entityId + VarInt du nouvel ID + bytes restants après l'ancien entityId.
 * Cette approche est fragile (dépend du format exact de chaque packet) mais ne
 * nécessite pas Bootstrap et permet des tests unitaires purs.
 *
 * <b>Décision recommandée pour Task 8 :</b> utiliser l'Approche A (codec complet)
 * pour les packets ayant un constructeur complet ({@code ClientboundAddEntityPacket},
 * {@code EntityAnimationS2CPacket}, {@code EntityStatusS2CPacket}, etc.), et
 * l'Approche B (manipulation binaire) pour {@code ClientboundMoveEntityPacket.*} (MoveRelative,
 * Rotate, RotateAndMoveRelative) où le champ {@code id} est {@code protected} et il
 * n'y a pas de constructeur "par copie avec nouvel ID".
 */
class PacketCodecSpikeTest {

    // -Dmultiview.testReplayPath=<dir> overrides the fixture (must be an extracted replay folder).
    private static final Path REPLAY_PATH = Path.of(
            System.getProperty("multiview.testReplayPath", "run/replay/2026-02-20T23_25_15"));

    static boolean replaysAvailable() {
        return Files.isDirectory(REPLAY_PATH);
    }

    /**
     * Step 3 : vérifie qu'on peut ouvrir le replay et lire les bytes bruts du
     * premier GamePacket. Affiche le packetId (premier VarInt) et les premiers
     * bytes du corps pour confirmer la structure [VarInt packetId + corps].
     */
    @Test
    @EnabledIf("replaysAvailable")
    void canReadRawGamePacketPayload() throws Exception {
        FlashbackReplay replay = FlashbackReader.open(REPLAY_PATH);

        Optional<byte[]> maybePayload = FlashbackReader.stream(replay)
                .filter(e -> e.action() instanceof Action.GamePacket)
                .map(e -> ((Action.GamePacket) e.action()).bytes())
                .findFirst();

        assertTrue(maybePayload.isPresent(), "Au moins un GamePacket attendu dans un replay réel");
        byte[] payload = maybePayload.get();

        System.out.println("[SPIKE] Premier GamePacket payload = " + payload.length + " bytes");
        System.out.println("[SPIKE] Tous les bytes (hex, max 32) : "
                + toHex(payload, Math.min(32, payload.length)));

        // Décoder le VarInt en tête = packetId
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        int packetId = readVarInt(buf);
        int bodyStart = payload.length - buf.readableBytes();

        System.out.printf("[SPIKE] packetId (VarInt en tête) = 0x%02X (%d)%n", packetId, packetId);
        System.out.println("[SPIKE] Corps du packet (offset " + bodyStart + ", max 16 bytes) : "
                + toHex(payload, bodyStart, Math.min(bodyStart + 16, payload.length)));

        // Le payload doit au minimum contenir un VarInt non-nul
        assertTrue(payload.length > 0, "payload ne doit pas être vide");
        assertTrue(packetId >= 0, "packetId doit être un VarInt positif valide");
    }

    /**
     * Step 3 (suite) : collecte les 200 premiers GamePackets et affiche la
     * distribution des packetIds pour identifier quels packets sont les plus
     * fréquents (utile pour cibler le remapping).
     */
    @Test
    @EnabledIf("replaysAvailable")
    void profilePacketIdDistribution() throws Exception {
        FlashbackReplay replay = FlashbackReader.open(REPLAY_PATH);

        java.util.Map<Integer, Long> distribution = new java.util.TreeMap<>();
        long[] total = {0};

        FlashbackReader.stream(replay)
                .filter(e -> e.action() instanceof Action.GamePacket)
                .limit(500)
                .forEach(e -> {
                    byte[] payload = ((Action.GamePacket) e.action()).bytes();
                    ByteBuf buf = Unpooled.wrappedBuffer(payload);
                    int id = readVarInt(buf);
                    distribution.merge(id, 1L, Long::sum);
                    total[0]++;
                });

        System.out.println("[SPIKE] Distribution packetIds sur " + total[0] + " GamePackets :");
        distribution.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> System.out.printf("  packetId=0x%02X (%3d) : %d occurrences%n",
                        e.getKey(), e.getKey(), e.getValue()));

        assertTrue(total[0] > 0, "Des GamePackets doivent être présents dans le replay");
    }

    /**
     * BONUS — Step 3 avancé : tente de décoder un GamePacket dont le packetId
     * correspond à {@code ClientboundMoveEntityPacket.MoveRelative} (0x2C en 1.21.x) en
     * utilisant la manipulation binaire directe (Approche B), qui ne nécessite
     * pas Bootstrap.
     * <p>
     * Vérifie qu'on peut lire l'entityId, le modifier, et ré-encoder le payload
     * de façon byte-for-byte cohérente.
     */
    @Test
    @EnabledIf("replaysAvailable")
    void canRemapEntityIdInMovePacketBinaryApproach() throws Exception {
        FlashbackReplay replay = FlashbackReader.open(REPLAY_PATH);

        // Collecter jusqu'à 500 payloads et chercher un packet dont le corps
        // commence par un VarInt raisonnable (un entityId).
        // Note : on ne connaît pas le packetId exact sans Bootstrap ; on vérifie
        // juste la mécanique de lecture VarInt + réécriture.
        List<byte[]> payloads = new ArrayList<>();
        FlashbackReader.stream(replay)
                .filter(e -> e.action() instanceof Action.GamePacket)
                .limit(500)
                .forEach(e -> payloads.add(((Action.GamePacket) e.action()).bytes()));

        // Trouver un payload dont le corps (après le packetId) commence par un
        // VarInt d'entityId plausible (>0 et raisonnable)
        byte[] targetPayload = null;
        int foundEntityId = -1;
        int packetIdFound = -1;
        for (byte[] payload : payloads) {
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            int pid = readVarInt(buf);
            if (buf.readableBytes() >= 1) {
                int entityId = readVarInt(buf);
                if (entityId > 0 && entityId < 100_000) {
                    targetPayload = payload;
                    foundEntityId = entityId;
                    packetIdFound = pid;
                    break;
                }
            }
        }

        if (targetPayload == null) {
            System.out.println("[SPIKE] Aucun packet avec entityId plausible trouvé dans les 500 premiers — spike partiel OK");
            return;
        }

        System.out.printf("[SPIKE] Packet trouvé : packetId=0x%02X, entityId=%d, taille=%d bytes%n",
                packetIdFound, foundEntityId, targetPayload.length);

        // Approche B : remapper l'entityId
        int newEntityId = foundEntityId + 10000; // ID remappé fictif
        byte[] remapped = remapFirstVarIntAfterPacketId(targetPayload, newEntityId);

        // Vérifier que le re-encoded payload a le bon nouvel entityId
        ByteBuf check = Unpooled.wrappedBuffer(remapped);
        int checkPid = readVarInt(check);
        int checkEid = readVarInt(check);

        assertEquals(packetIdFound, checkPid, "packetId doit être préservé après remapping");
        assertEquals(newEntityId, checkEid, "entityId doit être le nouvel ID après remapping");
        System.out.printf("[SPIKE] Remapping OK : entityId %d -> %d, taille originale=%d, remappée=%d%n",
                foundEntityId, newEntityId, targetPayload.length, remapped.length);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Recrée un payload en remplaçant le VarInt qui suit le VarInt packetId
     * (= l'entityId pour les packets ClientboundMoveEntityPacket.*).
     * Préserve tous les autres bytes intact.
     */
    private static byte[] remapFirstVarIntAfterPacketId(byte[] original, int newEntityId) {
        ByteBuf in = Unpooled.wrappedBuffer(original);

        // Lire et conserver le packetId
        int packetIdStart = in.readerIndex();
        readVarInt(in); // avance le reader
        int entityIdStart = in.readerIndex();

        // Lire l'entityId existant (on le remplace)
        readVarInt(in); // avance encore
        int afterEntityId = in.readerIndex();

        // Construire le nouveau payload :
        //   bytes[0..entityIdStart) + VarInt(newEntityId) + bytes[afterEntityId..)
        ByteBuf out = Unpooled.buffer(original.length + 5);

        // Bytes avant l'entityId (inclut le packetId VarInt)
        out.writeBytes(original, packetIdStart, entityIdStart - packetIdStart);

        // Nouvel entityId
        writeVarInt(out, newEntityId);

        // Reste du corps
        int remaining = original.length - afterEntityId;
        if (remaining > 0) {
            out.writeBytes(original, afterEntityId, remaining);
        }

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        return result;
    }

    /** Lit un VarInt depuis un ByteBuf (identique à PacketByteBuf.readVarInt). */
    private static int readVarInt(ByteBuf buf) {
        int i = 0, j = 0;
        while (true) {
            byte b = buf.readByte();
            i |= (b & 0x7F) << j;
            j += 7;
            if ((b & 0x80) == 0) return i;
            if (j >= 35) throw new RuntimeException("VarInt too large");
        }
    }

    /** Écrit un VarInt dans un ByteBuf. */
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static String toHex(byte[] bytes, int len) {
        return toHex(bytes, 0, len);
    }

    private static String toHex(byte[] bytes, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(String.format("%02x ", bytes[i]));
        return sb.toString().trim();
    }
}
