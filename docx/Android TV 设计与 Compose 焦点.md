# **深度解析 Android TV 应用架构：视觉规范、排版系统与 Jetpack Compose 高级导航交互**

## **1\. 绪论：10 英尺体验的独特性与工程挑战**

在数字交互设计的广阔版图中，Android TV 所代表的“10 英尺用户界面”（10-foot User Interface, 10-ft UI）占据着一个独特而复杂的生态位。与移动设备（手机、平板）的“精细触控”和桌面环境的“鼠标指针”截然不同，电视交互完全依赖于方向键（D-Pad）的离散导航。这种输入方式的根本性差异，结合观看距离（通常为 3 米/10 英尺）和显示硬件的多样性（从古老的 CRT 到现代 OLED 面板），对应用程序的架构设计提出了严苛的要求。  
本报告将深入探讨构建专业级 Android TV 应用所需的四大核心支柱：显示物理学中的过扫描（Overscan）与安全区域（Safe Area）处理、基于人类视觉工学的排版标准、Jetpack Compose 架构下的高级焦点恢复（Focus Restoration）机制，以及复杂嵌套视图中的滚动状态持久化技术。这些领域不仅关乎视觉美学，更是决定应用可用性与用户留存率的工程基石。

## **2\. 视觉物理学与显示规范：过扫描与安全区域的深度剖析**

### **2.1 过扫描（Overscan）的历史遗留与技术现状**

过扫描是指电视显示设备在渲染图像时，故意裁剪掉画面边缘部分区域的现象。这一技术实践最早追溯至阴极射线管（CRT）时代。在模拟信号传输时期，由于电子束偏转的不稳定性，画面的边缘往往会出现名为“消隐期”（Blanking Interval）的噪点或信号畸变。为了向观众呈现完美的矩形画面，电视制造商默认将图像放大，使这部分瑕疵落在物理屏幕边框之外 。  
尽管现代数字高清电视（HDTV）和 4K 面板已经消除了模拟信号的物理缺陷，但过扫描作为一个默认的出厂设置，依然广泛存在于大量消费级电视中。这是为了兼容旧有的广播信号标准，或者是为了掩盖因信号源缩放不当而产生的边缘黑边。对于 Android TV 开发者而言，这意味着应用渲染的 1920x1080 像素画面，其最外层的 5% 甚至更多区域可能根本无法被用户看到 。  
如果开发者忽视这一现象，将关键的导航按钮、文本信息或元数据放置在屏幕的绝对边缘，用户将面临信息截断或无法操作的严重问题。因此，建立一套严格的“安全区域”设计规范是电视应用开发的首要任务。

### **2.2 安全区域（Safe Area）的计算模型与设计标准**

Android TV 的设计规范明确指出，为了对抗过扫描带来的不确定性，应用必须在屏幕四周预留足够的安全边距。行业公认的标准是 **5%** 的安全边距 。这个标准并非随意制定，而是沿袭自 SMPTE（电影电视工程师协会）和 ITU-R（国际电信联盟无线电通信部门）的相关广播标准（如 ITU-R BT.1848），该标准详细定义了“动作安全区”（Action Safe Area）和“标题安全区”（Title Safe Area）。  
在现代 Android 开发中，我们通常将这两个概念合并为一个统一的“过扫描安全区”。

#### **2.2.1 分辨率与密度的换算逻辑**

虽然电视屏幕的物理分辨率通常为 1080p (1920x1080) 或 4K (3840x2160)，但在 Android 的布局系统中，为了保证跨设备的一致性，电视界面通常以 xhdpi 或 tvdpi 为基准进行缩放，逻辑分辨率通常被归一化为 **960dp x 540dp** 。这是一个至关重要的数值，所有的边距计算都应基于此逻辑尺寸，而非物理像素。

* **物理基准：** 1920px (宽) x 1080px (高)  
* **逻辑基准：** 960dp (宽) x 540dp (高)  
* **密度换算：** 在 1080p 屏幕上，1dp \= 2px（xhdpi 密度桶）。

#### **2.2.2 5% 边距的精确计算**

