package tinycat.ui;

import tinycat.Localization;
import tinycat.LocalizationKeys;
import tinycat.Utils;
import tinycat.stats.ClassStat;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.screens.stats.CharStat;

public class CharStatRenderer {
    private String info;
    private String info2;


    public CharStatRenderer(ClassStat cs) {
        this.info = CharStat.TEXT[0] + CharStat.formatHMSM(cs.playTime) + " NL ";
        if (cs.fastestTime != 0L) {
            this.info = this.info + CharStat.TEXT[13] + CharStat.formatHMSM(cs.fastestTime) + " NL ";
        }
        if (cs.averageWinningTime.mean() != 0D) {
            this.info +=
                    Localization.get(LocalizationKeys.AVG_TIME_WINS) +
                            ": " +
                            CharStat.formatHMSM((long) cs.averageWinningTime.mean()) +
                            " NL ";
        }
        
        // 显示平均卡组大小（总体和分角色都显示）
        if (cs.averageDeckSize.getSampleSize() > 0) {
            this.info += Localization.get(LocalizationKeys.AVG_DECK_SIZE) + ": #y" + String.format("%.1f", cs.averageDeckSize.mean()) + " NL ";
            this.info += Localization.get(LocalizationKeys.MIN_MAX_DECK_SIZE) + ": #y" + cs.minDeckSize + "/" + cs.maxDeckSize + " NL ";
        }
        
        // 只在分角色统计时显示连胜，总体统计不显示
        if (cs.bestWinStreak > 0 && !cs.rotate) {
            this.info = this.info + CharStat.TEXT[22] + cs.bestWinStreak + " NL ";
        }

        // 显示删牌统计（总体和分角色都显示）
        if (cs.totalCardsPurged > 0) {
            // 计算平均值
            int totalRuns = cs.numVictory + cs.numDeath;
            if (totalRuns > 0) {
                double avgPurges = (double) cs.totalCardsPurged / totalRuns;
                double avgShopPurges = (double) cs.shopPurges / totalRuns;
                double avgEventPurges = (double) cs.eventPurges / totalRuns;
                
                this.info += Localization.get(LocalizationKeys.AVG_CARDS_PURGED) + ": #y" + String.format("%.2f", avgPurges) + " NL ";
                if (cs.shopPurges > 0) {
                    this.info += Localization.get(LocalizationKeys.AVG_SHOP_PURGES) + ": #y" + String.format("%.2f", avgShopPurges) + " NL ";
                }
                if (cs.eventPurges > 0) {
                    this.info += Localization.get(LocalizationKeys.AVG_EVENT_PURGES) + ": #y" + String.format("%.2f", avgEventPurges) + " NL ";
                }
            }
        }

        this.info2 = CharStat.TEXT[17] + cs.numVictory + " NL ";
        this.info2 = this.info2 + CharStat.TEXT[18] + cs.numDeath + " NL ";
        this.info2 = this.info2 + CharStat.TEXT[19] + cs.totalFloorsClimbed + " NL ";
        this.info2 = this.info2 + CharStat.TEXT[20] + cs.bossKilled + " NL ";
        this.info2 = this.info2 + CharStat.TEXT[21] + cs.enemyKilled + " NL ";
        if (cs.averageScore.mean() > 0) {
            this.info2 += Localization.get(LocalizationKeys.AVG_SCORE) + ": #y" + String.format("%.0f", cs.averageScore.mean()) + " NL ";
        }
        if (cs.averageGold.getSampleSize() > 0) {
            this.info2 += Localization.get(LocalizationKeys.AVG_GOLD) + ": #y" + String.format("%.0f", cs.averageGold.mean()) + " NL ";
        }
        this.info2 += Localization.get(LocalizationKeys.WIN_RATE) + ": #y" + Utils.calculatePercent(cs.numVictory, cs.numDeath + cs.numVictory) + " NL ";
        this.info2 += Localization.get(LocalizationKeys.SURVIVAL_RATE_PER_ACT) + ": " +
                "#y" + cs.survivalRatePerAct.get(1).percent() + "%/" +
                cs.survivalRatePerAct.get(2).percent() + "%/" +
                cs.survivalRatePerAct.get(3).percent() + "%/" +
                cs.survivalRatePerAct.get(4).percent() + "% NL ";
    }

    public void render(SpriteBatch sb, float screenX, float renderY) {
        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                this.info,
                screenX + 75.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                this.info2,
                screenX + 675.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);
    }
}
