# 简签贴吧

一个无广告的 Android 贴吧签到工具。用自己的百度账号登录后，可以一次签到多个关注的贴吧，并查看服务端确认的结果。由个人独立开发，与百度官方无隶属关系。

## 安装与使用

1. 在 [GitHub Releases](https://github.com/wojiuren/jiansign-tieba/releases/latest) 下载 APK，安装到 Android 8.0 或更高版本的手机。
2. 打开简签，在百度官方页面登录，返回首页，点击「刷新关注列表」。
3. 点击「一键签到」，保持页面在前台。完成后可以看到本轮新增、已签和未确认的数量。需要重新核对时，点击「刷新关注列表」。

同一签名的简签旧版可以直接更新。第一次从其他签到应用切换过来，需要在简签内登录一次。

## 签到与隐私

简签会跳过当日已签的贴吧。普通模式最多同时处理 4 个吧，默认开启的「加速签到」先处理 4 个，收到 4 个新签成功结果后最多同时处理 8 个。遇到服务端限制或结果不确定时，应用停止提交后续签到，并等待已发出的请求返回。最终结果会再次与服务端核对。

应用仅申请联网权限。登录状态保存在设备上的 Android WebView 中，签到请求通过 HTTPS 直接发送到百度贴吧。应用没有自建账号服务器、广告或统计 SDK。

## 构建与测试

构建脚本适用于 Windows，需要 Python 3、JDK 17、Android SDK Build Tools 36.0.0 和 Android SDK Platform 37.0。将 `JAVA_HOME` 和 `ANDROID_SDK_ROOT` 分别设为本机 JDK 和 Android SDK 的安装目录，在仓库根目录运行：

```powershell
python source/build.py
```

安装包会生成在 `dist/`。首次构建会在用户主目录的 `.jiansign-signing/` 下生成自己的签名密钥，请妥善保管。自行构建的版本使用自己的签名，后续更新需要沿用同一把密钥。

要运行模拟数据测试，可以在专用 Android 测试设备上安装刚构建的应用和测试包：

```powershell
python source/test_build.py
adb install -r 'dist/简签贴吧-0.1.4.apk'
adb install -r dist/verification/tests.apk
adb shell am instrument -w app.local.tiebasign.tests/app.local.tiebasign.TestRunner
```

测试覆盖签到结果分类、已签跳过、并发限制和异常停止。它们使用模拟数据，无需登录百度账号。

## 参考资料

- [dailycheckin 的贴吧实现](https://github.com/Sitoi/dailycheckin/blob/main/dailycheckin/tieba/main.py) 与[贴吧签到脚本](https://greasyfork.org/scripts/410874)提供了关注列表和请求字段的参考。
- [Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)介绍了应用使用的 HTTPS 配置。
