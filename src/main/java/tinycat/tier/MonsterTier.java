package tinycat.tier;

/** 怪物危险度梯度：T0 为 Boss，T1 最高危，T4 最低危。 */
public enum MonsterTier {
    T0("T0", "Boss (separate)", "BOSS（单独排序）", "#e5c07b"),
    T1("T1", "Extreme danger", "极危", "#e06c75"),
    T2("T2", "High danger", "高危", "#d19a66"),
    T3("T3", "Moderate danger", "中危", "#61afef"),
    T4("T4", "Low danger", "低危", "#7f848e");

    public final String key;
    public final String labelEn;
    public final String labelZh;
    public final String color;

    MonsterTier(String key, String labelEn, String labelZh, String color) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
        this.color = color;
    }

    public boolean isBoss() {
        return this == T0;
    }
}
