package tinycat.stats;

import tinycat.Utils;

import java.util.Comparator;

public class Rate<T> implements Comparable<Rate<T>>, SampleSize {
    public final T what;
    public int win = 0;
    public int loss = 0;
    
    /**
     * 次数优先比较器（用于火堆行动、商店行动）
     * 排序逻辑：次数降序 → 同次数按胜率降序 → 同胜率按字典序升序
     */
    public static final Comparator<Rate<?>> COUNT_FIRST_COMPARATOR = (r1, r2) -> {
        // 第一级：按总出现次数降序排列
        if (r1.total() != r2.total()) {
            return Integer.compare(r2.total(), r1.total());
        }
        // 第二级：次数相同时，按胜率降序排列
        if (r1.percent() != r2.percent()) {
            return r1.percent() < r2.percent() ? 1 : -1;
        }
        // 第三级：次数和胜率都相同时，按名称字典序升序排列（A-Z）
        return r1.what.toString().compareTo(r2.what.toString());
    };

    public Rate(T what) {
        this.what = what;
    }

    public int total() {
        return win + loss;
    }

    public double percent() {
        return Utils.calculatePercent(win, total());
    }

    public String winLoss() {
        return "(" + win + "/" + total() + ")";
    }

    @Override
    public int compareTo(Rate<T> rate) {
        // 默认：按胜率/选取率降序排列（用于卡牌选取率、致死率等）
        if (percent() != rate.percent()) {
            return percent() < rate.percent() ? 1 : -1;
        }
        // 第二级：胜率相同时，按次数降序排列
        if (total() != rate.total()) {
            return Integer.compare(rate.total(), this.total());
        }
        // 第三级：胜率和次数都相同时，按名称字典序升序排列（A-Z）
        return what.toString().compareTo(rate.what.toString());
    }

    @Override
    public int getSampleSize() {
        return total();
    }
}
