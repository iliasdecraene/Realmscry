package packets.incoming;

import packets.Packet;
import packets.data.FameData;
import packets.reader.BufferReader;

/**
 * Replacement for Tomato v1.9.2's DeathPacket, shadowed into the fat jar
 * (our .class wins over the library's identical entry via `jar --update`).
 *
 * The live protocol added two compressed ints between totalFame and the
 * fame-bonus array (observed 2026-09-06: values 82 and 12,706,844 — likely
 * fame level and lifetime XP). Tomato's parser reads the first one as the
 * array count and explodes ("Buffer exploded: 43/1672"), and an exception
 * during deserialize makes the processor DROP the packet — so deaths were
 * never dispatched at all. Verified byte-exact against the real 1672-byte
 * death capture in error/error-2026-09-06-18.30.39.data.
 *
 * The fields the tracker consumes (killedBy, totalFame) sit before the
 * divergence point, so they are read unconditionally; everything after is
 * best-effort inside a catch-all — this parser must never throw, because a
 * throw means the death silently vanishes again on the next format drift.
 */
public class DeathPacket extends Packet {

    public String accountId;
    public int charId;
    public String killedBy;
    public int gravestoneType;
    public int totalFame;
    /** New in the live protocol; meaning unconfirmed (fame level?). */
    public int unknownA;
    /** New in the live protocol; meaning unconfirmed (lifetime XP?). */
    public int unknownB;
    public FameData[] fameData;
    public String stats;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        accountId = buffer.readString();
        charId = buffer.readCompressedInt();
        killedBy = buffer.readString();
        gravestoneType = buffer.readInt();
        totalFame = buffer.readCompressedInt();
        try {
            unknownA = buffer.readCompressedInt();
            unknownB = buffer.readCompressedInt();
            int n = buffer.readCompressedInt();
            if (n >= 0 && n <= 2000) {
                fameData = new FameData[n];
                for (int i = 0; i < n; i++) {
                    fameData[i] = new FameData().deserialize(buffer);
                }
            }
            stats = buffer.readString();
        } catch (Throwable ignored) {
            // Tail format drifted again — keep the prefix fields we already
            // have. Fall through to the drain below either way.
        }
        // Consume whatever is left so the processor never logs this packet
        // as misparsed; partial parses still dispatch, which is the point.
        if (buffer.getRemainingBytes() > 0) buffer.giveRemainingArray();
    }

    @Override
    public String toString() {
        return "DeathPacket{accountId=" + accountId + ", charId=" + charId
                + ", killedBy=" + killedBy + ", gravestoneType=" + gravestoneType
                + ", totalFame=" + totalFame
                + ", fameBonuses=" + (fameData == null ? 0 : fameData.length) + "}";
    }
}
