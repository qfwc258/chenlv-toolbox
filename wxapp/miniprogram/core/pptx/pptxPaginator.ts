/**
 * 智能自动分页引擎（由 Android 版 MdAutoPaginator.kt 移植）。
 *
 * 分页优先级（锁定）：
 * 1. 手动  ---  强制分页（最高优先）
 * 2. H1 / H2 / H3 标题优先新开一页（专业 PPT 逻辑）
 * 3. keep-with-next：标题与其后首个内容块（表格/列表/段落等）尽量保持同页，避免标题孤立成空白页
 * 4. 孤儿标题保护：当连续多级标题（如 H1+H2）后紧跟放不下的内容块时，
 *    将尾部标题串随内容一起推到新页，避免"两级标题孤零零占一页"
 * 5. 当前页剩余高度放不下下一整块 → 自动新开一页
 * 6. 绝不拆分单个语义块（段落 / 列表 / 引用 / 代码块）
 * 7. 超长单块（自身高度超过整页）→ 保留单页并标记溢出警告
 *
 * autoPaginate = false 时退化为传统模式：仅  ---  分页，其余顺序堆叠。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node 专有 API。
 * 与 Kotlin 版不同：style 由调用方注入（最后一个参数），不依赖全局可变状态。
 */
import { BlockType } from '../mdBlocks';
import type { MdBlock, TextBlock } from '../mdBlocks';
import { textOf } from '../mdBlocks';
import { SlidePage, PaginationResult, fragmentsOf } from './pptxModels';
import type { TableBlock } from '../mdBlocks';
import { PptStyleSheet, defaultStyle } from './pptxStyleSheet';
import { contentHeight, blockHeight, splitLongCode, splitLongTable } from './pptxLayout';

/**
 * 自动分页入口。
 * @param blocks 待分页的块列表（已由 Markdown 解析）。
 * @param autoPaginate 是否启用智能分页规则（false 退化为仅手动分页）。
 * @param coverTitle 封面标题（来自 FrontMatter title），非空时作为独立封面页置首。
 * @param style 排版样式表（控制内容区高度 / 字号等），默认出厂样式。
 */
