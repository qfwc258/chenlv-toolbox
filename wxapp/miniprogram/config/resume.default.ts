/**
 * 简历本地兜底数据：当 Gitee raw 拉取失败时使用，保证页面永不白屏/裂图。
 * 图片一律指向本地资源（pages/resume/images/），与 utils/resume.ts 的 fallback 配合。
 */
export const DEFAULT: Record<string, string> = {
  id: '1',
  name: '陈伟律师',
  xx01: '➤  陈伟律师2004年获得法学本科学历和法学学士学位，现执业于全国优秀律师事务所——金厚律所。',
  xx02: '➤  陈伟律师具有丰富的大型上市公司管理和商业争议解决经验，并熟知公司运营中可能存在的各种法律风险。',
  xx03: '➤  陈伟律师的核心业务涵盖民商事纠纷、侵权等争议的法律解决，公司风险防控与股权架构设计，以及提供法律顾问服务。',
  xx04: '',
  motto1: '争议解决 💖 法律顾问',
  motto2: '',
  src: '/pages/resume/images/user.png',
  background: '/pages/resume/images/bg_default.png',
  zzxx: '',
  mobile: '13975892485',
  qq: '47925738',
  weixin: 'law1top',
  email: 'cw.law@qq.com',
  addr: '湖南金厚（宁乡）律师事务所',
  background2: '/pages/resume/images/bg_default.png',
  mobile1: '13975892485',
  lianxian: '即刻连线陈伟律师',
  title: '陈伟律师-诉讼服务&法律顾问',
  description: '',
  imageUrl: '',
  url: '',
  bqsy: '中国 🇨🇳 长沙\n湘ICP备2024050769'
};
