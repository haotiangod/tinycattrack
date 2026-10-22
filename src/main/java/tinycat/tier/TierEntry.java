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
    /** card / boss_relic / skip */
    public final String type;
    public String imagePath;

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
