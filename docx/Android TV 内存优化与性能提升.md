# **针对资源受限 Android TV 终端的高性能图像渲染与内存架构优化深度研究报告**

## **1\. 摘要与硬件限制分析**

在 Android TV 应用开发的广阔领域中，针对低端硬件的优化始终是一个巨大的技术鸿沟。尽管高端设备如 Nvidia Shield 或 Sony Bravia 原生电视配备了充足的资源，但全球市场——特别是亚洲和新兴市场——仍被搭载 Amlogic（晶晨）S905 系列或 Rockchip（瑞芯微）RK33xx 系列芯片的机顶盒（STB）和智能电视所主导。这些设备最显著的特征是仅配备 1GB 物理内存（RAM），且通常运行 Android 7.0 至 11.0 版本的操作系统。对于图像密集型应用（如 Bilibili、Netflix 类流媒体客户端）而言，这种硬件环境构成了一个极其严苛的“Procrustean bed”（普罗克汝斯忒斯之床）：应用必须在极度受限的内存预算内，维持 1080p 甚至 4K UI 的 60fps 流畅渲染，任何超出预算的行为都将直接导致应用崩溃（OOM）或严重的界面卡顿（Jank）。

### **1.1 1GB 统一内存架构（UMA）的残酷数学**

要理解为何 1GB 内存对于 Android TV 如此致命，必须剖析统一内存架构（UMA）下的内存映射。在 PC 或独立显卡系统中，显存（VRAM）通常是独立的；而在 Amlogic/Rockchip SoC 中，GPU 共享系统主内存。

1. **内核与硬件保留（Kernel Reserved）：** Linux 内核、驱动程序以及用于视频解码的 ION 缓冲区（CMA, Contiguous Memory Allocator）通常占用 250MB \- 350MB。在播放 4K HEVC 视频时，硬件解码器会锁定大量物理内存用于帧缓冲，这些内存对上层应用是不可见的。  
2. **系统服务（System Server）：** Android 框架服务、SurfaceFlinger、InputManager 等常驻进程占用约 300MB。  
3. **用户空间可用内存（User Space Available）：** 留给前台应用的实际可用物理内存往往仅剩 300MB \- 400MB。

在这种环境下，一张全高清（1920x1080）的 ARGB\_8888 位图占用的内存为：  
一个典型的视频流媒体首页可能包含 20-30 个缩略图。如果应用未能正确处理图片加载，仅仅是第一屏的位图数据就可能消耗：  
这还没有计算双缓冲（Double Buffering）、应用代码堆（Java Heap）、以及 Jetpack Compose 或 View 系统的额外开销。一旦触发 Linux 内核的低内存查杀守护进程（LMK, Low Memory Killer），应用进程就会被无情终止。因此，内存优化在 Android TV 上不是“锦上添花”，而是生存的基石 。

## **2\. 图像加载库的深度架构：Coil 与 Glide 在低内存环境下的博弈**

在 Android 生态中，Glide 和 Coil 是两大主流图像加载解决方案。虽然它们的目标一致，但在内存管理策略、位图池实现以及对现代 Android 特性（如协程、Compose）的支持上存在显著差异。针对 1GB RAM 设备，我们需要深入源代码层面进行剖析和配置。

### **2.1 Glide：位图池（Bitmap Pooling）的终极防御**

Glide 历来被视为 Android 图片加载的“重型武器”，其核心优势在于极其激进且高效的位图池机制。在 Android 6.0 之前，Dalvik 虚拟机的垃圾回收（GC）机制不够成熟，频繁分配和回收位图会导致严重的“Stop-the-World”卡顿。Glide 通过 LruBitmapPool 解决了这一问题。

#### **2.1.1 LruBitmapPool 与 AttributeStrategy**

Glide 的位图池利用了 BitmapFactory.Options.inBitmap 属性。当一个图片从视图中被回收（例如 RecyclerView 滚动出屏幕）时，Glide 并不会释放其底层的像素数据内存，而是将其放入池中。当需要加载新图片时，如果池中存在大小和配置匹配的位图，Glide 会直接复用这块内存，从而避免了 malloc 系统调用和内存页的清零操作。  
在低内存设备上，这种机制是一把双刃剑：

* **优势：** 显著减少内存抖动（Memory Churn）和碎片化。  
* **风险：** 如果池太大，会占用宝贵的系统内存，导致 LMK 误杀；如果池太小，复用率低，导致 CPU 消耗在反复的内存分配上 。

