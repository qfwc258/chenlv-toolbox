/**
 * PPTX 配色统一由「单一主色调」驱动（由 Android 版 PptThemes.kt 移植）。
 *
 * 三套预设主题（商务深蓝/简约灰白/政务红）本质只是三种主色调，与自定义色板重复，
 * 现已去掉「主题」概念：全部配色由用户所选主色调派生，全局贯穿一致。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，不依赖 Compose / Android API。
 */
import { PptTheme } from './pptxModels';

/** 默认主色调（商务蓝） */
export const DEFAULT_TONE: string = '2E5FA3';

/** 预设主色调色板（点选即设定整套 PPTX 的主色调）。 */
export const CUSTOM_PALETTE: string[] = [
  'C0392B', 'E67E22', 'F1C40F', '27AE60',
  '16A085', '2980B9', '2E5FA3', '8E44AD',
  'D81B60', '795548', '607D8B', '2C3E50',
];

/**
 * 由主色调生成整套 PPTX 配色：
 *  - accent / 封面 / 章节色块 = 主色
 *  - 标题色 = 主色深色化（白底上保持可读与层次）
 *  - 代码块 / 引用底 = 主色浅色底
 *  - 正文保持深灰，白底可读
 */
export function fromTone(hex: string): PptTheme {
  const t = normalize(hex) ?? DEFAULT_TONE;
  return {
    id: 'custom',
    name: '自定义主色',
    bg: 'FFFFFF',
    titleColor: darken(t, 0.7),
    bodyColor: '333333',
    accent: t,
    codeBg: mixWhite(t, 0.9),
    quoteBg: mixWhite(t, 0.9),
    coverBg: t,
  };
}

/** 归一化 hex：去掉 # 与非法字符；非法输入返回 null */
export function normalize(hex: string): string | null {
  const h = hex.trim().replace(/^#/, '');
  if (h.length !== 6) return null;
  for (const c of h) {
    if (!/[0-9a-fA-F]/.test(c)) return null;
  }
  return h.toUpperCase();
}

/** 主色深色化（各通道按 factor 缩放），返回 RRGGBB */
export function darken(hex: string, factor: number): string {
  return channel(hex, (c) => Math.max(0, Math.min(255, Math.round(c * factor))));
}

/** 主色与白色按 ratio 混合（ratio 越大越浅），返回 RRGGBB */
export function mixWhite(hex: string, whiteRatio: number): string {
  const t = normalize(hex) ?? 'F2F2F2';
  let out = '';
  for (let i = 0; i < 3; i++) {
    const c = parseInt(t.substring(i * 2, i * 2 + 2), 16);
    const w = Math.round(255 * whiteRatio);
    const v = Math.max(0, Math.min(255, Math.round(c * (1 - whiteRatio) + w)));
    out += v.toString(16).padStart(2, '0').toUpperCase();
  }
  return out;
}

function channel(hex: string, f: (c: number) => number): string {
  const t = normalize(hex) ?? '222222';
  let out = '';
  for (let i = 0; i < 3; i++) {
    const c = parseInt(t.substring(i * 2, i * 2 + 2), 16);
    out += f(c).toString(16).padStart(2, '0').toUpperCase();
  }
  return out;
}

/** 判断背景色是否偏亮（相对亮度法），用于决定封面/强调背景上的前景文字颜色。 */
export function isLight(hex: string): boolean {
  const h = hex.trim().replace(/^#/, '');
  try {
    const r = parseInt(h.substring(0, 2), 16) / 255;
    const g = parseInt(h.substring(2, 4), 16) / 255;
    const b = parseInt(h.substring(4, 6), 16) / 255;
    // 相对亮度（sRGB 近似）
    const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    return lum > 0.6;
  } catch {
    return false;
  }
}

/** hex → 0xRRGGBB Int（用于无头导出着色） */
export function hexInt(hex: string): number {
  const h = hex.trim().replace(/^#/, '');
  try {
    return parseInt(h, 16);
  } catch {
    return 0xff000000;
  }
}
