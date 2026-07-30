# Android Sketches 项目设置总结

本文档总结了将 `android-sketches` 仓库初始化为 Android 应用程序仓库所做的更改和配置。

## 1. 项目概述

*   **应用名称**: Android Sketches
*   **包名**: `dev.hehe.sketch`
*   **Compile SDK**: 34
*   **Min SDK**: 26
*   **Target SDK**: 34

## 2. 环境配置

ADK Kotlin 0.6.0 的发布物使用 Kotlin 2.1 兼容元数据，Android target 的
`minSdk` 是 26。项目仅升级 Kotlin 编译器和 Gradle 的必要小版本，保留原有 AGP 与
JDK 11：

*   **Gradle 运行 Java**: JDK 11
*   **Java/Kotlin target**: 11
*   **Gradle 版本**: 7.6.3
*   **Android Gradle Plugin (AGP)**: 7.4.2
*   **Kotlin 版本**: 2.1.20

### Gradle 运行 JDK

项目继续使用 **JDK 11** 运行 Gradle，推荐安装 Azul Zulu JDK 11 LTS：

https://www.azul.com/downloads/?version=java-11-lts&package=jdk#zulu

下载页面中请保持：

*   **Java Version**: Java 11 LTS
*   **Package**: JDK
*   **Operating System**: 按当前系统选择 Windows 或 macOS
*   **Architecture**: 按机器选择，例如 Apple Silicon Mac 选择 ARM 64-bit，Intel/AMD Windows 选择 x86 64-bit

#### Windows 安装与配置

1.  在 Azul 下载页选择 **Windows**、**x86 64-bit**、**JDK**，下载 `.msi` 安装包。
2.  运行 `.msi` 安装包，建议安装到默认目录，例如：
    ```text
    C:\Program Files\Zulu\zulu-11
    ```
3.  配置 `JAVA_HOME`：
    *   打开 **Settings > System > About > Advanced system settings**。
    *   进入 **Environment Variables...**。
    *   在用户变量或系统变量中新增/修改 `JAVA_HOME`，值设为 Zulu JDK 11 安装目录，例如：
        ```text
        C:\Program Files\Zulu\zulu-11
        ```
    *   在 `Path` 中新增：
        ```text
        %JAVA_HOME%\bin
        ```
4.  打开新的 PowerShell 窗口验证：
    ```powershell
    java -version
    $env:JAVA_HOME
    ```
    `java -version` 应显示 `11.x`。
5.  如需只让当前终端临时使用该 JDK：
    ```powershell
    $env:JAVA_HOME="C:\Program Files\Zulu\zulu-11"
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    .\gradlew build
    ```

#### macOS 安装与配置

1.  在 Azul 下载页选择 **macOS**、对应架构、**JDK**：
    *   Apple Silicon 机器选择 **ARM 64-bit**。
    *   Intel Mac 选择 **x86 64-bit**。
2.  下载 `.dmg` 或 `.pkg` 安装包并完成安装。安装后 JDK 通常位于：
    ```text
    /Library/Java/JavaVirtualMachines/
    ```
3.  查看已安装的 JDK：
    ```bash
    /usr/libexec/java_home -V
    ```
4.  在当前终端临时配置：
    ```bash
    export JAVA_HOME=$(/usr/libexec/java_home -v 11)
    export PATH="$JAVA_HOME/bin:$PATH"
    ./gradlew build
    ```
5.  如需长期生效，按当前 shell 写入配置文件：
    *   zsh：
        ```bash
        echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 11)' >> ~/.zshrc
        echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
        source ~/.zshrc
        ```
    *   bash：
        ```bash
        echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 11)' >> ~/.bash_profile
        echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bash_profile
        source ~/.bash_profile
        ```
6.  验证：
    ```bash
    java -version
    echo "$JAVA_HOME"
    ```
    `java -version` 应显示 `11.x`。

#### Android Studio 中配置 Gradle JDK

如果使用 Android Studio，让 IDE 的 Gradle 运行环境继续指向本机 JDK 11：

1.  打开 Android Studio。
2.  进入 **File > Settings** (Windows/Linux) 或 **Android Studio > Preferences** (macOS)。
3.  打开 **Build, Execution, Deployment > Build Tools > Gradle**。
4.  在 **Gradle JDK** 中选择已安装的 **Zulu JDK 11**。
5.  如果列表中没有：
    *   Windows: 点击 `Add JDK...`，选择 JDK 11 安装目录，例如 `C:\Program Files\Zulu\zulu-11`。
    *   macOS: 点击 `Add JDK...`，选择 `/Library/Java/JavaVirtualMachines/` 下的 JDK 11。
