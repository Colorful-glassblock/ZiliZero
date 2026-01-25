# **BBLL Android TV 客户端弹幕实现逻辑深度技术分析报告**

## **1\. 概述与系统架构引论**

随着互联网流媒体技术的飞速发展，弹幕（Danmaku）作为一种即时互动的视频评论形式，已成为亚洲乃至全球二次元文化及流媒体生态中不可或缺的核心组件 1。在移动端和Web端，弹幕渲染技术已趋于成熟，但在Android TV（电视端）这一特定硬件环境下，弹幕的高效、流畅且高并发的实现仍面临诸多技术挑战。Android TV设备通常受限于处理器性能（往往落后于同期旗舰手机多代）、内存容量以及非触控交互的特性，这要求开发者在移植或开发弹幕功能时，必须进行深度优化与架构重构。

本报告旨在对GitHub开源项目 **BBLL**（一个广受欢迎的第三方Bilibili Android TV客户端）的弹幕实现逻辑进行详尽的逆向工程分析与技术解构 3。BBLL作为一个“仅供学习与交流使用”的第三方客户端，其核心价值在于不仅封装了Bilibili官方API，更在电视大屏上实现了媲美甚至超越官方应用的原生播放体验，其中弹幕系统的流畅度与定制化能力尤为突出 4。

本分析将从网络传输层的Protobuf协议解析、核心渲染引擎DanmakuFlameMaster的定制化集成、ExoPlayer播放器的时间轴同步机制、以及针对Android TV硬件特性的图形渲染优化等多个维度展开。通过深入剖析BBLL如何处理高并发文本渲染、内存抖动抑制以及音画同步漂移等核心难题，揭示其背后的工程逻辑与实现智慧。

### **1.1 系统架构概览**

BBLL的弹幕系统并非单一模块，而是一个跨越网络层、数据层、逻辑层与视图层的复杂协同系统。其架构设计遵循经典的MVP（Model-View-Presenter）或MVVM模式，确保了数据流与UI渲染的解耦。

| 架构分层 | 核心组件/技术 | 功能描述 |
| :---- | :---- | :---- |
| **网络层 (Network Layer)** | OkHttp, Retrofit, Protobuf | 负责与Bilibili服务器通信，获取二进制弹幕数据流，处理Gzip/Brotli解压与HTTP头伪装。 |
| **数据层 (Data Layer)** | Protobuf Parser, Kotlin Coroutines | 将二进制数据反序列化为Java/Kotlin对象实体，构建内存对象池。 |
| **逻辑层 (Logic Layer)** | DanmakuFlameMaster (DFM) Core | 负责弹幕的布局计算、碰撞检测、轨道分配以及缓存策略管理。 |
| **视图层 (View Layer)** | SurfaceView / TextureView | 利用Android图形子系统进行最终的像素合成与上屏显示。 |
| **同步层 (Sync Layer)** | ExoPlayer (Media3), SystemClock | 维护全剧“主时钟”（Master Clock），确保弹幕时间戳与视频帧的毫秒级对齐。 |

这一架构的核心在于将计算密集型的任务（如解析与布局）与IO密集型任务（如网络请求）分离，并利用Android的SurfaceView机制将渲染任务移出主UI线程，从而在低性能电视盒子上也能维持60fps的渲染帧率 4。

## ---

**2\. 网络传输与协议解析：从XML到Protobuf的演进**

Bilibili的弹幕系统在早期主要依赖XML格式进行数据传输。然而，随着视频热度的增加，单视频弹幕量级突破百万，XML冗长的标签结构带来了巨大的带宽浪费和解析开销。BBLL作为现代客户端，全面采用了基于Google Protocol Buffers（Protobuf）的二进制API，这一转变是其实现高性能弹幕加载的基础 7。

### **2.1 API端点与分段机制逻辑**

BBLL在请求弹幕时，并非一次性拉取全量数据。由于Android TV设备的内存限制（部分老旧设备仅有1GB RAM），加载数万条弹幕对象极易触发OOM（Out Of Memory）错误。因此，BBLL遵循Bilibili的分段（Segmentation）策略。

#### **2.1.1 弹幕分段请求逻辑**

视频通常以6分钟（360,000毫秒）为一个分段单位（Segment）。BBLL在播放器初始化阶段，会首先计算视频总时长对应的分段数量。

* **端点构造**：核心请求URL通常构造为 https://api.bilibili.com/x/v2/dm/web/seg.so 或其变体 9。  
* **参数解析**：  
  * oid (cid): 视频内容的唯一标识符，是弹幕绑定的核心Key。  
  * pid (aid): 稿件ID，辅助校验。  
  * type: 弹幕类型（通常为1，代表普通视频弹幕）。  
  * segment\_index: 分段索引（1, 2, 3...）。

BBLL内部实现了一个预加载机制。当播放器进度接近当前分段的末尾时，后台线程（利用Kotlin协程的Dispatchers.IO）会静默请求下一个分段的数据。这种“滑动窗口”式的加载策略，确保了内存中仅维持当前播放所需的弹幕数据，极大降低了内存峰值 4。

