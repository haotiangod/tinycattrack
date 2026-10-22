package tinycat;

import com.megacrit.cardcrawl.cards.AbstractCard;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 通用工具类，提供反射操作、数学计算、卡牌名称标准化等辅助功能。
 * 
 * 主要功能：
 * - 反射工具：invoke / getField / setField（用于访问游戏私有API）
 * - 数学工具：calculatePercent（百分比计算）/ round（四舍五入）
 * - 卡牌工具：normalizeCardName（名称标准化）/ cardUpgrade*（升级辅助）
 * 
 * 卡牌名称映射统一由 {@link CardNameNormalizer} 维护。
 */
public class Utils {

    /**
     * 通过反射调用私有方法。
     * @param obj 目标对象
     * @param clz 目标类（用于寻找方法声明）
     * @param m   方法名
     * @param args 方法参数
     */
    public static void invoke(Object obj, Class<?> clz, String m, Object... args) {
        Method method = null;
        try {
            method = clz.getDeclaredMethod(m);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        method.setAccessible(true);
        try {
            method.invoke(obj, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 通过反射获取私有字段值。
     * @param obj  目标对象
     * @param clz  目标类（用于寻找字段声明）
     * @param name 字段名
     * @return 字段值
     */
    @SuppressWarnings("unchecked")
    public static <A> A getField(Object obj, Class<?> clz, String name) {
        Field field = null;
        A result = null;
        try {
            field = clz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        field.setAccessible(true);
        try {
            result = (A) field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /**
     * 通过反射设置私有字段值。
     * @param obj   目标对象
     * @param clz   目标类（当前未使用，保留参数兼容性）
     * @param name  字段名
     * @param value 新值
     */
    public static <A> void setField(Object obj, Class<?> clz, String name, A value) {
        Field field = null;
        try {
            field = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        field.setAccessible(true);
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 计算百分比。
     * @param value 分子
     * @param total 分母
     * @return 百分比值（保留3位小数），total 为 0 时返回 0
     */
    public static double calculatePercent(int value, int total) {
        if (total == 0) {
            return 0;
        }
        double rate = (100D * value) / total;
        return round(rate, 3);
    }

    /**
     * 四舍五入到指定小数位。
     * @param value  原始值
     * @param places 保留小数位数
     * @return 四舍五入后的值
     */
    public static double round(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    /**
     * 标准化卡牌名称：去除升级后缀（+1/+2），并应用名称映射表。
     * 例如："Adaptation+1" -> "Rushdown"
     */
    public static String normalizeCardName(String s) {
        String normal = s.split("\\+")[0];
        return CardNameNormalizer.normalize(normal);
    }

    /** 调用卡牌的 upgradeName() 私有方法 */
    public static void cardUpgradeName(AbstractCard card) {
        invoke(card, AbstractCard.class, "upgradeName");
    }

    /** 调用卡牌的 createCardImage() 私有方法 */
    public static void cardCreateCardImage(AbstractCard card) {
        invoke(card, AbstractCard.class, "createCardImage");
    }

    /** 增加卡牌格挡值并标记为已升级 */
    public static void cardUpgradeBlock(AbstractCard card, int amount) {
        card.baseBlock += amount;
        card.upgradedBlock = true;
    }

    /** 增加卡牌伤害值并标记为已升级 */
    public static void cardUpgradeDamage(AbstractCard card, int amount) {
        card.baseDamage += amount;
        card.upgradedDamage = true;
    }

    /** 增加卡牌魔法数字并标记为已升级 */
    public static void cardUpgradeMagicNumber(AbstractCard card, int amount) {
        card.baseMagicNumber += amount;
        card.magicNumber = card.baseMagicNumber;
        card.upgradedMagicNumber = true;
    }

}
