package tinycat.tracker;

import basemod.BaseMod;
import basemod.interfaces.PostDungeonUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 独立测试模组入口：只记录局内状态，不参与 TinyCat 的统计界面。
 * 用于在校准阶段验证金币与第 1 层 HP 的精确值。
 */
@SpireInitializer
public class TinyCatTrackerTest implements PostDungeonUpdateSubscriber {
    public static final Logger logger = LogManager.getLogger(TinyCatTrackerTest.class.getName());

    private final RunStateTracker tracker = new RunStateTracker();

    public TinyCatTrackerTest() {
        BaseMod.subscribe(this);
    }

    public static void initialize() {
        new TinyCatTrackerTest();
        logger.info("TinyCatTrackerTest initialized");
    }

    @Override
    public void receivePostDungeonUpdate() {
        tracker.update();
    }
}
