# **2025年媒体中心型 Android 应用架构深度研究报告：Jetpack Compose for TV 与 Leanback 技术对比及高级实践**

## **1\. 引言：Android TV 开发范式的演进与 2025 年技术图景**

随着智能电视硬件算力的提升以及 Android TV 生态系统在全球范围内的持续扩张，用户对于大屏应用的交互体验、视觉流畅度以及功能复杂度的期待已达到前所未有的高度。在 2025 年的技术视阈下，构建高质量、沉浸式的媒体中心型应用（Media Center Applications）已不再仅仅是简单的内容展示，而是一场关于架构设计、状态管理与性能优化的深度工程挑战。  
媒体中心型应用，诸如 Netflix、Disney+ 或各类 OTT（Over-The-Top）平台，其核心交互逻辑深受“十英尺界面”（10-foot UI）设计哲学的制约与重塑。与移动端基于触摸的精确交互不同，TV 端用户完全依赖方向键（D-pad）遥控器进行操作。这种输入方式的根本性差异，使得“焦点”（Focus）取代了“点击”（Touch），成为了驱动 UI 状态流转的核心引擎。在这一背景下，如何构建一个响应迅速、路径清晰且能够智能记忆用户操作历史的焦点管理系统，成为了衡量应用质量的关键指标。  
长期以来，Google 提供的 Leanback 库作为 Android TV 开发的行业标准，通过提供高度封装的 UI 模板（如 BrowseFragment, DetailsFragment）极大地降低了开发门槛。然而，随着应用设计趋向高度定制化以及响应式编程范式的兴起，Leanback 基于传统 View 系统、Fragment 以及 MVP（Model-View-Presenter）架构的设计显得日益僵化，难以满足现代应用对灵活性和可维护性的需求。  
与此同时，Jetpack Compose 作为 Android 原生 UI 的现代工具包，凭借其声明式编程模型、强大的状态管理能力以及与 Kotlin 语言特性的深度结合，正在彻底重构 Android 开发的版图。Jetpack Compose for TV 的推出，并非简单的 UI 库移植，而是针对电视交互特性进行的底层重构。特别是随着 androidx.tv 库的演进，例如 2024 年中后期对 TvLazyRow 等专用组件的架构调整，标志着 Compose for TV 正逐步与移动端 Compose 基础库实现底层统一，从而为跨形态（Form-factor）代码复用铺平了道路。  
本报告旨在为资深 Android 工程师和系统架构师提供一份详尽的技术蓝图。报告将首先从架构哲学的维度，深入对比 Leanback 与 Jetpack Compose for TV 在 2025 年的实战表现；随后，针对 Compose for TV 开发中的两大核心痛点——嵌套导航（Nested Navigation）中的焦点控制与网格布局（Grid Layout）中的焦点恢复——提供基于最新 API（如 BringIntoViewSpec, FocusRequester）的健壮架构方案；最后，针对媒体播放的核心业务场景，探讨基于 ExoPlayer 的 MVI（Model-View-Intent）架构最佳实践，展示如何利用 Kotlin Flow 构建可预测的单向数据流。

## **2\. 技术深度对比：Jetpack Compose for TV vs. Leanback (2025)**

在 2025 年启动一个新的 Android TV 项目或重构现有应用时，技术选型是首要且最为关键的决策。尽管 Leanback 依然存在于大量遗留代码中，但其技术栈的陈旧与 Compose 的现代化优势形成了鲜明对比。

### **2.1 架构哲学：命令式 MVP 与声明式 MVVM/MVI 的碰撞**

#### **2.1.1 Leanback：继承重负下的命令式体系**

Leanback 库的设计深受 Android 早期开发模式的影响，其核心架构建立在 Fragment 和 MVP 模式之上。