export function paginate(
  blocks: MdBlock[],
  autoPaginate: boolean,
  coverTitle: string | null,
  style: PptStyleSheet = defaultStyle()
): PaginationResult {
  const pages: SlidePage[] = [];
  const overflow = new Set<number>();

  // 当前页内容区可用高度（由样式表推导，等价 Kotlin PptLayoutEngine.PAGE_CONTENT_H）
  const pageH = (): number => style.contentBottom - style.contentTop;

  // 封面页（来自 FrontMatter title），独立首页，不参与高度限制
  if (coverTitle !== null && coverTitle.trim().length > 0) {
    pages.push({
      blocks: [{ kind: 'text', type: BlockType.H1, fragments: fragmentsOf(coverTitle), raw: coverTitle }],
      title: coverTitle,
      isCover: true,
    });
  }

  let cur: MdBlock[] = [];
  let usedY = 0;

  /** 是否标题块（H1..H6）。 */
  const isHeading = (b: MdBlock): b is TextBlock =>
    b.kind === 'text' && b.type <= BlockType.H6;

  const flush = (): void => {
    if (cur.length === 0) return;
    const pageIndex = pages.length; // 即将加入的 0-based 页索引
    if (cur.some((b) => contentHeight(b, style) > pageH())) {
      overflow.add(pageIndex + 1); // 1-based 页码
    }
    pages.push({ blocks: cur.slice(), title: titleOf(cur), isCover: false });
    cur = [];
    usedY = 0;
  };

  // 使用带索引的循环以支持向前预读（keep-with-next）
  for (let idx = 0; idx < blocks.length; idx++) {
    const block = blocks[idx];
    if (block.kind === 'break') {
      flush();
    } else if (isHeading(block) && autoPaginate) {
      const headingH = blockHeight(block, style);

      // ── keep-with-next：向前预读下一非强制分页块 ──
      // 目标：标题与其后首个内容块（表格/列表/段落等）尽量保持同页，
      // 避免"标题孤立在页面顶部、内容被挤到下一页"的空白页问题。
      let nextBlock: MdBlock | undefined;
      for (let j = idx + 1; j < blocks.length; j++) {
        if (blocks[j].kind !== 'break') {
          nextBlock = blocks[j];
          break;
        }
      }
      let nextSubHeight = 0;
      if (nextBlock !== undefined) {
        const nb = nextBlock;
        if (nb.kind === 'table' && contentHeight(nb, style) > pageH()) {
          // 超长表格：取拆分后第一子表的高度（含表头）
          const first = splitLongTable(nb, style)[0];
          nextSubHeight = first !== undefined ? blockHeight(first, style) : 0;
        } else if (nb.kind === 'text' && nb.type === BlockType.CODE && contentHeight(nb, style) > pageH()) {
          // 超长代码块：取拆分后第一子块高度
          const first = splitLongCode(nb, style)[0];
          nextSubHeight = first !== undefined ? blockHeight(first, style) : 0;
        } else {
          nextSubHeight = blockHeight(nb, style);
        }
      }

      // 原有规则：当前页已有非标题内容时，标题应新开一页
      const hasContent = cur.some((b) => !isHeading(b));
      // 仅含标题的页若已装满（高度超限）才强制分页
      const titlesFull = !hasContent && usedY + headingH > pageH();

      // keep-with-new 判断：若标题+下一块能放入当前页剩余空间，则不分页
      const canKeepTogether = nextSubHeight > 0 && usedY + headingH + nextSubHeight <= pageH();

      if (cur.length > 0 && (hasContent || titlesFull) && !canKeepTogether) flush();
      cur.push(block);
      usedY += headingH;
    } else {
      // 超长代码块（```）按可放下的行数拆分为多个子块 → 自动分页、不截断
      // 超长表格按数据行拆分为多个子表（每页保留表头）→ 自动分页、不截断
      // 其余块保持单块不拆（段落/列表/引用均整块移动）。
      //
      // 关键：传入实际剩余高度 (PAGE_CONTENT_H - usedY) 而非整页高度，
      // 确保当前页已有内容（如标题）时，拆分出的第一子块能适配剩余空间，
      // 配合 keep-with-next 避免"标题孤立空白页"。
      const availH = Math.max(pageH() - usedY, Math.floor(pageH() / 3));
      let subBlocks: MdBlock[];
      if (block.kind === 'text' && block.type === BlockType.CODE && contentHeight(block, style) > availH) {
        subBlocks = splitLongCode(block, style, availH);
      } else if (block.kind === 'table' && contentHeight(block, style) > availH) {
        subBlocks = splitLongTable(block, style, availH);
      } else {
        subBlocks = [block];
      }
      for (const sb of subBlocks) {
        const bh = blockHeight(sb, style);
        // 剩余高度不足放下一整块 → 新开页（绝不拆单个语义块；代码块已在外部按行拆好）
        if (autoPaginate && cur.length > 0 && usedY + bh > pageH()) {
          // ── 孤儿标题保护 ──
          // 场景：页尾有连续多级标题（如 H1+H2），后面紧跟一个放不下的内容块（表格/列表）。
          // 若直接 flush，这些标题会孤零零留在当前页，内容被挤到下一页。
          // 解决：将 cur 尾部连续标题剥离，flush 后带回新页与内容团聚。
          const orphanHeadings: MdBlock[] = [];
          while (cur.length > 0 && isHeading(cur[cur.length - 1])) {
            orphanHeadings.unshift(cur.pop() as TextBlock); // 保持原顺序
          }
          let orphanH = 0;
          for (const oh of orphanHeadings) orphanH += blockHeight(oh, style);
          usedY -= orphanH;

          flush();

          // 剥离的标题放入新页，即将与后续内容块同页
          cur.push(...orphanHeadings);
          usedY += orphanH;
        }
        cur.push(sb);
        usedY += bh;
      }
    }
  }
  flush();

  return { pages, overflowPages: overflow };
}

/** 取该页首个标题文本作为页标题（用于预览提示）。 */
function titleOf(blocks: MdBlock[]): string {
  for (const b of blocks) {
    if (b.kind === 'text' && b.type <= BlockType.H6) {
      return textOf(b).slice(0, 40);
    }
  }
  return '';
}

// 类型再导出（供调用方按需引用）
export type { TableBlock };