基于 960dp x 540dp 的逻辑画布，我们可以精确计算出 5% 安全边距的具体数值：

* **水平边距（左右）：**  
* **垂直边距（上下）：**

在某些文档或设计实践中，为了方便记忆和对齐网格系统，垂直边距有时会被舍入为 28dp 甚至 32dp，但 **48dp** 和 **27dp** 是基于 5% 规则的精确底线 。  
**表 1：不同分辨率下的安全区域参数对照表**

| 参数 | 逻辑分辨率 (dp) | 物理分辨率 (px) | 左/右边距 (5%) | 上/下边距 (5%) | 安全区域尺寸 |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **标准 HD (720p)** | 640 x 360 | 1280 x 720 | 32dp | 18dp | 576 x 324 dp |
| **全高清 (1080p)** | **960 x 540** | **1920 x 1080** | **48dp** | **27dp** | **864 x 486 dp** |
| **超高清 (4K)** | 960 x 540 (缩放) | 3840 x 2160 | 48dp | 27dp | 864 x 486 dp |

### **2.3 工程实现策略：XML 与 Compose 的差异**

在实现这些安全区域时，不同的开发框架有不同的最佳实践。

1. **Legacy Android Views (XML / Leanback):** 在使用旧版 Leanback 库（如 BrowseSupportFragment）时，框架内部已经硬编码了过扫描保护。如果开发者再次手动添加边距，会导致“双重边距”，界面显得极其狭窄。因此，在 Leanback 体系下，通常不需要手动设置根布局的 Padding 。但如果是自定义 XML 布局，则必须在根 ViewGroup 上应用 android:layout\_marginTop="27dp" 等属性。  
2. **Jetpack Compose:** Compose 提供了更灵活的控制权。现代设计强调“沉浸感”，背景图片（Hero Image）通常应该铺满整个屏幕（包括过扫描区域），而内容（文本、按钮）则需要限制在安全区域内。  
   * **错误做法：** 将 Modifier.padding(48.dp, 27.dp) 应用于整个屏幕的根 Box。这会导致背景图也被裁剪，一旦电视没有过扫描，用户会看到四周有尴尬的空白或黑边。  
   * **正确做法：** 根 Box 保持全屏（无 Padding）以容纳背景。内部的内容容器（如 Column 或 LazyRow）应用 Padding。

`// 推荐的 Compose 布局结构`  
`Box(modifier = Modifier.fillMaxSize()) {`  
    `// 背景层：全屏，无视过扫描，允许被裁剪`  
    `Image(`  
        `painter = painterResource(id = R.drawable.hero_bg),`  
        `contentDescription = null,`  
        `contentScale = ContentScale.Crop,`  
        `modifier = Modifier.fillMaxSize()`  
    `)`

    `// 内容层：严格遵守 5% 安全区域`  
    `Column(`  
        `modifier = Modifier`  
           `.fillMaxSize()`  
           `.padding(horizontal = 48.dp, vertical = 27.dp) // 关键边距`  
    `) {`  
        `Text("标题安全区内的内容", style = MaterialTheme.typography.displayLarge)`  
        `//... 其他组件`  
    `}`  
`}`

## **3\. 10 英尺界面的排版科学：距离与可读性的博弈**

“10 英尺用户界面”的核心挑战在于观看距离。当用户距离屏幕 3 米远时，屏幕上的文字在视网膜上投射的视角急剧减小。如果在电视上使用手机应用的字体大小（如 14sp 或 16sp），用户看到的将是一团模糊的像素点。

### **3.1 视觉敏锐度与角分辨率**

根据斯内伦视力表（Snellen chart）原理，为了保持同等的可读性，文字的物理尺寸必须随着观看距离的增加而线性增加。虽然电视屏幕的物理尺寸比手机大得多，但单位像素密度（PPI）要低得多。  
在 Android 手机设计中，14sp 是正文的标准大小。但在 Android TV 上，任何小于 **12sp** 的文字通常被认为是不可读的，甚至 **14sp** 也仅限于次要信息 。为了确保舒适的阅读体验，电视应用的字体体系必须进行整体性的“通货膨胀”。

### **3.2 1080p 环境下的推荐字体规范 (SP)**

