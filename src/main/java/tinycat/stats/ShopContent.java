package tinycat.stats;

import java.util.List;

/**
 * 商店内容数据类
 * 用于解析 JSON 中的 shop_contents 字段
 */
public class ShopContent {
    public Integer floor;  // 商店楼层
    public List<String> relics;  // 商店中的遗物
    public List<String> cards;  // 商店中的卡牌
    public List<String> potions;  // 商店中的药水
    
    public ShopContent() {
    }
}
