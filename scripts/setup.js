/**
 * Setup script — checks environment and gives next-step instructions.
 * Run: node scripts/setup.js
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const GREEN = '\x1b[32m';
const YELLOW = '\x1b[33m';
const RED = '\x1b[31m';
const CYAN = '\x1b[36m';
const RESET = '\x1b[0m';

function check(cmd, name) {
  try {
    execSync(cmd, { stdio: 'pipe' });
    console.log(`  ${GREEN}✓${RESET} ${name}`);
    return true;
  } catch {
    console.log(`  ${RED}✗${RESET} ${name}`);
    return false;
  }
}

console.log(`\n${CYAN}=== 照片扫地僧 · 环境检查 ===${RESET}\n`);

console.log('基础工具:');
const hasNode = check('node --version', 'Node.js');
const hasNpm = check('npm --version', 'npm');
const hasJava = check('javac --version', 'JDK 17+');
const hasSdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;

if (hasSdk) {
  console.log(`  ${GREEN}✓${RESET} Android SDK (ANDROID_HOME = ${hasSdk})`);
} else {
  console.log(`  ${YELLOW}?${RESET} Android SDK — ANDROID_HOME not set`);
}

console.log(`\n${CYAN}=== 构建路径 ===${RESET}\n`);

if (hasNode && hasNpm && hasJava && hasSdk) {
  console.log(`${GREEN}全部就绪！可以构建 APK：${RESET}\n`);
  console.log(`  1. npm install`);
  console.log(`  2. npx cap add android`);
  console.log(`  3. 复制 android-plugin/*.java 到 android/ 对应目录`);
  console.log(`  4. npx cap sync`);
  console.log(`  5. cd android && ./gradlew assembleDebug`);
  console.log(`  6. APK 在 android/app/build/outputs/apk/debug/\n`);
} else {
  console.log(`${YELLOW}还缺少一些工具。两步走：${RESET}\n`);
  console.log(`${CYAN}【现在就可以用】PWA 模式：${RESET}`);
  console.log(`  npm install`);
  console.log(`  npm run dev`);
  console.log(`  → 手机浏览器打开 http://<你的IP>:3000`);
  console.log(`  → 添加到桌面即成为独立 App\n`);

  if (!hasJava || !hasSdk) {
    console.log(`${CYAN}【缺少 Android SDK / JDK】${RESET}`);
    console.log(`  方案 A: 安装 Android Studio（推荐，省心）`);
    console.log(`    https://developer.android.com/studio`);
    console.log(`  方案 B: 只装命令行工具（省空间，约 1GB）`);
    console.log(`    1. 安装 JDK 17: winget install EclipseAdoptium.Temurin.17.JDK`);
    console.log(`    2. 安装 Android SDK 命令行工具:`);
    console.log(`       https://developer.android.com/studio#command-line-tools-only`);
    console.log(`    3. 设置环境变量 ANDROID_HOME`);
    console.log(`    4. sdkmanager "platforms;android-34" "build-tools;34.0.0"`);
    console.log(`  方案 C: 用 GitHub Actions 云端构建（免费）`);
    console.log(`    推送到 GitHub → CI 自动出 APK → 下载安装\n`);
  }

  console.log(`${CYAN}【无限速方案 — 用 Capacitor Cloud 构建】${RESET}`);
  console.log(`  也可以直接用 Ionic AppFlow / Capacitor Cloud 在云端编译 APK`);
  console.log(`  上传代码 → 云端出包 → 扫码下载，不需要本地装 Android SDK\n`);
}
