# 照片扫地僧 — Tinder 风格相册整理

👈 左滑删除 · 右滑保留 👉
像刷交友软件一样清理相册。

## 两种使用方式

### 🟢 PWA 模式（现在就能用）

无需任何 SDK，手机浏览器打开即可，可添加到桌面。

```bash
npm install
npm run dev
```

浏览器访问 `http://<你的电脑IP>:3000`，手机打开 → 添加到桌面。

> ⚠️ PWA 模式**无法直接删除相册文件**（浏览器安全限制），只能标记然后对照清单手动删。

### 🔴 APK 原生模式（需要 Android SDK）

真正读取相册、一键删除照片的完整版。

**前置条件：** JDK 17 + Android SDK（安装 Android Studio 最省事）

```bash
# 1. 安装依赖
npm install

# 2. 添加 Android 平台（需要 Android SDK）
npx cap add android

# 3. 复制自定义插件
# 将 android-plugin/GalleryPlugin.java →
#   android/app/src/main/java/com/photoswipe/app/plugins/GalleryPlugin.java
# 将 android-plugin/MainActivity.java →
#   android/app/src/main/java/com/photoswipe/app/MainActivity.java

# 4. 同步 + 构建
npx cap sync
cd android && ./gradlew assembleDebug

# 5. 安装 APK
# APK 位置: android/app/build/outputs/apk/debug/app-debug.apk
# 传到手机直接安装
```

## 项目结构

```
photo-swipe-app/
├── public/
│   ├── index.html      # 主应用 (PWA + Capacitor 双模式)
│   ├── manifest.json    # PWA 安装配置
│   └── sw.js            # Service Worker (离线支持)
├── android-plugin/      # Capacitor 原生插件 (Java)
│   ├── GalleryPlugin.java   # 相册访问 + 删除照片
│   └── MainActivity.java    # 入口注册插件
├── scripts/
│   └── setup.js         # 环境检查脚本
├── package.json
└── capacitor.config.ts  # Capacitor 配置
```

## 技术原理

- **网页选照片**: `<input type="file">` → 浏览器沙箱，拿不到原始路径
- **原生选照片**: Android `PickVisualMedia` / `ACTION_OPEN_DOCUMENT` → 拿到 `content://` URI
- **原生删照片**: `MediaStore.createDeleteRequest()` (Android 11+) → 系统弹窗确认 → 彻底删除
