# **PiliPlus 弹幕系统技术架构与实现原理深度研究报告**

## **执行摘要**

随着流媒体交互技术的演进，“弹幕”（Danmaku）已成为现代视频平台核心的社交互动形式。作为一种实时覆盖在视频流上的用户生成内容（UGC），弹幕系统对客户端的渲染性能、时间同步精度以及内存管理提出了极高的工程要求。PiliPlus 作为一款基于 Flutter 框架开发的开源哔哩哔哩（Bilibili）第三方客户端，在跨平台（Android, iOS, Windows, macOS, Linux）环境下实现了高性能、高交互性的弹幕系统，其技术实现具有极高的研究价值。  
本报告旨在穷尽式地剖析 PiliPlus 弹幕系统的底层实现机理。通过对项目源代码结构、依赖关系图谱、社区贡献历史（Pull Requests）以及构建日志的深入挖掘，本研究揭示了 PiliPlus 如何利用 Flutter 的 CustomPainter 绘图管线、Dart 的 Isolate 并发模型以及 media-kit 的底层 FFI 调用，来解决高并发文本渲染、毫秒级音画同步以及复杂交互逻辑等核心技术难题。  
分析显示，PiliPlus 摒弃了传统的 Widget 堆叠方案，转而在单一渲染层上实现了基于“即时模式”（Immediate Mode）的图形模拟引擎。该架构不仅突破了 Flutter UI 线程的性能瓶颈，还通过 protobuf 二进制流的高效反序列化与内存缓存策略，实现了在移动设备上流畅渲染数千条同屏弹幕的能力。此外，项目对交互层（点赞、举报、复制）的实现，展现了如何在非原生组件上构建自定义命中测试（Hit-Testing）逻辑的独到设计。

## **1\. 弹幕系统的工程挑战与技术背景**

### **1.1 弹幕渲染的计算复杂度分析**

弹幕系统本质上是一个高密度的粒子系统（Particle System），其中每一个“粒子”都是一个包含文本内容、颜色、字体大小、运动轨迹和生命周期的独立对象。与传统的游戏粒子不同，弹幕的内容是不定长的文本，这意味着每一帧的渲染都涉及昂贵的字体排版（Text Shaping）和光栅化（Rasterization）过程。  
在计算机图形学视角下，弹幕渲染面临以下核心约束：

1. **高填充率（Fill Rate）压力**：在弹幕密集区域，屏幕像素可能被多次重绘（Overdraw），尤其是在开启“防挡脸”蒙版或高透明度混合时，GPU 的混合阶段负载极高。  
2. **主线程阻塞风险**：如果弹幕的逻辑计算（位置更新、碰撞检测）与 UI 渲染运行在同一线程，且计算耗时超过 16.6ms（对应 60FPS），将导致明显的掉帧（Jank）。  
3. **内存抖动（Memory Churn）**：每秒可能涌入数十条新弹幕，如果频繁创建和销毁对象，会触发垃圾回收（GC），进而导致瞬时卡顿。

### **1.2 Flutter 框架下的特殊考量**

PiliPlus 选择 Flutter 作为开发框架 ，这为弹幕实现带来了双重影响。一方面，Flutter 的 Skia（或 Impeller）图形引擎提供了底层的 Canvas API，允许开发者绕过复杂的 Widget 树直接操作绘图指令，这对于高性能绘制至关重要。另一方面，Dart 语言的单线程事件循环模型要求开发者必须严格管理计算密集型任务，避免阻塞 UI 线程。  
传统的 Flutter 布局方案（如使用 Stack 包裹大量 Positioned \+ Text 组件）在弹幕场景下是不可行的。因为每一帧更新位置都会触发布局（Layout）和重绘（Paint）流程，当组件数量达到数百个时，Element 树的维护成本将呈指数级上升。因此，PiliPlus 必须采用一种“去组件化”的渲染策略，即整个弹幕层在 Flutter 框架眼中只是一个单一的渲染对象（RenderObject）。

### **1.3 PiliPlus 的技术定位与演进**

PiliPlus 并非从零构建所有底层能力，而是站在了开源社区的肩膀上。它集成了 media-kit 以获得基于 libmpv 的硬件解码能力，利用 dio 处理复杂的网络请求，并依赖 canvas\_danmaku 这一专用库来处理弹幕的核心绘制逻辑 。项目活跃的开发记录显示，其弹幕功能经历了从简单的文本滚动到支持高级交互、智能防遮挡、以及针对不同平台架构（如 Linux ARM64）的深度优化过程 。

