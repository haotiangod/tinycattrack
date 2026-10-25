package tinycat.tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tinycat.stats.Card;

/**
 * 卡牌表的 Plackett-Luce 标准化率。
 *
 * <p>为什么卡牌需要第二份口径：简单率 = 被选 ÷ 被提供，分母把「同屏另外两张有多强」也算进去了。
 * 一张常和强卡同屏的牌会被系统性低估（观者第一幕实测平均挪 7.6 位、最大 30 位）。
 * PL 用整屏似然估计「同屏对手对齐之后」的相对受欢迎程度，模型细节见 {@link PlackettLuce}。</p>
 *
 * <p><b>SKIP 与颂钵必须进场</b>：{@code Run.cardChoices} 里只要没选 SKIP/颂钵，
 * 就会给 not_picked 补一个 SKIP（持有颂钵后补 Singing Bowl），也就是每一次奖励
 * 跳过都在场。把它剔除，第二幕后跳过率 74%~79% 的整段行为就没了对手，
 * 名次会整体偏 1~2 位。颂钵与 SKIP 分开拟合实测对名次没有影响，
 * 所以这里按表格的显示口径并入 SKIP，避免同一张表出现两行「跳过」。</p>
 *
 * <p><b>被最小样本过滤掉的卡仍然是对手</b>：强度在全卡池上拟合，
 * 只把结果挂到入表条目上。把过滤掉的卡从分母里删掉会改变其他卡的对手强度，
 * 那等于按用户的筛选条件偷偷换了模型。</p>
 *
 * <p><b>未校正的部分</b>：同一局里同一张卡第 2 次被提供时抓取率平均低 12pp
 * （已经有了一张，边际价值下降）。这会让「常被重复提供」的卡吃亏约 0.5~1 个名次
 * （最大 6 位）。因为影响小、又要多一套分层说明，导出里不做张数校正，只在 README 交代。</p>
 */
public final class CardRanker {

    private static final String SKIP = "SKIP";
    private static final String BOWL = "Singing Bowl";

    private CardRanker() {
    }

    /**
     * 把标准化率与 95%CI 写进条目（原地修改），口径 = 强度占比，合计 100%。
     *
     * 没有选择记录的条目保持 -1，{@link RateVariant} 会据此跳过。
     *
     * @param choices 每次奖励屏的原样记录：[0] 被选中，[1..] 同屏未选
     */
    public static void attach(List<Card[]> choices, List<TierEntry> entries) {
        if (choices == null || choices.isEmpty() || entries == null || entries.size() < 2) {
            return;
        }
        List<String[]> blocks = choiceIds(choices);
        if (blocks.isEmpty()) {
            return;
        }

        // 全卡池：出现在任意一屏里的卡（颂钵并入 SKIP）。被过滤的卡也留在这里当对手。
        Set<String> universeSet = new LinkedHashSet<String>();
        for (String[] block : blocks) {
            universeSet.addAll(Arrays.asList(block));
        }
        if (universeSet.size() < 2) {
            return;
        }
        List<String> universe = new ArrayList<String>(universeSet);
        Map<String, Integer> index = new LinkedHashMap<String, Integer>(universe.size() * 2);
        for (int i = 0; i < universe.size(); i++) {
            index.put(universe.get(i), Integer.valueOf(i));
        }
        List<int[]> groups = PlackettLuce.blocks(blocks, index);
        if (groups.size() < 2) {
            return;
        }

        // 报告集合：入表条目里、确实有选择记录的那些
        int[] report = reportOf(entries, index);
        if (report.length < 2) {
            return;
        }

        double[] strength = PlackettLuce.fit(groups, universe.size(), null);
        double total = PlackettLuce.sum(strength, report);
        if (total <= 0.0D) {
            return;
        }
        double[][] draws = PlackettLuce.bootstrap(groups, universe.size(), strength);
        double[] low = new double[universe.size()];
        double[] high = new double[universe.size()];
        PlackettLuce.shareInterval(draws, report, low, high);

        for (TierEntry entry : entries) {
            Integer id = index.get(entry.id);
            if (id == null) {
                continue;
            }
            double share = PlackettLuce.share(strength[id.intValue()], total);
            entry.standardized = share;
            entry.stdLow = low[id.intValue()];
            entry.stdHigh = high[id.intValue()];
            // 图片卡片只有 ~25 字符的副标宽度，这里只写数值；CI 走 CSV/xlsx/JSON 的独立列
            entry.context = String.format(Locale.US, "标准化 %.2f%%", share);
        }
    }

    private static int[] reportOf(List<TierEntry> entries, Map<String, Integer> index) {
        int[] buffer = new int[entries.size()];
        int len = 0;
        for (TierEntry entry : entries) {
            Integer id = index.get(entry.id);
            if (id != null) {
                buffer[len++] = id.intValue();
            }
        }
        return len == buffer.length ? buffer : Arrays.copyOf(buffer, len);
    }

    /** 选择块转成表格 id（颂钵并入 SKIP），保留「选中在前」的顺序，丢掉无名条目。 */
    private static List<String[]> choiceIds(List<Card[]> choices) {
        List<String[]> result = new ArrayList<String[]>();
        if (choices == null) {
            return result;
        }
        for (Card[] block : choices) {
            if (block == null || block.length < 2) {
                continue;
            }
            String[] names = new String[block.length];
            int len = 0;
            for (Card card : block) {
                String name = keyOf(card);
                if (name != null) {
                    names[len++] = name;
                }
            }
            if (len >= 2) {
                result.add(len == names.length ? names : Arrays.copyOf(names, len));
            }
        }
        return result;
    }

    private static String keyOf(Card card) {
        if (card == null || card.name == null || card.name.isEmpty()) {
            return null;
        }
        return BOWL.equals(card.name) ? SKIP : card.name;
    }
}
