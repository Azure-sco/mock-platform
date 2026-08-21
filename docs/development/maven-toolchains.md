# Maven Toolchains

## 为什么必须使用

Java 8 SDK 不能只在 JDK 17 上用 `source=8/target=8` 编译。Reactor 在每个模块的 validate 阶段按 `<java.version>` 选择真实 JDK，compiler、Surefire 和 Failsafe 都继承该 toolchain。

Maven 主进程本身必须由 JDK 17 启动。Spring Boot 3 的 Maven 插件运行在 Maven JVM 中，不使用项目 toolchain；若以 JDK 8 启动 Maven，Boot 3 模块会在打包阶段因 class file version 不兼容而失败。

## 本地配置

复制示例：

```powershell
$env:MOCK_JDK17_HOME = '<JDK 17 安装目录>'
$env:JAVA_HOME = $env:MOCK_JDK17_HOME
Copy-Item .mvn\toolchains.xml.example .mvn\toolchains.xml
```

将 `jdkHome` 分别改为本机 JDK 8、JDK 17 根目录。实际文件被 Git 忽略；示例只保留占位路径。

```xml
<toolchain>
  <type>jdk</type>
  <provides><version>8</version><vendor>any</vendor></provides>
  <configuration><jdkHome>C:\path\to\jdk8</jdkHome></configuration>
</toolchain>
```

JDK 17 配置同理。执行：

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml clean verify
```

日志必须分别出现类似：

```text
mock-client-core: Toolchain ... JDK 8
mock-client-boot2-starter: Toolchain ... JDK 8
mock-platform-runtime: Toolchain ... JDK 17
mock-client-boot3-starter: Toolchain ... JDK 17
```

## 模块归属

Java 8：annotation、core、config-spi、Apollo adapter、Boot 2 starter、JDK 8 sample。

Java 17：Nacos adapter、Boot 3 starter、platform common/control/runtime、Fake Real、JDK 17 sample。

JDK 8 的 Failsafe E2E 会从同一 toolchains 文件找到 JDK 17，独立启动已打包的 Runtime 进程；测试本身和 Sample 仍在 JDK 8 上运行。

## CI 要求

CI runner 必须安装两套 JDK，并生成私有 toolchains 文件。不要提交机器路径；缺少任一 toolchain 时构建应直接失败，不得回退到当前 Maven JVM。

CI 的 Maven launcher 同样必须使用 JDK 17；JDK 8 模块仍由 toolchain 交给真实 JDK 8 编译和测试。
