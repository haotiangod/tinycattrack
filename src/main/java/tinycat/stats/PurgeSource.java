package tinycat.stats;

/**
 * 删牌来源枚举
 */
public enum PurgeSource {
    NEOW("尼奥斯开局"),
    CAMPFIRE("篝火"),
    EVENT("事件"),
    RELIC("遗物"),
    SHOP("商店"),
    EMPTY_CAGE("空鸟笼"),
    OTHER("其他");
    
    private final String chineseName;
    
    PurgeSource(String chineseName) {
        this.chineseName = chineseName;
    }
    
    public String getChineseName() {
        return chineseName;
    }
}