#### **2.1.2 针对 1GB 设备的 Glide 深度配置**

默认的 Glide 配置是为拥有 4GB+ 内存的手机设计的。在 Android TV 上，必须通过自定义 AppGlideModule 强制进行“降级”配置。

| 配置项 | 默认行为 | 1GB TV 优化建议 | 技术原理 |
| :---- | :---- | :---- | :---- |
| **解码格式** | ARGB\_8888 | RGB\_565 | RGB\_565 每像素仅占 2 字节，内存占用减少 50%。虽然不支持透明通道，但对于 Bilibili 等视频封面（通常为不透明矩形）完全适用 。 |
| **位图池大小** | 动态计算 (约 4 屏) | 1.5 \- 2 屏 | 限制缓存池大小，防止其吃掉其它系统组件所需的内存。 |
| **内存缓存** | 动态计算 | 1.5 屏 | 减少 LruCache 的上限，优先保证当前屏幕的渲染内存。 |
| **硬件位图** | 启用 (Android O+) | **禁用** | 低端 Mali GPU 对硬件位图支持不佳，且显存与内存共享，使用硬件位图并不能节省物理内存，反而可能导致渲染崩溃 。 |

**代码实现：自定义 GlideModule**  
`@GlideModule`  
`class LowRamTvGlideModule : AppGlideModule() {`  
    `override fun applyOptions(context: Context, builder: GlideBuilder) {`  
        `// 1. 强制 RGB_565：内存减半`  
        `builder.setDefaultRequestOptions(`  
            `RequestOptions().format(DecodeFormat.PREFER_RGB_565)`  
        `)`

        `// 2. 激进的内存计算器配置`  
        `val calculator = MemorySizeCalculator.Builder(context)`  
           `.setMemoryCacheScreens(1.5f) // 仅缓存 1.5 屏图片`  
           `.setBitmapPoolScreens(2.0f)  // 位图池保留 2 屏`  
           `.setLowMemoryMaxSizeMultiplier(0.3f) // 极低内存模式：仅使用计算值的 30%`  
           `.build()`

        `builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))`  
        `builder.setBitmapPool(LruBitmapPool(calculator.bitmapPoolSize.toLong()))`

        `// 3. 禁用硬件位图，防止 GPU 显存溢出导致的 Crash`  
        `builder.setDefaultRequestOptions(RequestOptions().disallowHardwareConfig())`  
    `}`  
`}`

此配置通过 MemorySizeCalculator 的微调，强制 Glide 承认设备的“贫困”状态，通过牺牲一定的重加载 CPU 周期来换取内存的稳定性 。

### **2.2 Coil：Kotlin 时代的轻量化选择与陷阱**

Coil (Coroutine Image Loader) 是专为 Kotlin 和 Jetpack Compose 设计的现代化库。它利用 Kotlin 协程来简化异步加载流程，且体积极小（约 Glide 的 1/3）。然而，Coil 在设计之初更依赖现代 Android Runtime (ART) 的高效 GC，默认的位图复用策略不如 Glide 激进，这对 1GB 设备构成了挑战。

#### **2.2.1 内存缓存策略与生命周期绑定**

在 Android TV 上使用 Coil，最大的错误是未能正确管理 ImageLoader 的单例。如果在每个 Composable 或 Activity 中创建新的 ImageLoader，将导致内存缓存无法共享，瞬间耗尽内存。  
**Coil 内存优化策略：**

1. **全局单例：** 必须在 Application 级别通过 ImageLoaderFactory 实现单例。  
2. **百分比限制：** Coil 允许通过 maxSizePercent 设置缓存占用比例。在 1GB 设备上，建议设置为 15%-20%，绝对值不应超过 30-50MB 。  
3. **弱引用控制：** 启用 weakReferencesEnabled。这允许已不在屏幕上但未被回收的图片被重新“复活”，利用 GC 的间隙来提升缓存命中率 。

#### **2.2.2 硬件位图（Hardware Bitmaps）的灾难**

在 Amlogic 芯片的 Mali-450 或 G31 GPU 上，硬件位图（Bitmap.Config.HARDWARE）经常引发兼容性问题。硬件位图的数据存储在显存中，且是不可变的（Immutable）。

