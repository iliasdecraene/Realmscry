package tracker;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-class max stats from the game's own assets/xml/players.xml (generated
 * by the asset self-update pipeline). Used to compute the "6/8 maxed" badge
 * on death cards. Deliberately does NOT use Tomato's CharacterClass — its
 * static init is the known crash trap.
 *
 * The 8 stats in game order: HP, MP, ATT, DEF, SPD, DEX, VIT, WIS.
 * XML element names: MaxHitPoints, MaxMagicPoints, Attack, Defense, Speed,
 * Dexterity, HpRegen (=VIT), MpRegen (=WIS), each with a max="" attribute.
 */
final class ClassStats {

    private static final String[] XML_STATS = {
            "MaxHitPoints", "MaxMagicPoints", "Attack", "Defense",
            "Speed", "Dexterity", "HpRegen", "MpRegen"};

    private static Map<Integer, int[]> maxes; // classType -> max[8]; null until loaded

    private ClassStats() {
    }

    private static synchronized Map<Integer, int[]> load() {
        if (maxes != null) return maxes;
        Map<Integer, int[]> m = new HashMap<>();
        try {
            File f = new File("assets/xml/players.xml");
            if (f.isFile()) {
                Document doc = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(f);
                NodeList objects = doc.getElementsByTagName("Object");
                for (int i = 0; i < objects.getLength(); i++) {
                    Element obj = (Element) objects.item(i);
                    if (obj.getElementsByTagName("Player").getLength() == 0) continue;
                    int type;
                    try {
                        type = Integer.decode(obj.getAttribute("type"));
                    } catch (Exception e) {
                        continue;
                    }
                    int[] mx = new int[8];
                    boolean any = false;
                    for (int s = 0; s < 8; s++) {
                        NodeList el = obj.getElementsByTagName(XML_STATS[s]);
                        if (el.getLength() > 0) {
                            try {
                                mx[s] = Integer.parseInt(
                                        ((Element) el.item(0)).getAttribute("max").trim());
                                any = true;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (any) m.put(type, mx);
                }
            }
        } catch (Exception e) {
            System.err.println("[ClassStats] could not parse players.xml: " + e);
        }
        maxes = m;
        if (!m.isEmpty()) System.out.println("[ClassStats] max stats for " + m.size() + " classes");
        return m;
    }

    /**
     * How many of the 8 stats are maxed, given current (boosted) values and
     * their boost components; -1 when the class is unknown / xml missing.
     * base = shown stat minus equipment boost, compared to the class max.
     */
    static int maxedCount(int classType, int[] stat, int[] boost) {
        int[] mx = load().get(classType);
        if (mx == null) return -1;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            if (mx[i] > 0 && stat[i] - boost[i] >= mx[i]) n++;
        }
        return n;
    }
}
