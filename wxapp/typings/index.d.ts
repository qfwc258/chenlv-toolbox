/**
 * 微信小程序全局类型声明（最小子集，覆盖本项目用到的 API）。
 * 完整类型可参考官方 miniprogram-api-typings。
 */
declare namespace WechatMiniprogram {
  interface SaveFileResult {
    savedFilePath: string;
  }

  interface WriteFileOptions {
    filePath: string;
    data: string | ArrayBuffer;
    encoding?: string;
    success?: (res: any) => void;
    fail?: (err: any) => void;
    complete?: (res: any) => void;
  }

  interface OpenDocumentOptions {
    filePath: string;
    showMenu?: boolean;
    fileType?: string;
    success?: () => void;
    fail?: (err: any) => void;
  }

  interface ShareFileMessageOptions {
    filePath: string;
    fileName?: string;
    success?: (res: any) => void;
    fail?: (err: any) => void;
  }

  interface SetStorageOptions {
    key: string;
    data: any;
    success?: () => void;
    fail?: (err: any) => void;
  }

  interface GetStorageSyncResult {
    data: any;
  }

  interface ShowToastOptions {
    title: string;
    icon?: 'success' | 'error' | 'loading' | 'none';
    duration?: number;
  }

  interface ShowLoadingOptions {
    title: string;
    mask?: boolean;
  }

  interface CanvasToTempFilePathOptions {
    canvas?: any;
    x?: number;
    y?: number;
    width?: number;
    height?: number;
    destWidth?: number;
    destHeight?: number;
    fileType?: string;
    quality?: number;
    success?: (res: { tempFilePath: string }) => void;
    fail?: (err: any) => void;
  }

  interface SaveImageToPhotosAlbumOptions {
    filePath: string;
    success?: (res: any) => void;
    fail?: (err: any) => void;
  }

  interface DownloadFileSuccess {
    tempFilePath: string;
    statusCode: number;
  }

  interface FileSystemManager {
    writeFileSync(filePath: string, data: string | ArrayBuffer, encoding?: string): void;
    readFileSync(filePath: string, encoding?: string): string | ArrayBuffer;
    accessSync(filePath: string): void;
    unlinkSync(filePath: string): void;
    mkdirSync(dirPath: string, recursive?: boolean): void;
  }

  interface SelectorQuery {
    select(selector: string): NodesRef;
  }

  interface NodesRef {
    fields(fields: { node?: boolean; size?: boolean }): NodesRef;
    boundingClientRect(cb?: (res: any) => void): NodesRef;
    exec(cb?: (res: any[]) => void): NodesRef;
  }

  /** Canvas 2D 上下文（小程序 canvas type=2d） */
  interface CanvasRenderingContext2D {
    width: number;
    height: number;
    set fillStyle(v: string);
    set font(v: string);
    set textAlign(v: string);
    set textBaseline(v: string);
    set lineWidth(v: number);
    set strokeStyle(v: string);
    set globalAlpha(v: number);
    fillRect(x: number, y: number, w: number, h: number): void;
    strokeRect(x: number, y: number, w: number, h: number): void;
    fillText(text: string, x: number, y: number, maxWidth?: number): void;
    strokeText(text: string, x: number, y: number, maxWidth?: number): void;
    measureText(text: string): { width: number };
    beginPath(): void;
    moveTo(x: number, y: number): void;
    lineTo(x: number, y: number): void;
    stroke(): void;
    closePath(): void;
    save(): void;
    restore(): void;
    translate(x: number, y: number): void;
    rotate(angle: number): void;
    setTransform(...args: number[]): void;
    scale(x: number, y: number): void;
  }

  interface CanvasNode {
    width: number;
    height: number;
    getContext(type: string): CanvasRenderingContext2D;
  }
}

interface Wx {
  saveFile(options: {
    tempFilePath: string;
    success?: (res: WechatMiniprogram.SaveFileResult) => void;
    fail?: (err: any) => void;
  }): void;
  writeFile(options: WechatMiniprogram.WriteFileOptions): void;
  openDocument(options: WechatMiniprogram.OpenDocumentOptions): void;
  shareFileMessage(options: WechatMiniprogram.ShareFileMessageOptions): void;
  setStorage(options: WechatMiniprogram.SetStorageOptions): void;
  getStorageSync(key: string): any;
  setStorageSync(key: string, data: any): void;
  removeStorageSync(key: string): void;
  showToast(options: WechatMiniprogram.ShowToastOptions): void;
  showLoading(options: WechatMiniprogram.ShowLoadingOptions): void;
  hideLoading(): void;
  showModal(options: {
    title?: string;
    content?: string;
    showCancel?: boolean;
    confirmText?: string;
    success?: (res: { confirm: boolean; cancel: boolean }) => void;
  }): void;
  canvasToTempFilePath(options: WechatMiniprogram.CanvasToTempFilePathOptions): void;
  openSetting(options: { success?: (res: any) => void; fail?: (err: any) => void }): void;
  saveImageToPhotosAlbum(options: WechatMiniprogram.SaveImageToPhotosAlbumOptions): void;
  downloadFile(options: {
    url: string;
    success?: (res: WechatMiniprogram.DownloadFileSuccess) => void;
    fail?: (err: any) => void;
  }): void;
  getFileSystemManager(): WechatMiniprogram.FileSystemManager;
  getSystemInfoSync(): any;
  createSelectorQuery(): WechatMiniprogram.SelectorQuery;
  env: { USER_DATA_PATH: string };
  request(options: {
    url: string;
    method?: string;
    data?: any;
    header?: any;
    success?: (res: any) => void;
    fail?: (err: any) => void;
  }): void;
  makePhoneCall(options: {
    phoneNumber: string;
    success?: () => void;
    fail?: (err: any) => void;
  }): void;
  addPhoneContact(options: {
    firstName?: string;
    mobilePhoneNumber?: string;
    success?: () => void;
    fail?: (err: any) => void;
  }): void;
  setClipboardData(options: {
    data: string;
    success?: () => void;
    fail?: (err: any) => void;
  }): void;
  stopPullDownRefresh(): void;
}

declare const wx: Wx;
declare const App: (options: any) => void;
declare const Page: (options: any) => void;
declare const Component: (options: any) => void;
declare const getApp: () => any;
declare const getCurrentPages: () => any[];
