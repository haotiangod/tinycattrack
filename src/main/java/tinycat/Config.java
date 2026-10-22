package tinycat;
import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Properties;

/**
 * TinyCat 模组配置管理类。
 * 
 * 使用 ModTheSpire 的 SpireConfig 持久化存储配置，配置项包括：
 * - debug: 调试模式开关（默认 false），启用后显示调试按钮
 * 
 * 配置保存在游戏目录的 TinyCat/tinycat-config 文件中。
 * 纯本地模组，无网络相关配置。
 */
public class Config {
    public static final Logger logger = LogManager.getLogger(Config.class.getName());

    private static final String DEBUG_SETTINGS = "debug";

    private final SpireConfig config;

    public Config() throws IOException {
        Properties prop = new Properties();
        prop.setProperty(DEBUG_SETTINGS, "false");

        config = new SpireConfig("TinyCat", "tinycat-config", prop);
        config.load();

        logger.info(Localization.get("msg.loading_config"));
    }

    /**
     * 获取调试模式状态。
     * @return true 如果启用了调试模式
     */
    public boolean getDebug() {
        String debug = config.getString(DEBUG_SETTINGS);
        return debug.equals("true");
    }
}
