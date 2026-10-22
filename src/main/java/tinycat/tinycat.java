package tinycat;

import basemod.*;
import basemod.interfaces.PostDungeonInitializeSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
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
 * 3. receivePostDungeonInitialize() - 地牢初始化完成回调，捕获第0层HP
 * 
 * 核心职责：
 * - 模组初始化与生命周期管理
 * - 第0层HP精确捕获（用于战损计算）
 * - 调试模式支持
 * 
 * 依赖：BaseMod（订阅机制）、RunHistoryPlus（对局数据文件）
 */
@SpireInitializer
public class tinycat implements PostInitializeSubscriber, PostDungeonInitializeSubscriber {
    public static final Logger logger = LogManager.getLogger(tinycat.class.getName());
    /** 对局历史管理器，负责加载和管理所有对局数据 */
    public static RunHistoryManager runHistoryManager;

    /** 模组配置 */
    public static Config config;

    /** 模组单例 */
    public static tinycat instance;

    /**
     * 第0层（Neow房间）结束时的HP，即进入第1层前、未进行任何操作时的血量。
     * 在 PostDungeonInitialize 时捕获，此时 dungeon 已初始化，玩家已完成 Neow 选择。
     * 如果为 null，表示尚未捕获（历史数据回退到估算法）。
     */
    public static Integer capturedFloor0HP = null;

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

        BaseMod.registerModBadge(ImageMaster.loadImage("tinycat/img/nob-32.png"), "Tiny Cat", "vmService", "Local run statistics tracking", settingsPanel);
    }

    /**
     * 在地牢初始化后捕获第0层HP（Neow房间结束后、进入第1层前的血量），
     * 并写入伴生文件 runs/{runDir}/{timestamp}.floor0hp，供 Run.java 加载时读取。
     * 此时玩家已完成 Neow 选择，尚未进行任何战斗操作。
     */
    @Override
    public void receivePostDungeonInitialize() {
        if (AbstractDungeon.player != null) {
            capturedFloor0HP = AbstractDungeon.player.currentHealth;
            logger.info("Floor 0 HP captured: " + capturedFloor0HP);

            // 写入伴生文件，供历史数据加载时精确读取
            try {
                String runDir = getRunDirName();
                long timestamp = System.currentTimeMillis() / 1000;
                String filePath = "runs/" + runDir + "/" + timestamp + ".floor0hp";
                FileHandle file = Gdx.files.local(filePath);
                file.writeString(String.valueOf(capturedFloor0HP), false);
                logger.info("Floor 0 HP written to: " + filePath);
            } catch (Exception e) {
                logger.warn("Failed to write floor 0 HP file: " + e.getMessage());
            }
        }
    }

    /**
     * 获取当前 run 的目录名，格式与 RunHistoryPlus 一致：
     * - 默认存档位(0): 仅角色名，如 "IRONCLAD"
     * - 其他存档位: "{slot}_{角色名}"，如 "1_IRONCLAD"
     */
    private static String getRunDirName() {
        int slot = CardCrawlGame.saveSlot;
        String charName = AbstractDungeon.player.chosenClass.name();
        if (slot == 0) {
            return charName;
        }
        return slot + "_" + charName;
    }

}
