/**
 * 简历页：融合原「简介」+「连线」两个页面功能。
 *  - 数据来源：Gitee raw（utils/resume.ts），失败兜底本地 config/resume.default.ts；
 *  - 简介区：背景图 / 头像 / 名字 / 座右铭 / 个人介绍 / 一键拨号；
 *  - 连线区：加入通讯录 / 复制微信·邮箱·地址 / 在线客服；
 *  - 支持下拉刷新（force 重新拉取）与分享。
 */
import { getResume, ResumeData } from '../../utils/resume';

Page({
  data: {
    returnData: {} as ResumeData
  },

  onLoad() {
    const that = this;
    wx.showLoading({ title: '加载中' });
    getResume()
      .then((data) => {
        that.setData({ returnData: data });
        wx.hideLoading();
      })
      .catch(() => {
        wx.hideLoading();
        wx.showToast({ title: '加载失败，请重试', icon: 'none' });
      });
  },

  // 下拉刷新：重新从 Gitee 拉取（force 绕过缓存）
  onPullDownRefresh() {
    const that = this;
    getResume(true)
      .then((data) => {
        that.setData({ returnData: data });
      })
      .catch(() => {})
      .then(() => {
        wx.stopPullDownRefresh();
      });
  },

  tel() {
    const phoneNumber = this.data.returnData.mobile;
    if (!phoneNumber) return;
    wx.makePhoneCall({ phoneNumber });
  },

  // 图片加载失败（如 Gitee 图片不可用）时，回退到本地默认图
  onImgErr(e: any) {
    const f = e.currentTarget.dataset.f as string;
    const map: Record<string, string> = {
      background: '/pages/resume/images/bg_default.png',
      background2: '/pages/resume/images/bg_default.png',
      src: '/pages/resume/images/user.png'
    };
    if (f && map[f]) {
      const obj: any = {};
      obj['returnData.' + f] = map[f];
      this.setData(obj);
    }
  },

  // 加入通讯录：成功则提示，设备/模拟器不支持时退化为复制手机号
  addPhone() {
    const phoneNumber = this.data.returnData.mobile;
    const name = this.data.returnData.name;
    if (!phoneNumber) return;
    wx.addPhoneContact({
      firstName: name,
      mobilePhoneNumber: phoneNumber,
      success: () => {
        wx.showToast({ title: '已添加到通讯录', icon: 'success' });
      },
      fail: () => {
        wx.setClipboardData({
          data: phoneNumber,
          success: () => {
            wx.showToast({ title: '已复制手机号', icon: 'none' });
          }
        });
      }
    });
  },

  // 复制任意文本（微信 / 邮箱 / 地址）
  copyTBL(e: any) {
    const text = e.currentTarget.dataset.text as string;
    if (!text) return;
    wx.setClipboardData({
      data: text,
      success: () => {
        wx.showToast({ title: '复制成功', icon: 'none' });
      }
    });
  },

  onShareAppMessage() {
    const d = this.data.returnData;
    return { title: d.title || '陈伟律师' };
  },

  onShareTimeline() {
    const d = this.data.returnData;
    return {
      title: d.title || '陈伟律师',
      query: 'from=timeline'
    };
  }
});
