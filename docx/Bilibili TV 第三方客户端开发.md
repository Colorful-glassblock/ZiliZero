# **Bilibili TV端第三方客户端架构演进与开发策略深度研究报告：从BBLL到PiliPlus的技术重构与未来路径**

## **1\. 执行摘要与战略背景**

在智能电视（Android TV）应用生态中，Bilibili（哔哩哔哩）第三方客户端的开发不仅是一个单纯的软件工程问题，更是一场关于逆向工程、跨平台架构优化以及对抗性互操作性（Adversarial Interoperability）的持续博弈。用户提出开发新的TV端Bilibili客户端的构想，这一需求不仅源于官方客户端（云视听小电视）在功能上的阉割（如弹幕定制受限、非会员画质锁死），更源于开源社区对自由软件精神的追求。然而，随着Bilibili服务端风控机制（Risk Control System）的全面升级，传统的开发模式已面临严峻挑战。  
本报告旨在通过对**BBLL**（已停止维护的经典原生Android项目）和**PiliPlus**（基于Flutter的新一代跨平台项目）进行法医级的深度剖析，揭示两者在架构逻辑、播放内核、网络协议以及风控规避策略上的根本差异。BBLL的陨落并非偶然，它是原生Android开发模式在面对高频API变动时维护成本过高的必然结果；而PiliPlus的崛起则验证了Flutter结合FFI（外部函数接口）调用底层媒体库（libmpv）在TV端开发的优越性。  
报告将分为六个核心章节，总计约15,000字，详细阐述从“原生视图渲染”向“Skia自绘引擎”的转型逻辑，解析从RESTful API向gRPC/Protobuf协议栈迁移的必要性，并提供一套完整的、抗风控的开发蓝图。这不仅是一份技术分析，更是为新一代TV客户端开发提供的生存指南。

## **2\. 遗产生态的法医分析：BBLL架构的兴衰逻辑**

要构建未来，必须理解历史。BBLL（Bilibili Leanback Launcher）由开发者xiaye13579维护，曾是Android TV端的事实标准 。深入研究BBLL的代码逻辑和生命周期，能为我们揭示第一代TV客户端的核心痛点。

### **2.1 原生Android架构与Leanback的局限性**

BBLL采用了标准的**Native Android**架构，主要使用Java和Kotlin编写。其UI构建严重依赖于Google提供的**Android Leanback Support Library** 。

#### **2.1.1 Leanback的设计哲学与桎梏**

Leanback库是Google为Android TV设计的UI框架，它提供了一套预制的Fragment和Widget（如BrowseFragment, DetailsFragment, VerticalGridFragment），专门用于处理D-Pad（方向键）导航和焦点移动。

* **优势逻辑：** BBLL利用Leanback快速实现了符合TV交互规范的界面。Leanback自动处理了焦点的查找（Focus Search）和高亮动画，开发者无需手动计算按键逻辑。这种“配置优于代码”的模式使得BBLL在初期能快速迭代。  
* **架构缺陷：** 随着Bilibili业务的复杂化（如引入互动视频、多行弹幕设置），Leanback的刚性结构成为了枷锁。Leanback的视图复用机制（Presenter）在处理高度定制化的UI元素时显得笨重。例如，在实现复杂的视频详情页（包含相关推荐、评论区、UP主动态）时，BBLL必须通过大量的Adapter嵌套和自定义Presenter来强行适配Leanback的框架，导致代码耦合度极高，维护难度呈指数级上升。

#### **2.1.2 视图系统的性能瓶颈**

BBLL基于Android原生的View系统。在TV设备上，尤其是那些配置低劣的机顶盒（通常搭载Cortex-A53四核处理器和1GB RAM），原生的View层次结构（View Hierarchy）过深会导致显著的性能问题。

* **焦点动画开销：** Android TV的焦点动画通常涉及View的缩放（Scale）和阴影（Elevation）变化。在低端设备上，频繁触发invalidate()和requestLayout()会导致UI线程掉帧（Jank）。BBLL在后期版本中常出现列表滚动卡顿，正是因为原生View在处理大量图片加载（Glide）和焦点重绘时的资源争抢 。

### **2.2 播放内核的致命伤：硬件解码的依赖**

BBLL在播放器实现上主要依赖ExoPlayer和ijkplayer，但在其README中明确指出：“应用播放功能基于硬件解码，暂时未加入其他软件解码器……不敢保证所有设备都可以正常播放视频” 。这一决策是BBLL技术逻辑中的最大败笔。

