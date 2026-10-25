package tinycat.tier;

/**
 * Tier List 的单条数据。
 *
 * offered / picked 是原始计数，rate 是 0-100 的百分比（未四舍五入，仅展示时格式化）。
 * imagePath 是导出包内的相对路径，例如 images/cards/anger.png；
 * 图片导出阶段才会赋值，CSV/JSON/HTML 生成时读取。
 */
public class TierEntry {
    public final String id;
    public final String nameEn;
    public final String nameZh;
    public final int offered;
    public final int picked;
    public final double rate;
    public final Tier tier;
    /** card / boss_relic / relic / potion / action / neow / other */
    public final String type;
    public String imagePath;
    /**
     * 占位图上要写的 ASCII 文字（只有没有真实图源的条目才用：火堆行动类型、尼奥奖励、药水等）。
     * 像素字体只支持 ASCII，中文概况请放 nameZh。
     */
    public String imageText;
    /** 补充口径，例如火堆升级目标的「抓取 X/Y」；为空则不显示。 */
    public String context = "";
    /** 标准化率（%, Bradley-Terry）；< 0 表示该表没有标准化口径。 */
    public double standardized = -1.0D;
    public double stdLow = 0.0D;
    public double stdHigh = 0.0D;

    public TierEntry(String id,
                     String nameEn,
                     String nameZh,
                     int offered,
                     int picked,
                     double rate,
                     Tier tier,
                     String type) {
        this.id = id;
        this.nameEn = nameEn;
        this.nameZh = nameZh;
        this.offered = offered;
        this.picked = picked;
        this.rate = rate;
        this.tier = tier;
        this.type = type;
    }

    public String displayName(boolean chinese) {
        if (chinese && nameZh != null && !nameZh.isEmpty()) {
            return nameZh;
        }
        return nameEn;
    }
}
