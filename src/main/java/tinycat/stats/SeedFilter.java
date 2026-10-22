package tinycat.stats;

/**
 * 统计面板与导出共用的种子局筛选模式。
 */
public enum SeedFilter {
    ALL("all", "All runs (seeded included)", "全部对局（含种子）"),
    EXCLUDE_SEEDED("exclude_seeded", "Exclude seeded runs", "不含种子局"),
    ONLY_SEEDED("only_seeded", "Seeded runs only", "仅种子局");

    public final String key;
    public final String labelEn;
    public final String labelZh;

    SeedFilter(String key, String labelEn, String labelZh) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
    }

    public boolean accepts(Run run) {
        if (run == null) {
            return false;
        }
        switch (this) {
            case EXCLUDE_SEEDED:
                return !run.seeded;
            case ONLY_SEEDED:
                return run.seeded;
            default:
                return true;
        }
    }
}