## **2\. 系统总体架构设计**

PiliPlus 的弹幕系统架构呈现出明显的分层特征，自下而上可分为：数据接入层、核心处理层、渲染合成层以及交互控制层。各层之间通过定义良好的接口和状态流进行通信，确保了系统的模块化和可测试性 。

### **2.1 混合架构：Native 与 Dart 的协同**

在视频播放与弹幕渲染的协同上，PiliPlus 采用了“Native 解码，Dart 渲染”的混合模式。

| 组件层次 | 关键技术库 | 职责描述 | 架构意义 |
| :---- | :---- | :---- | :---- |
| **视频核心层** | media-kit (libmpv) | 视频解码、音频输出、硬件加速 | 利用 FFI (Foreign Function Interface) 调用 C/C++ 底层库，确保视频播放不占用 Dart 虚拟机（VM）的计算资源，保证画质与流畅度 。 |
| **弹幕逻辑层** | canvas\_danmaku | 弹幕轨道计算、碰撞检测、生命周期管理 | 纯 Dart 实现，运行在 Flutter UI 线程或独立 Isolate 中，负责计算每一帧弹幕应该出现在什么位置 。 |
| **数据传输层** | Dio, Protobuf | HTTP/2 连接、二进制流解析 | 负责与 Bilibili 服务器的高效通信，处理压缩与序列化数据 。 |
| **应用胶水层** | PiliPlus 业务代码 | 状态同步、交互事件分发、UI 覆盖 | 协调视频播放器的时间轴与弹幕控制器的时钟，处理用户的点击、设置更改等操作。 |

### **2.2 渲染管线分离**

为了实现极致性能，PiliPlus 将视频纹理的渲染与弹幕层的渲染在逻辑上解耦。media-kit 通过 Texture 组件将解码后的视频帧直接传递给 GPU，而弹幕层则覆盖在 Texture 之上作为一个透明的 CustomPaint 层。这种设计避免了将每一帧视频数据回传给 Dart 层进行处理，从而极大降低了 CPU 与 GPU 之间的带宽消耗。  
在这一架构中，同步机制成为了连接两者的纽带。视频播放器充当“时钟源”（Time Source），不断广播当前的播放进度（PTS \- Presentation Time Stamp）。弹幕控制器（DanmakuController）订阅该时钟信号，并基于当前时间戳检索并计算当前时刻应显示的弹幕集合。

## **3\. 数据接入与高并发处理机制**

弹幕系统的高效运行始于数据的快速加载与解析。Bilibili 的弹幕数据量巨大，热门视频单集可能包含数万条弹幕数据，这对客户端的 I/O 和 CPU 性能构成了严峻考验。

### **3.1 协议演进：从 XML 到 Protobuf**

早期的 Bilibili 客户端使用 XML 格式传输弹幕，这种格式冗余度高，解析速度慢。PiliPlus 紧跟技术趋势，全面适配了 Protobuf（Protocol Buffers）格式 。Protobuf 是 Google 开发的一种二进制序列化格式，相比 XML，它具有体积小（压缩比高）、解析快（直接映射内存结构）和类型安全等优势。  
在 PiliPlus 的源码依赖中，可以看到对 dm.proto 定义文件的处理逻辑。该文件定义了弹幕对象的核心字段：

* **id (int64)**: 弹幕的唯一标识符。  
* **progress (int32)**: 弹幕出现的时间点（毫秒）。  
* **mode (int32)**: 弹幕类型（1-3为滚动弹幕，4为底部固定，5为顶部固定，7为高级弹幕）。  
* **fontsize (int32)**: 字体大小。  
* **color (uint32)**: 颜色的整数表示（RGB）。  
* **content (string)**: 文本内容。  
* **pool (int32)**: 弹幕池类型（普通池、字幕池、特殊池）。

通过使用 Protobuf，PiliPlus 能够在极短时间内（通常在几十毫秒内）完成数千条弹幕数据的反序列化，显著缩短了视频起播的等待时间（TTFF）。

### **3.2 基于 Isolate 的并行解析架构**

尽管 Protobuf 解析效率极高，但在 Dart 的单线程模型下，如果在主 UI 线程解析数兆字节的二进制数据，仍可能导致帧率瞬时下降。为了解决这一问题，PiliPlus 引入了基于 Isolate 的并行解析机制，这一点在 Pull Request \#1785 "opt: isolate parse danmaku" 中得到了明确体现 。  
**技术实现细节：**

