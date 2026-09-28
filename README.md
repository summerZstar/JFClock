# JFClock —— 仿 ColorOS 风格安卓闹钟

一个参考 ColorOS 时钟 App 视觉风格实现的 Android 闹钟应用，核心特性是支持
**固定间隔天数的闹钟**（每隔 N 天响一次）。

## 功能特性

- 仿 ColorOS 的清爽卡片列表：大号时间、重复说明、右侧绿色开关（OPPO 绿）。
- 新建 / 编辑闹钟：滚轮式时间选择（时 / 分）。
- 三种重复方式：
  - **仅一次**
  - **每天**
  - **间隔重复**：每隔 N 天（2~30 天可选），以创建当天为基准日。
- 闹钟标签、震动开关、铃声开关。
- 全屏响铃界面（锁屏也能显示并点亮屏幕），支持「关闭」与「稍后提醒（5 分钟）」。
- 关机重启 / 时间变更后自动重新排程（BootReceiver）。
- 使用 `AlarmManager.setAlarmClock()` 调度，Doze 模式下也能精准触发，并出现在状态栏。

## 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/jfclock/
│   ├── JFClockApp.kt          // Application，提供 DB / Repository
│   ├── Alarm.kt               // Room 实体
│   ├── AlarmDao.kt
│   ├── AlarmDatabase.kt
│   ├── AlarmRepository.kt
│   ├── AlarmUtils.kt          // 日期与文案工具
│   ├── AlarmScheduler.kt      // 计算下次触发 + 调度
│   ├── AlarmReceiver.kt       // 触发广播 -> 响铃 + 重新排程
│   ├── BootReceiver.kt        // 开机 / 时间变更后重排
│   ├── AlarmAdapter.kt        // 列表适配器
│   ├── MainActivity.kt        // 列表 + 添加
│   ├── AlarmEditActivity.kt   // 编辑 / 新建
│   └── AlarmRingActivity.kt   // 全屏响铃
└── res/                       // 布局、主题、颜色、图标、菜单
```

### 固定间隔天数如何实现

`Alarm` 实体的 `repeatType` 字段：

| 值      | 含义           |
| ------- | -------------- |
| `-1`    | 仅一次         |
| `0`     | 每天           |
| `>0`    | 每隔 N 天      |

`AlarmScheduler.computeNextTrigger()` 以 `anchorTime`（创建当天 0 点的毫秒值）
为基准日，计算出下一次触发时刻：

```kotlin
// 每隔 N 天：从基准日向后累加 N 天直到超过当前时间
val interval = alarm.repeatType.toLong() * DAY
while (trigger <= now) trigger += interval
```

由于每次只安排「下一次」触发，触发后由 `AlarmReceiver` 计算并安排后续触发，
因此无需每天重新排程，性能友好。

## 构建与运行

要求：

- Android Studio (Hedgehog / Iguana 等，AGP 8.3)
- JDK 17（AGP 8 运行需要；编译目标为 Java 11）
- Android SDK Platform 34，Build-Tools 34.x
- 设备 / 模拟器 API 23+（`minSdk 23`）

步骤：

1. 用 Android Studio 打开本工程根目录。
2. 等待 Gradle 同步完成（会自动下载依赖）。
3. 连接设备或启动模拟器，点击 ▶ Run。

> 注：本项目未附带 `local.properties` 与 Gradle Wrapper 的二进制文件，
> 请用 Android Studio 打开后由 IDE 自动补全（`sdk.dir` 与 `gradlew`）。

## 注意事项

- 响铃默认使用系统默认闹钟铃声（`RingtoneManager.TYPE_ALARM`），无需额外音频资源。
- 锁屏显示依赖 `setShowWhenLocked` / `setTurnScreenOn`（API 27+），
  低版本回退到 `FLAG_SHOW_WHEN_LOCKED`。
- 若要使用自定义铃声，可将音频放入 `res/raw` 并通过 `RingtoneManager` 的
  `getRingtone(context, uri)` 加载对应 Uri。
