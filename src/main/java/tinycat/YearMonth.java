package tinycat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * 年月工具类，用于统计面板的日期范围筛选。
 * 
 * 封装年份和月份，提供日期比较、格式化输出功能。
 * 主要用于 MoreStatsScreen 中的起止日期下拉菜单数据源。
 * 
 * 格式示例：2026-06
 */
public class YearMonth implements Comparable<YearMonth> {
    public final int year;
    public final int month;

    public YearMonth(int year, int month) {
        this.year = year;
        this.month = month;
    }

    /**
     * 从 Date 对象转换。
     * 使用系统默认时区。
     */
    public static YearMonth fromDate(Date date) {
        LocalDate localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return new YearMonth(localDate.getYear(), localDate.getMonthValue());
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        YearMonth that = (YearMonth) o;
        return year == that.year && month == that.month;
    }

    @Override
    public int hashCode() {
        return 31 * year + month;
    }

    /**
     * 格式化为 YYYY-MM 格式，月份补零。
     * 示例：2026-06
     */
    public String toString() {
        String monthString = month < 10 ? "0" + month : "" + month;
        return year + "-" + monthString;
    }

    @Override
    public int compareTo(YearMonth yearMonth) {
        if (this.year == yearMonth.year) {
            return this.month - yearMonth.month;
        }
        return this.year - yearMonth.year;
    }
}
