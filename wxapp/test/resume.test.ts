/**
 * resume 数据层冒烟测试：Gitee 拉取失败 / 空数据 → 本地兜底；正常返回 → 远端数据生效。
 * 运行：npx tsx test/resume.test.ts
 */
import { getResume } from '../miniprogram/utils/resume';
import { DEFAULT } from '../miniprogram/config/resume.default';

let passed = 0;
let failed = 0;

function assert(cond: boolean, msg: string): void {
  if (cond) {
    passed++;
    console.log(`  ✓ ${msg}`);
  } else {
    failed++;
    console.error(`  ✗ ${msg}`);
  }
}

(async () => {
  console.log('\n[1] resume 数据层（utils/resume.ts）');

  // 1. 请求失败 → 兜底本地默认
  (global as any).wx = { request: (o: any) => o.fail({ errMsg: 'timeout' }) };
  const r1 = await getResume(true);
  assert(r1 === DEFAULT, '请求失败回退本地默认');

  // 2. 远端返回空对象 → 兜底本地默认
  (global as any).wx = { request: (o: any) => o.success({ data: {} }) };
  const r2 = await getResume(true);
  assert(r2 === DEFAULT, '空数据回退本地默认');

  // 3. 正常返回 → 远端数据生效（兼容 { data: {...} } 结构）
  (global as any).wx = { request: (o: any) => o.success({ data: { data: { name: '测试律师' } } }) };
  const r3 = await getResume(true);
  assert((r3 as any).name === '测试律师', '远端数据生效');

  // 4. 兜底数据关键字段完整
  assert(!!DEFAULT.name && !!DEFAULT.mobile && !!DEFAULT.weixin && !!DEFAULT.email && !!DEFAULT.addr, '兜底数据关键字段完整');

  console.log(`\n==== 结果：通过 ${passed}，失败 ${failed} ====\n`);
})();
