package tracker;

import packets.PacketType;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.DamagePacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.NewTickPacket;
import packets.incoming.ServerPlayerShootPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.EnemyHitPacket;
import packets.outgoing.PlayerShootPacket;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.register.Register;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.net.BindException;
import java.util.List;

/**
 * Standalone RotMG damage/loot tracker built on the RealmShark library.
 * Ships as a single self-contained jar: double-click it, the sniffer and the
 * local web UI start, and a small launcher window offers an open-in-browser
 * button. No other program needs to run.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Updater.run(args); // may relaunch into a newer version and exit
        AssetBootstrap.ensure();

        boolean gui = !GraphicsEnvironment.isHeadless()
                && System.getProperty("tracker.nogui") == null;

        WebServer web;
        try {
            web = new WebServer();
        } catch (BindException e) {
            // A tracker is already running — just bring up its UI.
            Launcher.openBrowser(WebServer.URL);
            if (gui) {
                JOptionPane.showMessageDialog(null,
                        "Realmscry is already running.\nOpened " + WebServer.URL + " instead.",
                        "Realmscry", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }

        Launcher launcher = gui ? new Launcher(WebServer.URL) : null;

        // Forward events to the web UI; mirror map changes onto the launcher.
        GameState.Publisher publisher = new GameState.Publisher() {
            @Override public void lootDropped(String tier, boolean boosted, int bagType, List<int[]> items, long ts) {
                web.lootDropped(tier, boosted, bagType, items, ts);
                Sounds.playBag(tier);
            }
            @Override public void bossKilled(String name, int bossType, long total, long fightMs, List<Object[]> top, long ts) {
                web.bossKilled(name, bossType, total, fightMs, top, ts);
            }
            @Override public void died(GameState.Death death) {
                web.died(death);
            }
            @Override public void mapChanged(String mapName) {
                web.mapChanged(mapName);
                if (launcher != null) launcher.setMap(mapName);
            }
        };
        GameState state = new GameState(publisher);
        web.setState(state);
        Sounds.init(); // preload clips off the capture thread

        PartyClient party = new PartyClient(web);
        party.setProfileSource(state::myIcon, state::myIgn);
        state.setPartyNames(web::partyMemberIgns);
        web.setParty(party);
        party.autoRejoin(); // reconnect to a saved party silently

        GuildClient guild = new GuildClient();
        guild.setProfileSource(state::myIcon, state::myIgn, state::myAccountId);
        web.setGuild(guild);

        if (!GraphicsEnvironment.isHeadless()) {
            web.setOverlay(new OverlayManager(web, guild)); // in-game overlay
        }

        Register r = Register.INSTANCE;
        r.register(PacketType.MAPINFO, safe(state, p -> state.mapInfo((MapInfoPacket) p)));
        r.register(PacketType.CREATE_SUCCESS, safe(state, p -> state.createSuccess((CreateSuccessPacket) p)));
        r.register(PacketType.UPDATE, safe(state, p -> state.update((UpdatePacket) p)));
        r.register(PacketType.NEWTICK, safe(state, p -> state.newTick((NewTickPacket) p)));
        r.register(PacketType.PLAYERSHOOT, safe(state, p -> state.playerShoot((PlayerShootPacket) p)));
        r.register(PacketType.SERVERPLAYERSHOOT, safe(state, p -> state.serverPlayerShoot((ServerPlayerShootPacket) p)));
        r.register(PacketType.ENEMYHIT, safe(state, p -> state.enemyHit((EnemyHitPacket) p)));
        r.register(PacketType.DAMAGE, safe(state, p -> state.damage((DamagePacket) p)));
        r.register(PacketType.DEATH, safe(state, p -> state.death((packets.incoming.DeathPacket) p)));

        // Heartbeat 1: raw reassembled port-2050 bytes, before decryption.
        // The logger subscriber fires on every stream chunk the sniffer emits.
        Register.INSTANCE.subscribePacketLogger(log -> state.noteRaw());

        System.out.println("[Tracker] Starting packet sniffer (port 2050, TCP)...");
        PacketProcessor processor = new PacketProcessor();
        processor.start();
        System.out.println("[Tracker] Running. UI at " + WebServer.URL);

        startCaptureWatchdog(state, processor);

        Launcher.openBrowser(WebServer.URL);
    }

    /**
     * A game disconnect can leave the sniffer's stream/cipher state machine
     * permanently stuck (straggler packets from the dead connection corrupt
     * the new stream, a lost segment stalls reassembly, or the reconnect goes
     * out an interface the sniffer already closed). None of that heals by
     * itself, so watch two heartbeats and rebuild the sniffer when they say
     * the capture is broken. A restart while disconnected is free: handlers
     * stay registered in the Register singleton, the next MapInfo resets
     * GameState, and mid-map the bulletId fallback keeps damage flowing.
     */
    private static void startCaptureWatchdog(GameState state, PacketProcessor initial) {
        Thread watchdog = new Thread(() -> {
            PacketProcessor proc = initial;
            long lastRestartMs = 0;
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                long rawAge = state.rawAge(now);
                long decodedAge = state.decodedAge(now);
                // Stalled: encrypted bytes still flowing but nothing decodes —
                // cipher/stream desync (dirty reconnect). In-game, NewTick
                // decodes ~5x/second, so 20 s of decode silence with traffic
                // flowing is unambiguous. (Was 45 s: a real white bag was
                // missed inside that window.)
                boolean stalled = rawAge < 15_000 && decodedAge > 20_000;
                // Silent: traffic was seen since the last (re)start but the
                // wire has gone quiet — disconnect, or the connection moved to
                // an interface this sniffer no longer captures. Restarting
                // reopens all interfaces; harmless if the game is just closed
                // (rawSeenSinceRestart stays false, so it fires only once).
                boolean silent = rawAge > 60_000 && state.rawSeenSinceRestart();
                boolean cooledDown = now - lastRestartMs > 30_000;
                if (!cooledDown || (!stalled && !silent)) continue;

                System.out.println("[Tracker] Capture " + (stalled ? "stalled" : "silent")
                        + " (raw " + rawAge / 1000 + "s ago, decoded " + decodedAge / 1000
                        + "s ago) — restarting sniffer");
                try {
                    proc.stopSniffer();
                    proc.interrupt(); // frees the processor thread from wait()
                } catch (Throwable t) {
                    state.recordError(t);
                }
                try {
                    Thread.sleep(500); // let capture threads die before reopening
                } catch (InterruptedException e) {
                    return;
                }
                state.noteSnifferRestart();
                proc = new PacketProcessor();
                proc.start();
                lastRestartMs = System.currentTimeMillis();
            }
        }, "capture-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Wraps a handler so a single bad packet logs instead of killing tracking. */
    private static packets.packetcapture.register.IPacketListener<packets.Packet> safe(
            GameState state, java.util.function.Consumer<packets.Packet> handler) {
        return p -> {
            state.noteDecoded(); // heartbeat 2: the packet decrypted + parsed
            try {
                handler.accept(p);
            } catch (Throwable t) {
                state.recordError(t);
            }
        };
    }
}
