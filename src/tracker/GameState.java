package tracker;

import assets.IdToAsset;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.ConditionBits;
import packets.data.enums.StatType;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.DamagePacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.NewTickPacket;
import packets.incoming.ServerPlayerShootPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.EnemyHitPacket;
import packets.outgoing.PlayerShootPacket;
import util.RNG;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the game state Tomato-style from sniffed packets and produces
 * two event streams: loot drops (white/orange/red bags) and boss kills
 * with a per-player damage breakdown.
 *
 * Damage attribution mirrors tomato.backend.data.TomatoData:
 *  - Own shots: PlayerShootPacket + RNG(map seed) rolls exact weapon damage,
 *    EnemyHitPacket confirms the hit, defense applied on arrival.
 *  - Other players: DamagePacket carries the final server-computed damage
 *    with the owning player's object id.
 */
public class GameState {

    /** Minimal entity: stats + accumulated damage per attacking player. */
    static class Ent {
        final int id;
        int objectType = -1;
        final HashMap<Integer, StatData> stats = new HashMap<>();
        final LinkedHashMap<Integer, Attacker> dmg = new LinkedHashMap<>();
        long totalDmg;
        long firstHitMs, lastHitMs;

        Ent(int id) { this.id = id; }

        int stat(StatType t) {
            StatData s = stats.get(t.get());
            return s == null ? 0 : s.statValue;
        }

        boolean hasStat(StatType t) { return stats.containsKey(t.get()); }

        String name() {
            StatData s = stats.get(StatType.NAME_STAT.get());
            if (s != null && s.stringStatValue != null && !s.stringStatValue.isEmpty()) {
                return s.stringStatValue.split(",")[0];
            }
            return null;
        }
    }

    static class Attacker {
        String name;
        int icon; // skin id, or class objectType when unskinned; 0 = unknown
        int[] loadout; // 4 equipped item ids (0 = empty slot); null = never seen
        long dmg;
    }

    /** A registered in-flight projectile. */
    static class Proj {
        final int damage;
        final boolean armorPiercing;
        final int summonerId;
        Proj(int damage, boolean armorPiercing, int summonerId) {
            this.damage = damage;
            this.armorPiercing = armorPiercing;
            this.summonerId = summonerId;
        }
    }

    static class PendingBag {
        final int entityId;
        final int objectType;
        final boolean onlyIfShiny; // untracked bag tier: publish only for shinies
        int ticksLeft = 4;
        PendingBag(int entityId, int objectType, boolean onlyIfShiny) {
            this.entityId = entityId;
            this.objectType = objectType;
            this.onlyIfShiny = onlyIfShiny;
        }
    }

    // Loot bag object types the user cares about: id -> [tier, boosted]
    private static final Map<Integer, String[]> BAG_TIERS = new HashMap<>();
    static {
        BAG_TIERS.put(1292, new String[]{"white", "0"});
        BAG_TIERS.put(1296, new String[]{"white", "1"});
        BAG_TIERS.put(1295, new String[]{"orange", "0"});
        BAG_TIERS.put(1727, new String[]{"orange", "1"});
        BAG_TIERS.put(1708, new String[]{"red", "0"});
        BAG_TIERS.put(1728, new String[]{"red", "1"});
    }

