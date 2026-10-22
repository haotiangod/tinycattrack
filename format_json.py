import json
import os

def smart_format_json(obj, indent=4, level=0):
    """智能格式化 JSON，空数组紧凑"""
    if isinstance(obj, list):
        # 检查是否是空数组或全是空数组
        if len(obj) == 0:
            return "[]"
        all_empty = all(isinstance(item, list) and len(item) == 0 for item in obj)
        if all_empty:
            return "[" + ", ".join(["[]"] * len(obj)) + "]"
        
        # 普通数组，分行
        items = []
        for item in obj:
            formatted = smart_format_json(item, indent, level + 1)
            items.append(formatted)
        ind = " " * (indent * (level + 1))
        return "[\n" + ",\n".join([ind + item for item in items]) + "\n" + " " * (indent * level) + "]"
    elif isinstance(obj, dict):
        if len(obj) == 0:
            return "{}"
        items = []
        for k, v in obj.items():
            key = json.dumps(k, ensure_ascii=False)
            val = smart_format_json(v, indent, level + 1)
            items.append(f"{key}: {val}")
        ind = " " * (indent * (level + 1))
        return "{\n" + ",\n".join([ind + item for item in items]) + "\n" + " " * (indent * level) + "}"
    else:
        return json.dumps(obj, ensure_ascii=False)

def main():
    test_dir = r"C:\Users\Administrator\Desktop\tinycat\sts-stat-tracker-master\runs\test"
    for filename in os.listdir(test_dir):
        if filename.endswith(".json"):
            filepath = os.path.join(test_dir, filename)
            print(f"Processing {filename}...")
            with open(filepath, "r", encoding="utf-8-sig") as f:
                data = json.load(f)
            formatted = smart_format_json(data)
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(formatted)
            print(f"Done {filename}")
    print("All files processed!")

if __name__ == "__main__":
    main()