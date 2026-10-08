# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分，不改变第三方已有权利。

当前仓库包含原创数据定义、契约测试、文档、维护工具及 Gradle Wrapper；未打包 Minecraft、Fabric、owo-lib、Inventory UI Framework、Botany Framework 或任何原生运行库。

| 组件 | 版本 | 来源与许可 | 使用和分发方式 |
|---|---|---|---|
| Gradle Wrapper | 8.8 | [Gradle](https://github.com/gradle/gradle/tree/v8.8.0)，Apache License 2.0 | 仓库包含生成的启动脚本与 Wrapper JAR；保留脚本版权头与 JAR 内的 `META-INF/LICENSE`；发行包 SHA-256 固定于 Wrapper 配置 |
| Protobuf Gradle Plugin | 0.9.6 | [protobuf-gradle-plugin](https://github.com/google/protobuf-gradle-plugin)，BSD 3-Clause | 仅构建插件，由 Gradle 下载，不包含在主 JAR 中 |
| Protocol Buffers（protoc / protobuf-java） | 4.36.2 | [Protocol Buffers](https://github.com/protocolbuffers/protobuf)，BSD 3-Clause | protoc 在构建时生成 Java 消息与描述集；protobuf-java 为公开运行依赖，由 Gradle 解析，当前 Java 库不将其内嵌或重打包；第三方原始许可随其自身依赖产物保留 |
| JUnit | 4.13.2 | [JUnit 4](https://github.com/junit-team/junit4/tree/r4.13.2)，Eclipse Public License 1.0 | 仅测试依赖，由 Gradle 下载，不包含在本项目主 JAR 中 |
| Hamcrest Core | 1.3 | [Java Hamcrest](https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3)，BSD 3-Clause | JUnit 的传递测试依赖，由 Gradle 下载，不包含在本项目主 JAR 中 |

项目结构参考 Kizuna's Inventory UI Framework；本轮没有复制其 MIT 运行时代码或 Wiki 发布脚本。许可证正文采用 PolyForm Noncommercial 1.0.0 标准文本。

未来加入第三方代码、字体、声音、模型、贴图或依赖时，必须登记来源、版本、许可与分发方式，保留其原始版权声明。许可证文件和本声明须随正式 JAR/原生库发行物一并提供。Minecraft 本体不随本项目分发。