基于 Material Design 3 的最新规范以及 ITU 对广播字幕的可读性研究，以下是在 1080p 画布（960dp x 540dp 逻辑分辨率）上的推荐最小字体大小标准。  
需要特别注意的是，电视界面通常使用深色模式（Dark Mode），在这种高对比度环境下（亮文本、暗背景），字体的字重（Weight）如果不加以控制，容易产生“光晕效应”。因此，尽量避免使用极细（Thin/ExtraLight）的字重作为正文 。  
**表 2：Android TV (1080p @ 3m) 推荐排版阶梯**

| 文本类别 (Category) | 用途示例 (Use Case) | 最小推荐值 (sp) | 理想推荐值 (sp) | 推荐字重 (Weight) | 备注 |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **Display / Hero Title** | 首页精选影片的巨大标题 | 57sp | **57sp \- 72sp** | Light / Regular | 用于视觉冲击，吸引注意力 。 |
| **Headline** | 分类标题、详情页主标题 | 32sp | **34sp \- 48sp** | Regular / Medium | 必须在 3 米外清晰可辨，引导视线。 |
| **Title** | 卡片标题、列表项名称 | 20sp | **24sp \- 30sp** | Medium | 这是用户浏览列表时最主要阅读的层级。 |
| **Body Text** | 剧情简介、长段落说明 | 16sp | **20sp \- 24sp** | Regular | **关键差异点：** 手机上正文是 14-16sp，电视上建议至少 20sp 以减轻眼部疲劳 。 |
| **Metadata / Caption** | 年份、时长、评分、角标 | 14sp | **16sp** | Medium | 绝对不应小于 14sp。小于此数值在某些低端面板上会产生锯齿。 |

### **3.3 深入分析：为何行业标准常常突破 16sp？**

虽然早期的 Material Design 指南曾建议 Body 文本可以使用 14sp ，但在实际的流媒体应用开发（如 Netflix, YouTube TV）中，设计师发现 14sp 对于剧情简介来说过于费力。Android Auto 的设计规范（同样是远距离、扫视型交互）甚至建议正文文本的最小值为 **24dp** 。  
在电视上，**可扫描性（Scannability）** 优于 **信息密度（Density）**。用户无法像在手机上那样通过手指捏合来缩放，因此开发者必须默认提供足够大的字号。  
**排版设计建议总结：**

* **层次感：** 使用字号差异而非仅仅颜色差异来区分层级，因为电视的色准和对比度在不同品牌间差异巨大。  
* **字重补偿：** 亮色文字在黑色背景上视觉上会显得比实际更粗。如果使用细体（Light），请务必增大字号。  
* **行高（Line Height）：** 电视排版需要更宽松的行高，建议设置为字号的 **1.4倍 至 1.6倍**，以防止行间视线跳跃 。

## **4\. Jetpack Compose TV 焦点恢复：TvLazyVerticalGrid 与 FocusRestorer**

在 D-Pad 导航体系中，焦点的管理是用户体验的核心。一个常见且极具破坏性的 UX 问题是“焦点丢失”或“焦点重置”。当用户在一个长列表中向下滚动浏览，点击进入详情页，然后按返回键时，如果焦点重置到了列表的第一个项目（左上角），用户的浏览上下文就丢失了。这种挫败感足以导致用户流失。

### **4.1 技术背景：FocusRestorer 的作用机理**

在 Jetpack Compose 中，当一个页面（Composable）从导航栈中弹出（Pop）并销毁时，其内部的所有状态（包括哪个 Item 拥有焦点）默认都会丢失。当页面重新进入重组（Recomposition）阶段时，系统默认会将焦点赋予第一个可聚焦的元素（FocusRequester.Default）。  
FocusRestorer（在较新的库中可能体现为 Modifier.focusRestorer 或自定义实现）的作用是保存离开页面时的焦点状态，并在页面重建时恢复它。这涉及到两个关键技术点：

