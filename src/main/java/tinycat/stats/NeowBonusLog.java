package tinycat.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * 尼奥斯开局选择日志
 */
public class NeowBonusLog {
    public List<String> cardsObtained = new ArrayList<>();
    public List<String> cardsUpgraded = new ArrayList<>();
    public List<String> cardsRemoved = new ArrayList<>();
    public List<String> cardsTransformed = new ArrayList<>();
    public List<String> relicsObtained = new ArrayList<>();
    public int maxHpGained;
    public int goldGained;
    public int damageTaken;
    public int goldLost;
    public int maxHpLost;
}
