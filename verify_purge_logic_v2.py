#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
删牌统计逻辑校验脚本
验证 1-4_test.json 的删牌数据是否与代码逻辑一致
"""

import json
import os

def analyze_purge_data(run_data, filename):
    """分析单个对局的删牌数据"""
    print(f"\n{'='*60}")
    print(f"【{filename}】")
    print(f"{'='*60}")
    
    # 1. 商店删牌 (itemsPurged)
    items_purged = run_data.get('items_purged', [])
    shop_purges = len(items_purged) if items_purged else 0
    print(f'\n【1. 商店删牌 shopPurges】: {shop_purges} 张')
    if items_purged:
        for i, card in enumerate(items_purged, 1):
            floor = run_data.get('items_purged_floors', [None] * len(items_purged))[i-1] if 'items_purged_floors' in run_data else None
            print(f'  {i}. {card} (楼层：{floor})')
    
    # 2. 事件删牌 (event_stats[].cards_removed)
    event_stats = run_data.get('event_choices', [])
    event_purges = []
    for event in event_stats:
        if event.get('cards_removed'):
            for card in event['cards_removed']:
                event_purges.append({
                    'card': card,
                    'event': event.get('event_name', 'N/A'),
                    'floor': event.get('floor', 'N/A')
                })
    
    print(f'\n【2. 事件删牌 eventPurges】: {len(event_purges)} 张')
    if event_purges:
        for ep in event_purges:
            print(f'  - {ep["card"]} (事件：{ep["event"]}, 楼层：{ep["floor"]})')
    else:
        print('  (无)')
    
    # 3. 开局删牌 (neow_bonus_log.cardsRemoved)
    neow_bonus_log = run_data.get('neow_bonus_log', {})
    neow_purges = neow_bonus_log.get('cardsRemoved', [])
    print(f'\n【3. 开局删牌 neowPurges】: {len(neow_purges)} 张')
    if neow_purges:
        for card in neow_purges:
            print(f'  - {card}')
    else:
        print('  (无)')
    
    # 4. 篝火删牌 (campfire_choices key="PURGE")
    campfire_choices = run_data.get('campfire_choices', [])
    campfire_purges = []
    for choice in campfire_choices:
        if choice.get('key') == 'PURGE' and choice.get('data'):
            campfire_purges.append({
                'card': choice['data'],
                'floor': choice.get('floor', 'N/A')
            })
    
    print(f'\n【4. 篝火删牌 campfirePurges】: {len(campfire_purges)} 张')
    if campfire_purges:
        for cp in campfire_purges:
            print(f'  - {cp["card"]} (楼层：{cp["floor"]})')
    else:
        print('  (无)')
    
    # 5. 空鸟笼 Boss 遗物 (强制 +2，JSON 不记录)
    relics = run_data.get('relics', [])
    has_empty_cage = 'Empty Cage' in relics
    empty_cage_purges = 2 if has_empty_cage else 0
    
    print(f'\n【5. Boss 遗物删牌 emptyCagePurges】: {empty_cage_purges} 张')
    if has_empty_cage:
        print(f'  ✓ 检测到空鸟笼遗物，强制 +2 补偿')
    else:
        print(f'  ✗ 未检测到空鸟笼遗物')
    
    # 总计
    total_from_json = shop_purges + len(event_purges) + len(neow_purges) + len(campfire_purges)
    total_with_empty_cage = total_from_json + empty_cage_purges
    
    print(f'\n【总计】')
    print(f'  JSON 记录的删牌：{total_from_json} = 商店 ({shop_purges}) + 事件 ({len(event_purges)}) + 开局 ({len(neow_purges)}) + 篝火 ({len(campfire_purges)})')
    print(f'  加上空鸟笼补偿：{total_with_empty_cage} = {total_from_json} + {empty_cage_purges}')
    
    # 验证 purchased_purges 字段
    purchased_purges = run_data.get('purchased_purges', 0)
    print(f'\n【验证 purchased_purges 字段】: {purchased_purges}')
    if purchased_purges == shop_purges:
        print(f'  ✓ 与 items_purged 数量一致')
    else:
        print(f'  ⚠ 与 items_purged 数量不一致 (差异：{purchased_purges - shop_purges})')
    
    return {
        'shop': shop_purges,
        'event': len(event_purges),
        'neow': len(neow_purges),
        'campfire': len(campfire_purges),
        'empty_cage': empty_cage_purges,
        'total': total_with_empty_cage
    }


def main():
    """主函数"""
    runs_dir = os.path.join(os.path.dirname(__file__), 'runs')
    test_files = ['1_test.json', '2_test.json', '3_test.json', '4_test.json']
    
    all_results = {}
    
    for test_file in test_files:
        file_path = os.path.join(runs_dir, test_file)
        
        if not os.path.exists(file_path):
            print(f'\n❌ 文件不存在：{file_path}')
            continue
        
        with open(file_path, 'r', encoding='utf-8') as f:
            run_data = json.load(f)
        
        result = analyze_purge_data(run_data, test_file)
        all_results[test_file] = result
    
    # 汇总对比
    print(f'\n\n{"="*60}')
    print(f'【汇总对比】')
    print(f'{"="*60}')
    
    print(f'\n{"文件":<15} {"商店":<6} {"事件":<6} {"开局":<6} {"篝火":<6} {"空鸟笼":<8} {"总计":<6}')
    print(f'{"-"*60}')
    
    for test_file, result in all_results.items():
        print(f'{test_file:<15} {result["shop"]:<6} {result["event"]:<6} {result["neow"]:<6} {result["campfire"]:<6} {result["empty_cage"]:<8} {result["total"]:<6}')
    
    print(f'\n【代码逻辑验证】')
    print(f'✓ 商店删牌 (shopPurges): 通过 items_purged 统计')
    print(f'✓ 事件删牌 (eventPurges): 通过 event_choices[].cards_removed 统计')
    print(f'✓ 开局删牌 (neowPurges): 通过 neow_bonus_log.cardsRemoved 统计')
    print(f'✓ 篝火删牌 (campfirePurges): 通过 campfire_choices[key="PURGE"] 统计')
    print(f'✓ Boss 遗物删牌 (emptyCagePurges): 检测到 Empty Cage 时强制 +2（独立来源）')
    print(f'\n✓ 总删牌数 = shopPurges + eventPurges + neowPurges + campfirePurges + emptyCagePurges')
    print(f'\n✓ Face Trader 事件选择鸟算作事件删牌（不计入空鸟笼）')
    print(f'✓ 空鸟笼仅指 Boss 遗物掉落时的强制 +2 补偿')


if __name__ == '__main__':
    main()