* **问题现象：** 在 Jetpack Compose 进行共享元素转场（Shared Element Transition）或使用 Palette 提取颜色时，如果图片是硬件位图，会直接抛出 java.lang.IllegalArgumentException: Software rendering doesn't support hardware bitmaps 异常或导致应用闪退。  
* **解决方案：** 必须在 ImageLoader 或 ImageRequest 中显式设置 allowHardware(false) 。

**代码实现：针对 1GB 设备的 Coil ImageLoader 工厂**  
`class TvApplication : Application(), ImageLoaderFactory {`  
    `override fun newImageLoader(): ImageLoader {`  
        `return ImageLoader.Builder(this)`  
           `.memoryCache {`  
                `MemoryCache.Builder(this)`  
                    `// 激进限制：仅使用 15% 的可用 RAM 作为缓存`  
                   `.maxSizePercent(0.15)`  
                    `// 启用弱引用以利用 GC 间隙`  
                   `.weakReferencesEnabled(true)`  
                   `.strongReferencesEnabled(true)`  
                   `.build()`  
            `}`  
           `.diskCache {`  
                `DiskCache.Builder()`  
                   `.directory(cacheDir.resolve("image_cache"))`  
                    `// 限制磁盘缓存为 100MB，防止低端 eMMC 存储 I/O 拥堵`  
                   `.maxSizeBytes(100L * 1024 * 1024)`   
                   `.build()`  
            `}`  
            `// 关键：强制 RGB_565，禁用硬件位图`  
           `.bitmapConfig(Bitmap.Config.RGB_565)`  
           `.allowHardware(false)`   
            `// 优化：将解码任务调度到 IO 线程，避免阻塞 UI 线程（Main）`  
           `.fetcherDispatcher(Dispatchers.IO)`  
           `.transformationDispatcher(Dispatchers.Default)`  
           `.build()`  
    `}`  
`}`

此代码不仅限制了内存，还通过 Dispatchers.IO 确保了在单核性能较弱的 CPU 上，图片解码不会抢占主线程的时间片，从而减少 D-pad 导航时的输入延迟 。

## **3\. 服务器端优化：Bilibili 图片 CDN 参数逆向工程**

即便客户端的内存优化再完美，如果在 200x200 的 ImageView 中加载一张 4K 原图，也是资源的巨大浪费。服务器端缩放（Server-Side Scaling/Resizing）是降低带宽和内存压力的最有效手段。

### **3.1 Bilibili CDN 参数体系解析**

Bilibili 的图片存储系统（通常涉及 hdslb.com 域名）支持一套基于 URL 后缀的动态处理语法。通过在图片 URL 后添加 @ 符号及一系列参数，可以让 CDN 节点实时处理并返回优化后的图片 。  
**核心语法结构：**  
`@[宽度]w_[高度]h_[质量]q_[格式].[扩展名]`

### **3.2 关键参数详解表**

根据调研 ，以下是针对 Android TV 优化的关键参数列表：

| 参数代码 | 示例 | 含义 | 优化作用 | 推荐值 (TV场景) |
| :---- | :---- | :---- | :---- | :---- |
| **w** | 320w | Width (宽) | 指定目标宽度，按比例缩放。 | 根据 UI 容器实际像素决定（如 320, 480, 640） |
| **h** | 180h | Height (高) | 指定目标高度。 | 同上 |
| **q** | 75q | Quality (质量) | 图片压缩质量 (1-100)。 | **75** (在电视距离观看肉眼难以分辨，体积减少显著) |
| **c** | 1c | Crop (裁剪) | 是否裁剪。1c 表示启用裁剪。 | **1c** (配合宽高使用，确保填满容器不留黑边) |
| **e** | 1e | Resize Logic | 缩放逻辑。0: 保留比例取其小; 1: 保留比例取其大; 2: 强制缩放。 | **1e** (通常配合 1c 使用以实现 CenterCrop 效果) |
| **o** | 0o/1o | Unknown | 作用未知，部分文档提及可能与自动旋转有关。 | 建议忽略 |
| **格式后缀** | .webp | Format | 强制转换格式。 | **.webp** (体积比 JPEG 小 30-40%) |
| **WebP 宏** | @web\_webp | Web Preset | B站 Web 端常用的预设，通常对应 WebP 格式。 | 可作为兜底策略 |

### **3.3 动态参数注入策略**

在应用中，不应硬编码这些参数，而应根据 UI 组件的实际大小动态生成。  
**实现逻辑：**