* **MVP 模式的固化与样板代码**：Leanback 强制将 UI 逻辑与数据分离，通过 Presenter 来控制视图的渲染。虽然在理论上实现了关注点分离，但在实践中，这导致了大量的样板代码。每一种不同类型的卡片（Card）或列表行（ListRow）都需要实现特定的 Presenter。UI 的更新往往依赖于 notifyItemChanged 等命令式调用，状态同步容易出错。  
* **基于继承的扩展陷阱**：Leanback 的定制化主要通过继承复杂的基类（如 ListRowPresenter, BaseCardView）来实现。这种“继承优于组合”的设计导致了脆弱的类层级结构。当开发者需要实现设计师提出的非标准交互（例如：卡片在获得焦点时进行非线性的 3D 翻转并伴随动态光照效果）时，往往需要深入重写父类的内部逻辑，甚至通过反射去修改私有字段，这极大地增加了维护成本和崩溃风险。  
* **XML 主题系统的局限性**：Leanback 的样式严重依赖 XML 资源文件和特定的 Leanback 主题属性。这种非类型安全的资源管理方式，在处理动态主题（Dynamic Theming）——例如根据当前选中电影海报的主色调实时改变背景渐变色——时显得力不从心，通常需要繁琐的 Context 操作和运行时资源查找 。

#### **2.1.2 Jetpack Compose：函数式组合与单一事实来源**

Jetpack Compose 采用声明式 UI 范式，其核心理念是“UI 是状态的函数”（UI \= f(State)）。

* **组合优于继承**：Compose 摒弃了复杂的类继承体系。UI 组件通过函数的嵌套调用进行组合。例如，一个具备焦点缩放效果的电影卡片，可以通过组合 Card、Image、Text 以及 Modifier.onFocusChanged 和 Modifier.graphicsLayer 轻松构建。这种原子化的构建方式赋予了开发者极高的灵活性，任何复杂的 UI 都可以拆解为基础组件的组合。  
* **状态驱动的单一事实来源（SSOT）**：在 Compose 中，界面状态（如焦点位置、加载状态、播放进度）被提升（Hoisted）到 ViewModel 或状态持有者中。UI 仅仅是对当前状态的快照渲染。这种单向数据流（UDF）极大消除了 Leanback 中常见的视图状态与数据状态不一致的 Bug。  
* **Kotlin 驱动的样式系统**：Compose 的样式系统完全基于 Kotlin 代码。这意味着开发者可以利用 Kotlin 的完整语言特性（条件逻辑、循环、类型推断）来定义样式。实现动态主题仅需更改传递给 Composable 函数的参数，系统会自动处理重组（Recomposition）和过渡动画 。

### **2.2 焦点管理机制的底层差异**

焦点管理是 TV 应用的灵魂。Leanback 和 Compose 在处理焦点时的机制存在本质区别。

| 特性维度 | Leanback (View System) | Jetpack Compose for TV | 深度解析与 2025 年现状 |
| :---- | :---- | :---- | :---- |
| **焦点寻找算法** | 基于 View 树几何位置的邻近算法（Nearest Neighbor），隐式处理。 | 基于 FocusModifier 构建的独立焦点树（Focus Tree），显式与隐式结合。 | Leanback 的焦点寻找虽然自动化程度高，但在复杂的嵌套布局（如异形网格）中经常出现“焦点黑洞”或路径预测错误。Compose 通过 FocusRequester 和 Modifier.focusProperties 提供了对焦点转移路径的精细控制能力。开发者可以显式定义 next, previous, up, down 的目标，解决了自动算法的死角 。 |
| **高亮状态处理** | 依赖 OnItemViewSelectedListener 回调和 XML 中的 StateListDrawable。 | 依赖 Modifier.onFocusChanged 监听状态变化，结合 State 驱动重组。 | 在 Leanback 中，实现一个复杂的焦点动画（如放大并改变阴影）需要编写繁琐的 Animator 代码。在 Compose 中，利用 animate\*AsState（如 animateFloatAsState），可以轻松实现基于物理特性的流畅动画，且代码量仅为 Leanback 的几分之一 。 |
| **容器滚动协调** | BrowseSupportFragment 和 RowsSupportFragment 内部硬编码了滚动逻辑。 | 早期使用 TvLazyColumn，现已迁移至标准 LazyColumn 配合 BringIntoViewSpec。 | 这是一个关键的技术转折点。Compose 早期试图模仿 Leanback 创建专用的 TV 列表组件，但在 2024/2025 年，架构转向了统一的基础组件。通过自定义 BringIntoViewSpec，开发者可以数学级地精确控制获得焦点的项目在屏幕上的最终停留位置（锚点），比 Leanback 的预设行为更具可塑性 。 |

### **2.3 性能表现：视图回收 vs. 重组跳过**

#### **2.3.1 Leanback 的渲染管线**

