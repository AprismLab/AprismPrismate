# AprismPrismate

Aprism | AprismPrismate 是一个运行在 Fabric 与 NeoForge 内部的 Minecraft Java 版模组，
让这些加载器能够加载 Aprism 原生的 `*.aje` 模组。（`forge/` 模块是可见的占位 stub；
经典 Forge 延后到 1.0 之后。）

> 作者：BlockConnect@StarsailsClover | 许可：Apache-2.0
> [Aprism](https://github.com/NDBlockConnect/Aprism) 的配套仓库。

## 这是什么

Aprism 原生加载器通过 javaagent 直接加载 `.aje`。但如果用户已经在用 Fabric / NeoForge，
不想切换到 Aprism agent，Prismate 就是那座桥：它作为宿主加载器的一个普通模组运行，
把 Aprism 运行时（重定位后）嵌入自身，扫描 `mods/` 目录中的 `.aje` 包，
提取其中的模组 jar / 资源 / mixin，注入宿主加载器的类加载器，并驱动 Aprism 的完整生命周期
（`PREINIT -> INIT -> SETUP -> COMPLETE`，然后是 `CLIENT` / `SERVER` 侧）。

它是 [AprismRefract](https://github.com/NDBlockConnect/AprismRefract) 的镜像：
Refract 把其它加载器带进 Aprism；Prismate 把 Aprism 带进其它加载器。两者构成生态双向互通。

## 文档

- 英文正本（权威）：[docs/01-architecture-design.md](docs/01-architecture-design.md)（架构设计）
  与 [docs/02-developer-guide.md](docs/02-developer-guide.md)（开发者实现指南）
- 本中文摘要与英文正本同步维护

## 核心设计要点（中文摘要）

1. **忠实嵌入运行时**：直接复用 Aprism 的 `AprismManifestParser` / `DependencyResolver`，
   重定位到 `com.aprism.prismate.internal`，但**不重定位** `com.aprism.api`，保证模组绑定的 API 类一致。
2. **委托宿主加载器**：不自建类加载层级，把提取出的 jar / 资源注入宿主加载器的类加载器与生命周期。
3. **一码两载**：`common/` 共享逻辑，`fabric/` 与 `neoforge/` 各自入口（`forge/` 是拒绝启动的 stub）。
4. **与 Aprism agent 互斥**：同一实例不能同时装 Prismate 和 Aprism agent，Prismate 检测到 agent 会拒绝启动。
5. **失败可见**：坏包、缺依赖、版本不符都必须给出可读的具名错误，绝不静默跳过。
6. **上游同步纪律**：JE 版本线（`PrismateVersionLine`）与绑定的 Aprism mod API 由一致性测试守护，
   被嵌入的 Aprism 核心若发生漂移会立即 loudly 失败（v26.1-Alpha.1 / Alpha.6）。

## 生命周期映射（摘要）

| Aprism 阶段 | Fabric | NeoForge |
|---|---|---|
| PREINIT/INIT/SETUP | 自身 ModInitializer 早期 | @Mod 构造器 |
| CLIENT | ClientModInitializer | FMLClientSetupEvent |
| SERVER | DedicatedServerModInitializer | FMLDedicatedServerSetupEvent |
| COMPLETE | GAME_READY 等后期事件 | 生命周期后期事件 |

## 版本与发布

- 版本规范与 Aprism 家族一致：`v<年份>.<小版本>[-Alpha.<n>]`，与被嵌入的 Aprism 核心同小版本线。
- 制品命名：`AprismPrismate-v26.1-Alpha.6-Fa-26.2.jar`（Fabric）、`-N-`（NeoForge）。
- cosign 无密钥签名 + SHA-256 校验 + CycloneDX SBOM + GitHub Pre-Release，与 Aprism / Refract 流程一致。

## 支持的 Minecraft 版本（JE 线，v26.1+）

Prismate 覆盖 JE 线 `1.20 .. 26.2`（镜像 Aprism 的 `VersionLineRegistry`），并已通过真实游戏验证落地：

| 段 | 加载器支持 | 真实游戏已验证 | 说明 |
|---|---|---|---|
| 26.x | Fabric + NeoForge | 生命周期 + mixin + 资源 + soak | 未混淆，无需重映射 |
| 1.21.x | Fabric | 生命周期 + 资源 | 混淆；Intermediary 重映射是 Aprism agent 的职责 |
| 1.20.x | Fabric | 生命周期 + 资源 | 混淆；同样的重映射边界 |

- **NeoForge 仅支持 26.x**（v26.1）：桥接面向 FML 11；NeoForge 1.20.2-1.21.x 运行 FML 2-4（不同的 API），不在 v26.1 范围内。这些段由 Fabric 桥接覆盖。
- **Forge（经典）是 stub**，会以具名错误拒绝启动（延后到 1.0 之后）。
- **Java 运行时下限是 21**，而非 1.20/1.21 官方的 Java 17：被嵌入的 Aprism API 是 Java 21 字节码（上游 Aprism 以 `--release 21` 编译）。`fabric.mod.json` 保持 `java: ">=21"`，加载器不会把 Prismate 装进 Java 17 环境。MC 1.20+ 向后兼容更新的 JVM，因此 1.20.x/1.21.x 在 Java 21 下运行。
- 模组包按原样加载；Prismate 不做混淆类的重映射（docs/01 §13，第 6-7 条）。

## 安装

把 Prismate 放进宿主加载器的 `mods/`，再把 Aprism 的 `.aje` 模组放进同一实例的 `mods/`。
**不要**在同一实例再装 Aprism javaagent（两者互斥）。
