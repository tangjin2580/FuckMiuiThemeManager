# [Xposed模块] MIUI / HyperOS 主题破解

适用于中国版 MIUI / HyperOS 的主题破解模块。

[![Stars](https://img.shields.io/github/stars/qqlittleice/FuckMiuiThemeManager?label=stars)](https://github.com/Xposed-Modules-Repo/com.yuk.fuckmiuithememanager)
[![Release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.yuk.fuckmiuithememanager?label=release)](https://github.com/Xposed-Modules-Repo/com.yuk.fuckmiuithememanager/releases/latest)

## 功能

- 允许无条件使用第三方主题
- 允许免费使用所有上架字体
- 去除应用内部广告（不含开屏广告，开屏广告来自另一个 MIUI 应用）
- 支持最近所有版本的主题壁纸
- 支持背屏（后置屏）主题的 apply
- 内置日志界面，可直接查看模块 hook 日志

## 注意事项

- 作用域必须**全部勾选**：主题壁纸、智能助理、系统框架、桌面
- 系统框架勾选后**必须重启手机**才生效
- 在系统框架生效前，主题可能会不定时恢复默认

## 日志界面

模块带一个桌面入口（HyperOS主题破解），打开后：

| 按钮 | 作用 |
| --- | --- |
| 刷新日志 | 一次性拉取最近 300 行本模块日志 |
| 开始 / 停止 | 实时跟随日志 |
| 清空 | 清空当前显示 |
| 重启主题 / 重启桌面 | 快速重启对应宿主，便于验证 hook |
| V/D/I/W/E | 等级过滤 |

日志来源说明：Android 11 之后应用读不到其它进程的 logcat，所以界面读的是
LSPosed 的落盘日志 `/data/adb/lspd/log/modules_*.log`，并只保留本模块
（`[com.yuk.fuckMiuiThemeManager,XposedBridge`）的输出，需要 root 授权。

## 构建

```bash
./gradlew assembleRelease
```

环境要求：JDK 17、Android SDK（compileSdk 34）。产物在
`app/build/outputs/apk/release/`。

依赖：

| 依赖 | 用途 |
| --- | --- |
| `de.robv.android.xposed:api:82` | Xposed API（compileOnly） |
| `app/libs/miui-framework.jar` | MIUI 内部类桩（compileOnly） |
| `com.github.kyuubiran:EzXHelper:2.2.1` | 方法 / 字段查找 |
| `org.luckypray:DexKit:1.1.8` | 按字符串特征反查被混淆的方法 |

## 源码恢复说明

2026-09-14：仓库此前停留在 2022 年的 1.2 版本，1.3 ~ 1.9.0 的源码已丢失。
当前代码是**从设备上安装的 1.9.0 APK 反编译（jadx + apktool smali 交叉校验）
后还原的 Kotlin 源码**，并顺手修掉了还原过程中发现的几个缺陷：

- 日志读取由 logcat 改为 LSPosed 落盘日志（原 `logcat -s FuckThemeManager:*`
  中 `*` 不是合法优先级，命令恒返回空）
- 补齐缺失的 `LogReader.trimLine()` 与 `LogActivity.onClearClick()`
- 修正 `LogReader.run()` 的循环条件（原写法在开始后立刻退出）
- 修正 `LogReader.levelOf()` 的等级判定（异常行被判成 I 级）
- 修正 `RearScreenMamlSkip` 里 `className == null` 的取反错误

功能逻辑（hook 点、DexKit 特征字符串、背屏 60 秒窗口）与原 APK 保持一致。
