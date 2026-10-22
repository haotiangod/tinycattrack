package tinycat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.core.Settings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Localization {
    private static final Gson gson = new Gson();
    private static Map<String, String> strings = new HashMap<>();
    private static String currentLanguage = null;
    
    public static void initialize() {
        // 首次加载时不指定语言，在第一次 get() 时再检测
        loadLocalization();
    }
    
    private static void loadLocalization() {
        String langFile = "uilocalization/strings.json";
        
        try {
            // 根据游戏语言选择文件
            if (Settings.language != null) {
                String newLanguage = Settings.language.toString();
                if (newLanguage.equals("ZHS") || newLanguage.equals("ZHT")) {
                    langFile = "uilocalization/strings-zh.json";
                }
                
                // 如果语言发生变化，重新加载
                if (newLanguage.equals(currentLanguage)) {
                    return; // 语言没变，不重新加载
                }
                currentLanguage = newLanguage;
            }
            
            strings.clear(); // 清空旧的缓存
            
            InputStream inputStream = Localization.class.getClassLoader().getResourceAsStream(langFile);
            if (inputStream == null) {
                tinycat.logger.error("Localization file not found: {}", langFile);
                tinycat.logger.warn("Falling back to default language file");
                // 尝试加载默认的英文文件
                inputStream = Localization.class.getClassLoader().getResourceAsStream("uilocalization/strings.json");
                if (inputStream == null) {
                    tinycat.logger.error("Default localization file also not found!");
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
            
            // Load UI labels
            if (root.has("ui_labels")) {
                JsonObject uiLabels = root.getAsJsonObject("ui_labels");
                for (Map.Entry<String, JsonElement> entry : uiLabels.entrySet()) {
                    strings.put("ui." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load profile options
            if (root.has("profile_options")) {
                JsonObject profileOptions = root.getAsJsonObject("profile_options");
                for (Map.Entry<String, JsonElement> entry : profileOptions.entrySet()) {
                    strings.put("profile." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load messages
            if (root.has("messages")) {
                JsonObject messages = root.getAsJsonObject("messages");
                for (Map.Entry<String, JsonElement> entry : messages.entrySet()) {
                    strings.put("msg." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load stat labels
            if (root.has("stat")) {
                JsonObject stat = root.getAsJsonObject("stat");
                for (Map.Entry<String, JsonElement> entry : stat.entrySet()) {
                    strings.put("stat." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load export labels
            if (root.has("export")) {
                JsonObject export = root.getAsJsonObject("export");
                for (Map.Entry<String, JsonElement> entry : export.entrySet()) {
                    strings.put("export." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load result labels
            if (root.has("result")) {
                JsonObject result = root.getAsJsonObject("result");
                for (Map.Entry<String, JsonElement> entry : result.entrySet()) {
                    strings.put("result." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            // Load mod labels
            if (root.has("mod")) {
                JsonObject mod = root.getAsJsonObject("mod");
                for (Map.Entry<String, JsonElement> entry : mod.entrySet()) {
                    strings.put("mod." + entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            tinycat.logger.info("Localization loaded: {}", langFile);
            tinycat.logger.info("Loaded {} localization entries", strings.size());
            
        } catch (Exception e) {
            tinycat.logger.error("Failed to load localization: {}", e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static String get(String key) {
        // 如果还没有加载，或者语言变化了，重新加载
        if (strings.isEmpty() || (Settings.language != null && !Settings.language.toString().equals(currentLanguage))) {
            loadLocalization();
        }
        return strings.get(key);
    }
    
    public static String get(String key, Object... args) {
        String value = get(key);
        if (value == null) return key;
        if (args.length > 0) {
            return String.format(value, args);
        }
        return value;
    }
}