1. **任务分发**：当网络层（Dio）接收到 Bilibili 服务器返回的二进制流（List\<int\>）时，主线程并不直接处理，而是通过 compute() 函数或手动生成的 Isolate 将数据包转发出去。  
2. **独立堆内存**：Dart 的 Isolate 拥有独立的内存堆，不与主线程共享。这意味着数据传输涉及一次内存拷贝（Copy），或者使用 TransferableTypedData 进行零拷贝传输（优化点）。  
3. **后台解码**：在后台 Isolate 中，Protobuf 解码器将二进制流转换为 Dart 的对象列表（List\<DanmakuItem\>）。由于不涉及 UI 操作，此过程可以全速运行，不受屏幕刷新率限制。  
4. **结果回传**：解析完成后的对象列表通过 SendPort 发回主线程。此时，主线程只需将这些对象插入到弹幕控制器的内存池中即可。

这种架构设计彻底消除了数据加载阶段的“卡顿感”，使得 PiliPlus 在加载超长视频或极高密度弹幕时，UI 依然能保持丝般顺滑的响应能力。

### **3.3 分段加载与数据预取**

Bilibili 的弹幕数据是按时间段（segment）分页存储的（通常每 6 分钟为一个分段）。为了支持长视频播放，PiliPlus 实现了一套智能的分段加载策略 。

* **初始加载**：播放器启动时，并行请求当前时间点所在的分段以及下一个分段的数据。  
* **无缝拼接**：当播放进度接近当前分段的末尾时，后台静默请求下一分段数据，并将其合并（Merge）到现有的弹幕池中。合并过程需要对弹幕按时间戳进行重新排序（通常使用归并排序算法），以保证渲染顺序的正确性。  
* **内存淘汰**：为了防止内存泄漏，系统会定期清理已经播放过且距离当前时间较远的分段数据，维持内存占用的动态平衡。

## **4\. 核心渲染引擎：canvas\_danmaku 深度剖析**

PiliPlus 的视觉核心在于其依赖的 canvas\_danmaku 库 。这个库是专门为 Flutter 打造的高性能弹幕渲染引擎，其设计哲学是“绕过 Widget 树，直面 Canvas”。

### **4.1 深入 CustomPainter 绘图管线**

在 Flutter 中，CustomPainter 提供了一个直接操作 GPU 绘图指令的接口。PiliPlus 的弹幕层本质上是一个全屏的 CustomPaint 组件，其 painter 属性绑定了一个自定义的画笔类。  
每当屏幕刷新信号（VSync）到来时，Flutter 引擎会调用 paint(Canvas canvas, Size size) 方法。在这个方法内部，PiliPlus 执行以下逻辑闭环：

1. **时间同步**：获取当前视频播放器的准确时间戳 T\_{current}。  
2. **对象筛选**：在弹幕池中通过二分查找（Binary Search）快速定位出所有满足 T\_{start} \\le T\_{current} \\le T\_{end} 的活跃弹幕对象。  
3. **位置计算**：对于每一个活跃对象，根据其类型（滚动、顶部、底部）计算当前的 (x, y) 坐标。  
   * **滚动弹幕公式**： 其中 W\_{screen} 是屏幕宽度，W\_{text} 是文字宽度，D\_{uration} 是弹幕飞过屏幕的总时长。  
4. **指令录制**：调用 TextPainter.paint(canvas, Offset(x, y)) 将文字绘制到画布上。

### **4.2 文本排版与光栅化缓存**

文本渲染是图形界面中最昂贵的操作之一。它涉及字形查找（Glyph Lookup）、字形定位（Shaping）和像素填充（Rasterization）。如果每一帧都对成百上千条弹幕重新进行排版（Layout），CPU 将不堪重负。  
PiliPlus 采用了激进的缓存策略来优化这一过程 。

* **预排版（Pre-layout）**：在弹幕对象首次被加载或即将进入屏幕前，系统会提前构建 TextPainter 并调用 layout() 方法。计算出的文本宽度和高度被存储在弹幕对象中，并在该对象的整个生命周期内复用。  
* **图像缓存（Raster Cache）**：对于复杂的弹幕（如带有描边、阴影或特殊字体），系统可能会将其绘制到一个离屏的 Picture 或 Image 对象中。在随后的每一帧中，直接绘制这个位图（Bitmap），而不是重新执行矢量文本绘制指令。这种“以空间换时间”的策略极大地降低了 GPU 的每帧绘制成本。