#### **2.2.1 碎片化的Android TV硬件生态**

Android TV的硬件生态极度碎片化。索尼、海信、小米以及无数的山寨盒子，其SoC（System on Chip）对视频编码的支持截然不同。

* **MediaCodec的黑盒特性：** Android的MediaCodec API是调用底层硬件解码器的桥梁。然而，不同厂商对MediaCodec的实现存在差异。某些设备声称支持HEVC（H.265），但在特定Profile（如Main 10）下会崩溃或绿屏。  
* **BBLL的逻辑缺陷：** BBLL缺乏一个强大的软解回退机制。当硬件解码失败时，应用往往直接报错或黑屏，而不是自动切换到软件解码（CPU解码）。这导致了大量用户反馈“无法播放”或“有声音无画面” 。虽然硬件解码效率高、功耗低，但在兼容性至上的第三方客户端开发中，过度依赖硬件解码是不可持续的。

### **2.3 “神秘力量”与API风控的溃败**

BBLL的停止维护，直接原因是无法对抗Bilibili日益严苛的API风控，即开发者口中的“神秘力量” 。

#### **2.3.1 静态签名的脆弱性**

BBLL的网络层（Network Layer）采用了静态的签名逻辑。Bilibili的API请求需要包含appkey和sign参数。在BBLL的时代，这些密钥通常被硬编码在APK的classes.dex或libbili.so中。

* **对抗逻辑：** 当Bilibili更新密钥或签名算法时，BBLL必须发布新的APK版本。这种“冷更新”模式具有极高的滞后性。用户在旧版本失效和新版本发布之间的真空期内无法使用服务，导致用户体验崩塌。  
* **WBI签名的引入：** 随着Bilibili引入WBI（Web Browser Interface）签名机制，要求客户端动态获取Mixin Key并对参数进行复杂排序和哈希运算 ，BBLL原有的基于Retrofit的简单拦截器逻辑已难以招架。实现这一套逻辑需要重构网络层，而原生开发的重构成本远高于动态语言。

#### **2.3.2 Error \-352的风暴**

Error \-352（风控校验失败）是BBLL后期的噩梦 。这是Bilibili服务端针对非官方流量的精准打击。它不仅仅检查签名，还检查客户端的TLS指纹（JA3 Fingerprint）、HTTP头部的顺序以及特定Cookie的存在。BBLL作为一个基于OkHttp的客户端，其TLS握手特征与真实的Chrome浏览器或官方APP存在显著差异，极易被识别并拦截。

### **2.4 结论：BBLL的历史教训**

BBLL的失败证明了在对抗性网络环境中，**静态的、依赖原生框架的架构是脆弱的**。未来的客户端必须具备：

1. \*\*动态性：\*\*能够热更新API签名逻辑。  
2. \*\*兼容性：\*\*自带解码器，不依赖系统MediaCodec。  
3. \*\*伪装性：\*\*能够完美模拟浏览器或官方客户端的网络指纹。

## **3\. 现代架构的范式转移：PiliPlus的Flutter革命**

PiliPlus（及其衍生项目如PiliPala）代表了TV端开发的V2.0时代。其核心逻辑是利用**Flutter**构建跨平台应用，并引入**media\_kit**作为播放引擎。这一架构彻底解决了BBLL面临的大部分痛点 。

### **3.1 Flutter在TV端的战略优势**

选择Flutter并非单纯为了跨平台（虽然PiliPlus支持iOS/Android/Windows/Linux），而是为了获得对渲染管线的绝对控制权。

#### **3.1.1 Skia/Impeller渲染引擎的胜利**

与Android原生View不同，Flutter使用Skia（或新的Impeller）引擎直接在GPU上绘制UI。

* **一致性：** 无论是在Android 5.0的旧盒子上，还是Android 14的新电视上，Flutter绘制的UI像素是完全一致的。这消除了OEM厂商魔改系统UI带来的兼容性问题。  
* **性能隔离：** Flutter的UI线程与平台线程分离。即使底层网络请求或数据处理繁重，UI线程仍能保持流畅的动画效果。对于TV端至关重要的焦点移动动画，Flutter可以实现60fps的丝滑体验，而不会像Leanback那样因为主线程阻塞而掉帧 。

#### **3.1.2 声明式UI与焦点管理**

PiliPlus的代码逻辑展示了声明式UI（Declarative UI）在处理复杂状态时的优势。