Leanback 依赖 RecyclerView 的视图回收（View Recycling）机制。在处理含有成百上千张海报的网格时，视图对象的创建（Inflation）和绑定（Binding）是主线程的繁重任务。尽管 RecyclerView 已经高度优化，但在快速滚动场景下，复杂的 XML 布局解析依然可能导致掉帧（Jank）。此外，View 对象的内存占用相对较高。

#### **2.3.2 Compose 的渲染优化**

Compose 不存在“视图回收”的概念，取而代之的是“可组合项的生命周期”和“重组跳过”（Recomposition Skipping）。

* **智能重组**：当数据发生变化时，Compose 运行时会比较新旧数据。如果数据未变，Compose 会智能跳过该节点的执行。对于纯展示的电影卡片，这意味着极低的 CPU 开销。  
* **布局与绘制分离**：Compose 严格区分组合（Composition）、布局（Layout）和绘制（Drawing）阶段。利用 Modifier.graphicsLayer 进行焦点的缩放和位移操作，只会触发绘制阶段的更新，而不会触发布局重算（Relayout）。这对于 TV 大屏上常见的大规模焦点动画至关重要，能显著减少渲染延迟 。  
* **基准配置文件（Baseline Profiles）**：2025 年的 Android 构建工具链已默认集成基准配置文件生成。这使得 Compose 应用的启动速度和首次滚动性能得到了预编译级别的优化，有效缓解了早期版本存在的 JIT 编译开销问题 。

### **2.4 生态系统与维护现状**

必须明确指出，**Leanback 库已被 Google 官方标记为废弃（Deprecated）** 。这意味着它将不再接收任何新功能更新，仅限于关键的安全修复。相比之下，Compose for TV 是 Android TV UI 开发的未来主航道。新的 Material 3 TV 组件库、针对新硬件特性（如 4K/8K 优化、可变刷新率支持）的适配，都将优先甚至独占地在 Compose 生态中落地。对于 2025 年的企业级项目，继续选择 Leanback 无异于主动背负沉重的技术债务。

## **3\. 核心架构模式：处理嵌套导航（Nested Navigation）**

在媒体中心应用中，最经典且最考验架构能力的布局模式莫过于“嵌套导航”：一个垂直滚动的分类列表（Parent Column），其中每一项又是一个水平滚动的媒体内容列表（Child Row）。在 2025 年的 Compose for TV 开发中，这一模式的实现经历了从专用组件到通用组件的架构变迁。

### **3.1 架构变迁：从 TvLazy\* 到标准 Lazy\***

在 Compose for TV 的早期 Alpha 版本中，Google 提供了 TvLazyColumn 和 TvLazyRow。这些组件内置了处理 D-pad 焦点滚动和对齐的逻辑。然而，从 androidx.tv.foundation 1.0.0-alpha11 版本开始，**这些专用组件已被废弃并移除** 。这一决策旨在消除 Mobile 和 TV 之间基础组件的割裂，确立了“一套代码，多端适配”的架构方向。  
现在的最佳实践是使用标准的 androidx.compose.foundation 中的 LazyColumn 和 LazyRow，并通过 TV 特有的 BringIntoViewSpec 来注入焦点控制逻辑。

### **3.2 关键挑战：滚动锚点与视觉稳定性**

在触摸屏上，用户滑动列表的停止位置由手指动量决定。但在 TV 上，当焦点从一行移动到下一行时，系统必须自动计算滚动的目标位置。通常的设计需求是：

1. **垂直方向**：获得焦点的行（Row）不应贴着屏幕边缘，而应位于屏幕垂直方向的特定比例处（例如 30% 或中心），以便展示上方或下方的上下文。  
2. **水平方向**：获得焦点的卡片（Card）在行内滚动时，通常保持在起始位置，但在到达列表末尾时会有不同的行为。

### **3.3 架构模式实战：自定义 BringIntoViewSpec**

为了实现上述需求，我们需要实现 BringIntoViewSpec 接口。这是一个强大的 API，允许开发者接管滚动容器计算“如何将子项移入视野”的数学逻辑。  
以下展示了如何在 2025 年架构中实现这一模式：  
`// 导入标准 Foundation 组件，而非已废弃的 TvLazy 组件`  
`import androidx.compose.foundation.gestures.BringIntoViewSpec`  
`import androidx.compose.foundation.gestures.LocalBringIntoViewSpec`  
`import androidx.compose.foundation.lazy.LazyColumn`  
`import androidx.compose.foundation.lazy.LazyRow`

