# JFClock AI 开发规则

本文件用于约束 AI Coding Agent。

## 1. 第一原则

**不要为了增加功能而破坏已有功能。**

JFClock 是时间工具，响铃可靠性优先。

## 2. 修改前

AI 必须先：

1. 阅读相关现有代码
2. 阅读 `PRODUCT_SPEC.md`
3. 阅读相关测试用例
4. 判断是否涉及 P0 链路
5. 再修改

禁止看到需求后直接大规模重构。

## 3. 架构原则

后台响铃统一使用：

```text
AlarmManager
→ Receiver
→ RingService
→ RingActivity
```

除非 Android 系统限制要求，否则不要为每个功能重新实现一套后台响铃机制。

## 4. UI 原则

技术栈固定：

- Kotlin
- XML View
- Material 3
- ViewBinding

不要擅自迁移 Compose。

## 5. 数据原则

Room：

- 长期业务数据

SharedPreferences：

- 临时运行状态

不要为了“统一”而进行无必要的数据迁移。

## 6. 时间计算

时间计算必须：

- 独立
- 可测试
- 不依赖 Activity
- 不依赖 UI
- 不依赖当前系统时间的隐式状态

核心计算必须有 Unit Test。

## 7. 权限

任何权限失败都必须：

- 不崩溃
- 明确提示
- 尽可能提供系统设置入口
- 使用可用的降级策略

禁止假设某个厂商一定允许后台启动 Activity。

## 8. Android 厂商

不要把：

> OPPO 测试通过

等同于：

> Android 测试通过。

代码中避免加入没有依据的厂商特殊判断。

只有出现实际兼容性问题时才增加厂商适配。

## 9. 测试真实性

AI 不得声称没有实际运行过的测试已经通过。

正确：

> 编译逻辑已检查，但当前环境没有设备，未完成真机验证。

错误：

> 已验证所有 Android 手机正常。

## 10. 修改后输出

每次开发完成后必须说明：

### 修改内容

### 修改文件

### 测试结果

### 未验证项目

### 潜在风险

### 建议下一步

## 11. 不主动扩展需求

除非用户明确要求，不要主动增加：

- 日历
- 天气
- 任务
- 记账
- AI
- 云同步
- 社交

## 12. P0 保护

以下功能属于 P0：

- AlarmManager
- Receiver
- RingService
- RingActivity
- AlarmUtils
- 权限
- Room Migration
- 开机恢复

修改这些模块后必须执行对应回归测试。

## 13. 发布原则

不能因为：

> 本地没有 Android SDK / 真机

而跳过 GitHub Actions。

CI 是项目正式编译验证入口。

## 14. 最终目标

JFClock 不是功能越多越好。

目标是：

> **简单、可靠、好用的 Android 时间提醒与计时工具。**