1. **持久化存储：** 需要使用 rememberSaveable 来跨越重组和配置变更（如屏幕旋转，虽然电视很少旋转，但内存回收是存在的）保存焦点 ID。  
2. **延迟请求：** 在列表数据加载完成且布局上屏（Layout Phase）之后，由于使用了 Lazy 加载，目标 Item 可能尚未被实例化。因此需要结合滚动状态恢复和焦点请求。

### **4.2 架构迁移警告：androidx.tv 的变迁**

必须指出的是，根据最新的 AndroidX 发布说明 ，androidx.tv.foundation 包中的专用 Lazy 布局（如 TvLazyVerticalGrid, TvLazyRow）已被**弃用（Deprecated）**。Google 建议迁移至标准的 androidx.compose.foundation 中的 LazyVerticalGrid，并配合 TV 特有的修饰符。  
然而，鉴于用户明确请求了 TvLazyVerticalGrid 的示例，且许多现有项目仍在使用该 API，下文将提供基于 TvLazyVerticalGrid 的实现，同时展示通用的 FocusRestorer 逻辑，该逻辑同样适用于标准 Grid。

### **4.3 代码实现：带有焦点恢复功能的 TvLazyVerticalGrid**

此代码示例演示了如何构建一个网格，当用户导航离开并返回时，它能够记住并重新聚焦到之前选中的项目。  
`import androidx.compose.foundation.layout.PaddingValues`  
`import androidx.compose.foundation.layout.fillMaxSize`  
`import androidx.compose.foundation.layout.padding`  
`import androidx.compose.runtime.*`  
`import androidx.compose.runtime.saveable.rememberSaveable`  
`import androidx.compose.ui.Modifier`  
`import androidx.compose.ui.focus.*`  
`import androidx.compose.ui.unit.dp`  
`import androidx.tv.foundation.lazy.grid.TvGridCells`  
`import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid`  
`import androidx.tv.foundation.lazy.grid.itemsIndexed`  
`import androidx.compose.ui.ExperimentalComposeUiApi`  
`import androidx.tv.material3.Card`  
`import androidx.tv.material3.Text`

`// 假设的数据模型`  
`data class MovieItem(val id: String, val title: String)`

`/**`  
 `* 封装的 FocusRestorer 实现。`  
 `* 在 Compose TV 1.0.0-alpha11+ 中，官方提供了 Modifier.focusRestorer。`  
 `* 如果使用旧版本或需要自定义逻辑，核心思想是保存索引并请求焦点。`  
 `*/`  
`@OptIn(ExperimentalComposeUiApi::class)`  
`@Composable`  
`fun FocusRestoringTVGrid(`  
    `movies: List<MovieItem>,`  
    `onMovieClick: (MovieItem) -> Unit`  
`) {`  
    `// 1. 状态保存：用于记录最后一次获得焦点的 Item 索引。`  
    `// 使用 rememberSaveable 确保在导航栈切换（Back Stack Pop/Push）时数据不丢失。`  
    `var lastFocusedIndex by rememberSaveable { mutableIntStateOf(0) }`

    `// 2. FocusRestorer 机制`  
    `// 在较新的 Compose UI 版本中，我们可以使用 Modifier.focusRestorer。`  
    `// 这里我们演示一种显式的、兼容性更强的实现方式：`  
    `// 使用 FocusRequester 并不是给整个 Grid，而是利用 saved index 来决定初始化行为。`  
      
    `// 注意：TvLazyVerticalGrid 会自动处理滚动状态的恢复，但不会自动处理焦点的恢复。`  
    `// 我们需要配合 focusRestorer 修饰符。`

    `TvLazyVerticalGrid(`  
        `columns = TvGridCells.Fixed(4),`  
        `// 应用之前计算的安全区域边距`  
        `contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),`  
        `modifier = Modifier`  
           `.fillMaxSize()`  
            `// 3. 应用 FocusRestorer。`  
            `// 当此 Grid 重新进入视图并获得焦点控制权时，它会尝试将焦点分发给`  
            `// 之前保存的子节点（如果使用了 focusRestorer 库功能的化）。`  
            `// 或者，我们可以通过自定义逻辑，在 LaunchedEffect 中请求焦点。`  
           `.focusRestorer {`   
                `// 回调逻辑：如果恢复失败（例如数据变了），回退到默认策略（通常是第一个）`  
                `FocusRequester.Default`   
            `}`  
    `) {`  
        `itemsIndexed(movies) { index, movie ->`  
            `// 为每个 Item 创建（或复用）修饰符逻辑`  
              
            `key(movie.id) { // 显式使用 key 是 Lazy 列表状态管理的关键`  
                `MovieCardItem(`  
                    `movie = movie,`  
                    `index = index,`  
                    `// 4. 关键点：当 Item 获得焦点时，更新 lastFocusedIndex`  
                    `onFocused = { lastFocusedIndex = index },`  
                    `onClick = { onMovieClick(movie) }`  
                `)`  
            `}`  
        `}`  
    `}`  