### **2.2 Protobuf 协议的深度应用**

Protobuf（Protocol Buffers）是一种语言中立、平台中立的可扩展机制，用于序列化结构化数据。相比XML或JSON，Protobuf生成的二进制流体积更小，且解析速度快3-10倍，这对于CPU性能较弱的电视盒子至关重要 7。

#### **2.2.1 数据结构的逆向定义**

BBLL通过集成编译后的Protobuf类（通常由.proto文件生成）来解析响应体。根据开源社区的逆向工程资料 9，一个标准的弹幕消息（Message）结构包含如下关键字段：

| 字段索引 (Tag) | 数据类型 (Wire Type) | 业务含义 | BBLL处理逻辑 |
| :---- | :---- | :---- | :---- |
| **1** | int64 | id (弹幕ID) | 用于去重和基于ID的屏蔽（Blocklist）。 |
| **2** | int32 | progress (出现时间) | 单位为毫秒，这是渲染引擎调度的核心依据。 |
| **3** | int32 | mode (弹幕模式) | 决定弹幕是滚动（1-3）、底部固定（4）、顶部固定（5）还是逆向（6）。 |
| **4** | int32 | fontsize (字体大小) | 原始值（如25），BBLL会根据TV分辨率进行缩放适配。 |
| **5** | uint32 | color (颜色值) | 十进制整数，需转换为ARGB格式供画笔（Paint）使用。 |
| **6** | string | midHash (发送者哈希) | 用于屏蔽特定用户的弹幕，BBLL需维护哈希黑名单。 |
| **7** | string | content (文本内容) | 实际渲染的字符串，需进行特殊字符过滤和换行处理。 |
| **8** | int64 | ctime (发送时间戳) | 用于按发送时间排序或过滤历史弹幕。 |

#### **2.2.2 Varints 编码与解析效率**

Protobuf使用变长整数（Varints）编码，对于较小的数值（如弹幕模式、字体大小），仅占用1个字节。BBLL使用的解析器（基于Google官方protobuf-java或其Lite版本）能够直接对内存中的字节流进行位操作读取，避免了字符串解析带来的临时对象分配（String Object Allocation），显著减少了GC压力 7。

### **2.3 “神秘力量”与反爬虫对抗**

在BBLL的issue和讨论区中，用户常提到“神秘力量”导致无法加载弹幕或视频 14。这通常指代Bilibili API的安全校验机制升级。

* **Wbi 签名机制**：Bilibili近期引入了Wbi签名，要求客户端在请求参数中通过特定的算法（混合了用户Cookie中的即时密钥）生成签名。BBLL作为一个非官方客户端，必须在本地实现这一复杂的签名算法，或者通过动态获取JS源码中的混淆逻辑来模拟签名过程。  
* **Header 伪装**：为了模拟官方客户端，BBLL在OkHttp拦截器链中注入了特定的Header，包括User-Agent（模拟Android TV官方客户端版本）、Referer以及特定的gRPC相关头（如x-bili-metadata-bin），这些头信息本身也是Protobuf序列化后的Base64字符串 13。

## ---

**3\. 核心渲染引擎：DanmakuFlameMaster (DFM) 的集成与定制**

BBLL的弹幕渲染并非从零自研，而是基于Bilibili开源的 **DanmakuFlameMaster (DFM)** 引擎 6。DFM是Android平台上最成熟的弹幕渲染解决方案，它解决了原生Android View体系在处理大量动态文本时的性能瓶颈。BBLL在此基础上进行了针对TV端的配置与二次开发。

### **3.1 渲染表面的选择：SurfaceView vs. TextureView**

在Android图形系统中，选择正确的渲染载体是性能优化的第一步。

* **SurfaceView**：拥有独立的Window，其UI合成由系统SurfaceFlinger直接处理，不经过App的主UI线程。优点是性能极高，更省电；缺点是层级关系复杂，难以进行View级别的动画（如透明度渐变、位移）。  
* **TextureView**：作为一个普通View存在，内容被渲染到OpenGL纹理中。优点是可以像普通View一样进行变换；缺点是存在额外的内存拷贝，且必须在硬件加速开启的窗口中使用。

**BBLL的决策逻辑**：考虑到Android TV盒子的GPU性能差异巨大，且大部分场景下弹幕层覆盖在视频层之上，不需要复杂的View动画，BBLL主要采用 **SurfaceView**（对应DFM中的DanmakuSurfaceView）。这种选择保证了在低端芯片（如晶晨S905系列）上也能维持弹幕的流畅滚动，因为合成工作被卸载到了系统层面，且SurfaceView通常支持“打洞”机制，直接与视频解码器的输出平面叠加 15。

### **3.2 缓存机制（CacheManager）深度解析**

