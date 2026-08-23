/**
 * fflate（UMD 构建）的最小类型声明。
 * 完整包：https://www.npmjs.com/package/fflate
 */

export interface ZipOptions {
  level?: number;
  mtime?: string | number | Date;
  os?: number;
}
export type Zippable =
  | Uint8Array
  | [Uint8Array, ZipOptions]
  | { [key: string]: Zippable };
export function zipSync(data: Zippable, opts?: ZipOptions): Uint8Array;
export function unzipSync(data: Uint8Array, opts?: { filter?: (file: UnzipFileInfo) => boolean }): { [key: string]: Uint8Array };
export interface UnzipFileInfo {
  name: string;
  size: number;
  originalSize: number;
  compression: number;
}
export function strToU8(str: string, latin1?: boolean): Uint8Array;
export function strFromU8(data: Uint8Array, latin1?: boolean): string;
