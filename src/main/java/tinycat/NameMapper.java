package tinycat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 卡牌和遗物名称映射工具类
 * 从 location/chinese_english_mapping.json 加载汉化映射
 */
public class NameMapper {
    private static final Gson gson = new Gson();
    private static Map<String, String> englishToChinese = new HashMap<>();
    private static Map<String, String> chineseToEnglish = new HashMap<>();
    
    // 缓存已翻译的结果，避免重复计算
    private static Map<String, String> translationCache = new HashMap<>();
    
    // 不区分大小写的索引（用于快速查找）
    private static Map<String, String> caseInsensitiveIndex = new HashMap<>();
    
    // 标记是否已初始化
    private static boolean initialized = false;
    
    public static void initialize() {
        if (initialized) {
            return; // 避免重复初始化
        }
        
        try {
            // 注意：由于 build.gradle.kts 配置，location 目录的内容会被复制到 JAR 根目录
            // 所以路径应该是 "chinese_english_mapping.json" 而不是 "location/chinese_english_mapping.json"
            String mappingFile = "chinese_english_mapping.json";
            
            InputStream inputStream = NameMapper.class.getClassLoader().getResourceAsStream(mappingFile);
            if (inputStream == null) {
                tinycat.logger.error("Name mapping file not found: {}", mappingFile);
                tinycat.logger.error("Trying to load from classpath root...");
                // 尝试从类路径根目录加载
                inputStream = NameMapper.class.getResourceAsStream("/" + mappingFile);
                if (inputStream == null) {
                    tinycat.logger.error("Name mapping file not found in classpath either");
                    tinycat.logger.error("Card and relic names will not be translated");
                    return;
                }
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder jsonContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }
            reader.close();
            
            JsonObject root = gson.fromJson(jsonContent.toString(), JsonObject.class);
            
            // 遍历所有键值对，建立双向映射
            // 支持两种格式："英文" : "中文" 或 "中文" : "英文"
            // 自动检测哪个是英文，哪个是中文
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();
                // 跳过注释键
                if (key.contains("===") || key.equals("...")) {
                    continue;
                }
                
                String value = entry.getValue().getAsString();
                if (value.isEmpty()) {
                    continue;
                }
                
                // 自动检测语言方向
                // 中文字符的 Unicode 范围：\u4e00-\u9fff
                boolean keyIsChinese = isChinese(key);
                boolean valueIsChinese = isChinese(value);
                
                String englishName, chineseName;
                
                if (keyIsChinese && !valueIsChinese) {
                    // 格式："中文" : "English"
                    chineseName = key;
                    englishName = value;
                } else if (!keyIsChinese && valueIsChinese) {
                    // 格式："English" : "中文"
                    englishName = key;
                    chineseName = value;
                } else {
                    // 无法识别或混合，跳过
                    tinycat.logger.warn("Skipping ambiguous mapping: {} -> {}", key, value);
                    continue;
                }
                
                // 英文 -> 中文
                englishToChinese.put(normalizeEnglish(englishName), chineseName);
                // 中文 -> 英文
                chineseToEnglish.put(chineseName, englishName);
                
                // 构建不区分大小写的索引（用于快速查找）
                caseInsensitiveIndex.put(normalizeEnglish(englishName).toLowerCase(), chineseName);
            }
            
            tinycat.logger.info("===========================================");
            tinycat.logger.info("Name mapping loaded successfully!");
            tinycat.logger.info("Total entries: {}", englishToChinese.size());
            tinycat.logger.info("Case-insensitive index size: {}", caseInsensitiveIndex.size());
            tinycat.logger.info("Sample translations:");
            tinycat.logger.info("  Apotheosis -> {}", englishToChinese.get(normalizeEnglish("Apotheosis")));
            tinycat.logger.info("  Finesse -> {}", englishToChinese.get(normalizeEnglish("Finesse")));
            tinycat.logger.info("  AccursedBlacksmith -> {}", englishToChinese.get(normalizeEnglish("AccursedBlacksmith")));
            tinycat.logger.info("===========================================");
            
            initialized = true;
            
        } catch (Exception e) {
            tinycat.logger.error("Failed to load name mapping: {}", e.getMessage());
            tinycat.logger.error("Exception details:", e);
            e.printStackTrace();
            // 即使加载失败也标记为已初始化，避免重复尝试
            initialized = true;
        }
    }
    
    /**
     * 将英文名称翻译为中文
     * @param englishName 英文名称
     * @return 中文名称（如果找不到则返回原文）
     */
    public static String toChinese(String englishName) {
        if (englishName == null || englishName.isEmpty()) {
            return englishName;
        }
        
        // 1. 检查缓存
        String cached = translationCache.get(englishName);
        if (cached != null) {
            return cached;
        }
        
        // 2. 尝试不区分大小写匹配（使用索引，O(1) 复杂度）- 这是最常见的情况
        String normalized = normalizeEnglish(englishName);
        String chinese = caseInsensitiveIndex.get(normalized.toLowerCase());
        if (chinese != null) {
            translationCache.put(englishName, chinese);
            return chinese;
        }
        
        // 3. 尝试精确匹配（标准化后）
        chinese = englishToChinese.get(normalized);
        if (chinese != null) {
            translationCache.put(englishName, chinese);
            return chinese;
        }
        
        // 4. 尝试驼峰转空格匹配 (如 "InfernalBlade" -> "Infernal Blade")
        String withSpaces = camelToSpaces(englishName);
        String normalizedWithSpaces = normalizeEnglish(withSpaces);
        chinese = caseInsensitiveIndex.get(normalizedWithSpaces.toLowerCase());
        if (chinese != null) {
            translationCache.put(englishName, chinese);
            return chinese;
        }
        
        // 5. 尝试移除所有空格/下划线后匹配 (如 "Dark Embrace" -> "DarkEmbrace")
        String noSpaces = englishName.replaceAll("[\\s_-]+", "");
        String normalizedNoSpaces = normalizeEnglish(noSpaces);
        chinese = caseInsensitiveIndex.get(normalizedNoSpaces.toLowerCase());
        if (chinese != null) {
            translationCache.put(englishName, chinese);
            return chinese;
        }
        
        // 找不到翻译，返回原文
        translationCache.put(englishName, englishName);
        return englishName;
    }
    
    /**
     * 将驼峰命名转换为空格分隔 (如 "InfernalBlade" -> "Infernal Blade")
     */
    private static String camelToSpaces(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
    
    /**
     * 将空格分隔转换为驼峰命名 (如 "Pommel Strike" -> "PommelStrike")
     */
    private static String spacesToCamel(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }
        return result.toString();
    }
    
    /**
     * 将中文名称翻译为英文
     * @param chineseName 中文名称
     * @return 英文名称（如果找不到则返回原文）
     */
    public static String toEnglish(String chineseName) {
        if (chineseName == null || chineseName.isEmpty()) {
            return chineseName;
        }
        
        // 1. 尝试精确匹配
        String english = chineseToEnglish.get(chineseName);
        if (english != null) {
            return english;
        }
        
        // 2. 尝试忽略空格匹配
        String noSpaces = chineseName.replaceAll("\\s+", "");
        for (Map.Entry<String, String> entry : chineseToEnglish.entrySet()) {
            if (entry.getKey().replaceAll("\\s+", "").equals(noSpaces)) {
                return entry.getValue();
            }
        }
        
        return chineseName;
    }
    
    /**
     * 检测字符串是否包含中文字符
     * @param str 待检测的字符串
     * @return 如果包含中文字符返回 true
     */
    private static boolean isChinese(String str) {
        for (char c : str.toCharArray()) {
            // 检查是否在CJK统一表意文字范围内
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 标准化英文名称：转小写、移除所有空格和下划线
     * @param english 英文名称
     * @return 标准化后的英文名称
     */
    private static String normalizeEnglish(String english) {
        if (english == null) {
            return "";
        }
        // 转小写、移除空格、下划线、连字符等
        return english.toLowerCase()
                     .replaceAll("[\\s_-]+", "");
    }
    
    /**
     * 检查是否有中文翻译（使用模糊匹配）
     * 支持多种格式：驼峰命名、空格分隔、下划线分隔等
     * @param englishName 英文名称
     * @return 是否有翻译
     */
    public static boolean hasTranslation(String englishName) {
        return existsInMapping(englishName);
    }
    
    /**
     * 检查术语是否存在于映射文件中（使用模糊匹配）
     * 支持多种格式匹配：驼峰命名、空格分隔、下划线分隔等
     * @param term 要检查的术语（英文或中文）
     * @return 是否存在翻译（即使格式不同，只要标准化后匹配就算存在）
     */
    public static boolean existsInMapping(String term) {
        if (term == null || term.isEmpty()) {
            return false;
        }
        
        String normalized = normalizeEnglish(term);
        
        // 1. 检查精确匹配（标准化后）
        if (englishToChinese.containsKey(normalized)) {
            return true;
        }
        
        // 2. 检查忽略大小写匹配
        for (String key : englishToChinese.keySet()) {
            if (key.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        
        // 3. 检查驼峰转空格匹配
        String withSpaces = camelToSpaces(term);
        String normalizedWithSpaces = normalizeEnglish(withSpaces);
        if (englishToChinese.containsKey(normalizedWithSpaces)) {
            return true;
        }
        
        // 4. 检查小写 + 空格匹配
        for (String key : englishToChinese.keySet()) {
            if (key.equalsIgnoreCase(normalizedWithSpaces)) {
                return true;
            }
        }
        
        // 5. 检查空格转驼峰匹配
        String withoutSpaces = spacesToCamel(term);
        String normalizedWithoutSpaces = normalizeEnglish(withoutSpaces);
        if (englishToChinese.containsKey(normalizedWithoutSpaces)) {
            return true;
        }
        
        // 6. 检查移除所有空格后匹配
        String noSpaces = term.replaceAll("\\s+", "");
        String normalizedNoSpaces = normalizeEnglish(noSpaces);
        if (englishToChinese.containsKey(normalizedNoSpaces)) {
            return true;
        }
        
        // 7. 检查小写 + 移除空格匹配
        for (String key : englishToChinese.keySet()) {
            String normalizedKey = normalizeEnglish(key.replaceAll("\\s+", ""));
            if (normalizedKey.equalsIgnoreCase(normalizedNoSpaces)) {
                return true;
            }
        }
        
        // 8. 检查下划线转空格匹配
        String withUnderscoresAsSpaces = term.replaceAll("_", " ");
        String normalizedUnderscores = normalizeEnglish(withUnderscoresAsSpaces);
        if (englishToChinese.containsKey(normalizedUnderscores)) {
            return true;
        }
        
        // 9. 检查忽略下划线匹配
        String noUnderscores = term.replaceAll("_", "");
        String normalizedNoUnderscores = normalizeEnglish(noUnderscores);
        for (String key : englishToChinese.keySet()) {
            String normalizedKey = normalizeEnglish(key.replaceAll("_", ""));
            if (normalizedKey.equalsIgnoreCase(normalizedNoUnderscores)) {
                return true;
            }
        }
        
        return false;
    }
}