弹幕渲染最大的性能杀手是**文字的光栅化（Rasterization）**。每一次调用Canvas.drawText，CPU都需要从字体文件中读取字形（Glyph），计算贝塞尔曲线，并将其填充为像素。如果每帧都对屏幕上数百条弹幕进行重绘，任何移动设备的CPU都无法承受。

BBLL集成的DFM引擎采用了激进的**位图缓存策略**：

1. **预绘制（Pre-drawing）**：当弹幕对象被创建且即将进入屏幕时，CacheManager会计算其宽高，分配一个Bitmap，并在后台线程将文字绘制到这个Bitmap上。  
2. **位图复用（Bitmap Blitting）**：在渲染循环的主路径（Main Loop）中，引擎不再调用drawText，而是调用drawBitmap。在GPU上，将纹理拷贝到屏幕的操作远快于文字光栅化。  
3. **对象池与驱逐策略**：  
   * **内存控制**：TV设备内存紧张，BBLL必须限制缓存池的大小（例如设置为设备最大可用内存的1/8）。  
   * **LRU算法**：当缓存满时，使用最近最少使用（LRU）算法通过CacheManager驱逐那些已经滚出屏幕的弹幕的Bitmap，释放内存。  
   * **回收复用**：被驱逐的Bitmap对象不会立即被GC回收，而是放入对象池（Bitmap Pool）中，供新生成的弹幕复用，从而避免了内存抖动（Memory Churn）导致的卡顿 15。

### **3.3 布局与碰撞检测算法**

为了保证弹幕的可读性，不能让文字随意重叠。BBLL使用了基于\*\*轨道（Track）\*\*的布局算法。

1. **轨道划分**：屏幕高度被划分为若干行，行高由字体大小决定。  
2. **可用性检查**：当一条新的滚动弹幕（R2L）尝试上屏时，布局器会遍历所有轨道，寻找一个“空闲”轨道。  
3. **追及问题计算**：  
   * 对于滚动弹幕，不仅要求当前轨道末端为空，还要求新弹幕在滚动过程中不会“追尾”上一条弹幕。  
   * 设上一条弹幕位置为 ![][image1]，速度为 ![][image2]；新弹幕位置为 ![][image3]（屏幕右边缘），速度为 ![][image4]。  
   * 引擎计算碰撞时间 ![][image5]。如果 ![][image5] 小于上一条弹幕完全离开屏幕的时间，则判定该轨道不可用。  
4. **溢出处理**：当所有轨道都被占用时，BBLL根据用户设置（如“防挡字幕”或“限制同屏数量”）决定是丢弃该弹幕，还是允许重叠显示 2。

### **3.4 针对电视大屏的定制化渲染参数**

BBLL在源码层面针对TV进行了特殊的参数调整，这些调整反映了电视与手机观看环境的差异：

* **密度缩放（Density Scaling）**：手机DPI通常很高（300-500），而电视虽然是4K，但UI通常运行在1080p或720p的密度下。BBLL在初始化DanmakuContext时，会注入一个自定义的缩放因子，确保字体在电视数米外的观看距离下依然清晰可见 4。  
* **描边优化**：为了在复杂的视频背景上看清文字，BBLL默认开启较粗的文字描边（Stroke）。DFM提供了基于Shader的描边实现，比传统的Paint描边性能更好。  
* **透明度合成**：用户在设置中调节的不透明度（Alpha），实际上是修改了绘制Bitmap时的Paint属性。对于SurfaceView，这可能涉及到离屏缓冲区的混合操作 5。

## ---

**4\. 音画同步机制：ExoPlayer 与主时钟的博弈**

弹幕必须与视频画面精确同步。早一秒出现可能会剧透，晚一秒则失去了互动的意义。BBLL使用 **ExoPlayer** 作为播放核心 18，其同步逻辑是一场关于“时间”的精密控制。

### **4.1 主时钟（Master Clock）概念**

在BBLL的播放体系中，**视频播放器的当前播放位置（Current Position）是绝对的主时钟**。弹幕引擎是从属系统。

* **时间校准**：DFM维护了一个内部计时器DanmakuTimer。在每一帧的渲染回调（doFrame）中，BBLL会将ExoPlayer的player.getCurrentPosition()赋值给DanmakuTimer。  
* **平滑插值**：由于getCurrentPosition()的更新频率可能低于屏幕刷新率（60Hz），直接使用可能导致弹幕移动由于时间戳跳变而产生抖动。BBLL可能实现了一个线性插值器，根据上一帧的时间和系统流逝时间（SystemClock.elapsedRealtime）来预测当前帧的渲染时间，仅在偏差过大时强制同步ExoPlayer的时间。

### **4.2 隧道播放模式（Tunneled Playback）的双刃剑**

Android TV的一个重要特性是**隧道播放（Tunneled Playback）**，这是ExoPlayer支持的一种硬件加速模式 20。