`}`

`@Composable`  
`fun MovieCardItem(`  
    `movie: MovieItem,`  
    `index: Int,`  
    `onFocused: () -> Unit,`  
    `onClick: () -> Unit`  
`) {`  
    `Card(`  
        `onClick = onClick,`  
        `modifier = Modifier`  
           `.padding(8.dp)`  
           `.onFocusChanged { focusState ->`  
                `if (focusState.isFocused) {`  
                    `onFocused()`  
                `}`  
            `}`  
            `// 在某些自定义实现中，可能需要在此处根据 index == savedIndex`   
            `// 显式调用 focusRequester.requestFocus()，`  
            `// 但 Modifier.focusRestorer 通常能自动处理视图树中的焦点保存。`  
    `) {`  
        `Text(text = movie.title, modifier = Modifier.padding(16.dp))`  
    `}`  
`}`

**深入解析代码逻辑：**

1. **rememberSaveable 的必要性：** 当用户点击卡片进入详情页时，当前的 Grid 页面可能会被销毁（取决于 Navigation 库的配置）或者仅仅是进入后台。无论哪种情况，简单的 remember 可能会丢失数据。rememberSaveable 将数据序列化到 Bundle 中，是状态恢复的基石 。  
2. **Modifier.focusRestorer 的魔法：** 这个修饰符（处于 ExperimentalComposeUiApi）通过在 Compose 的 Modifier 节点树中保存“最后聚焦的子节点引用”来工作。当父节点（Grid）再次获得焦点时，它不再盲目地将焦点给第一个子节点，而是查询保存的记录，直接将焦点派发给那个子节点。  
3. **Lazy 加载的陷阱：** 如果用户滚动到了第 100 行，然后进入详情页。返回时，TvLazyVerticalGrid 会首先恢复滚动位置（这是 LazyListState 的默认行为）。一旦滚动位置恢复，第 100 行的 Item 就会被重组（Recompose）。此时，focusRestorer 发现目标子节点已经存在于树中，便能成功恢复焦点。如果滚动状态没有恢复，目标节点不存在，焦点恢复就会失败。因此，**滚动状态恢复是焦点恢复的前提**。

## **5\. 嵌套滚动视图的持久化记忆：LazyRow in LazyColumn**

Android TV 应用最经典的布局莫过于“Netflix 模式”：一个垂直滚动的 LazyColumn，其中每一行都是一个水平滚动的 LazyRow（通常用于展示不同的电影分类）。

### **5.1 痛点：水平滚动位置丢失**

当用户在垂直列表中向下滚动时，顶部的 LazyRow 会滑出屏幕。为了优化内存，LazyColumn 会回收（Recycle）或销毁这些滑出屏幕的 Composable 节点。当用户由于好奇滑回顶部时，LazyRow 被重新创建。  
**问题：** 重新创建的 LazyRow 会初始化一个新的 LazyListState，其默认滚动偏移量为 0。这就导致用户之前滑到的“第 10 个电影”的位置丢失了，重置回了开头。这在电视交互中是极差的体验。

### **5.2 解决方案：状态提升与 MapSaver**

要解决这个问题，我们不能让 LazyRow 自己管理状态，也不能仅仅在 LazyRow 的父级 Composable 中使用简单的 remember（因为父级 item 也可能被回收）。我们需要将所有行的滚动状态提升（Hoist）到屏幕级别的容器中，并使用 Map 来通过唯一的 ID（如分类 ID）索引这些状态。  
更进一步，为了防止屏幕旋转或进程重建导致数据丢失，我们需要自定义 Saver 来持久化这个 Map。

