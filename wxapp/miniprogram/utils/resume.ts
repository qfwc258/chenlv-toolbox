/**
 * 简历数据获取（来源：Gitee raw，永久免费）。
 * 注意：小程序后台需将 gitee.com 加入 request 合法域名；
 * 开发阶段可在「详情 → 本地设置」勾选「不校验合法域名」。
 * 容错：请求失败 / 返回空数据时，回退到本地 config/resume.default.ts，
 * 保证页面永不白屏、图片永不裂图。
 */
import { DEFAULT } from '../config/resume.default';

export const RESUME_URL = 'https://gitee.com/xlking/cw_resume/raw/master/wxapp/basic';

/** 简历字段类型 */
export type ResumeData = Record<string, string>;

let _cache: Promise<ResumeData> | null = null; // 模块级缓存，避免切 tab 重复请求；force 可强制刷新

export function getResume(force?: boolean): Promise<ResumeData> {
  if (_cache && !force) return _cache;

  _cache = new Promise<ResumeData>((resolve, reject) => {
    wx.request({
      url: RESUME_URL,
      header: { 'content-type': 'application/json' },
      success: (res: any) => {
        // 远端结构为 { data: {...} }，兼容直接返回对象
        const data = res.data && res.data.data ? res.data.data : res.data;
        resolve(data && Object.keys(data).length ? data : DEFAULT);
      },
      fail: (err: any) => {
        reject(err);
      }
    });
  }).catch(() => {
    _cache = null; // 允许下次重试
    return DEFAULT; // 兜底：返回本地数据，不再向外抛错
  });

  return _cache;
}
