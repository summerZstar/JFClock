# JFClock 测试计划

## 1. 测试现实条件

当前开发环境：

- 没有 Android 真机测试矩阵
- 只有一台 OPPO 真机
- 主要验证手段：
  - GitHub Actions
  - Android Emulator
  - OPPO 真机

因此不要求开发阶段拥有多品牌真机。

## 2. 四层测试模型

### 第一层：静态检查 / CI

GitHub Actions：

```text
checkout
↓
JDK 17
↓
Gradle
↓
assembleDebug
↓
产出 APK
```

同时执行：

- Kotlin 编译
- XML 资源检查
- Manifest 检查
- Unit Test
- Room Migration Test

### 第二层：Android Emulator

建议至少建立：

| 模拟器 | 用途 |
|---|---|
| Android 6 / API 23 | minSdk 基线 |
| Android 10 / API 29 | 后台限制 |
| Android 12 / API 31 | PendingIntent / FGS 限制 |
| Android 13 / API 33 | 通知权限 |
| Android 14 / API 34 | 全屏提醒 / FGS |

如果 CI 成本有限，开发电脑至少保留 API 29、31、34。

### 第三层：OPPO 真机

重点测试：

- 后台清理
- 自启动
- 电池优化
- 全屏提醒
- 锁屏
- 通知
- 精确闹钟
- 后台弹出界面
- 增强提醒
- RingService
- 工作日
- Snooze

OPPO 真机是“厂商行为测试机”，不是 Android 全版本测试机。

### 第四层：真实用户反馈

未来发布 APK 后：

- 收集 Android 版本
- 收集厂商/型号
- 收集失败场景
- 收集权限状态
- 收集 JFClock 内部诊断信息

不要求提前购买多台手机。

## 3. 测试分类

### P0

必须通过：

- 闹钟准点触发
- 锁屏响铃
- 后台响铃
- Snooze
- 重复闹钟
- 月末
- 2/29
- 工作日
- Room Migration
- RingService
- 权限降级

### P1

应通过：

- 番茄钟
- 计时器
- 秒表
- 煮蛋
- UI 刷新
- 进程回收恢复

### P2

体验：

- 动画
- 文案
- 间距
- 字号
- 深色模式

## 4. 不要假装验证

AI 开发规则：

如果没有设备，禁止写：

> 已验证 OPPO / 小米 / 华为 / 荣耀均正常。

只能写：

> 未在该设备验证。

同样：

> Android 14 Emulator 通过

不能写成：

> 所有 Android 14 手机通过。

## 5. 厂商兼容性记录

建议维护：

```text
DEVICE_COMPATIBILITY.md
```

格式：

| 厂商 | 型号 | Android | 测试日期 | 结果 | 备注 |
|---|---|---|---|---|---|
| OPPO | 当前设备 | 实际版本 | 日期 | PASS/PARTIAL/FAIL | |
| Google | Emulator | API 34 | 日期 | PASS | |
| Google | Emulator | API 31 | 日期 | PASS | |

## 6. 每次发布前

必须：

- CI Build
- Unit Test
- Migration Test
- Emulator P0
- OPPO P0
- APK 安装
- 版本号检查
- Release Notes