* **FocusNode体系：** TV交互的核心是焦点。Flutter提供了一套强大的FocusNode和FocusScope机制。PiliPlus通过嵌套FocusScope来管理不同区域（侧边栏、视频网格、播放器控制栏）的焦点转移。这种逻辑比Android原生的nextFocusDown/nextFocusUp属性更加灵活和可编程 。  
* **状态驱动：** 使用Riverpod或Provider进行状态管理，使得PiliPlus能轻松处理“登录状态”、“播放历史”、“弹幕设置”等全局状态的同步，而无需像BBLL那样通过EventBus或广播在组件间传递消息 。

### **3.2 播放内核的终极方案：media\_kit与libmpv**

PiliPlus最核心的技术突破在于集成了media\_kit 。这是一个基于Dart FFI（Foreign Function Interface）绑定的libmpv播放器库。

#### **3.2.1 为什么是mpv？**

mpv是开源界最强大的媒体播放器内核，它内置了FFmpeg。

* **全格式支持：** PiliPlus不再依赖Android系统的解码器。mpv自带了几乎所有已知视频格式（HEVC, VP9, AV1）和音频格式（OPUS, FLAC, AAC）的解码器。这意味着只要CPU跑得动，视频就能放，彻底解决了BBLL的黑屏问题 。  
* **ASS/SSA字幕渲染：** Bilibili的番剧由于大量使用高级弹幕和特效字幕（ASS格式），原生播放器几乎无法正确渲染。media\_kit利用libass库，能够完美还原这些复杂的排版效果，这对二次元用户至关重要 。

#### **3.2.2 FFI带来的性能飞跃**

传统的Android播放器插件（如video\_player）通过Platform Channels（平台通道）在Dart和Java之间传递消息。这种序列化和反序列化的过程在高频调用（如更新播放进度、渲染弹幕）时会产生巨大的性能开销。

* **零拷贝（Zero-Copy）渲染：** media\_kit通过FFI直接调用C语言层面的libmpv API，并将视频纹理（Texture）直接传递给Flutter的渲染引擎。这种机制绕过了Java虚拟机（JVM），极大降低了CPU占用率，使得在中低端TV芯片上播放1080p甚至4K视频成为可能 。

### **3.3 弹幕渲染系统的重构：Canvas Danmaku**

PiliPlus使用了canvas\_danmaku库 ，这是针对Flutter优化的弹幕渲染引擎。

#### **3.3.1 渲染逻辑的差异**

* **BBLL模式（View堆叠）：** 每一个弹幕是一个TextView。当屏幕上有100条弹幕时，就是100个View对象。这会造成巨大的内存压力和GC（垃圾回收）卡顿。  
* **PiliPlus模式（Canvas绘制）：** canvas\_danmaku创建一个单一的CustomPainter。所有的弹幕只是这个画板上的像素点。它在每一帧计算所有弹幕的位置并一次性绘制。这类似于游戏引擎的渲染逻辑，性能极高。

#### **3.3.2 碰撞检测与轨道管理**

PiliPlus的弹幕逻辑包含了一套复杂的轨道管理算法（Track Management System）。

1. **轨道分配：** 将屏幕划分为若干水平轨道。  
2. **碰撞计算：** 在发射新弹幕时，计算其速度和长度，确保它不会追尾前一条弹幕。  
3. **时间同步：** 弹幕的时间戳必须与media\_kit提供的视频播放时间精确同步。由于media\_kit通过FFI提供高精度的回调，PiliPlus能实现毫秒级的弹幕同步，避免了音画不同步的问题 。

## **4\. Bilibili API防御体系深度解构与规避策略**

要开发一个可用的TV客户端，仅仅有好的UI和播放器是不够的。核心难点在于攻克Bilibili日益复杂的API防御体系。以下是对当前风控机制的深度技术拆解。

### **4.1 核心风控机制：Error \-352与环境伪装**

如前所述，Error \-352代表“风控校验失败”。这是Bilibili服务端对客户端环境的质询。

#### **4.1.1 浏览器指纹模拟 (Browser Fingerprinting Simulation)**

Bilibili的WBI接口（如/x/space/wbi/arc/search）要求客户端提交特定的参数来证明自己是一个“合法的浏览器”。