1. **拦截请求：** 使用 Coil 的 Interceptor 或 Glide 的 ModelLoader。  
2. **获取尺寸：** 读取 ImageRequest 中的 Size（即 View 或 Composable 的测量尺寸）。  
3. **规整化（Bucketing）：** 将请求尺寸向上取整到最近的阶梯值（例如 100px, 200px, 300px）。这样做是为了提高 CDN 缓存命中率，避免因为 1px 的差异导致 CDN 认为是新图片而回源。  
4. **重写 URL：** 拼接参数。

**代码示例：Coil 动态 URL 映射器**  
`class BilibiliCdnMapper : Mapper<String, String> {`  
    `override fun map(data: String, options: Options): String {`  
        `// 仅处理 Bilibili 域名且未包含处理参数的 URL`  
        `if (!data.contains("hdslb.com") |`

`| data.contains("@")) return data`

        `// 获取请求尺寸，默认为 480px (常规缩略图)`  
        `val targetWidth = options.size.widthPxOrNull()?: 480`  
        `// 尺寸规整化：向上取整到 100 的倍数`  
        `val bucketWidth = ((targetWidth + 99) / 100) * 100`  
          
        `// 构建参数：宽度限制 + 裁剪 + 75质量 + WebP`  
        `// 例如：https://.../img.jpg@400w_1c_75q.webp`  
        `return "$data@${bucketWidth}w_1c_75q.webp"`  
    `}`

    `private fun Size.widthPxOrNull(): Int? {`  
        `return if (this is Dimension.Pixels) px else null`  
    `}`  
`}`

通过这种方式，客户端下载的数据量可减少 70% 以上，解码所需的内存也随之大幅下降，这是在 1GB 内存设备上运行流畅的关键 。

## **4\. Jetpack Compose 在低端芯片上的性能深渊与救赎**

Jetpack Compose 极大地简化了 UI 开发，但其基于“重组（Recomposition）”的机制在弱 CPU（如 Amlogic S905X 的 Cortex-A53 核心）上代价高昂。在 TV 端，焦点移动（Focus Traversal）是最大的性能杀手。

### **4.1 重组风暴（Recomposition Storm）**

在 Android TV 上，当用户按下遥控器方向键时，焦点从 Item A 移动到 Item B。这会触发：

1. Item A 的状态变更（失去焦点）。  
2. Item B 的状态变更（获得焦点）。  
3. 父容器（如 LazyRow）可能发生的滚动偏移。

如果代码编写不当，这会导致整个列表甚至整个屏幕的重组。在低端芯片上，这将直接导致掉帧。

#### **4.1.1 稳定类型（Stability）与不可变性**

Compose 编译器会检查参数的稳定性来决定是否跳过重组。标准的 List\<T\> 接口在 Compose 看来是“不稳定”的，因为它可能是可变的。因此，必须使用 kotlinx.collections.immutable 库。

* **错误做法：** 使用 List\<VideoModel\> 作为 Composable 参数。  
* **正确做法：** 使用 ImmutableList\<VideoModel\>，并给数据类加上 @Immutable 注解。

`@Immutable // 显式承诺该对象不可变`  
`data class VideoModel(`  
    `val id: Long,`  
    `val title: String,`  
    `val coverUrl: String`  
`)`

`@Composable`  
`fun VideoList(videos: ImmutableList<VideoModel>) {... }`

这能确保当焦点变化但数据列表未变时，Compose 直接跳过列表内容的重组 。

### **4.2 延迟列表（Lazy Layouts）的优化**

LazyVerticalGrid 是 TV 应用的核心组件。

1. **Key 的强制使用：** 必须为 items 提供稳定的 key。如果没有 key，Compose 在列表滚动时会进行位置推断，这在低端 CPU 上极其耗时。  
2. **避免嵌套：** 在 LazyColumn 中嵌套 LazyRow（即常见的“泳道”布局）时，应为内部的 LazyRow 指定固定的高度或使用 contentType 复用策略，减少测量（Measure）阶段的计算量 。

### **4.3 焦点管理与输入延迟**

Android TV 的焦点系统在 Compose 中是一个独立的树形结构。频繁的 onFocusChanged 回调会导致性能问题。

* **优化策略：** 避免在 onFocusChanged 中执行复杂的逻辑（如大图加载）。应使用 LaunchedEffect 配合延时，仅当焦点停留超过一定时间（如 200ms）才开始加载高清大图，避免快速扫过时触发无意义的网络请求和解码 。