### **5.3 完整的代码解决方案**

以下代码展示了如何构建一个自定义的 rememberNestedScrollStates 钩子，它能自动保存和恢复所有嵌套行的滚动位置。  
`import androidx.compose.foundation.lazy.LazyColumn`  
`import androidx.compose.foundation.lazy.LazyListState`  
`import androidx.compose.foundation.lazy.LazyRow`  
`import androidx.compose.foundation.lazy.items`  
`import androidx.compose.runtime.Composable`  
`import androidx.compose.runtime.saveable.Saver`  
`import androidx.compose.runtime.saveable.rememberSaveable`  
`import androidx.compose.ui.Modifier`

`// 假设的数据结构`  
`data class Category(val id: String, val title: String, val items: List<String>)`

`/**`  
 `* 核心解决方案：为每个分类 ID 保存一个独立的 LazyListState。`  
 `* 使用自定义 Saver 确保这些状态能存活于进程重建。`  
 `*/`  
`@Composable`  
`fun rememberRowScrollStates(ids: List<String>): Map<String, LazyListState> {`  
    `// 定义 Saver：如何将 Map<String, LazyListState> 转换为可序列化的格式`  
    `val saver = Saver<MutableMap<String, LazyListState>, Map<String, List<Int>>>(`  
        `save = { map ->`  
            `// 将每个 State 转换为由 [index, offset] 组成的列表进行保存`  
            `map.mapValues { (_, state) ->`  
                `listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)`  
            `}`  
        `},`  
        `restore = { restoredMap ->`  
            `// 恢复时，利用保存的整数重建 LazyListState 对象`  
            `restoredMap.mapValues { (_, values) ->`  
                `LazyListState(`  
                    `firstVisibleItemIndex = values,`  
                    `firstVisibleItemScrollOffset = values`  
                `)`  
            `}.toMutableMap()`  
        `}`  
    `)`

    `// 使用 rememberSaveable 初始化 Map`  
    `val scrollStates = rememberSaveable(saver = saver) {`  
        `mutableMapOf()`  
    `}`

    `// 确保当前列表中的每个 ID 都有对应的 State`  
    `// 如果是新出现的 ID，则创建一个新的默认 State`  
    `ids.forEach { id ->`  
        `if (!scrollStates.containsKey(id)) {`  
            `scrollStates[id] = LazyListState()`  
        `}`  
    `}`

    `return scrollStates`  
`}`

`@Composable`  
`fun NestedScrollCatalogScreen(categories: List<Category>) {`  
    `// 1. 获取所有行的状态 Map`  
    `val scrollStateMap = rememberRowScrollStates(categories.map { it.id })`

    `LazyColumn {`  
        `items(categories, key = { it.id }) { category ->`  
            `// 2. 从 Map 中取出该行对应的持久化状态`  
            `// 这里的!! 是安全的，因为我们在 rememberRowScrollStates 中已经确保了 key 存在`  
            `val rowState = scrollStateMap[category.id]!!`

            `CategoryRow(`  
                `category = category,`  
                `scrollState = rowState`  
            `)`  
        `}`  
    `}`  
`}`

`@Composable`  
`fun CategoryRow(category: Category, scrollState: LazyListState) {`  
    `// 3. 将状态绑定到 LazyRow`  
    `// 即使此 Composable 被回收，scrollState 对象依然保存在父级的 Map 中`  
    `// 当此 Composable 重建时，它会使用同一个 scrollState 对象，从而恢复位置`  
    `LazyRow(`  
        `state = scrollState,`  
        `modifier = Modifier.padding(vertical = 8.dp)`  
    `) {`  
        `items(category.items) { item ->`  
            `// Item Content...`  
        `}`  
    `}`  
`}`

### **5.4 技术原理与性能考量**