`/**`  
 `* 2025年 Android TV 嵌套导航标准架构模式`  
 `* 使用 CompositionLocal 注入自定义的滚动规范`  
 `*/`  
`@OptIn(ExperimentalFoundationApi::class)`  
`@Composable`  
`fun MediaCatalogScreen(`  
    `categories: List<Category>,`  
    `onItemClick: (MediaItem) -> Unit`  
`) {`  
    `// 1. 定义垂直方向的滚动规范（针对 Parent LazyColumn）`  
    `// 目标：当某一行获得焦点时，将其定位在屏幕高度的 30% 处，而不是顶部边缘`  
    `val verticalBringIntoViewSpec = remember {`  
        `object : BringIntoViewSpec {`  
            `override fun calculateScrollDistance(`  
                `offset: Float, // 当前子项相对于视口的偏移量`  
                `size: Float,   // 子项的大小`  
                `containerSize: Float // 容器（屏幕）的大小`  
            `): Float {`  
                `// 计算锚点位置：屏幕高度的 30%`  
                `val targetPosition = containerSize * 0.3f`  
                `// 返回需要的滚动增量：将当前 offset 调整到 targetPosition`  
                `return offset - targetPosition`  
            `}`  
              
            `// 可以自定义滚动动画规范，实现更平滑的弹簧效果等`  
            `// override val scrollAnimationSpec: AnimationSpec<Float>...`  
        `}`  
    `}`

    `// 2. 定义水平方向的滚动规范（针对 Child LazyRow）`  
    `// 目标：焦点项始终尝试位于行首（考虑 Padding），提供明确的阅读顺序`  
    `val horizontalBringIntoViewSpec = remember {`  
        `object : BringIntoViewSpec {`  
            `override fun calculateScrollDistance(`  
                `offset: Float,`  
                `size: Float,`  
                `containerSize: Float`  
            `): Float {`  
                `// 简单的起始对齐策略。`  
                `// 如果 offset 为正（在视口内），滚动使其贴边；`  
                `// 如果 offset 为负（在左侧外部），滚动使其贴边。`  
                `return offset`   
            `}`  
        `}`  
    `}`

    `// 3. 通过 CompositionLocalProvider 将垂直规范注入组件树`  
    `// 这个规范将影响其内部所有的可滚动容器，除非被覆盖`  
    `CompositionLocalProvider(`  
        `LocalBringIntoViewSpec provides verticalBringIntoViewSpec`  
    `) {`  
        `LazyColumn(`  
            `contentPadding = PaddingValues(bottom = 100.dp),`  
            `modifier = Modifier.fillMaxSize()`  
        `) {`  
            `items(categories) { category ->`  
                `// 标题`  
                `Text(`  
                    `text = category.title,`  
                    `style = MaterialTheme.typography.titleMedium,`  
                    `modifier = Modifier.padding(start = 16.dp, top = 24.dp)`  
                `)`  
                  
                `// 4. 嵌套的 CompositionLocalProvider，覆盖水平规范`  
                `// 仅影响内部的 LazyRow`  
                `CompositionLocalProvider(`  
                    `LocalBringIntoViewSpec provides horizontalBringIntoViewSpec`  
                `) {`  
                    `LazyRow(`  
                        `contentPadding = PaddingValues(horizontal = 16.dp),`  
                        `horizontalArrangement = Arrangement.spacedBy(16.dp),`  
                        `modifier = Modifier.fillMaxWidth()`  
                    `) {`  
                        `items(category.items) { mediaItem ->`  
                            `MediaCard(`  
                                `item = mediaItem,`  
                                `onClick = { onItemClick(mediaItem) }`  
                            `)`  
                        `}`  
                    `}`  
                `}`  
            `}`  
        `}`  
    `}`  
`}`

### **3.4 架构解析**

这一模式的核心优势在于**解耦**。UI 组件（LazyColumn）不再硬编码滚动逻辑，而是依赖环境配置（LocalBringIntoViewSpec）。这意味着我们可以轻松地为不同的屏幕（如主页、搜索页）定义不同的滚动体验，或者根据设备类型（TV vs. Tablet）动态切换滚动规范，而无需修改组件代码。这正是 Compose 声明式架构在 2025 年展现出的强大适应性。

