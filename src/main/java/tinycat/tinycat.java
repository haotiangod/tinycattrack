package tinycat;

import basemod.*;
import basemod.interfaces.PostDungeonUpdateSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * TinyCat 模组主入口类。
 *
 * 生命周期：
 * 1. initialize() - ModTheSpire 初始化入口，创建实例、初始化本地化和名称映射
 * 2. receivePostInitialize() - BaseMod 初始化完成回调，创建 RunHistoryManager 和设置面板
 * 3. receivePostDungeonUpdate() - 每帧记录本局起始 HP 与累计金币
 *
 * 依赖：BaseMod（订阅机制）、RunHistoryPlus（对局数据文件）
 */
@SpireInitializer
public class tinycat implements PostInitializeSubscriber, PostDungeonUpdateSubscriber {
    public static final Logger logger = LogManager.getLogger(tinycat.class.getName());
    /** 对局历史管理器，负责加载和管理所有对局数据 */
    public static RunHistoryManager runHistoryManager;

    /** 模组配置 */
    public static Config config;

    /** 模组单例 */
    public static tinycat instance;

    private final RunStateTracker stateTracker = new RunStateTracker();

    public tinycat() {
        try {
            config = new Config();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        BaseMod.subscribe(this);
    }

    /**
     * ModTheSpire 初始化入口。
     * 由 @SpireInitializer 注解标记，ModTheSpire 在加载模组时自动调用。
     */
    public static void initialize() {
        instance = new tinycat();
        Localization.initialize();
        NameMapper.initialize();
    }

    /**
     * BaseMod PostInitialize 回调。
     * 注册模组徽章和设置面板。
     */
    @Override
    public void receivePostInitialize() {
        runHistoryManager = new RunHistoryManager();

        ModPanel settingsPanel = new ModPanel();
        float x = 400;
        float y = 750;

        // 纯本地模组，仅保留调试按钮（如果启用）
        if (config.getDebug()) {
            ModLabeledButton debugButton = new ModLabeledButton(Localization.get("ui.debug_button"), x, y, settingsPanel, btn -> {
                logger.info("Debug mode enabled - local stats only");
            });
            settingsPanel.addUIElement(debugButton);
        }

        BaseMod.registerModBadge(ImageMaster.loadImage("tinycat/img/nob-32.png"), "Tiny Cat", "tinycat", "Local run statistics tracking", settingsPanel);
    }

    /**
     * 每帧更新本局状态文件。地基初始化（Neow 前）时不做任何记录，
     * 进入第 1 层后才开始捕获。
     */
    @Override
    public void receivePostDungeonUpdate() {
        stateTracker.update();
    }

}