* **机制**：在隧道模式下，压缩的视频流直接发送给硬件解码器，解码后的帧直接送入硬件合成器（Hardware Composer）显示，完全绕过Android的应用层和Framework层。  
* **优势**：极大地减轻了CPU负担，且由于视频帧携带了PTS（Presentation Time Stamp），音画同步由底层硬件保证，非常精准。  
* **BBLL的挑战**：在隧道模式下，ExoPlayer无法精确回调每一帧的渲染时刻（因为应用层接触不到解码后的帧）。这会导致弹幕引擎获取到的CurrentPosition是一个估算值，可能导致弹幕与画面的轻微不同步。  
* **解决方案**：BBLL通常提供开关让用户选择是否开启隧道模式。对于追求极致弹幕同步的用户，可能需要关闭隧道模式；而对于追求4K/8K视频流畅度的用户，开启隧道模式后，BBLL可能通过硬编码的偏移量（Offset）来手动补偿音频延迟（Audio Latency）带来的时间差 22。

### **4.3 播放状态变化的响应逻辑**

* **Seek（快进/快退）**：  
  当用户按下遥控器左右键进行Seek操作时：  
  1. ExoPlayer触发onPositionDiscontinuity事件。  
  2. BBLL捕获该事件，立即调用DanmakuView.seekTo(targetTime)。  
  3. DFM引擎清空当前屏幕上的所有弹幕（因为它们的位置是基于旧时间计算的）。  
  4. 引擎根据新时间，快速索引内存中的弹幕列表，计算出在这一时刻应该出现在屏幕上的弹幕及其位置（例如，某条长弹幕可能刚走到屏幕中间），并立即绘制。这一过程要求极高的计算效率，否则Seek后会出现短暂的无弹幕真空期 16。  
* **Pause（暂停）**：  
  当视频暂停时，BBLL调用DanmakuView.pause()。此时，渲染线程挂起，不再请求重绘。SurfaceView的内容保持不变，实现了弹幕“悬停”在空中的效果。

## ---

**5\. 硬件加速与性能优化策略**

针对Android TV良莠不齐的硬件环境，BBLL在代码层面实施了多项优化策略，以确保在低端设备上不发生ANR（应用无响应）或掉帧。

### **5.1 硬件加速层的开启**

在Android中，View的绘制可以由CPU完成（软件渲染）或GPU完成（硬件加速）。

BBLL在初始化DanmakuView时，会根据系统版本和设置，尝试开启硬件层（Hardware Layer）：

Java

// 伪代码逻辑示意  
if (config.useHardwareAcceleration) {  
    danmakuView.setLayerType(View.LAYER\_TYPE\_HARDWARE, null);  
}

这使得View的绘制指令被转化为OpenGL指令序列（DisplayList），由GPU执行。然而，对于SurfaceView，其本身就是直接操作显存的，因此这一设置更多作用于TextureView模式或UI覆盖层。更为关键的是DFM引擎内部的**OpenGL绘制模式**（DFM支持使用OpenGL ES直接绘制弹幕），BBLL可能在实验性选项中开启了此功能，利用片元着色器（Fragment Shader）处理文字颜色和描边，极大释放CPU压力 24。

### **5.2 内存抖动（GC）抑制**

Java的垃圾回收机制是造成画面卡顿（Jank）的主要原因之一。在弹幕密集场景下，每秒可能有数百个对象产生和销毁。

* **对象池（Object Pooling）**：BBLL复用了DFM的Retainer机制。当弹幕移出屏幕后，其Java对象不会被销毁，而是被重置状态（坐标归零、Bitmap引用清空）后放入池中。下一条新弹幕直接从池中取出对象填充数据。这意味着在长时间播放后，BBLL的堆内存占用会趋于稳定，不会频繁触发GC 15。  
* **避免自动装箱**：在处理坐标计算时，尽量使用基本数据类型（float, int），避免使用Float或Integer包装类，减少微小对象的产生。

### **5.3 过度绘制（Overdraw）的规避**

在电视大屏上，全屏弹幕可能导致像素填充率（Pixel Fill Rate）爆表，即同一个像素点在一帧内被绘制了多次（背景视频+弹幕A+弹幕B...）。

BBLL提供了**同屏密度限制**和**防挡字幕**功能：

* **防挡字幕**：逻辑层判断弹幕的Y坐标，如果是位于屏幕底部20%区域的滚动弹幕，则强制提升其Y坐标或丢弃。这不仅提升了体验，也减少了底部的重绘压力。  
* **智能丢帧**：当检测到单帧渲染时间超过16ms（60fps的阈值）时，引擎会自动丢弃低优先级的弹幕（如滚动弹幕），优先保证顶部/底部固定弹幕的渲染，或者直接跳过当前帧的绘制以追赶时间 16。

## ---

**6\. 用户交互逻辑与遥控器适配**

与手机端的触控交互不同，BBLL必须通过D-Pad（方向键）实现所有控制。

### **6.1 焦点管理系统**

Android TV开发的核心难点在于焦点（Focus）管理。BBLL的播放界面实际上是一个覆盖在SurfaceView之上的透明Fragment。