## **5\. 基线配置文件（Baseline Profiles）：解决启动与掉帧的终极手段**

在 Amlogic/Rockchip 等低性能芯片上，Android 的 JIT（即时编译）机制是造成应用启动慢和首次滑动卡顿的主要原因。CPU 需要一边解释执行字节码，一边进行编译，导致 CPU 满载。**基线配置文件（Baseline Profiles）** 允许我们将热点代码的编译提前到应用安装阶段（AOT，Ahead-of-Time 编译）。

### **5.1 Android TV 上的 Macrobenchmark 挑战**

在 TV 上生成配置文件比手机更复杂，因为 TV 设备通常没有电池优化设置，但却有更严格的后台进程限制，且往往不带屏幕触摸输入，必须模拟 D-pad 输入。

#### **5.1.1 环境搭建**

需要在项目中添加 com.android.test 模块，并引入 androidx.benchmark:benchmark-macro-junit4 库。 **关键配置：**

1. **Profileable:** 在 AndroidManifest.xml 的 \<application\> 标签中添加 \<profileable android:shell="true"/\>。这是允许 Macrobenchmark 读取性能数据的关键，且不会像 debuggable=true 那样影响性能 。  
2. **ADB 连接：** 确保 TV 开启了开发者模式和 USB 调试。对于部分机顶盒，可能需要通过 WiFi ADB 连接：adb connect 192.168.x.x 。

### **5.2 编写针对 TV 的生成规则**

我们需要定义“关键用户旅程”（CUJ），特别是 D-pad 导航。  
`@OptIn(ExperimentalBaselineProfilesApi::class)`  
`class TvBaselineProfileGenerator {`  
    `@get:Rule`  
    `val baselineProfileRule = BaselineProfileRule()`

    `@Test`  
    `fun generate() {`  
        `baselineProfileRule.collect(`  
            `packageName = "com.example.tvapp",`  
            `includeInStartupProfile = true // 优化冷启动`  
        `) {`  
            `// 1. 启动应用`  
            `pressHome()`  
            `startActivityAndWait()`  
              
            `// 2. 模拟 TV 遥控器操作 (D-Pad Navigation)`  
            `// 等待首页内容加载`  
            `device.waitForIdle()`  
              
            `// 向下导航，触发 LazyColumn 的加载和渲染代码路径`  
            `repeat(5) {`  
                `device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)`  
                `device.waitForIdle() // 等待焦点动画完成`  
            `}`  
              
            `// 向右导航，触发 LazyRow/Grid 的代码路径`  
            `repeat(3) {`  
                `device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)`  
                `device.waitForIdle()`  
            `}`  
              
            `// 3. 打开详情页 (模拟点击)`  
            `device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)`  
            `device.waitForIdle()`  
        `}`  
    `}`  
`}`

这段代码会生成一个 baseline-prof.txt 文件。将其放入 src/main/baseline-prof.txt 后，应用在构建时会将此文件打包。当用户在 TV 上安装或更新应用时，系统会在后台根据此文件将关键代码预编译为机器码 。

### **5.3 效果预期**

在 Cortex-A53 架构的 TV 芯片上，基线配置文件通常能带来：

* **启动速度提升：** 20% \- 30%。  
* **首次帧渲染时间：** 显著减少，消除了首次聚焦卡片时的微卡顿 。

## **6\. 结论**

在 1GB RAM 的 Android TV 设备上开发图像密集型应用，是一场针对资源的精细化战争。任何单一层面的优化都不足以解决问题，必须采用全链路的深度优化策略：

1. **架构层：** 必须使用 Glide（配合激进的位图池）或配置极其保守的 Coil（单例、15% 内存上限、禁用硬件位图）。  
2. **传输层：** 利用 **Bilibili CDN 参数**（@...w\_...h\_1c\_75q.webp）实现服务器端缩放，从源头减少数据量。  
3. **渲染层：** 在 **Jetpack Compose** 中严格遵守不可变性原则，使用稳定的 Key，并针对 TV 焦点机制优化重组逻辑。  
4. **编译层：** 部署 **Baseline Profiles**，利用 Macrobenchmark 模拟 D-pad 操作，将 JIT 开销转移至安装时，释放运行时 CPU 算力。

只有将上述技术有机结合，才能在这些“史前性能”的硬件上，交付现代化、丝般顺滑的用户体验。