## **4\. 深度解析：网格布局中的“焦点恢复”健壮逻辑**

### **4.1 场景与痛点**

在媒体应用中，网格布局（Grid Layout）是展示海量内容（如“所有电影”）的标准形态。一个典型的用户路径是：

1. 用户在网格中滚动，焦点停留在第 5 行第 3 列的卡片上（假设索引为 18）。  
2. 用户点击该卡片，导航至“详情页”。  
3. 用户在详情页按“返回”键。

在传统的 View 系统或 Compose 的默认行为中，当返回到网格页时，由于页面重组（Recomposition），焦点往往会重置到第一个可见项（Index 0）甚至丢失。**需求**是：焦点必须精确无误地恢复到索引 18 的卡片上，且该卡片必须自动滚动到可视区域内。

### **4.2 解决方案演进：从 LaunchedEffect 到 FocusRequester 体系**

早期的解决方案倾向于使用 LaunchedEffect 配合 LazyGridState.scrollToItem。然而，这种命令式的方法存在严重的**竞态条件（Race Condition）**：当数据尚未完全加载、布局尚未完成测量时，请求焦点会失败。更糟糕的是，如果目标项在屏幕可视区域之外（Off-screen），Compose 的 Lazy 机制根本不会组合（Compose）该项，导致对应的 FocusRequester 尚未初始化，从而引发崩溃或无效操作 。  
2025 年推荐的健壮架构是结合 **rememberSaveable**（状态持久化）、**FocusRequester**（显式焦点请求）以及 **延迟请求模式（Lazy Request Pattern）**。

### **4.3 核心实现代码：网格焦点记忆与恢复**

以下代码展示了如何构建一个能够跨越导航生命周期记忆焦点的网格组件。  
`import androidx.compose.foundation.layout.PaddingValues`  
`import androidx.compose.foundation.lazy.grid.GridCells`  
`import androidx.compose.foundation.lazy.grid.LazyVerticalGrid`  
`import androidx.compose.foundation.lazy.grid.itemsIndexed`  
`import androidx.compose.foundation.lazy.grid.rememberLazyGridState`  
`import androidx.compose.runtime.*`  
`import androidx.compose.runtime.saveable.rememberSaveable`  
`import androidx.compose.ui.Modifier`  
`import androidx.compose.ui.focus.FocusRequester`  
`import androidx.compose.ui.focus.focusRequester`  
`import androidx.compose.ui.focus.onFocusChanged`  
`import androidx.compose.ui.unit.dp`

`@Composable`  
`fun RobustFocusGridScreen(`  
    `mediaList: List<MediaItem>,`  
    `onMediaClick: (MediaItem) -> Unit`  
`) {`  
    `// 1. 状态持久化：使用 rememberSaveable 保存最后焦点的索引`  
    `// 这确保了即使 Fragment 被销毁或进程重建，索引依然保留`  
    `var lastFocusedIndex by rememberSaveable { mutableIntStateOf(0) }`

    `// 2. 保持 Grid 的滚动状态`  
    `// 当返回时，Grid 会尝试恢复到之前的滚动偏移量，这对于焦点恢复至关重要`  
    `// 因为它增加了目标 Item 在可视区域内的概率`  
    `val gridState = rememberLazyGridState()`

    `// 3. 焦点恢复信号锁`  
    `// 用于确保只在页面重新进入时执行一次焦点恢复，防止后续滚动时的干扰`  
    `var isFocusRestored by remember { mutableStateOf(false) }`

    `LazyVerticalGrid(`  
        `columns = GridCells.Fixed(4),`  
        `state = gridState,`  
        `contentPadding = PaddingValues(16.dp)`  
    `) {`  
        `itemsIndexed(mediaList) { index, item ->`  
            `// 为每个可见的 Item 实例化一个 FocusRequester`  
            `val itemRequester = remember { FocusRequester() }`

            `// 4. 关键逻辑：延迟请求模式`  
            `// 我们不试图在 Grid 层级请求焦点，而是在 Item 被组合（Composed）时请求。`  
            `// LazyVerticalGrid 只会组合可见区域附近的项。`  
            `// 当 gridState 恢复滚动位置后，目标 index 对应的 Item 会进入组合。`  
            `if (index == lastFocusedIndex &&!isFocusRestored) {`  
                `LaunchedEffect(Unit) {`  
                    `// 此时 Item 已进入组合，FocusRequester 已附加到 Modifier`  
                    `// 请求焦点不仅会激活状态，还会触发 BringIntoView，确保完全可见`  
                    `itemRequester.requestFocus()`  
                    `isFocusRestored = true`  
                `}`  
            `}`

            `MediaCard(`  
                `modifier = Modifier`  
                   `.focusRequester(itemRequester)`  
                   `.onFocusChanged { focusState ->`  
                        `// 5. 实时监听并更新最后焦点索引`  
                        `if (focusState.isFocused) {`  
                            `lastFocusedIndex = index`  
                        `}`  
                    `},`  
                `item = item,`  
                `onClick = { onMediaClick(item) }`  
            `)`  
        `}`  
    `}`  