* 当用户按下“下”键或“菜单”键时，BBLL会弹出一个侧边栏或底部栏。  
* 弹幕设置（大小、透明度、速度）被映射为SeekBar或Switch控件。  
* BBLL在XML布局文件中定义了明确的nextFocusDown、nextFocusRight属性，确保用户在盲操遥控器时，焦点能逻辑顺畅地在各个设置项之间流转，而不会迷失 4。

### **6.2 弹幕屏蔽体系**

BBLL支持本地弹幕屏蔽，其实现逻辑位于渲染管线的末端，即“布局之前”。

* **关键词屏蔽**：用户输入的关键词被存储在本地数据库（如Room/SQLite）。在每条弹幕准备进入布局计算前，通过正则表达式或字符串包含检查（String.contains）进行过滤。  
* **正则屏蔽**：支持高级用户输入正则表达式。这对性能有一定影响，因此BBLL可能会限制正则的复杂度和长度。  
* **用户等级/类型屏蔽**：利用Protobuf解析出的元数据（如用户Hash、弹幕类型），直接在数据层拦截，不进入渲染队列，这是最高效的屏蔽方式 5。

## ---

**7\. 总结与展望**

通过对BBLL源代码结构、依赖库以及运行机制的综合分析，我们可以得出结论：BBLL之所以能在Android TV上提供卓越的Bilibili观看体验，核心在于其对**数据流的高效处理**与**图形渲染的极致优化**。

1. **协议层面**：通过逆向Protobuf协议，实现了低带宽、低延迟的弹幕数据获取。  
2. **渲染层面**：基于DanmakuFlameMaster引擎，利用SurfaceView、位图缓存和对象池技术，成功克服了电视芯片性能不足的短板。  
3. **同步层面**：深度集成ExoPlayer，建立了一套以视频帧为基准的主从时钟同步体系，并适配了Android TV特有的隧道播放模式。

**未来展望**：

随着Bilibili API安全性的不断升级（如更复杂的gRPC加密），BBLL等第三方客户端面临着持续的维护挑战。技术层面上，未来的演进方向可能包括：

* **Vulkan/Metal 渲染**：彻底抛弃CPU辅助的绘图，利用新一代图形API实现全GPU管线的弹幕渲染。  
* **AI辅助过滤**：利用电视NPU芯片，实现基于内容的本地AI弹幕情感分析与过滤，提升观看质量。  
* **ASS/高级弹幕支持**：目前第三方客户端对代码弹幕（BAS）支持有限，未来可能通过WebAssembly等技术在Native端安全地运行高级弹幕脚本。

BBLL的工程实践不仅是Android开发技术的集大成者，也是开源社区对抗封闭生态、追求极致用户体验的生动写照。

### ---

**附录：关键技术参数对照表**

| 技术指标 | 官方客户端 (Web/App) | BBLL (Android TV) | 差异原因分析 |
| :---- | :---- | :---- | :---- |
| **数据格式** | Protobuf / JSON | Protobuf | 电视端需节省解析算力与带宽。 |
| **渲染引擎** | Canvas / CSS3 / WebGL | DanmakuFlameMaster (SurfaceView) | WebGL在低端Android WebView上兼容性差，原生SurfaceView性能更稳。 |
| **缓存策略** | 浏览器内存管理 | 激进的Bitmap对象池 | 电视盒子RAM通常较小（1-2GB），需手动管理内存防止OOM。 |
| **字体渲染** | 系统字体渲染 | 预渲染Bitmap \+ 描边Shader | 电视观看距离远，需更粗的描边和更大的字号，Shader描边效率更高。 |
| **交互方式** | 鼠标/触控 | D-Pad 遥控器 | 必须适配焦点导航系统。 |

---

*\[本报告基于截止2026年初的公开技术资料、GitHub源码仓库快照及社区逆向工程文档整理而成。\]*

#### **Works cited**