    private final HashMap<Integer, Ent> entities = new HashMap<>();
    private final HashMap<Integer, Ent> players = new HashMap<>();
    private final HashMap<Integer, Integer> minionOwner = new HashMap<>();
    // key = (ownerId << 32) | bulletIndex — capped so long sessions don't leak
    private final LinkedHashMap<Long, Proj> projectiles = new LinkedHashMap<Long, Proj>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Proj> e) { return size() > 8192; }
    };
    // Fallback registry for our own weapon shots keyed by bulletId alone —
    // works before CreateSuccess/MapInfo have arrived (tracker started mid-map).
    private final LinkedHashMap<Integer, Proj> ownShots = new LinkedHashMap<Integer, Proj>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Proj> e) { return size() > 512; }
    };
    private final ArrayList<PendingBag> pendingBags = new ArrayList<>();
    private final HashMap<Integer, Long> seenBags = new HashMap<>();

    // Diagnostics, readable at /debug
    private long cPlayerShoot, cServerShoot, cEnemyHit, cEnemyHitMatched,
            cDamagePkts, cDamageAttributed, cLootBags, cBossKills, cDeaths, cErrors;
    private String lastError = "";

    // Capture-health heartbeats for the sniffer watchdog. Volatile instead of
    // synchronized: written from capture threads on every stream chunk, read
    // by the watchdog — must never contend for the state lock.
    private final long startMs = System.currentTimeMillis();
    private volatile long lastRawMs;      // last reassembled port-2050 bytes
    private volatile long lastDecodedMs;  // last successfully decoded packet
    private volatile boolean rawSinceRestart;
    private volatile int snifferRestarts;

    private RNG rng;
    private int myId = -1;
    private int myWeapon = 0; // last weapon we fired; fills our loadout slot 0 mid-map
    private String mapName = "";
    private final Publisher publisher;

    /** Snapshot of our character the moment a DeathPacket arrives. */
    public static class Death {
        public String name = "";      // IGN ("" if never seen — mid-map ghost)
        public String killedBy = "";
        public int icon;              // skin, else class sprite
        public int classType;         // class objectType (for the ?/8 lookup)
        public long fame;
        public int maxed = -1;        // 0..8, -1 = unknown (no players.xml)
        public int[] equip = new int[4];
        public int[] backpack = new int[0]; // inventory + backpack items, ids > 0
        public long ts;
    }

    /** Sink for UI events. */
    public interface Publisher {
        /** items: one int[] per item — {objectId, shinyFlag(0/1), enchantSlots(0-4)}. */
        void lootDropped(String tier, boolean boosted, int bagType, List<int[]> items, long ts);
        void bossKilled(String bossName, int bossType, long totalDmg, long fightMs, List<Object[]> top, long ts);
        void died(Death death);
        void mapChanged(String mapName);
    }

    public GameState(Publisher publisher) {
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------
    // Packet handlers (called from the sniffer thread, all synchronized)
    // ------------------------------------------------------------------

    public synchronized void mapInfo(MapInfoPacket p) {
        entities.clear();
        players.clear();
        minionOwner.clear();
        projectiles.clear();
        ownShots.clear();
        pendingBags.clear();
        seenBags.clear();
        rng = new RNG(p.seed);
        myWeapon = 0;
        mapName = p.displayName != null ? p.displayName : p.name;
        publisher.mapChanged(mapName);
    }

    public synchronized void createSuccess(CreateSuccessPacket p) {
        myId = p.objectId;
        ensureSelfPlayer();
    }

    /**
     * Our own entity only arrives via newObjects at a real map join. When the
     * tracker starts mid-map we still learn myId (CreateSuccess or EnemyHit
     * inference) but our entity would stay a nameless ghost outside `players`
     * — damage rows then show "Player#id" with no loadout. Register the (maybe
     * sparse) entity so whatever stats do trickle in mid-map attach to us.
     */
    private void ensureSelfPlayer() {
        if (myId != -1 && !players.containsKey(myId)) {
            players.put(myId, ent(myId));
        }
    }

    public synchronized void update(UpdatePacket p) {
        for (ObjectData od : p.newObjects) {
            Ent e = ent(od.status.objectId);
            e.objectType = od.objectType;
            applyStats(e, od.status);
            if (isPlayerType(od.objectType)) {
                players.put(e.id, e);
            } else if (!seenBags.containsKey(e.id)
                    && (BAG_TIERS.containsKey(od.objectType) || isLootBag(od.objectType))) {
                // Tracked tiers always publish; any other loot bag (brown,
                // pink, purple, cyan, …) is scanned and published only when
                // it holds a shiny.
                seenBags.put(e.id, System.currentTimeMillis());
                pendingBags.add(new PendingBag(e.id, od.objectType,
                        !BAG_TIERS.containsKey(od.objectType)));
            }
        }
        for (int dropId : p.drops) {
            Ent e = entities.get(dropId);
            minionOwner.remove(dropId);
            players.remove(dropId);
            if (e != null && e.totalDmg > 0) {
                maybeBossKill(e, false);
            }
        }
    }

    public synchronized void newTick(NewTickPacket p) {
        for (ObjectStatusData s : p.status) {
            applyStats(ent(s.objectId), s);
        }
        processPendingBags();
    }

    public synchronized void playerShoot(PlayerShootPacket p) {
        cPlayerShoot++;
        int pid = p.projectileId == -1 ? 0 : p.projectileId;
        int min, max;
        boolean ap;
        int slot;
        try {
            min = IdToAsset.getIdProjectileMinDmg(p.weaponId, pid);
            max = IdToAsset.getIdProjectileMaxDmg(p.weaponId, pid);
            ap = IdToAsset.getIdProjectileArmorPierces(p.weaponId, pid);
            slot = IdToAsset.getIdProjectileSlotType(p.weaponId);
        } catch (Exception ex) {
            return; // unknown weapon: no roll, keeps RNG in sync with the client
        }
        myWeapon = p.weaponId;
        int dmg;
        if (min != max) {
            if (rng == null) {
                dmg = (min + max) / 2; // no seed yet, approximate
            } else {
                long r = rng.next();
                dmg = (int) (min + r % (max - min));
            }
        } else {
            dmg = min;
        }
        if (isMainWeaponSlot(slot)) {
            dmg = (int) (dmg * statsMultiplier(players.get(myId)));
        }
        Proj proj = new Proj(dmg, ap, 0);
        ownShots.put(p.bulletId & 0xffff, proj);
        if (myId != -1) {
            projectiles.put(key(myId, p.bulletId), proj);
        }
    }

    public synchronized void serverPlayerShoot(ServerPlayerShootPacket p) {
        cServerShoot++;
        if (p.summonerId != 0 && p.ownerId != 0) {
            minionOwner.put(p.ownerId, p.summonerId);
        }
        boolean ap = false;
        try {
            ap = IdToAsset.getIdProjectileArmorPierces(p.containerType, p.bulletType);
        } catch (Exception ignored) {
        }
        Proj proj = new Proj(p.damage, ap, p.summonerId);
        if (p.bulletCount > 1) {
            for (int j = p.bulletId; j < p.bulletId + p.bulletCount; j++) {
                projectiles.put(key(p.ownerId, j % 256 + 256), proj);
            }
        } else if (p.bulletId > 255 && p.bulletId < 512) {
            projectiles.put(key(p.ownerId, p.bulletId), proj);
        } else {
            projectiles.put(key(p.ownerId, p.bulletId % 256 + 256), proj);
        }
    }

    public synchronized void enemyHit(EnemyHitPacket p) {
        cEnemyHit++;
        Proj proj = projectiles.get(key(p.shooterID, p.bulletId));
        if (proj == null) {
            proj = projectiles.get(key(p.shooterID, p.bulletId % 256 + 256));
        }
        if (proj == null) {
            // Fallback: our own weapon shot registered before we knew our id.
            proj = ownShots.get(p.bulletId & 0xffff);
            if (proj != null && myId == -1) {
                // EnemyHit only confirms our own bullets, so the shooter is us.
                myId = p.shooterID;
            }
        }
        ensureSelfPlayer();
        if (proj != null) cEnemyHitMatched++;
        Ent target = ent(p.targetId);
        int attackerId = (proj != null && proj.summonerId != 0) ? proj.summonerId : p.shooterID;
        if (proj != null && proj.damage != 0) {
            int dmg = damageWithDefense(proj.damage, proj.armorPiercing, target);
            if (dmg > 0) {
                addDamage(target, attackerId, dmg);
            }
        }
        if (p.kill) {
            maybeBossKill(target, true);
        }
    }

    public synchronized void damage(DamagePacket p) {
        cDamagePkts++;
        if (p.damageAmount <= 0) return;
        int attackerId = -1;
        if (players.containsKey(p.objectId)) {
            attackerId = p.objectId;
        } else {
            Integer owner = minionOwner.get(p.objectId);
            if (owner != null && players.containsKey(owner)) {
                attackerId = owner;
            }
        }
        if (attackerId == -1) return; // damage from non-players (enemies, traps)
        cDamageAttributed++;
        addDamage(ent(p.targetId), attackerId, p.damageAmount);
    }

    /**
     * Our own death. The DeathPacket is only ever sent to the dying client,
     * so no ownership check is needed; everything about the character is
     * snapshotted from the stats we tracked up to this moment.
     */
    public synchronized void death(packets.incoming.DeathPacket p) {
        Death d = new Death();
        d.ts = System.currentTimeMillis();
        d.killedBy = p.killedBy == null ? "" : p.killedBy;
        d.fame = p.totalFame;
        Ent me = players.get(myId);
        if (me != null) {
            String n = me.name();
            if (n != null) d.name = n;
            d.classType = me.objectType > 0 ? me.objectType : 0;
            int skin = me.stat(StatType.SKIN_ID);
            d.icon = skin > 0 ? skin : d.classType;
            for (int i = 0; i < 4; i++) {
                StatData sd = me.stats.get(StatType.INVENTORY_0_STAT.get() + i);
                if (sd != null && sd.statValue > 0) d.equip[i] = sd.statValue;
            }
            // Carried items: main inventory (stats 12..19) then backpack
            // (131..138) — shown together on the death card.
            ArrayList<Integer> carried = new ArrayList<>();
            for (int i = 4; i < 12; i++) {
                StatData sd = me.stats.get(StatType.INVENTORY_0_STAT.get() + i);
                if (sd != null && sd.statValue > 0) carried.add(sd.statValue);
            }
            for (int i = 0; i < 8; i++) {
                StatData sd = me.stats.get(StatType.BACKPACK_0_STAT.get() + i);
                if (sd != null && sd.statValue > 0) carried.add(sd.statValue);
            }
            d.backpack = carried.stream().mapToInt(Integer::intValue).toArray();
            // 8 stats + their equipment-boost components, game order.
            int[] stat = {
                    me.stat(StatType.MAX_HP_STAT), me.stat(StatType.MAX_MP_STAT),
                    me.stat(StatType.ATTACK_STAT), me.stat(StatType.DEFENSE_STAT),
                    me.stat(StatType.SPEED_STAT), me.stat(StatType.DEXTERITY_STAT),
                    me.stat(StatType.VITALITY_STAT), me.stat(StatType.WISDOM_STAT)};
            int[] boost = {
                    me.stat(StatType.MAX_HP_BOOST_STAT), me.stat(StatType.MAX_MP_BOOST_STAT),
                    me.stat(StatType.ATTACK_BOOST_STAT), me.stat(StatType.DEFENSE_BOOST_STAT),
                    me.stat(StatType.SPEED_BOOST_STAT), me.stat(StatType.DEXTERITY_BOOST_STAT),
                    me.stat(StatType.VITALITY_BOOST_STAT), me.stat(StatType.WISDOM_BOOST_STAT)};
            if (d.classType > 0 && me.hasStat(StatType.MAX_HP_STAT)) {
                d.maxed = ClassStats.maxedCount(d.classType, stat, boost);
            }
        }
        cDeaths++;
        publisher.died(d);
    }

    /** Own game account id (string stat), "" while unknown. */
    public synchronized String myAccountId() {
        Ent me = players.get(myId);
        if (me == null) return "";
        StatData sd = me.stats.get(StatType.ACCOUNT_ID_STAT.get());
        return sd != null && sd.stringStatValue != null ? sd.stringStatValue : "";
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Ent ent(int id) {
        return entities.computeIfAbsent(id, Ent::new);
    }

    private static long key(int ownerId, int bulletIndex) {
        return (long) ownerId << 32 | (bulletIndex & 0xffffffffL);
    }

    private static void applyStats(Ent e, ObjectStatusData status) {
        if (status.stats == null) return;
        for (StatData s : status.stats) {
            e.stats.put(s.statTypeNum, s);
        }
    }

    private static boolean isMainWeaponSlot(int slot) {
        switch (slot) {
            case 1: case 2: case 3: case 8: case 17: case 24: return true;
            default: return false;
        }
    }

    /** Mirrors Entity.playerStatsMultiplier() from Tomato (minus crucible bonus). */
    private float statsMultiplier(Ent player) {
        if (player == null) return 1.0f;
        // A mid-map ghost self has no ATTACK stat yet; treating it as 0 would
        // yield a 0.5x multiplier — worse than the honest "unknown" fallback.
        if (!player.hasStat(StatType.ATTACK_STAT)) return 1.0f;
        int condition = player.stat(StatType.CONDITION_STAT);
        boolean weak = (condition & ConditionBits.WEAK.value()) != 0;
        boolean damaging = (condition & ConditionBits.DAMAGING.value()) != 0;
        if (weak) return 0.5f;
        int attack = player.stat(StatType.ATTACK_STAT);
        float mult = (attack + 25) * 0.02f;
        if (damaging) mult *= 1.25f;
        float exalt = player.hasStat(StatType.EXALTATION_BONUS_DAMAGE)
                ? player.stat(StatType.EXALTATION_BONUS_DAMAGE) / 1000.0f : 1.0f;
        if (exalt > 0) mult *= exalt;
        return mult;
    }

    /** Mirrors Projectile.damageWithDefense() from Tomato. */
    private static int damageWithDefense(int damage, boolean armorPiercing, Ent target) {
        int c0 = target.stat(StatType.CONDITION_STAT);
        int c1 = target.stat(StatType.NEW_CON_STAT);
        int defense = target.stat(StatType.DEFENSE_STAT);
        if (!armorPiercing && (c0 & 0x4000000) == 0) {   // not armor-broken
            if ((c0 & 0x2000000) != 0) defense = (int) (defense * 1.5); // armored
        } else {
            defense = 0;
        }
        if ((c1 & 0x20000) != 0) defense -= 20;           // exposed
        int minDmg = damage * 2 / 20;
        int dmg = Math.max(minDmg, damage - defense);
        if ((c0 & 0x1000000) != 0) dmg = 0;               // invulnerable
        if ((c1 & 0x8) != 0) dmg = (int) (dmg * 0.9);
        if ((c1 & 0x40) != 0) dmg = (int) (dmg * 1.25);   // cursed
        return dmg;
    }

    private void addDamage(Ent target, int attackerId, int dmg) {
        long now = System.currentTimeMillis();
        if (target.totalDmg == 0) target.firstHitMs = now;
        target.lastHitMs = now;
        target.totalDmg += dmg;
        Attacker a = target.dmg.computeIfAbsent(attackerId, k -> new Attacker());
        a.dmg += dmg;
        Ent p = players.get(attackerId);
        if (p != null) {
            if (a.name == null) a.name = p.name();
            if (a.icon == 0) {
                int skin = p.stat(StatType.SKIN_ID);
                // objectType is -1 for a ghost self entity — keep icon 0
                // ("unknown") so a later real sighting can still fill it.
                if (skin > 0) a.icon = skin;
                else if (p.objectType > 0) a.icon = p.objectType;
            }
            // Refresh the loadout on every hit so mid-fight gear swaps show
            // the gear that was actually used last. Equip slots are the first
            // four inventory stats (weapon, ability, armor, ring).
            int[] lo = new int[4];
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                StatData sd = p.stats.get(StatType.INVENTORY_0_STAT.get() + i);
                if (sd != null) {
                    any = true;
                    if (sd.statValue > 0) lo[i] = sd.statValue;
                }
            }
            if (any) a.loadout = lo;
        }
    }

    /**
     * Called when an entity we damaged leaves the world (or our client flags a
     * killing blow). Publishes a boss-kill event when it looks like a boss died.
     */
    private void maybeBossKill(Ent e, boolean killConfirmed) {
        if (e.totalDmg <= 0 || e.objectType == -1) return;
        boolean boss = isBoss(e.objectType) || e.stat(StatType.MAX_HP_STAT) >= 40000;
        if (!boss) return;
        if (!killConfirmed) {
            // Entity left view; only treat as a kill if its HP was nearly gone,
            // so leaving a dungeon mid-fight doesn't fire a false kill. Both
            // stats must have actually arrived: a missing stat defaults to 0,
            // which reads as "dead" and made phase despawns publish mid-fight.
            if (!e.hasStat(StatType.MAX_HP_STAT) || !e.hasStat(StatType.HP_STAT)) return;
            int maxHp = e.stat(StatType.MAX_HP_STAT);
            int hp = e.stat(StatType.HP_STAT);
            if (maxHp <= 0 || hp > maxHp * 0.2) return;
        }
        String name = displayName(e.objectType);
        // Top 5 by damage; if we placed below that, append our own row with
        // its real rank so the UI can show it under a separator.
        List<Map.Entry<Integer, Attacker>> sorted = new ArrayList<>(e.dmg.entrySet());
        sorted.sort((x, y) -> Long.compare(y.getValue().dmg, x.getValue().dmg));
        // Below the top 5, keep our own row AND any party member's row so
        // the UI can list everyone under the separator with real ranks.
        java.util.Set<String> pNames = partyNames.get();
        List<Object[]> top = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<Integer, Attacker> en : sorted) {
            rank++;
            boolean isMe = en.getKey() == myId;
            Attacker a = en.getValue();
            boolean isParty = !isMe && a.name != null && pNames.contains(a.name);
            if (rank > 5 && !isMe && !isParty) continue;
            // Mid-map ghost self: name/skin were never on the wire, but we
            // know who we are and what we fired.
            String pname = a.name != null ? a.name : (isMe ? "You" : "Player#" + en.getKey());
            int[] loadout = a.loadout != null ? a.loadout.clone() : new int[0];
            if (isMe && myWeapon > 0) {
                if (loadout.length == 0) loadout = new int[4];
                if (loadout[0] == 0) loadout[0] = myWeapon;
            }
            top.add(new Object[]{pname, a.dmg, isMe, a.icon, rank, loadout});
        }
        long fightMs = Math.max(0, e.lastHitMs - e.firstHitMs);
        cBossKills++;
        publisher.bossKilled(name, e.objectType, e.totalDmg, fightMs, top, System.currentTimeMillis());
        // Only a confirmed killing blow ends the fight for sure. A despawn
        // publish may really be a phase transition (boss reappears); clearing
        // the tally there erased everyone's damage for the rest of the fight
        // (seen live: "I was #18 mid-fight, gone from the list at the kill").
        // Left intact, the real kill re-publishes the complete leaderboard.
        if (killConfirmed) {
            e.dmg.clear();
            e.totalDmg = 0;
        }
    }

    private final HashMap<Integer, Boolean> playerTypeCache = new HashMap<>();

    /** Player detection via the asset table's Class attribute ("Player"). */
    private boolean isPlayerType(int objectType) {
        return playerTypeCache.computeIfAbsent(objectType, t -> {
            try {
                return "Player".equals(IdToAsset.getClazz(t));
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static final String[] SHINY_TIER = {"shiny", "0"};

    private final HashMap<Integer, Boolean> lootBagCache = new HashMap<>();

    /**
     * Any loot-bag container ("Loot Bag 0..9", boosts, soulbound). Restricted
     * by name on purpose: registering every Container would also read
     * persistent chests (vault, gifts) and re-announce their contents each
     * visit.
     */
    private boolean isLootBag(int objectType) {
        return lootBagCache.computeIfAbsent(objectType, t -> {
            try {
                if (!"Container".equals(IdToAsset.getClazz(t))) return false;
                String n = IdToAsset.objectName(t);
                return n != null && n.toLowerCase().contains("loot bag");
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** Shiny variants are distinct object ids carrying a SHINY label token. */
    private static boolean isShiny(int objectType) {
        try {
            String label = IdToAsset.getIdLabel(objectType);
            if (label != null) {
                for (String s : label.split(",")) {
                    if (s.equals("SHINY")) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isBoss(int objectType) {
        try {
            String label = IdToAsset.getIdLabel(objectType);
            if (label != null) {
                for (String s : label.split(",")) {
                    if (s.equals("BOSS") || s.equals("MINIBOSS")) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String displayName(int objectType) {
        try {
            String n = IdToAsset.getDisplayName(objectType);
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {
        }
        try {
            return IdToAsset.objectName(objectType);
        } catch (Exception ignored) {
        }
        return "Unknown #" + objectType;
    }

    /** Read bag contents once its inventory stats have arrived (retries a few ticks). */
    private void processPendingBags() {
        if (pendingBags.isEmpty()) return;
        ArrayList<PendingBag> retry = new ArrayList<>();
        for (PendingBag pb : pendingBags) {
            Ent bag = entities.get(pb.entityId);
            List<int[]> items = new ArrayList<>();
            if (bag != null) {
                // Per-slot unique-item data (enchantments) rides on the bag
                // entity as one comma-separated string, index-matched to the
                // inventory stats.
                String[] codes = null;
                StatData ud = bag.stats.get(StatType.UNIQUE_DATA_STRING.get());
                if (ud != null && ud.stringStatValue != null) {
                    codes = ud.stringStatValue.split(",", -1);
                }
                for (int i = 0; i < 8; i++) {
                    StatData sd = bag.stats.get(StatType.INVENTORY_0_STAT.get() + i);
                    if (sd != null && sd.statValue >= 1) {
                        int slots = (codes != null && i < codes.length)
                                ? enchantSlotCount(codes[i]) : 0;
                        items.add(new int[]{sd.statValue, isShiny(sd.statValue) ? 1 : 0, slots});
                    }
                }
            }
            if (items.isEmpty() && --pb.ticksLeft > 0) {
                retry.add(pb);
                continue;
            }
            if (!items.isEmpty()) {
                boolean anyShiny = false;
                for (int[] it : items) {
                    if (it[1] == 1) { anyShiny = true; break; }
                }
                if (pb.onlyIfShiny && !anyShiny) continue;
                String[] tier = BAG_TIERS.get(pb.objectType);
                if (tier == null) tier = SHINY_TIER; // shiny in an untracked bag
                cLootBags++;
                publisher.lootDropped(tier[0], "1".equals(tier[1]), pb.objectType, items, System.currentTimeMillis());
            }
        }
        pendingBags.clear();
        pendingBags.addAll(retry);
    }

    /**
     * Number of enchantment slots (0-4) an item's unique-data code declares.
     * The code is plain base64url (Tomato's sixBitStringToBytes is the same
     * table) decoding to little-endian [version byte][short 0x0402] followed
     * by one short per slot: -1 empty, -2 locked, >0 an enchant id; -3
     * terminates. The ubiquitous zero-slot sentinel "AAIE_f_9__3__f8="
     * decodes to an immediate terminator.
     */
    static int enchantSlotCount(String code) {
        if (code == null || code.isEmpty()) return 0;
        try {
            byte[] b = Base64.getUrlDecoder().decode(code);
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 3) return 0;
            buf.get();                             // version byte
            if (buf.getShort() != 0x0402) return 0; // not enchant-slot data
            int slots = 0;
            while (buf.remaining() >= 2) {
                short s = buf.getShort();
                if (s == -3) break;
                slots++;
            }
            return Math.min(slots, 4);
        } catch (Exception e) {
            return 0; // malformed/unknown data: just show no slots
        }
    }

    public synchronized String mapName() {
        return mapName;
    }

    /** Own avatar sprite id (skin, else class), 0 while unknown. For party profiles. */
    public synchronized int myIcon() {
        Ent p = players.get(myId);
        if (p == null) return 0;
        int skin = p.stat(StatType.SKIN_ID);
        if (skin > 0) return skin;
        return p.objectType > 0 ? p.objectType : 0;
    }

    /** Own in-game name, "" while unknown (mid-map ghost self). For party profiles. */
    public synchronized String myIgn() {
        Ent p = players.get(myId);
        String n = p == null ? null : p.name();
        return n == null ? "" : n;
    }

    // In-game names of current party members; their leaderboard rows are
    // kept below the top 5 just like our own. Supplied by WebServer.
    private volatile java.util.function.Supplier<java.util.Set<String>> partyNames =
            java.util.Collections::emptySet;

    public void setPartyNames(java.util.function.Supplier<java.util.Set<String>> s) {
        if (s != null) partyNames = s;
    }

    // ------------------------------------------------------------------
    // Capture health (sniffer watchdog) — deliberately not synchronized
    // ------------------------------------------------------------------

    /** Raw stream bytes seen on port 2050 (called per TCP chunk, pre-decrypt). */
    public void noteRaw() {
        lastRawMs = System.currentTimeMillis();
        rawSinceRestart = true;
    }

    /** A packet made it through decryption + parsing to one of our handlers. */
    public void noteDecoded() {
        lastDecodedMs = System.currentTimeMillis();
    }

    /** Ms since raw traffic was last seen (since startup if never). */
    public long rawAge(long now) {
        long t = lastRawMs;
        return now - (t == 0 ? startMs : t);
    }

    /** Ms since a packet last decoded (since startup if never). */
    public long decodedAge(long now) {
        long t = lastDecodedMs;
        return now - (t == 0 ? startMs : t);
    }

    /** True if any raw traffic arrived since the last sniffer (re)start. */
    public boolean rawSeenSinceRestart() {
        return rawSinceRestart;
    }

    public void noteSnifferRestart() {
        rawSinceRestart = false;
        snifferRestarts++;
    }

    /** Called by the packet-handler wrapper so one bad packet can't kill tracking. */
    public synchronized void recordError(Throwable t) {
        cErrors++;
        lastError = t.toString();
        System.err.println("[Tracker] handler error: " + t);
        t.printStackTrace();
    }

    /** Internal state snapshot for the /debug endpoint. */
    public synchronized Map<String, Object> debug() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version", Updater.VERSION);
        d.put("myId", myId);
        d.put("rngSeeded", rng != null);
        d.put("map", mapName);
        d.put("players", players.size());
        d.put("entities", entities.size());
        d.put("projectilesKeyed", projectiles.size());
        d.put("ownShotsRegistered", ownShots.size());
        d.put("playerShootPkts", cPlayerShoot);
        d.put("serverPlayerShootPkts", cServerShoot);
        d.put("enemyHitPkts", cEnemyHit);
        d.put("enemyHitMatched", cEnemyHitMatched);
        d.put("damagePkts", cDamagePkts);
        d.put("damageAttributed", cDamageAttributed);
        d.put("lootBags", cLootBags);
        d.put("bossKills", cBossKills);
        d.put("deaths", cDeaths);
        d.put("handlerErrors", cErrors);
        d.put("lastError", lastError);
        long now = System.currentTimeMillis();
        d.put("rawTrafficAgeSec", rawAge(now) / 1000);
        d.put("decodedPacketAgeSec", decodedAge(now) / 1000);
        d.put("snifferRestarts", snifferRestarts);
        return d;
    }
}