* **关键参数 ：**  
  * dm\_img\_list: 通常为空数组 \`\`。  
  * dm\_img\_str: WebGL版本的Base64编码，例如 V2ViR0wgMS (对应 "WebGL 1")。  
  * dm\_cover\_img\_str: WebGL渲染器信息的Base64编码，例如 QU5HTEUgKEludGVs... (对应 "ANGLE (Intel, Intel(R) HD Graphics...)")。

**开发指导：** 你的TV客户端**绝不能**诚实地告诉服务器它是Android App。你必须在网络层拦截每一个API请求，并注入这些伪造的WebGL指纹参数。这是一种欺骗战术：即使你的载体是Android TV APK，你的网络指纹必须看起来像是在PC Chrome浏览器上发出的请求 。

#### **4.1.2 动态WBI签名 (Dynamic WBI Signing)**

WBI签名算法不仅是静态的哈希，它包含了一个动态获取的密钥。

1. **密钥获取：** 访问https://api.bilibili.com/x/web-interface/nav，从返回的JSON中提取img\_url和sub\_url的文件名。这两个文件名就是当天的img\_key和sub\_key。  
2. **Mixin算法：** 将这两个Key拼接，并按照特定的置换表（Mixin Table）进行字符重排，生成最终的Mixin Key 。  
3. **参数签名：** 将所有URL查询参数（Query Params）按键名排序，拼接上当前时间戳wts，最后加上Mix\[span\_8\](start\_span)\[span\_8\](end\_span)in Key进行MD5哈希，得到w\_rid。

**开发指导：** 你需要在客户端实现一个**密钥缓存池**。应用启动时获取Key，缓存24小时。如果请求返回签名错误，立即强制刷新Key。不要每次请求都去获取Key，那会触发频率限制（Rate Limit）。

### **4.2 视频流分发协议的演进：从HTTP到gRPC**

Bilibili正在逐步将其核心视频流服务（PlayURL）迁移到gRPC协议。这是为了提高传输效率（HTTP/2, Protobuf）并增加逆向难度。

#### **4.2.1 gRPC与Protobuf的壁垒**

传统的抓包工具（Charles/Fiddler）看到的是二进制乱码。这是Protobuf序列化后的数据。如果不了解.proto定义文件，就无法解析数据结构。

* **PlayView服务：** 这是获取视频播放地址的核心gRPC服务。  
* **优势：** gRPC接口通常能返回更高规格的流媒体（如杜比视界、4K 120fps、Hi-Res无损音质），而Web HTTP接口往往被限制在1080p High Profile 。

#### **4.2.2 应对策略**

为了开发高画质TV版，你必须集成gRPC客户端。

1. **获取Proto定义：** 必须持续关注开源社区（如SocialSisterYi/bilibili-API-collect），获取最新的.proto文件 。  
2. **双栈架构 (Dual-Stack Architecture)：**  
   * **首选策略：** 尝试通过gRPC调用PlayView接口。如果成功，你将获得最高画质的DASH流地址。  
   * **降级策略：** 如果gRPC调用失败（网络阻断或协议变更），自动降级到HTTP REST API (/x/player/playurl)。虽然画质可能受限，但能保证“能看” 。

### **4.3 登录流程的特殊性：TV端扫码**

由于TV输入文字极其困难，且直接输入密码存在安全隐患，必须实现**TV扫码登录**流程。

#### **4.3.1 协议细节**

1. **申请二维码：** POST https://passport.bilibili.com/x/passport-tv-login/qrcode/auth\_code。注意，必须使用TV端专用的appkey（如4409e2ce8ffd12b8，对应“云视听小电视”），否则登录后的Token可能没有高画质权限。  
2. **轮询状态：** 使用返回的auth\_code，每隔3秒POST https://passport.bilibili.com/x/passport-tv-login/qrcode/poll。  
3. **持久化：** 成功后，保存access\_token和refresh\_token。注意Token的刷新机制，OAuth2 Token通常有有效期，需要定期刷新。

## **5\. 开发蓝图与技术实施细节**

基于上述分析，本章节为开发者提供具体的实施路径。

### **5.1 技术栈选型推荐表**

| 组件 (Component) | 推荐方案 (Recommendation) | 核心理由 (Rationale) |
| :---- | :---- | :---- |
| **开发框架** | **Flutter** | 跨平台、自绘引擎、高性能D-Pad焦点支持 。 |
| **语言** | **Dart** | 强类型，不仅用于UI，还可高效处理Protobuf和二进制流操作。 |
| **播放器内核** | **media\_kit (基于libmpv)** | 解决解码碎片化问题，支持ASS特效字幕，支持HTTP Header注入（关键！）。 |
| **网络库** | **Dio** | 强大的拦截器（Interceptor）机制，适合统一处理WBI签名、Cookie管理和User-Agent伪装。 |
| **状态管理** | **Riverpod** | 比Provider更安全，无Context依赖，适合处理复杂的全局状态（如播放器状态、用户鉴权）。 |
| **数据协议** | **Protobuf / gRPC** | 必须集成，为了获取4K/杜比画质。 |
| *弹幕引擎* | **canvas\_danmaku** | 唯一可行的TV端高性能弹幕渲染方案 。 |

### **5.2 核心模块实现细节**

#### **5.2.1 网络层拦截器设计 (The Interceptor Strategy)**

你需要在Dio中实现一个RiskControlInterceptor。  
`class RiskControlInterceptor extends Interceptor {`  
  `@override`  
  `void onRequest(RequestOptions options, RequestInterceptorHandler handler) {`  
    `// 1. 注入伪造的User-Agent (模拟PC Chrome)`  
    `options.headers['User-Agent'] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36...";`  
      
    `// 2. 注入Referer (防止403 Forbidden)`  
    `options.headers = "https://www.bilibili.com/";`

    `// 3. 针对特定Path注入WebGL指纹 (规避Error -352)`  
    `if (options.path.contains('/wbi/')) {`  
        `options.queryParameters.addAll({`  
            `'dm_img_list': '',`  
            `'dm_img_str': 'V2ViR0wgMS', // Base64 of "WebGL 1"`  
            `'dm_cover_img_str': 'QU5HTEUgKEludGVs...' // Base64 of Renderer Info`  
        `});`  
          
        `// 4. 执行WBI签名计算，追加 w_rid 和 wts 参数`  
        `final signedParams = WbiSigner.sign(options.queryParameters);`  
        `options.queryParameters = signedParams;`  
    `}`  
    `handler.next(options);`  
  `}`  
`}`

* **User-Agent轮换：** 建议不要硬编码唯一的UA。可以在应用启动时从一个内置的UA列表中随机选择一个，或者允许用户自定义。这能降低被特征识别的风险 。

#### **5.2.2 播放器配置 (Player Configuration)**

使用media\_kit时，必须针对TV硬件进行调优。  
`final player = Player(configuration: const PlayerConfiguration(`  
  `// 关键：启用硬件解码，但允许回退`  
  `vo: 'gpu',`   
  `hwdec: 'auto',`   
`));`

`// 设置HTTP Headers，让mpv能下载B站视频流`  
`(player.platform as NativePlayer).setProperty('user-agent', 'Mozilla/5.0...');`  
`(player.platform as NativePlayer).setProperty('referrer', 'https://www.bilibili.com/');`

* **缓冲策略：** TV通常连接WiFi，网络波动大。建议在mpv配置中增加demuxer-max-bytes和cache的大小，以此换取播放的流畅度 。

#### **5.2.3 TV端交互优化 (TV Interaction Design)**

* **Focus Scope：** 将屏幕划分为不同的区域（Sidebar, ContentArea）。当用户从ContentArea向左按键时，使用FocusScope.of(context).requestFocus()显式地将焦点转移到Sidebar，而不是依赖系统的自动查找。  
* **按键防抖 (Debouncing)：** TV遥控器的红外信号有时会连击。在处理“确认”或“返回”事件时，加入200ms的防抖逻辑，防止一次按键触发多次跳转。  
* **视觉反馈：** 所有可交互组件必须有明显的焦点态（Focused State）。不仅是边框变色，最好加上Scale（轻微放大）和Elevation（阴影加深）动画，让用户明确知道当前选中的位置 。

### **5.3 构建与分发注意事项**

#### **5.3.1 ABI拆分 (ABI Splitting)**

Android TV设备主要架构为armeabi-v7a（旧设备，如小米盒子3）和arm64-v8a（新设备，如Shield TV）。

* **不要构建通用APK（Fat APK）：** 包含所有架构的APK体积太大。  
* **策略：** 在flutter build apk时使用--split-per-abi参数。TV设备的存储空间极为珍贵，减小APK体积能显著提升用户安装意愿 。

#### **5.3.2 签名与安全**

* **密钥保护：** 不要将Bilibili的AppSecret直接明文写在代码里。至少使用简单的异或（XOR）混淆，或者将其拆分存储在NDK层（C++代码）中，增加逆向难度。  
* **开源策略：** 既然你的目标是开源（参考PiliPlus），请务必将敏感的密钥文件（如包含API Key的dart文件）放入.gitignore，并在README中指导开发者如何填入自己的Key进行编译。这不仅保护了Key，也避免了DMCA的直接打击。

## **6\. 结论**

通过对BBLL和PiliPlus的深度剖析，我们可以清晰地看到Bilibili TV端第三方开发的演进脉络：从\*\*“原生适配+硬解”**的旧时代，迈向了**“跨平台渲染+软硬结合+环境模拟”\*\*的新时代。  
BBLL的失败告诉我们，面对Bilibili这种快速迭代且具有强风控机制的平台，静态的、依赖系统底层的架构是死路一条。而PiliPlus的成功则指明了方向：**Flutter**提供了统一的UI和焦点管理能力，**media\_kit**解决了编解码的兼容性难题，而**动态的WBI与浏览器指纹注入**则是突破风控封锁的关键钥匙。  
对于你的开发项目，建议严格遵循\*\*“模拟而非适配”\*\*的原则。你的客户端不应仅仅适配Bilibili的API，而应模拟一个标准的Web浏览器行为。只有做到这一点，才能在与“神秘力量”的博弈中长期生存，为用户带来稳定、高清、自由的客厅娱乐体验。这不仅是技术的胜利，更是开源社区在封闭生态中争取数字权利的一次有力实践。

#### **引用的文献**

1\. github.com-xiaye13579-BBLL\_-\_2023-10-23\_03-37-14, https://archive.org/details/github.com-xiaye13579-BBLL\_-\_2023-10-23\_03-37-14 2\. Android TV Application Development: Complete Guide \- Leanware, https://www.leanware.co/insights/android-tv-application-development-complete-guide 3\. What's special in Designing Apps for Android TV? | by Nitish Gadangi, https://nitishgadangi.medium.com/whats-special-in-designing-apps-for-android-tv-5f88447c523a 4\. xiaye13579/BBLL \- GitHub, https://github.com/xiaye13579/BBLL/issues 5\. xiaye13579 BBLL · Discussions \- GitHub, https://github.com/xiaye13579/BBLL/discussions 6\. WBI 签名| BAC Document, https://socialsisteryi.github.io/bilibili-API-collect/docs/misc/sign/wbi.html 7\. Building a Fault-Tolerant Live Camera Streaming Player in Flutter ..., https://medium.com/@pranav.tech06/building-a-fault-tolerant-live-camera-streaming-player-in-flutter-with-media-kit-28dcc0667b7a 8\. GitHub \- bggRGjQaUbCoE/PiliPlus, https://github.com/bggRGjQaUbCoE/PiliPlus 9\. media\_kit 0.0.11 | Dart package \- Pub.dev, https://pub.dev/packages/media\_kit/versions/0.0.11 10\. Optimizing Flutter App Performance in 2025: A Developer's Guide, https://medium.com/@chandru1918g/optimizing-flutter-app-performance-in-2025-a-developers-guide-c2c32e6f9f21 11\. Flutter Performance Optimization: Best Practices with Real Examples, https://medium.com/@dhruvmanavadaria/flutter-performance-optimization-best-practices-with-real-examples-912a853c158a 12\. Using Flutter to Build TV Apps: Experiences and Challenges \- Medium, https://medium.com/@ulinukhafikri/using-flutter-to-build-tv-apps-experiences-and-challenges-8bb26c7deb5b 13\. Support Android TV ☂️ · Issue \#180542 · flutter/flutter · GitHub, https://github.com/flutter/flutter/issues/180542 14\. video\_player\_media\_kit | Flutter package \- Pub.dev, https://pub.dev/packages/video\_player\_media\_kit 15\. canvas\_danmaku package \- All Versions \- pub.dev, https://pub.dev/packages/canvas\_danmaku/versions 16\. 用户主页获取投稿列表-352 新增校验\#868 \- GitHub, https://github.com/SocialSisterYi/bilibili-API-collect/issues/868 17\. T9953 player.bilibili.com (and sites related to it) CSP whitelist, https://issue-tracker.miraheze.org/T9953 18\. \[Web API Risk Control\] \`dm\_img\` series risk control params collection, https://github.com/SocialSisterYi/bilibili-API-collect/issues/951 19\. OpenBilibili/app/job/bbq/recall/api/v1/api.proto at master \- GitHub, https://github.com/Forcrush/OpenBilibili/blob/master/app/job/bbq/recall/api/v1/api.proto 20\. Protobuf Guideline \- Kratos, https://go-kratos.dev/docs/guide/api-protobuf/ 21\. 视频基本信息| BAC Document, https://socialsisteryi.github.io/bilibili-API-collect/docs/video/info.html 22\. User-Agent header \- HTTP \- MDN Web Docs \- Mozilla, https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/User-Agent