1. 每日一词：弹幕 \- 英语点津, accessed on January 25, 2026, [https://language.chinadaily.com.cn/a/201905/07/WS5cd147f9a3104842260ba555.html](https://language.chinadaily.com.cn/a/201905/07/WS5cd147f9a3104842260ba555.html)  
2. 弹幕 \- 萌娘百科, accessed on January 25, 2026, [https://zh.moegirl.org.cn/%E5%BC%B9%E5%B9%95](https://zh.moegirl.org.cn/%E5%BC%B9%E5%B9%95)  
3. bilibili-tv · GitHub Topics, accessed on January 25, 2026, [https://github.com/topics/bilibili-tv](https://github.com/topics/bilibili-tv)  
4. GitHub \- xiaye13579/BBLL: 一个第三方哔哩哔哩客户端, accessed on January 25, 2026, [https://github.com/xiaye13579/BBLL](https://github.com/xiaye13579/BBLL)  
5. BBLL V1.50：哔哩哔哩的个性化与流畅体验增强版原创 \- CSDN博客, accessed on January 25, 2026, [https://blog.csdn.net/ITWorldView/article/details/141283087](https://blog.csdn.net/ITWorldView/article/details/141283087)  
6. GitHub \- bilibili/DanmakuFlameMaster: Android开源弹幕引擎, accessed on January 25, 2026, [https://github.com/bilibili/DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster)  
7. Overview | Protocol Buffers Documentation, accessed on January 25, 2026, [https://protobuf.dev/overview/](https://protobuf.dev/overview/)  
8. News Announcements for Version 30.x \- Protocol Buffers, accessed on January 25, 2026, [https://protobuf.dev/news/v30/](https://protobuf.dev/news/v30/)  
9. bilibili-API-collect/docs/danmaku/danmaku\_proto.md at ... \- GitHub, accessed on January 25, 2026, [https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/danmaku/danmaku\_proto.md](https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/danmaku/danmaku_proto.md)  
10. history.md \- melon-444/bilibili-API-collect-fork · GitHub, accessed on January 25, 2026, [https://github.com/melon-444/bilibili-API-collect-fork/blob/master/docs/danmaku/history.md](https://github.com/melon-444/bilibili-API-collect-fork/blob/master/docs/danmaku/history.md)  
11. github.com-xiaye13579-BBLL\_-\_2023-10-23\_03-37-14, accessed on January 25, 2026, [https://archive.org/details/github.com-xiaye13579-BBLL\_-\_2023-10-23\_03-37-14](https://archive.org/details/github.com-xiaye13579-BBLL_-_2023-10-23_03-37-14)  
12. Protocol Buffers \- Wikipedia, accessed on January 25, 2026, [https://en.wikipedia.org/wiki/Protocol\_Buffers](https://en.wikipedia.org/wiki/Protocol_Buffers)  
13. How We Cracked Bilibili's “Impenetrable” gRPC API When AI and ..., accessed on January 25, 2026, [https://medium.com/@muushroomking/how-we-cracked-bilibilis-impenetrable-grpc-api-when-ai-and-old-maps-failed-us-0164c0261d7f](https://medium.com/@muushroomking/how-we-cracked-bilibilis-impenetrable-grpc-api-when-ai-and-old-maps-failed-us-0164c0261d7f)  
14. xiaye13579 BBLL · Discussions \- GitHub, accessed on January 25, 2026, [https://github.com/xiaye13579/BBLL/discussions](https://github.com/xiaye13579/BBLL/discussions)  
15. DanmakuFlameMaster download | SourceForge.net, accessed on January 25, 2026, [https://sourceforge.net/projects/danmakuflamemaster.mirror/](https://sourceforge.net/projects/danmakuflamemaster.mirror/)  
16. how to control the danmaku density · Issue \#124 \- GitHub, accessed on January 25, 2026, [https://github.com/bilibili/DanmakuFlameMaster/issues/124](https://github.com/bilibili/DanmakuFlameMaster/issues/124)  
17. DanMage: YouTube和Twitch等網站上新增彈幕, accessed on January 25, 2026, [https://chromewebstore.google.com/detail/danmage-niconico-style-ch/elhaopojedichjdgkglifmijgkeclalm?hl=zh\_HK](https://chromewebstore.google.com/detail/danmage-niconico-style-ch/elhaopojedichjdgkglifmijgkeclalm?hl=zh_HK)  
18. Getting started | Android media, accessed on January 25, 2026, [https://developer.android.com/media/media3/exoplayer/hello-world](https://developer.android.com/media/media3/exoplayer/hello-world)  
19. Building a Video Player with ExoPlayer in Jetpack Compose \- Medium, accessed on January 25, 2026, [https://medium.com/@niteshkrjhag/building-a-video-player-with-exoplayer-in-jetpack-compose-a-beginner-to-experienced-guide-cb351d79393e](https://medium.com/@niteshkrjhag/building-a-video-player-with-exoplayer-in-jetpack-compose-a-beginner-to-experienced-guide-cb351d79393e)  
20. Tunneled video playback in ExoPlayer | by Olly Woodman \- Medium, accessed on January 25, 2026, [https://medium.com/google-exoplayer/tunneled-video-playback-in-exoplayer-84f084a8094d](https://medium.com/google-exoplayer/tunneled-video-playback-in-exoplayer-84f084a8094d)  
21. Audio out of sync problem ? : r/JustPlayer \- Reddit, accessed on January 25, 2026, [https://www.reddit.com/r/JustPlayer/comments/s268vy/audio\_out\_of\_sync\_problem/](https://www.reddit.com/r/JustPlayer/comments/s268vy/audio_out_of_sync_problem/)  
22. Fixed Audio Delay with Firestick. Now able to use default exoplayer ..., accessed on January 25, 2026, [https://www.reddit.com/r/Stremio/comments/1ktekup/fixed\_audio\_delay\_with\_firestick\_now\_able\_to\_use/](https://www.reddit.com/r/Stremio/comments/1ktekup/fixed_audio_delay_with_firestick_now_able_to_use/)  
23. What does the Audio Sync function do? \- JBL Support, accessed on January 25, 2026, [https://support.jbl.com/us/en/howto/jbl-one-app-audio-sync-feature-us/000037207.html](https://support.jbl.com/us/en/howto/jbl-one-app-audio-sync-feature-us/000037207.html)  
24. xqq/DanmakuFlameMaster-HWACC \- GitHub, accessed on January 25, 2026, [https://github.com/xqq/DanmakuFlameMaster-HWACC](https://github.com/xqq/DanmakuFlameMaster-HWACC)  
25. bytedance/DanmakuRenderEngine \- GitHub, accessed on January 25, 2026, [https://github.com/bytedance/DanmakuRenderEngine](https://github.com/bytedance/DanmakuRenderEngine)  
26. DanmakuAnalyser B站弹幕屏蔽规则测试工具 \- GitHub, accessed on January 25, 2026, [https://github.com/jnxyp/DanmakuAnalyser](https://github.com/jnxyp/DanmakuAnalyser)

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAYCAYAAACIhL/AAAABxklEQVR4Xu2WvytFYRjHv4oihCgp5feghFImslgtshkNTGyM+AcMJJGSQSirzXDLolgsrJQfk5RQUvh+e97TOfft3OFy3XOH+6lPznmfl/Oc933P8wCKFCmSNbW0ybM8bUaCVMESuqfftMPdyxb66MZ1nSjvsER8eugz3aalXixvlMCSu/MDpB22irewVU2EOliCx36AjNIvekZrvFjemIQloWR8HmDJl/mBfKLzpe1t9sZ7YckdeON5pY++0Au6FXGatobTkkPbq1Ua9wOFwjkswWo/UCi8Ib7+FQRB/XvyAzEswVb7iM7SS1h32aTLdJVu0CvaZr+CEbpGJ+gK7Hm7dI5OuTl6vj7SNDRxkM7AJqhTdMHaXhwNtBFWJzvd2CksKX1M+tB2YPVUL9tNx+irmysOaSXszOsFFty4/o6S/jND9CNyr0T23LUS1UtE0YsrwRPYCgeoXe4jLPrziK+/WaOtTblrPeQaVje1WnFHRGd7IHKv1dPOaUyxACWYE25g7U4MIyxL+vnprqOo8Gs7hRJbpxUI667QKvrN4dcEZ6se9sAA/d+Y6eyqPfrzA3Smc9Y+taUpZE4kUfoRtr9FL/Yv/ACIA1NTf5nXHgAAAABJRU5ErkJggg==>

[image2]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACcAAAAZCAYAAACy0zfoAAAB/klEQVR4Xu2WTSilYRTHj3xE5oOINCbXNBtZWFiIfGwoFiYxZWqWlhaKGtmYsbC1IImUrJRmOwuyEBtlNlOGsjKT7aQ0Y6OJ/79zXvdxXCm3e6/F/dUv5z3H9R7P5xXJkiXLHYrdc6EZUgJzLc6HBUEtpVwlsD+ol8J9Vx8N6imnSvSlu74QcARrfDIdPBdt7pcvGDE46JPphM1d+CRog799Mt1E68mzAyd8Mt0kaq4edrlcRvgnt5vjkbEO84JcxjgVbe6ZPXMDnNxUM8y2aHPVMAduSrzRjLMq2lwjbDKfDDz12dx3+MPVCEezQXQtMu6FZVZ7AXssfi16qIfEYJ/cvgLbg+cWWG5xQvgyNvcfvnM1wl07AzfgChyGx6JTvwin4CxcgIewVj8mHXAOvhf9PP8xztIIHLLf4XuXLU5IHfwD3/qCMSl6z34T/WLAXfwVjouOKO/jVtGXd9vPA7hnMf/ugH2uAn6AzaJ8lAfua47AZ590vBFthnAafopefYSj5qeGI/IXbomObAQbXIMv7fkT7IyXH8c0fGXxF3hmcTTqHp6d3GAR/HrGUWSOtQg2lxQc2W2JryXet2MWc0ovLQ7h2ckpI2xqHhaJLoNzy3P0eHwlBTcJNwvXG9dMCHP3nYncmdzVbM5TKVpPGk7piU8+BWJwyfRnWEq4BkklVpKPktLuAAAAAElFTkSuQmCC>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACYAAAAZCAYAAABdEVzWAAABxklEQVR4Xu2WQSsFURTHj1AUIUXKAtkoscBObGxsJEnKB2BLSskGWdqIjZSsyP7tfABhy4pCdpISFhb4/zv3vrlzEovxZjbzq1/dmXPfnfPOud0ZkZycnCL1sMVYFZuRARvw6xef4Upxdga8iyZi6RZNbg9WmFgqMKlPe1O0rXfwBjabWMlpEE2sYANgRDThU1hnYiWnVzSxZRsA26KxORtIA+6fB9hq7veIJnVk7qcC23gOH+E+3HXeiiY1Bcv95DRhG1/ghURJ0VnYFk1LH1aJlemygaxhG5lYrQ1kjT/h/2IQ9sEy0RazwnbvMTYuOs8zZK59AbiFOoP7MbgQk3qygR9Yg1dwB67Da7gZxIfhJZyEW6Jr89xbFP2dxx/UfCa7FYOnua9U6EI4KaBf9CjhkcIHEh7GJ27cJNEBzCoci76+luC06OuOdEh0Vg7AQzdOBN8AH8E1k+QHABmFr/Aerkq8RXw4kyZcg/rxvBsnIqwQq+ErxBZOSHwfEV/ZN9E4OYA1bsx2/8srLqwQtwJbwhZy8XY442JMaAxWu2vuJcYa4ZnogU78/MRwQV8FwgeFH5OVops6nOPhPD+Xf4YfpjmJ+QbPL1plN3otUgAAAABJRU5ErkJggg==>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACUAAAAZCAYAAAC2JufVAAABxklEQVR4Xu2WvyvFURjGX/kRIaRIKT+SksGATEyMDBbKH2AmJRQWC5uSyWCQsthNN0rCYmFSyGpkUOJ5et/jnvu6BnS/d3A/9eSc9z3O9zk/7xEpUOAfUenq5aaYWqjYyqVQWZTLCe9ZNB7l66ALl5+N8jmjSfRjJz4RcQO1+GAuqRY1de8TRis04YNJQFMvPggGoQcfTIqwXzzH0IIPJkU2U93QiIslyrNkmuLRP4BKoljiPIqaqrI6N/bdZzZPpERNNUNF0JGkDeaNXVFTvdCAKe/wlqapS+jK5Qhnr0d0r5FRqC+dzoC5oajOMgcb4L1I2F9HFP8CO6KpN2jM5QjzG9AOtAxNQqdQRdSmEzqz3JLlaqA56Dpqdws1Qk+iP2Hf0iXa6DvnM6IfOJT0bG2Kdk5ogIelzWJb1m5e1GS4mNuhRSv3Q/tWzgo39YoPOmh82so0yFkJVwaX4lX09l+XzOXih9mWDJtCmYP9E/HMrIrODC/XKdFRc4ljwnOHd2B4efBAhVPN/ji4X8PNyVdEmJkUtAdtiy4ZO1+zHKHJeitzW9A46+eizyHC2J/g6QudBfj4i+HMNNhfT/x4ZBv/vwV+xAeECU8oXTqzUAAAAABJRU5ErkJggg==>

[image5]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAD8AAAAZCAYAAACGqvb0AAACw0lEQVR4Xu2XS6hNYRTHlzxChMgj5FGUDJkQugpRBpJQUkp5lJGJV3QmIhMSSUoZyMRMQhkYUZSRx4QyUIqYUSisX2t9nXXXvvfEPWfgnrN/9a/9rX323mutb33r+45ITU1NTU3vsFf1+y/10Z/pGj6rjoXxKNVt1QfVwmBfq/oZxl3BU9XUMJ6teqe6qxob7LNUr8J42LNEtSDZjoiV+LpkZ/w42YY1BESZRyh5go8lD6tVl5Kt62BdE3xPQuC/srEXoMER/Pt8ow1mSP/GOV01Moz/G+gBBH8832gDEjnHr6eobqpGNG/LYdX+MG4FFZkbcceg03fyA8x43DLZXXKg01Sjk20wVkr/xHWMwfb3DI4uV81N9vmqLdI/EJIYE3lRLNjCMtWqMC6QpG3SXB68c41UAy++LA73OI+Uc0ufaoNftwQnmfVWJc8H3oiV6hPVdrfz8WtiDj9XzXP7aWlumZT8M2lurTh+0m2RSarzYu+6IPZNlspRsaN4IfpyX3VFbHldV71W7VHtEks4yawwRqrn9yKOvfEhZulrGOP4TNUjsVNigTEiaI7IBRyJx+ODqhWqb8EGN8QCnyiWRJLFbO4U+z0UX8pslzHLlobKf5BFfo9J3eTXQ4YMDnS2p1qY4cJLseD56I9g53nuRRpS3VkOSHMCxrmNBNwSqwrIvpSqhdxnGlI9sP0zD8WCKrAex4tlnMwDM/FJtVTMAXRWbJ1TSTS7E/5bIDk43vAxS+aeX+Nw6ReHxJ7vU+2Tqi8vVHf8Os50qSyq4YzbhgQlhXOFq6rdqi+qjW6juWz167diFXFZbOa+izU3yrfAeqc5rfcx7zrl15v9HjwQO3afE0ssvrCugd5B8KXP0LPKTPM7Kouk73BbW7D+JiQbJZZtVEH8t4iTzECEZyYPYMvvgvw74ACVt8n8LL7lXaKmZ/kD4pSK+tX64BgAAAAASUVORK5CYII=>