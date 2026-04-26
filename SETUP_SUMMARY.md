# Android Sketches 项目设置总结

本文档总结了将 `android-sketches` 仓库初始化为 Android 应用程序仓库所做的更改和配置。

## 1. 项目概述

*   **应用名称**: Android Sketches
*   **包名**: `dev.hehe.sketch`
*   **Compile SDK**: 34
*   **Min SDK**: 24
*   **Target SDK**: 34

## 2. 环境配置 (Java 11 兼容性)

为了在 **Java 11** 环境下成功构建项目，我们对工具链进行了如下配置：

*   **Java 版本**: Java 11 (推荐使用 Azul Zulu JDK 11 LTS)
*   **Gradle 版本**: 7.6.1 (降级自 8.2 以适配 Java 11)
*   **Android Gradle Plugin (AGP)**: 7.4.2 (降级自 8.2.0 以适配 Java 11)
*   **Kotlin 版本**: 1.8.10

### Gradle 运行 JDK

本项目的 Java target 是 11，Gradle 也应使用 **JDK 11** 运行。推荐安装 Azul Zulu JDK 11 LTS：

https://www.azul.com/downloads/?version=java-11-lts&os=macos&architecture=arm-64-bit&package=jdk#zulu

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
    `java -version` 应显示 `11.x` 和 `Zulu`。
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
3.  查看已安装的 JDK 11：
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
    `java -version` 应显示 `11.x` 和 `Zulu`。

#### Android Studio 中配置 Gradle JDK

如果使用 Android Studio，同步项目时还需要让 IDE 的 Gradle 运行环境指向 Zulu JDK 11：

1.  打开 Android Studio。
2.  进入 **File > Settings** (Windows/Linux) 或 **Android Studio > Preferences** (macOS)。
3.  打开 **Build, Execution, Deployment > Build Tools > Gradle**。
4.  在 **Gradle JDK** 中选择已安装的 **Zulu JDK 11**。
5.  如果列表中没有：
    *   Windows: 点击 `Add JDK...`，选择 Zulu JDK 11 安装目录，例如 `C:\Program Files\Zulu\zulu-11`。
    *   macOS: 点击 `Add JDK...`，选择 `/Library/Java/JavaVirtualMachines/` 下的 Zulu JDK 11。
6.  点击 **Apply** / **OK**，然后重新 **Sync Project with Gradle Files**。

不要把个人机器上的绝对路径写进仓库的 `gradle.properties`。如果确实需要临时固定 Gradle JDK，可以只在本机未提交的配置里使用：

```properties
org.gradle.java.home=/path/to/zulu-11
```

## 3. 关键配置文件

### `gradle.properties`

*   **Java Home**: 设置为 Java 11 的路径。
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
    distributionUrl=https\://services.gradle.org/distributions/gradle-7.6.1-bin.zip
    ```

### `gradle/libs.versions.toml`

*   **版本定义**:
    ```toml
    [versions]
    agp = "7.4.2"
    kotlin = "1.8.10"
    ```

## 4. 问题解决与变通

### Lint 兼容性问题
由于部分 AndroidX 库的 Lint 检查工具使用 Java 17 编译，导致在 Java 11 环境下运行时崩溃。

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

### 语法修正
*   将 `minifyEnabled` 修正为 `isMinifyEnabled` 以适应 Kotlin DSL 和 AGP 7.4.2。

## 5. 构建指南

在项目根目录下运行以下命令进行构建：

```bash
./gradlew build
```

构建成功后，您应该会看到 `BUILD SUCCESSFUL` 的消息。

## 6. Android Studio 同步问题

当在 Android Studio 中打开此项目并尝试同步时，可能会遇到以下错误：

```
Your build is currently configured to use incompatible Java 21.0.8 and Gradle 7.6.1. Cannot sync the project.

We recommend upgrading to Gradle version 9.0-milestone-1.

The minimum compatible Gradle version is 8.5.

The maximum compatible Gradle JVM version is 19.

Possible solutions:
 - Upgrade to Gradle 9.0-milestone-1 and re-sync
 - Upgrade to Gradle 8.5 and re-sync
```

### 问题分析

这个错误是因为项目当前配置的 Gradle 版本 (7.6.1) 与 Android Studio 默认用于运行 Gradle Daemon 的 Java 版本 (Java 21) 不兼容。Gradle 7.6.1 最高只支持到 Java 19。

Android Studio 提示升级 Gradle 的建议是基于它检测到的 Java 21 环境倒推的，而不是考虑项目实际需要的 Java 11 环境。

### 解决方案 (无需修改项目代码)

您需要调整 Android Studio 的设置，使其使用 **Java 11** 来运行 Gradle 守护进程。请按照以下步骤操作：

1.  打开 Android Studio。
2.  进入 **File > Settings** (Windows/Linux) 或 **Android Studio > Preferences** (macOS)。
3.  导航到 **Build, Execution, Deployment > Build Tools > Gradle**。
4.  在 **Gradle JDK** 选项中，选择您系统上安装的 **Zulu JDK 11**。如果列表中没有，可以点击 `Add JDK...` 并选择本机 Zulu JDK 11 安装目录。
5.  点击 **Apply** 和 **OK**。
6.  重新点击 **Sync Project with Gradle Files**（通常是工具栏上的大象图标）。

通过将 Android Studio 的 Gradle JDK 设置为 Java 11，您将解决此同步问题，并且可以正常在 IDE 中操作项目。

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