1. **状态分离（State Hoisting）：** 这里的核心思想是将“UI 的生命周期”与“状态的生命周期”解耦。UI 节点（LazyRow）是短暂的，随滚动而生灭；但状态（LazyListState Map）是长久的，与屏幕生命周期绑定。  
2. **Saver 接口：** LazyListState 本身不能直接放入 Bundle，但它包含的两个整数（索引和偏移量）可以。自定义 Saver 充当了序列化器 。  
3. **内存性能：** 开发者可能会担心保存成百上千个 LazyListState 是否耗费内存。实际上，LazyListState 是一个非常轻量级的对象，主要只维护两个整型变量和一些观察者。即使保存 1000 行的状态，其内存占用也可忽略不计，但换来的用户体验提升是巨大的 。  
4. **Key 的重要性：** 在最外层的 LazyColumn 中，必须使用 key \= { it.id }。这不仅有助于性能优化，更是为了确保在数据刷新（如下拉刷新）导致列表顺序变化时，状态能够正确地映射到对应的内容上，而不是错误地映射到索引位置。

## **6\. 结论**

构建卓越的 Android TV 应用并非简单的移动端移植，而是一场针对“距离”、“输入方式”和“显示特性”的深度重构。  
通过严格执行 **48dp/27dp 的安全区域规范**，我们确保了内容的完整性；通过采纳 **20sp+ 的排版标准**，我们尊重了用户的视觉生理局限；通过 **FocusRestorer** 和 **MapSaver** 等 Compose 高级模式，我们解决了 D-Pad 导航中最棘手的状态丢失问题。这些技术细节的累积，最终将转化为用户在沙发上那份流畅、自然且沉浸的“10 英尺”体验。

#### **引用的文献**

1\. Layouts in the Leanback UI toolkit | Android TV, https://developer.android.com/training/tv/playback/leanback/layouts 2\. Overscan \- NESdev Wiki, https://www.nesdev.org/wiki/Overscan 3\. Layouts | TV \- Android Developers, https://developer.android.com/design/ui/tv/guides/styles/layouts 4\. Safe areas of wide-screen 16:9 aspect ratio digital productions \- ITU, https://www.itu.int/dms\_pubrec/itu-r/rec/bt/R-REC-BT.1848-1-201510-I\!\!PDF-E.pdf 5\. What screen size to design in for Android TV OTT app? \- Reddit, https://www.reddit.com/r/UIUX/comments/1mo7ch5/what\_screen\_size\_to\_design\_in\_for\_android\_tv\_ott/ 6\. Layouts for TV | Android Developers, https://spot.pcc.edu/\~mgoodman/developer.android.com/preview/tv/ui/layouts.html 7\. Ask me Anything: What minimum font-size for a high-density data ..., https://stephaniewalter.design/blog/what-minimum-font-size-for-a-high-density-data-web-app-do-you-suggest/ 8\. Mastering Mobile App Typography: Best Practices & Pro Tips, https://www.zignuts.com/blog/mastering-mobile-app-typography-best-practices-pro-tips 9\. Typography for TV applications \- coeno, https://www.coeno.com/en-blog/typography-for-tv-applications 10\. The Android/Material Design Font Size Guidelines, https://www.learnui.design/blog/android-material-design-font-size-guidelines.html 11\. Typography | Design for Driving \- Google for Developers, https://developers.google.com/cars/design/android-auto/design-system/typography 12\. Font Size Usage in UI/UX Design: Web, Mobile & Tablet \- Medium, https://medium.com/design-bootcamp/font-size-usage-in-ui-ux-design-web-mobile-tablet-52a9e17c16ce 13\. Migrating from tv lazy layouts to compose ... \- Issue Tracker \- Google, https://issuetracker.google.com/issues/348896032 14\. Android Jetpack Compose TV Focus restoring \- kotlin \- Stack Overflow, https://stackoverflow.com/questions/76281554/android-jetpack-compose-tv-focus-restoring 15\. mapSaver – Compose Runtime Saveable, https://composables.com/docs/androidx.compose.runtime/runtime-saveable/functions/mapSaver 16\. Nested Scroll with Jetpack Compose | by Syntia \- ProAndroidDev, https://proandroiddev.com/nested-scroll-with-jetpack-compose-9c3b054d2e12