### **4.3 轨道管理与防碰撞算法**

为了保证弹幕在屏幕上整齐排列且互不遮挡，PiliPlus 实现了一套精密的轨道管理系统（Track System）。  
**轨道网格化**： 屏幕高度被划分为若干个逻辑轨道，轨道高度通常由字体大小（如 25px）加上行间距（Line Height）决定。  
**贪婪分配算法（Greedy Allocation）**： 当一条新弹幕需要显示时，系统会自上而下遍历所有轨道，寻找“空闲”轨道。判断轨道是否空闲的逻辑包含两个维度：

1. **空间维度**：当前轨道上最后一条弹幕的尾部是否已经完全进入屏幕（即 x\_{tail} \< W\_{screen}）。  
2. **时间维度（追尾检测）**：如果新弹幕的速度比前一条弹幕快，系统需要计算它们是否会在屏幕中间发生碰撞。PiliPlus 的 PR \#1791 "refa: danmaku & feat: scroll fixed velocity" 专门优化了这一逻辑，引入了固定速度滚动的选项，简化了碰撞检测逻辑，同时也符合 Bilibili 网页版的视觉习惯。

如果所有轨道都被占用，系统会根据用户设置决定策略：是直接重叠显示（Overlapping），还是丢弃该弹幕（Drop），亦或是进行智能堆叠。

### **4.4 渲染分层与脏区刷新**

为了进一步提升性能，PiliPlus 可能会利用 RepaintBoundary 将弹幕层与下方的视频层、上方的控制层隔离开。这样，弹幕的频繁重绘不会导致视频纹理的重新上传，也不会触发布局树的重建。  
此外，在 canvas\_danmaku 内部，静态弹幕（顶部/底部固定）和动态弹幕（滚动）可能被分层处理。静态弹幕不随时间改变位置（除非消失），因此可以绘制在一个单独的 Layer 上，利用 Flutter 的 Layer 缓存机制，仅在有新弹幕加入或旧弹幕消失时重绘，而滚动层则每帧重绘。

## **5\. 同步机制与时间管理**

音画同步是播放体验的生命线。弹幕如果快于或慢于视频内容，会造成严重的体验割裂（如剧透或反应迟钝）。

### **5.1 视频时钟源的获取**

PiliPlus 通过 media-kit 暴露的 stream.position 获取视频进度。然而，Dart 的 Stream 事件频率通常较低（例如每 200ms 推送一次），不足以驱动平滑的 60FPS 动画。  
为了解决这个问题，PiliPlus 采用了一种“推断式”时间同步策略：

1. **基准校对**：记录最近一次 media-kit 返回的准确时间戳 T\_{base} 和接收到该事件的系统时间 S\_{base}。  
2. **插值计算**：在每一帧渲染时（VSync 回调中），获取当前系统时间 S\_{now}。  
3. **推导**：当前视频时间 T\_{now} \\approx T\_{base} \+ (S\_{now} \- S\_{base}) \\times \\text{PlaybackRate}。

这种算法假设视频播放是线性且连续的。当发生暂停、缓冲或跳转（Seek）行为时，media-kit 会发送状态变更事件，控制器会立即重置基准时间，并清除当前的插值状态，以防止弹幕“瞬移”或时间倒流。

### **5.2 暂停与跳转的处理逻辑**

当用户拖动进度条（Seek）时：

1. **清空画布**：立即清除当前屏幕上的所有弹幕。  
2. **状态重置**：重置所有弹幕对象的内部状态（如已行驶距离）。  
3. **二分检索**：在排序后的弹幕列表中，快速找到新时间点 T\_{new} 对应的索引位置。  
4. **回溯加载**：由于滚动弹幕在屏幕上存在约 10-12 秒的存续时间，系统不仅要加载 T\_{new} 之后的弹幕，还要回溯加载 $$ 范围内的弹幕，并计算它们在 T\_{new} 时刻应处的中间位置，确保画面是“连续”的，而不是从空屏开始。

## **6\. 交互系统的实现：在像素中寻找目标**

传统的 Flutter 交互依赖于 Widget 的 GestureDetector。但在 PiliPlus 中，弹幕只是画在 Canvas 上的像素，没有对应的 Widget 实体。因此，PiliPlus 必须自行实现一套命中测试（Hit-Testing）系统 。

