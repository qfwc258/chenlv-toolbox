/**
 * 极简 CSS 解析器：把主题 CSS 文本解析为「选择器 -> 声明」规则列表，
 * 供公众号 HTML 渲染逐元素内联使用（移植自 Android 版 CssParser.kt）。
 *
 * 支持：
 *   - 多选择器逗号分隔（h1, h2）
 *   - 后代选择器（pre code、ul li）
 *   - 去掉注释块、!important
 *   - 兼容普通声明（font-size:16px; color:#333;）
 *
 * 不支持（也不需要）：@media / @keyframes / 嵌套，因为公众号主题只用静态声明。
 */

export interface CssRule {
  selectors: string[];
  declarations: Record<string, string>;
  isBodyRule: boolean;
}

export function parseCss(css: string): CssRule[] {
  const rules: CssRule[] = [];
  const noComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const blocks = noComments.split('}');

  for (const block of blocks) {
    const brace = block.indexOf('{');
    if (brace < 0) continue;
    const selectorPart = block.substring(0, brace).trim();
    const declPart = block.substring(brace + 1).trim();
    if (selectorPart === '' || declPart === '') continue;

    const selectors = selectorPart
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s !== '');
    const declarations = parseDeclarations(declPart);
    if (selectors.length > 0 && Object.keys(declarations).length > 0) {
      rules.push({
        selectors,
        declarations,
        isBodyRule: selectors.some((s) => s.toLowerCase() === 'body')
      });
    }
  }
  return rules;
}

function parseDeclarations(decl: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const pair of decl.split(';')) {
    const idx = pair.indexOf(':');
    if (idx <= 0) continue;
    const prop = pair.substring(0, idx).trim().toLowerCase();
    let value = pair.substring(idx + 1).trim();
    if (value === '') continue;
    value = value.replace(/!important/gi, '').trim();
    if (prop !== '') map[prop] = value;
  }
  return map;
}