#### **引用的文献**

1\. Managing Bitmap Memory | App quality \- Android Developers, https://developer.android.com/topic/performance/graphics/manage-memory 2\. r/Android on Reddit: \[GUIDE\] \[ROOT\] How to lower RAM Usage and ..., https://www.reddit.com/r/Android/comments/98s9wy/guide\_root\_how\_to\_lower\_ram\_usage\_and\_improve/ 3\. Jetpack Compose Performance Checklist for Production, https://proandroiddev.com/jetpack-compose-performance-checklist-for-production-d697abe3f50c 4\. How to optimize memory consumption when using Glide, https://proandroiddev.com/how-to-optimize-memory-consumption-when-using-glide-9ac984cfe70f 5\. Built My Own Android Image Loader to Understand Glide's Magic, https://medium.com/@rajabhandari100/i-built-my-own-android-image-loader-to-understand-image-loader-internals-3f8666bfce6a 6\. Glide v4 : Configuration \- GitHub Pages, https://bumptech.github.io/glide/doc/configuration.html 7\. Building a Custom Image Loader with Disk Caching for Android, https://proandroiddev.com/building-a-custom-image-loader-with-disk-caching-for-android-5f4b151108f7 8\. OutOfMemoryError when Bitmap gets decoded · Issue \#190 · coil-kt ..., https://github.com/coil-kt/coil/issues/190 9\. Coil Compose — Loading and Caching Images in Compose \- Medium, https://medium.com/@sudhanshukumar04/coil-compose-loading-and-caching-images-in-compose-ebd7b25820c0 10\. How to use Coil in Compose Multiplatform | by Kashif Mehmood, https://proandroiddev.com/coil-for-compose-multiplatform-5745ea76356f 11\. Recipes \- Coil \- GitHub Pages, https://coil-kt.github.io/coil/recipes/ 12\. Coil AsyncImage Software rendering not supported for Pictures that ..., https://stackoverflow.com/questions/77719246/coil-asyncimage-software-rendering-not-supported-for-pictures-that-require-hardw 13\. Software rendering doesn't support hardware bitmaps \#159 \- GitHub, https://github.com/coil-kt/coil/issues/159 14\. Coil: My Favorite Image Loading Library for Jetpack Compose, https://proandroiddev.com/coil-my-favorite-image-loading-library-for-jetpack-compose-877fa0b818fe 15\. B站图床搭建教程 \- Arui, https://czrui99.github.io/post/f35d2fbc 16\. Bilibili图床typora插件\_b站会员图片插件 \- CSDN博客, https://blog.csdn.net/lklalmq/article/details/132607642 17\. CDN:Enable and use image editing \- Alibaba Cloud, https://www.alibabacloud.com/help/en/cdn/user-guide/image-processing-operation-method 18\. How does the Android Image Loading library optimize memory usage?, https://outcomeschool.com/blog/android-image-loading-library-optimize-memory-usage 19\. Performance Optimization in Jetpack Compose: Best Practices for ..., https://medium.com/@poojalondhe1911/performance-optimization-in-jetpack-compose-best-practices-for-smooth-uis-0daf5b7c7851 20\. Jetpack Compose Performance: Advanced Optimization Guide, https://medium.com/@therahulpahuja/jetpack-compose-performance-advanced-optimization-guide-c91d971c769e 21\. Compose-for- Android TV Development(Basic Code) Part-4 \- Medium, https://medium.com/@prahaladsharma4u/compose-for-android-tv-development-basic-code-part-4-93b8c53da563 22\. Jetpack Compose \- Android TV : r/androiddev \- Reddit, https://www.reddit.com/r/androiddev/comments/1gb01u9/jetpack\_compose\_android\_tv/ 23\. Use a baseline profile | Jetpack Compose \- Android Developers, https://developer.android.com/develop/ui/compose/performance/baseline-profiles 24\. Inspect app performance with Macrobenchmark \- Android Developers, https://developer.android.com/codelabs/android-macrobenchmark-inspect 25\. ADB Setup — androidtv 0.0.75 documentation, https://androidtv.readthedocs.io/en/stable/adb\_setup.html 26\. Improving App Performance with Baseline Profiles, https://android-developers.googleblog.com/2022/01/improving-app-performance-with-baseline.html 27\. Improve Android App Performance With Baseline Profiles, https://tech.gr.vodafone.com/post/android-baseline-profiles