`}`

### **4.4 逻辑深度解析**

1. **持久化机制**：rememberSaveable 是此架构的基石。它利用 Android 的 Bundle 机制，在 Activity/Fragment 的生命周期转换中保存数据。这是实现“跨屏记忆”的必要条件。  
2. **Laziness 的利用**：我们没有尝试在 Grid 层面去计算“第 15 个 item 在哪里”，而是利用了 Compose 的 Lazy 特性。当 gridState 恢复滚动位置使得第 15 个 item 变为可见时，该 item 的代码块会被执行（Recomposition）。此时，if (index \== lastFocusedIndex) 条件成立，LaunchedEffect 被触发，焦点请求随之发出。这种设计完美避开了“请求不可见元素焦点”的经典错误。  
3. **防止焦点抢夺**：引入 isFocusRestored 布尔值作为一次性锁（One-shot Latch）。一旦焦点成功恢复，该标志位翻转，防止用户后续主动滚动回该位置时，代码逻辑错误地再次强制夺取焦点，造成交互卡顿。

**进阶模式：FocusRequesterModifiers 封装** 对于更复杂的双面板（Master-Detail Panels）布局，为了实现从右侧网格返回左侧菜单时的焦点记忆，可以使用自定义的 FocusRequesterModifiers 类封装 focusProperties 的 enter 和 exit 逻辑 。

* **Exit**：当焦点离开容器时，保存当前子项状态。  
* **Enter**：当焦点试图进入容器时，拦截默认的“第一个子项”分配策略，强制导向保存的子项。

## **5\. 媒体播放架构：ExoPlayer 的 MVI 最佳实践**

在媒体中心应用中，播放器页面是状态最为复杂的部分。它涉及播放状态（Idle, Buffering, Ready, Ended）、进度更新、错误处理、媒体元数据加载等多个维度的状态变化。传统的 ExoPlayer 开发模式依赖于大量的 Player.Listener 回调，这往往导致 UI 代码中充满了命令式的状态同步逻辑，极易引发“UI 显示暂停，实际上正在播放”的 Bug。  
2025 年的最佳实践是采用 **MVI（Model-View-Intent）** 架构，利用 **Kotlin Flow** 将 ExoPlayer 的所有异构事件归一化为单一的、不可变的 UI 状态流。

### **5.1 架构分层设计**

1. **UI 层 (Compose)**：纯粹的消费者。订阅 StateFlow\<PlayerUiState\> 并渲染界面；将用户操作（点击播放、拖动进度条）封装为 PlayerIntent 发送给 ViewModel。  
2. **ViewModel 层**：状态持有者。处理 Intent，调用 Repository/Manager，并将结果转化为 State 更新。  
3. **PlayerManager (Repository)**：核心逻辑层。持有 ExoPlayer 实例，负责将 ExoPlayer 的回调系统转换为响应式的 Flow。

### **5.2 代码实现：将播放器事件映射到单向状态流**

#### **5.2.1 定义状态与意图**

首先，我们需要定义一个不可变的数据类来描述播放器的所有可能状态。  
`// 不可变的 UI 状态`  
`data class PlayerUiState(`  
    `val mediaItem: MediaItem? = null,`  
    `val isPlaying: Boolean = false,`  
    `val playbackState: Int = Player.STATE_IDLE,`  
    `val currentPosition: Long = 0L,`  
    `val duration: Long = 0L,`  
    `val bufferedPercentage: Int = 0,`  
    `val errorMessage: String? = null`  