6.  点击 **Apply** / **OK**，然后重新 **Sync Project with Gradle Files**。

不要把个人机器上的绝对路径写进仓库的 `gradle.properties`。如果确实需要临时固定 Gradle JDK，可以只在本机未提交的配置里使用：

```properties
org.gradle.java.home=/path/to/jdk-11
```

## 3. 关键配置文件

### `gradle.properties`

*   **Java Home**: 不提交机器相关路径，在 IDE 或 `JAVA_HOME` 中配置 JDK 11。
    ```properties
    # Do not commit org.gradle.java.home with a machine-specific path.
    # Configure JDK 11 locally in Android Studio or via JAVA_HOME.
    ```
*   **AndroidX**: 启用 AndroidX 支持。
    ```properties
    android.useAndroidX=true
    ```

### `local.properties`

*   **SDK 路径**: 指向本地 Android SDK 安装目录。
    ```properties
    sdk.dir=/Users/zhaoheh/Library/Android/sdk
    ```

### `gradle/wrapper/gradle-wrapper.properties`

*   **Distribution URL**:
    ```properties
    distributionUrl=https\://services.gradle.org/distributions/gradle-7.6.3-bin.zip
    ```

### `gradle/libs.versions.toml`

*   **版本定义**:
    ```toml
    [versions]
    agp = "7.4.2"
    kotlin = "2.1.20"
    adk = "0.6.0"
    ```

## 4. 问题解决与变通

### Lint 配置
实验聚合工程不让 release Lint 阻断本地验证：

**解决方案**: 在 `app/build.gradle.kts` 中禁用了 Release 构建的 Lint 检查，并允许构建在错误时继续运行。

```kotlin
android {
    // ...
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
```

### ADK 依赖兼容性

`google-adk-kotlin-core:0.6.0` 使用 Kotlin 2.1 元数据，且 Android 变体的 `minSdk`
为 26。因此 Kotlin 升级到 2.1.20、Gradle 升级到该版本支持的最低稳定线 7.6.3，
应用 `minSdk` 升级到 26。Kotlin 2.1.20 官方仍支持 AGP 7.4.2，所以无需迁移到 AGP 8
或 JDK 17。

ADK 默认传递的 Room 2.8 session backend 要求 AGP 8.1.1；`feat-adk` 使用
`InMemorySessionService`，因此在模块依赖中排除了未使用的 `room-runtime` 和
`room-ktx`，避免可选能力污染宿主工程工具链。

## 5. 构建指南

在项目根目录下运行以下命令进行构建：

```bash
./gradlew build
```

构建成功后，您应该会看到 `BUILD SUCCESSFUL` 的消息。

## 6. Android Studio 同步问题

如果同步提示 Gradle JVM 不兼容，请在
**Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** 选择本机
JDK 11（当前项目配置名为 `zulu-11`），然后重新执行 **Sync Project with Gradle Files**。

## 7. 调试与分析文件

### `java_pid*.hprof` (Java 堆转储文件)

这是一个 **Java 堆转储 (Heap Dump)** 文件（例如 `java_pid66787.hprof`），记录了 Java 进程在特定时刻的内存快照。

**主要用途：**
用于分析内存泄漏 (Memory Leaks) 和优化内存使用。

**如何使用 (推荐方法):**

1.  **Android Studio (最推荐)**
    *   **操作**: 直接将 `.hprof` 文件拖入 Android Studio 编辑窗口，或通过 **Profiler** 面板 (`+` -> `Load from file...`) 加载。
    *   **功能**: 自动检测 Activity/Fragment 泄漏，查看对象引用链，分析支配树 (Dominator Tree) 和直方图 (Histogram)。

2.  **Eclipse Memory Analyzer (MAT)**
    *   **适用**: 深度分析，适合大型堆转储。
    *   **功能**: 生成 "Leak Suspects" 报告，非常直观地指出内存泄漏疑点。

3.  **VisualVM**
    *   **适用**: 通用 Java 分析。
    *   **功能**: 查看类实例数量、大小及引用关系。

**注意**:
*   这些文件通常体积较大。
*   如果不是主动生成的，通常意味着应用发生了 **OutOfMemoryError**。
*   分析完毕后如果不需要可以安全删除。