### **6.1 逆向投影算法**

当用户点击屏幕坐标 (x\_{touch}, y\_{touch}) 时，系统会触发以下逻辑：

1. **冻结时间**：记录点击发生的精确时刻 T\_{touch}。  
2. **遍历检索**：控制器倒序遍历当前屏幕上所有处于活跃状态的弹幕对象（倒序是为了优先选中渲染在最上层的弹幕）。  
3. **包围盒计算（AABB）**：对于每一个候选弹幕，利用其运动公式计算出它在 T\_{touch} 时刻的精确位置 (x\_{item}, y\_{item})，并结合其缓存的宽高 (w, h) 构建一个轴对齐包围盒（Axis-Aligned Bounding Box）：  
4. **几何包含测试**：判断点 (x\_{touch}, y\_{touch}) 是否在 Rect 内部。  
5. **结果响应**：一旦找到第一个命中的弹幕，立即停止遍历，并触发选中回调。

### **6.2 交互反馈与浮层菜单**

命中弹幕后，PiliPlus 会暂停视频播放（可选设置），并在点击位置弹出一个 OverlayEntry 浮层菜单。该菜单包含“点赞”、“复制内容”、“举报用户”、“查看发送者信息”等功能按钮。  
为了实现这一功能，项目集成了 bilibili-API-collect 提供的接口信息。例如，“点赞”操作需要调用 Bilibili 的 HTTP API，并带上弹幕的 oid (视频ID) 和 dmid (弹幕ID)。这些 ID 信息在最初的 Protobuf 解析阶段就已经被存储在 DanmakuItem 对象中。  
Pull Request \#1798 "add option to turn off dynamic interactions" 揭示了交互功能的性能开销。持续的命中测试计算（尤其是在鼠标悬停事件 onHover 中）会占用 CPU 资源，因此提供开关允许用户在低端设备上关闭此功能是一种必要的优化手段。

## **7\. 高级特性与 Shader 技术应用**

Bilibili 的弹幕文化中包含一种“高级弹幕”（BAS \- Bilibili Action Script），允许用户编写代码控制弹幕的运动轨迹、透明度变化甚至绘图。PiliPlus 对此进行了部分支持 。

### **7.1 矩阵变换与动画插值**

对于缩放、旋转等效果，PiliPlus 利用了 Canvas 的矩阵变换能力。

* **旋转**：canvas.rotate(radians)。  
* **缩放**：canvas.scale(sx, sy)。  
* **位移**：canvas.translate(dx, dy)。

为了实现平滑的动画效果，PiliPlus 内部实现了一套类似于 CSS Animation 的插值器（Interpolator）。例如，定义一个弹幕在 0s 到 2s 期间透明度从 0 变到 1，系统会在每一帧计算 opacity \= lerp(0, 1, progress)，并将其应用到 Paint 对象的 color 属性上。

### **7.2 GLSL 着色器的应用**

项目源码统计显示包含 2.7% 的 GLSL 代码 ，这表明 PiliPlus 在某些环节使用了自定义着色器。虽然 Flutter 主要依赖 Skia 自身生成的 Shader，但在一些特效处理上，开发者可能通过 FragmentProgram API 注入了自定义的片段着色器。  
可能的应用场景包括：

* **智能防挡脸（Smart Masking）**：这是一种高端弹幕技术，要求弹幕在经过人脸或主体人物时自动透明或被遮挡。这通常需要 AI 分割掩码（Segmentation Mask）。PiliPlus 可能利用 GLSL 将视频帧的掩码纹理作为输入，在绘制弹幕时进行像素级的 Alpha 混合测试：if (maskColor.r \> threshold) discard;。  
* **色彩滤镜**：为弹幕添加全局的描边、发光（Bloom）或反色效果，增强在高亮视频背景下的可读性。

## **8\. 性能优化与内存治理**

在移动设备资源受限的环境下，性能优化是 PiliPlus 弹幕系统成功的关键。

### **8.1 内存池（Object Pooling）**

Dart 语言拥有垃圾回收机制（GC）。如果在每一帧都创建新的 Offset、Paint 或 DanmakuItem 对象，将导致年轻代（Young Generation）GC 频繁触发，造成微小的卡顿。 PiliPlus 采用了对象复用策略，特别是对于高频使用的绘图对象。此外，对于滚出屏幕的弹幕，系统并不立即销毁其对象，而是将其重置状态后放回对象池，供新进入的弹幕复用数据结构，从而减少内存分配的开销。