`)`

`// 用户意图`  
`sealed class PlayerIntent {`  
    `data class PlayMedia(val url: String) : PlayerIntent()`  
    `object Pause : PlayerIntent()`  
    `object Resume : PlayerIntent()`  
    `data class SeekTo(val position: Long) : PlayerIntent()`  
    `object Release : PlayerIntent()`  
`}`

#### **5.2.2 PlayerManager：从 Listener 到 Flow 的转换**

这是架构的核心。我们需要使用 callbackFlow 来桥接基于回调的 ExoPlayer API 和基于流的 Kotlin Coroutines。  
特别注意：ExoPlayer 的 Listener **不会**回调当前的播放进度（currentPosition）。为了实现进度条的实时更新，我们需要在 Flow 内部自行构建一个轮询机制（Ticker）。  
`import android.content.Context`  
`import androidx.media3.common.MediaItem`  
`import androidx.media3.common.PlaybackException`  
`import androidx.media3.common.Player`  
`import androidx.media3.exoplayer.ExoPlayer`  
`import kotlinx.coroutines.CoroutineScope`  
`import kotlinx.coroutines.channels.awaitClose`  
`import kotlinx.coroutines.delay`  
`import kotlinx.coroutines.flow.*`  
`import kotlinx.coroutines.isActive`  
`import kotlinx.coroutines.launch`

`class PlayerManager(`  
    `private val context: Context,`  
    `private val externalScope: CoroutineScope // 应用级或 ViewModel 级 Scope`  
`) {`  
    `private val _player = ExoPlayer.Builder(context).build()`  
      
    `// 对外暴露的单向状态流`  
    `val playerState: StateFlow<PlayerUiState> = callbackFlow {`  
        `// 1. 发射初始状态`  
        `trySend(snapshotState(_player))`

        `// 2. 定义 ExoPlayer 监听器`  
        `val listener = object : Player.Listener {`  
            `override fun onEvents(player: Player, events: Player.Events) {`  
                `// 每当发生任何状态变更（播放/暂停、缓冲、错误等），快照当前状态并发射`  
                `trySend(snapshotState(player))`  
            `}`

            `override fun onPlayerError(error: PlaybackException) {`  
                `trySend(snapshotState(_player).copy(errorMessage = error.message))`  
            `}`  
        `}`

        `_player.addListener(listener)`

        `// 3. 处理进度更新：启动一个协程轮询进度`  
        `// ExoPlayer 不会通过 Listener 回调 currentPosition，必须手动轮询`  
        `val progressJob = launch {`  
            `while (isActive) {`  
                `if (_player.isPlaying) {`  
                    `trySend(snapshotState(_player))`  
                `}`  
                `delay(500) // 每 500ms 更新一次 UI 进度，平衡性能与流畅度`  
            `}`  
        `}`

        `// 4. 清理资源`  
        `awaitClose {`  
            `_player.removeListener(listener)`  
            `progressJob.cancel()`  
            `// 注意：player.release() 通常由 ViewModel 在 onCleared 中显式调用`  
            `// 或者在这里处理，取决于生命周期管理策略`  
        `}`  
    `}.stateIn(`  
        `scope = externalScope,`  
        `started = SharingStarted.WhileSubscribed(5000), // 关键：自动停止/重启流收集`  
        `initialValue = PlayerUiState()`  
    `)`

    `// 辅助函数：从 Player 对象生成纯数据状态快照`  
    `private fun snapshotState(player: Player): PlayerUiState {`  
        `return PlayerUiState(`  
            `mediaItem = player.currentMediaItem,`  
            `isPlaying = player.isPlaying,`  
            `playbackState = player.playbackState,`  
            `currentPosition = player.currentPosition,`  
            `duration = player.duration.coerceAtLeast(0L),`  
            `bufferedPercentage = player.bufferedPercentage`  
        `)`  
    `}`

    `// 处理 Intent：将用户意图转化为播放器指令`  
    `fun processIntent(intent: PlayerIntent) {`  
        `when (intent) {`  
            `is PlayerIntent.PlayMedia -> {`  
                `val item = MediaItem.fromUri(intent.url)`  
                `_player.setMediaItem(item)`  
                `_player.prepare()`  
                `_player.play()`  
            `}`  
            `PlayerIntent.Pause -> _player.pause()`  
            `PlayerIntent.Resume -> _player.play()`  
            `is PlayerIntent.SeekTo -> _player.seekTo(intent.position)`  
            `PlayerIntent.Release -> _player.release()`  
        `}`  
    `}`  
      
    `// 提供 Player 实例给 UI 层的 AndroidView (SurfaceView) 使用`  
    `fun getPlayerInstance() = _player`  
`}`

### **5.3 架构洞察与 2025 年趋势**

1. **进度更新的解耦**：上述代码中，我们将高频的进度更新（Polling）与低频的状态变更（Event Listener）合并到了同一个 Flow 中。这是 MVI 的精髓——UI 不需要知道数据来自回调还是轮询，它只需要渲染最新的 PlayerUiState。  
2. **生命周期感知**：stateIn(SharingStarted.WhileSubscribed(5000)) 的使用至关重要。当用户切出应用（应用进入后台）时，UI 停止收集 Flow，这会导致 WhileSubscribed 策略生效，自动停止内部的进度轮询协程，从而节省 CPU 资源。当用户切回时，Flow 重新活跃，轮询自动恢复。  
3. **Composable 集成**：在 UI 层，开发者只需使用 AndroidView (封装 PlayerView) 或 Compose for TV 提供的 PlayerSurface，并将 ViewModel 提供的 playerState 通过 collectAsStateWithLifecycle() 转换为 Compose State。这彻底消除了 View 系统中常见的因生命周期处理不当导致的内存泄漏问题。

## **6\. 性能优化与总结**

### **6.1 性能优化关键点**

在 TV 这种低算力、高分辨率的设备上，Compose 的性能优化尤为关键：

* **避免过度重组**：在 MediaCard 中，确保传递给回调的 Lambda 是稳定的（Stable）。使用方法引用（::）或 remember 包裹 Lambda。  
* **使用 DerivedStateOf**：在监听滚动状态（如 gridState.firstVisibleItemIndex）来触发背景图模糊渐变时，务必使用 derivedStateOf 来过滤高频变化，只在计算结果真正改变时触发重组。  
* **Baseline Profiles**：务必为 TV 应用生成基准配置文件。这可以将应用冷启动时间缩短 30% 以上，并显著减少首次滑动列表时的卡顿（Jank）。

### **6.2 结论**

2025 年的 Android TV 开发已发生范式转移。虽然 Leanback 依然存在，但其架构的封闭性已无法满足现代应用的需求。Jetpack Compose for TV 虽然经历了 API 的剧烈迭代（如 TvLazy 组件的废弃与统一），但目前已形成了一套成熟、健壮的架构体系。  
通过掌握 **BringIntoViewSpec** 实现精确的嵌套导航控制，利用 **rememberSaveable \+ FocusRequester** 解决焦点恢复难题，以及应用 **MVI \+ Flow** 架构驾驭复杂的媒体播放状态，开发者可以构建出在交互体验、代码可维护性和性能表现上均超越传统 Leanback 应用的次世代媒体中心。这不仅是代码层面的重构，更是对 TV 用户体验精细化控制能力的全面升维。

#### **引用的文献**

1\. Migrating from Leanback to Jetpack Compose in Android TV, https://www.tothenew.com/blog/migrating-from-leanback-to-jetpack-compose-in-android-tv/ 2\. What's New in Jetpack Compose \- Android Developers Blog, https://android-developers.googleblog.com/2025/05/whats-new-in-jetpack-compose.html 3\. Notes of Android Item on Google IO 2025\. : r/androiddev \- Reddit, https://www.reddit.com/r/androiddev/comments/1krzcf1/notes\_of\_android\_item\_on\_google\_io\_2025/ 4\. Leanback UI toolkit libraries | Android TV \- Android Developers, https://developer.android.com/training/tv/playback/leanback/leanback-libraries 5\. Migrating from tv lazy layouts to compose ... \- Issue Tracker \- Google, https://issuetracker.google.com/issues/348896032 6\. Android Jetpack Compose TV Focus restoring \- kotlin \- Stack Overflow, https://stackoverflow.com/questions/76281554/android-jetpack-compose-tv-focus-restoring 7\. (Android TV) Advanced — Focus Requester Manipulation, https://oleksii-tym.medium.com/android-tv-advanced-focus-requester-manipulation-7569e818a734