### **8.2 列表操作优化**

在弹幕筛选和遍历过程中，PiliPlus 极力避免使用高复杂度的操作。例如：

* 使用 List.generate 预分配固定长度的数组，而非动态增长的 List。  
* 在排序算法上，由于弹幕数据天然具有时间局部性，新合并的分段数据往往是有序的，因此采用归并排序（O(N)）优于快速排序（O(N \\log N)）。

### **8.3 平台特定优化**

构建日志显示 PiliPlus 针对不同指令集架构（x86\_64, arm64）发布了独立的安装包 。这确保了底层的 media-kit 动态库（.so/.dll）能以最高效率运行。针对 Android 平台，项目可能还调整了 Flutter 引擎的线程优先级，确保渲染线程获得足够的 CPU 时间片。

## **9\. 结论**

PiliPlus 的弹幕系统是现代移动端图形工程的一个杰出范例。它没有止步于简单的 UI 堆叠，而是深入到底层渲染原理，构建了一套完整的、基于时间驱动的图形仿真引擎。  
通过**混合架构**，它解决了视频解码的性能瓶颈；通过**Isolate 并行解析**，它攻克了大数据量的加载延迟；通过**自定义绘制管线与轨道算法**，它实现了高帧率的密集文本渲染；通过**逆向投影交互**，它在非原生组件上复刻了复杂的社交互动体验。  
这一系统的实现不仅展示了 Flutter 框架在构建高性能富媒体应用方面的潜力，也为后续的开源项目提供了关于如何在跨平台环境下处理高并发、强交互图形系统的宝贵经验。随着未来 Flutter Impeller 引擎的成熟以及 WebAssembly 的普及，PiliPlus 的架构有望进一步演进，为用户带来更加极致的视听体验。

#### **引用的文献**

1\. bggRGjQaUbCoE/PiliPlus \- GitHub, https://github.com/bggRGjQaUbCoE/PiliPlus 2\. AUR (en) \- piliplus \- Arch Linux, https://aur.archlinux.org/packages/piliplus 3\. bggRGjQaUbCoE/PiliPlus 1.1.4.13 on GitHub \- NewReleases.io, https://newreleases.io/project/github/bggRGjQaUbCoE/PiliPlus/release/1.1.4.13 4\. Releases · bggRGjQaUbCoE/PiliPlus \- GitHub, https://github.com/bggRGjQaUbCoE/PiliPlus/releases 5\. Download shotcut-win\_ARM-25.12.31.exe (Shotcut) \- SourceForge, https://sourceforge.net/projects/shotcut/files/v25.12.31/shotcut-win\_ARM-25.12.31.exe/download 6\. PiliPlus download | SourceForge.net, https://sourceforge.net/projects/piliplus.mirror/ 7\. 技术日报｜社交媒体分析工具爆火，AI训练器与离线语音应用崛起\_ ..., https://devpress.csdn.net/aibjcy/6902beac0e4c466a32e2ac17.html 8\. hoilc/scoop-lemon: Yet Another Personal Bucket for Scoop \- GitHub, https://github.com/hoilc/scoop-lemon 9\. Build · Workflow runs · bggRGjQaUbCoE/PiliPlus \- GitHub, https://github.com/bggRGjQaUbCoE/PiliPlus/actions/workflows/build.yml 10\. Private Flutter Package repository \- OnePub, https://onepub.dev/packageDependerView/flutter\_overlay/versions/1.0.0 11\. 10个PiliPlus动画效果库插件推荐：打造极致UI体验的完整指南, https://blog.csdn.net/gitblog\_00544/article/details/152246311 12\. Danmaku plugin for mpv powered by dandanplay API \- GitHub, https://github.com/lu0se/danmaku 13\. canvas\_danmaku changelog | Flutter package \- Pub.dev, https://pub.dev/packages/canvas\_danmaku/changelog 14\. flutter鸿蒙：实现类似B站或抖音的弹幕功能原创 \- CSDN博客, https://blog.csdn.net/2501\_91974903/article/details/153728234 15\. View github: bggRGjQaUbCoE/PiliPlus | OpenText Core SCA, https://debricked.com/select/package/github-bggRGjQaUbCoE/PiliPlus 16\. PiliPlus 1.1.3版本更新解析：功能优化与体验升级- AtomGit | GitCode ..., https://blog.gitcode.com/e319f163cdab4a9bf3d33c8f9a8a25ea.html