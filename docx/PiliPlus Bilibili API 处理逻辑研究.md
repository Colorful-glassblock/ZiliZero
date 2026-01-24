# **PiliPlus 客户端 Bilibili API 处理逻辑深度研究报告**

## **1\. 执行摘要**

随着移动互联网技术的飞速发展与流媒体平台的日益普及，Bilibili（哔哩哔哩）作为中国年轻一代核心的视频社区，其客户端生态呈现出多元化的发展趋势。在官方客户端之外，开源社区涌现出了一批基于现代跨平台框架构建的第三方客户端，其中 **PiliPlus** 作为一个备受瞩目的项目，凭借其激进的功能迭代和对 Flutter 框架的深度应用，成为了研究 Bilibili API 交互逻辑的绝佳样本。

本报告旨在对 PiliPlus 客户端针对 Bilibili API 的所有处理逻辑进行详尽的、原子级的技术解构。报告将深入剖析其基于 Dart 语言的网络栈架构、复杂的多模式鉴权系统（包括 Wbi 签名与 AppKey 签名）、Protobuf 协议在弹幕系统中的二进制实现、以及基于 DASH 协议的高级流媒体播放逻辑。通过对源代码依赖（如 dio、media\_kit）、公开文档（bilibili-API-collect）及版本迭代日志的交叉验证分析，本报告揭示了 PiliPlus 如何在没有官方文档支持的情况下，通过逆向工程与协议模拟，实现与 Bilibili 庞大后端服务的无缝对接与高可用交互 1。

研究发现，PiliPlus 的核心竞争力在于其构建了一套高度模块化且具有自适应能力的 API 处理中间件。该中间件不仅能够处理常规的 RESTful 请求，还能动态应对 Bilibili 频繁变动的风控机制（如 Geetest 验证、User-Agent 封锁及 API 签名算法变更），从而保证了 4K 播放、弹幕互动及用户社区功能的稳定性。

## **2\. 架构基础与技术生态**

PiliPlus 的 API 处理逻辑并非孤立存在，而是深深植根于其技术栈的选择之中。作为 PiliPala 的分支（Fork），PiliPlus 继承了 Flutter 的跨平台基因，并在此基础上进行了更激进的架构调整，以支持更多原生 Bilibili 功能 1。

### **2.1 Flutter 与 Dart 的异步 I/O 模型**

PiliPlus 96.7% 的代码库由 Dart 语言编写 1。Dart 的单线程事件循环（Event Loop）模型决定了 PiliPlus 在处理网络请求时必须采用非阻塞（Non-blocking）的异步逻辑。

在与 Bilibili API 进行交互时，PiliPlus 广泛使用了 Dart 的 Future 和 Stream 机制。每一个 API 调用，无论是获取视频详情还是发送一条弹幕，本质上都是一个异步操作。这种设计确保了即便是在网络环境较差、API 响应延迟较高的情况下，应用的主 UI 线程（负责 60fps/120fps 的渲染）也不会被阻塞，从而维持了流畅的用户体验。

这种异步模型对 API 逻辑的影响深远：

1. **并发请求管理**：在加载用户主页或视频详情页时，PiliPlus 需要同时请求多个接口（如用户信息、视频列表、粉丝数统计）。Dart 的 Future.wait 被用于并行处理这些请求，并在所有数据就绪后统一刷新 UI，极大减少了页面的“白屏”时间。  
2. **流式数据处理**：对于视频流下载或实时弹幕，PiliPlus 利用 Stream 对数据包进行分片处理，实现了边下载边缓存（或边播放）的逻辑，而非等待整个文件下载完成。

### **2.2 核心网络引擎：Dio 的深度定制**

在 Flutter 生态中，http 标准库的功能较为基础，无法满足复杂的企业级 API 交互需求。PiliPlus 选择了 dio —— 一个强大的 Dart HTTP 客户端，作为其网络通信的基石 1。PiliPlus 对 Bilibili API 的所有“处理逻辑”，在代码层面几乎全部封装在对 dio 实例的配置与拦截器中。

#### **2.2.1 BaseOptions 配置策略**

为了适配 Bilibili 服务器的特性，PiliPlus 在初始化 dio 单例时，会配置一套特定的 BaseOptions：

| 配置项 | 说明 | 逻辑意图 |
| :---- | :---- | :---- |
| **connectTimeout** | 连接超时 | 设置为 15-30 秒。Bilibili 的服务端在高峰期可能响应缓慢，过短的超时会导致频繁的连接错误，过长则会导致用户界面长时间无反馈 3。 |
| **receiveTimeout** | 接收超时 | 针对大体积响应（如长视频的弹幕列表或超长评论区），给予足够的时间窗口传输数据。 |
| **responseType** | 响应类型 | 默认为 json。Dio 会自动将返回的 JSON 字符串反序列化为 Dart 的 Map 对象，简化了后续的业务逻辑处理。 |
| **headers** | 全局请求头 | 预埋 Accept-Encoding: gzip 等标准头，确保数据传输的高效性。 |

#### **2.2.2 拦截器（Interceptors）体系**

拦截器是 PiliPlus API 处理逻辑的“神经中枢”。它允许开发者在请求发出前（onRequest）、响应到达后（onResponse）以及发生错误时（onError）注入自定义逻辑。PiliPlus 利用这一机制实现了 API 的标准化和自动化处理。

* **请求拦截器（Request Interceptor）**：  
  这是 API 逻辑的起点。每当业务层发起一个请求（例如 userRepository.login()），拦截器会捕获该请求，并根据目标 URL 的特征动态注入必要的元数据。对于 Bilibili 的 APP 接口，拦截器会自动添加 appkey、ts（时间戳）、build（版本号）、mobi\_app（客户端类型）等参数，并计算签名（Sign）。这种“切面编程”的设计使得业务代码无需关心底层的签名算法，只需关注业务参数本身。  
* **响应拦截器（Response Interceptor）**：  
  Bilibili 的 API 设计存在一种“业务错误包含在 HTTP 200 响应中”的模式。即 HTTP 状态码返回 200 OK，但 JSON Body 中的 code 字段可能为负数（如 \-101 账号未登录，-404 视频不存在）。PiliPlus 的响应拦截器会统一解析这个 code 字段。如果 code\!= 0，拦截器会抛出一个自定义的 ApiException，中断正常的响应流程，并由全局错误处理器捕获。这种逻辑确保了上层 UI 组件只会接收到“成功”的数据，简化了 UI 的状态判断逻辑。  
* **Cookie 管理拦截器**： 集成 dio\_cookie\_manager 4，自动处理 Bilibili 后端下发的 Set-Cookie 指令。这对于维持用户的登录状态（SESSDATA）至关重要。

## **3\. 鉴权与身份认证逻辑**

身份认证是客户端与服务器建立信任的桥梁。PiliPlus 必须模拟官方客户端的鉴权行为，才能获取高清视频流（1080P+）、查看历史记录及进行弹幕互动。由于 Bilibili 提供了 Web 端、TV 端和移动端（APP）多种登录接口，PiliPlus 在逻辑上实现了对这些接口的混合调用与适配。

### **3.1 二维码登录逻辑（TV/Web 协议）**

二维码登录因其安全性高（无需在第三方应用输入密码）而被广泛推荐。PiliPlus 采用了类似 Bilibili TV 端的扫码登录协议，其处理逻辑如下 5：

1. **获取鉴权码（Auth Code）**：  
   客户端向 /x/passport-tv-login/qrcode/auth 发起 GET 请求。该接口会返回一个 url（用于生成二维码图片）和一个 auth\_code（作为本次会话的唯一标识）。PiliPlus 的 UI 层利用 Flutter 的二维码渲染库将 url 转换为图形展示给用户。  
2. **长轮询（Long-Polling）机制**：  
   这是二维码登录逻辑的核心。PiliPlus 会启动一个定时器（Timer），通常每隔 3 秒向 /x/passport-tv-login/qrcode/poll 接口发送一次请求，携带 auth\_code。  
   该接口的返回逻辑是一个典型的状态机：  
   * **Code 86038（二维码已过期）**：逻辑层捕获此码后，会自动停止轮询，并提示用户刷新，重新触发第一步。  
   * **Code 86090（未确认）**：表示用户已扫码但未在手机端点击“确认登录”。UI 层会更新文案提示用户操作。  
   * **Code 0（成功）**：表示登录成功。响应体中会包含 access\_token（用于 APP 接口）、refresh\_token 以及关键的 Cookie 信息。  
3. **持久化存储**：  
   一旦轮询成功，PiliPlus 会立即将获取到的 Token 和 Cookie 写入本地安全存储（如 flutter\_secure\_storage 或加密的 Hive 盒子），并通知全局状态管理器（Provider/Riverpod）更新用户状态为“已登录”。

### **3.2 密码登录与验证码挑战（Geetest）**

尽管 PiliPala 因为极验（Geetest）SDK 的闭源协议问题曾一度移除相关功能 6，但 PiliPlus 在其“激进”的更新中重新引入了对复杂登录场景的支持，尤其是针对 Windows 和 Mac 端的极验验证 1。

当用户选择账号密码登录时，PiliPlus 需要处理 Bilibili 的加密与风控逻辑：

1. **密码加密**：Bilibili 不传输明文密码。客户端首先请求服务器的公钥（Public Key）和盐值（Salt）。PiliPlus 内部实现了 RSA 加密算法，将 (用户密码 \+ 盐值) 进行加密后作为参数发送。  
2. **风控拦截处理**：  
   如果登录环境（IP、设备指纹）被判定为风险，API 会返回特定的错误码，并附带一个极验的 challenge 字符串。  
   PiliPlus 的处理逻辑是：暂停登录流程 \-\> 唤起一个 WebView 或调用原生桥接（Channel）加载极验的验证页面 \-\> 用户完成拼图/点击验证 \-\> 获取 validate 和 seccode \-\> 将这些凭证附加到登录接口的参数中进行“二次提交”。  
   这一逻辑的实现难度极高，因为涉及到 Flutter 与原生 WebView 的上下文通信以及 Cookie 的同步问题。

### **3.3 Cookie 与 Token 的生命周期管理**

* **SESSDATA**：这是 Web 接口鉴权的核心 Cookie。PiliPlus 依靠 dio\_cookie\_manager 自动维护其生命周期。每次 API 请求都会自动携带 Cookie: SESSDATA=... 头。  
* **Refresh Token**：OAuth2 协议的一部分。当 API 返回 Access Token Expired 错误时，PiliPlus 内部的拦截器会捕获该错误，锁定所有正在发出的请求（Request Queueing），在后台利用 refresh\_token 换取新的 access\_token，更新本地存储，然后重试之前失败的请求。这种“无感刷新”逻辑保证了用户在长期使用中无需频繁重新登录 7。

## **4\. 签名算法与安全防护机制**

为了防止 API 被滥用，Bilibili 对不同类型的接口实施了严格的签名验证机制。PiliPlus 必须完美复刻这些签名算法，否则请求将被服务器直接拒绝（HTTP 403 或 Code \-40x）。

### **4.1 APP 接口签名（AppKey \+ MD5）**

绝大多数涉及视频流获取、下载、推荐流的接口都属于 APP 接口范畴。

* **原理**：Bilibili 为不同的官方客户端（Android, iOS, iPadHD）分配了不同的 AppKey 和 AppSecret。  
* **PiliPlus 的实现逻辑**：  
  1. **参数收集**：拦截器收集所有 URL 查询参数（Query Parameters），如 avid, qn, ts 等。  
  2. **字典排序**：将参数按照键名（Key）的 ASCII 码顺序进行升序排列。  
  3. **拼接**：将排序后的参数拼接成 key1=value1\&key2=value2 的字符串格式。  
  4. **加盐**：在字符串末尾直接拼接对应的 AppSecret（这是一个不在网络传输中显露的私钥）。  
  5. **哈希计算**：对最终字符串进行 MD5 运算，生成 32 位小写的十六进制字符串，作为 sign 参数附加到请求中。  
* **密钥管理**：PiliPlus 的源码中内置了多组 Key（如 Android 手机端 Key、Android TV 端 Key）。逻辑层允许在某一 Key 被风控失效时，切换使用另一组 Key，或者通过远程配置更新 Key 1。

### **4.2 Web 接口签名（Wbi Signature）**

这是 Bilibili 在 2023 年引入的新型风控机制，主要用于 Web 端的搜索、用户信息等高频接口。PiliPlus 紧跟这一变化，实现了复杂的 Wbi 签名逻辑 7。

* **Mixin Key 的推导逻辑**：  
  与 AppKey 不同，Wbi 的密钥是动态的。  
  1. PiliPlus 首先访问 /x/web-interface/nav 接口。  
  2. 解析响应中的 wbi\_img 字段，获取 img\_url 和 sub\_url。  
  3. 从这两个 URL 中提取文件名部分（去除 .png 后缀），拼接成一个长字符串。  
  4. **Mixin 混淆**：PiliPlus 内置了一个特定的置换表（Permutation Table），将上述拼接后的字符串按照特定索引顺序重新排列，并截取前 32 位，生成当次会话的 mixin\_key。  
* **签名计算**：  
  1. 在请求参数中注入当前的 Unix 时间戳 wts。  
  2. 对参数进行排序和 URL 编码（RFC 3986 标准）。  
  3. 拼接 mixin\_key 后进行 MD5 运算，生成 w\_rid 参数。  
* **缓存策略**：由于 mixin\_key 每天可能发生变化，但不需要每次请求都获取。PiliPlus 实现了一个缓存逻辑，仅在启动时或签名验证失败（Code \-403）时才重新拉取 nav 接口更新 Key，以减少网络开销。

### **4.3 应对风控代码 \-352**

错误码 \-352 是 Bilibili 风控系统拦截的标志，通常意味着请求指纹（Fingerprint）异常。PiliPlus 对此有一套防御性逻辑：

* **API 混用策略**：PiliPlus 不单纯依赖某一种接口。例如，在获取视频详情时，它可能优先尝试 APP 接口；如果失败或遭遇风控，则降级尝试 Web 接口（Wbi 签名）。  
* **User-Agent 伪装**：PiliPlus 允许动态配置 User-Agent。在请求视频流时，它会伪装成官方 Android 客户端（例如 Mozilla/5.0... BiliDroid/7.35.0），而在请求 Web 接口时则伪装成桌面浏览器。这种上下文感知的 UA 切换逻辑是规避风控的关键 10。

## **5\. 数据序列化与传输协议**

PiliPlus 处理的数据格式主要有两种：JSON 和 Protobuf。

### **5.1 JSON 数据的严谨处理**

Bilibili 的 API 返回的 JSON 数据结构有时并不稳定（例如，某些字段在空值时可能直接不返回，或者类型发生隐式转换）。PiliPlus 使用了 json\_serializable 等代码生成库来处理 JSON 反序列化。

* **空安全（Null Safety）**：Dart 的空安全特性强制 PiliPlus 的数据模型（Model）必须明确字段的可空性。处理逻辑中包含了大量的 defaultValue 填充和类型转换检查（如将字符串 "123" 安全转换为整型 123），以防止因 API 格式微调导致的应用崩溃。

### **5.2 Protobuf 与弹幕系统**

为了传输高密度的实时弹幕，Bilibili 使用了 Google 的 Protocol Buffers（Protobuf）二进制协议。相比 JSON，Protobuf 体积更小，解析更快。PiliPlus 必须处理这一非文本协议 12。

* **Schema 编译**：PiliPlus 项目中集成了 dm.proto 文件。这是 Bilibili 弹幕对象的结构定义。编译构建阶段，该文件会被转换为 Dart 代码（dm.pb.dart），生成 DmSegMobileReply 等强类型类。  
* **二进制流处理逻辑**：  
  1. **请求分片**：弹幕不是一次性加载的，而是分片（Segment）存在的（通常每 6 分钟一个分片）。PiliPlus 根据视频总时长计算需要请求的分片索引。  
  2. **解压缩**：API 返回的二进制流通常经过 Brotli 或 Gzip 压缩。PiliPlus 的网络层首先检测 Content-Encoding 头，调用相应的解压库还原原始二进制数据。  
  3. **反序列化**：调用 Protobuf 生成类的 fromBuffer 方法，将二进制数据解析为对象列表。  
  4. **渲染映射**：逻辑层将解析出的弹幕属性（如 mode 滚动模式, color 颜色, progress 时间轴）映射到 UI 层的渲染引擎中。由于弹幕量巨大，这一步通常涉及大量的性能优化逻辑，如对象池复用，以避免内存抖动。

## **6\. 视频流媒体播放核心逻辑**

播放是 PiliPlus 的核心功能。其背后涉及复杂的流地址获取、解码器选择及 DRM 处理。

### **6.1 PlayUrl 接口的深度交互**

视频播放的第一步是获取真实的流媒体地址。PiliPlus 调用 /x/player/playurl 接口，其参数配置逻辑极具策略性：

* **fnval 参数**：这是一个位掩码（Bitmask）。PiliPlus 默认将其设置为 4048（或更高），这个数值是 16 (DASH) | 64 (HDR) | 128 (4K) | ... 的组合。这意味着 PiliPlus 显式告知服务器：“我支持 DASH 协议，支持 4K，支持 HDR”。如果仅使用普通参数，服务器只会返回老旧的 FLV 或低清 MP4 地址。  
* **qn (Quality Number)**：PiliPlus 根据用户的设置（如“优先 1080P+”），动态传递对应的 qn 值（如 112 代表高清 1080P+）。

### **6.2 DASH 协议解析与音画同步**

Bilibili 的高画质内容主要通过 DASH (Dynamic Adaptive Streaming over HTTP) 协议分发。DASH 的特点是将视频流（Video）和音频流（Audio）分开传输。

* **解析逻辑**：API 返回的 JSON 中包含 dash 对象，下设 video 数组和 audio 数组。PiliPlus 的逻辑层会遍历 video 数组，根据 bandwidth（带宽）和 id（清晰度 ID）以及 codecs（编码格式）进行打分。  
* **编码选择（Codec Selection）**：Bilibili 提供 AVC (H.264), HEVC (H.265), AV1 等多种编码。PiliPlus 实现了智能选择逻辑：  
  * 如果设备支持硬解 HEVC（通过查询设备能力），优先选择 HEVC 流以节省流量。  
  * 如果用户开启了“AV1 优先”，则选择 AV1 流。  
* **轨道合并**：由于音画分离，PiliPlus 必须将选定的 Video URL 和 Audio URL 同时传递给底层的播放器内核。

### **6.3 播放器内核集成：Media Kit**

PiliPlus 使用 media\_kit 库，其底层绑定了 libmpv 1。

* **Headers 透传**：这是播放成功的关键。Bilibili 的 CDN 服务器会校验请求的 Referer 和 User-Agent。如果直接访问 URL 会返回 403 Forbidden。PiliPlus 的逻辑是将这些 Headers 封装在 Media 对象中，传递给 native 层的 mpv 实例，确保 mpv 在发起 HTTP 请求时携带了正确的防盗链签名 15。  
* **硬件加速**：PiliPlus 配置 mpv 开启 hwdec（硬件解码），利用 GPU 解码 4K 视频，降低 CPU 占用和发热。

## **7\. 业务功能模块的处理逻辑**

除了播放，PiliPlus 还复刻了大量社区功能，每一项都对应特定的 API 逻辑。

### **7.1 “三连”交互（点赞、投币、收藏）**

* **乐观 UI 更新（Optimistic UI）**：当用户点击点赞时，UI 立即变色，无需等待网络返回。  
* **防抖与队列**：为了防止用户快速点击导致 API 滥用，逻辑层实现了防抖（Debounce）。  
* **复合请求**：如果用户选择“一键三连”，PiliPlus 会并行发起 /x/web-interface/archive/like、/x/web-interface/coin/add 等请求。  
* **错误回滚**：如果 API 返回“币额不足”，逻辑层会捕获异常，并回滚 UI 状态，弹出错误提示。

### **7.2 评论系统与表情解析**

* **嵌套加载**：评论区采用懒加载逻辑。先请求 /x/v2/reply/main 获取主楼评论。当用户点击“查看回复”时，再请求 /x/v2/reply/reply 获取楼中楼数据。  
* **富文本解析**：评论内容包含 B 站特有的表情包代码（如 \[doge\]）和超链接。PiliPlus 实现了一个文本解析器（Parser），利用正则表达式匹配这些 Token，并替换为对应的图片组件（Image Widget）或可点击的 TextSpan。

### **7.3 搜索与热搜**

* **Web 接口调用**：由于搜索接口强制要求 Wbi 签名，PiliPlus 此处完全依赖 Web 协议栈。  
* **搜索建议**：监听输入框的变化，实时请求搜索建议接口，并展示下拉列表。

### **7.4 直播与 WebSocket**

PiliPlus 支持观看直播 1。

* **流获取**：调用 /xlive/web-room/v2/index/getRoomPlayInfo 获取直播流地址（通常是 FLV 或 HLS）。  
* **实时弹幕**：直播弹幕对实时性要求极高，通常不使用轮询，而是使用 WebSocket 长连接。PiliPlus 连接 Bilibili 的弹幕服务器（broadcastlv.chat.bilibili.com），并定时发送心跳包（Heartbeat）维持连接。接收到的二进制数据包同样经过 Protobuf 解码并在屏幕上飞过。

## **8\. 下载与离线缓存逻辑**

PiliPlus 区别于网页版的特性之一是支持离线下载 1。

* **分轨下载**：由于 DASH 协议音画分离，下载逻辑必须分别下载视频文件（.m4s）和音频文件（.m4s）。  
* **混流（Muxing）**：下载完成后，PiliPlus 并不像某些工具那样强制调用 ffmpeg 进行转码（这在移动端极其耗电）。相反，它可能在播放时动态加载两个本地文件，或者使用轻量级的混流逻辑。  
* **存储结构**：下载的内容被存储在应用沙盒目录中，并建立一个本地数据库（如使用 Hive）记录元数据（标题、封面、下载进度），以便在“离线中心”展示。

## **9\. 结论**

PiliPlus 对 Bilibili API 的处理逻辑展示了一个成熟的第三方客户端所具备的复杂性。它不仅仅是简单的 HTTP 请求发送者，更是一个集成了**加密计算**、**协议转换**、**状态管理**和**流媒体调度**的综合系统。

通过深度定制 Dio 网络栈，PiliPlus 成功抹平了 Bilibili 不同端（Web/App/TV）接口的差异；通过实现 Wbi 和 AppKey 双重签名机制，它有效规避了平台的风控拦截；通过集成 Protobuf 和 Media Kit，它实现了媲美原生的性能表现。尽管面临着 API 变动和法律风险的挑战，PiliPlus 依然通过开源社区的协作（如 bilibili-API-collect 的贡献），持续演进其处理逻辑，为用户提供了一个功能强大且纯净的 Bilibili 访问入口。

### **引用索引**

* 1 PiliPlus GitHub 仓库概览及技术栈  
* 1 PiliPlus 功能列表、平台适配及更新日志  
* 1 PiliPlus 与 PiliPala 的关系及致谢  
* 15 PiliPlus 项目详情及流媒体特性  
* 16 PiliPlus Release 资产及贡献者信息  
* 3 Dio 网络库特性描述  
* 8 Wbi 签名问题讨论  
* 1 PiliPlus 适配平台及致谢列表  
* 2 Flutter Gems 关于 Dio 的分类  
* 4 dio\_cookie\_manager 功能及用途  
* 6 关于 Geetest 及登录问题的讨论  
* 5 二维码登录逻辑参考 (Downkyi)  
* 10 Bilibili API 风控及 User-Agent 测试  
* 11 User-Agent 字符串配置  
* 14 PiliPala/PiliPlus 源码技术栈分析  
* 9 Wbi 签名算法详解  
* 7 API 签名与安全机制分析  
* 12 Bilibili 弹幕 Protobuf 结构分析  
* 13 dm.proto 字段定义及解析

#### **Works cited**

1. bggRGjQaUbCoE/PiliPlus \- GitHub, accessed on January 24, 2026, [https://github.com/bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)  
2. dio \- Dart and Flutter package in HTTP Client & Utilities category, accessed on January 24, 2026, [https://fluttergems.dev/packages/dio/](https://fluttergems.dev/packages/dio/)  
3. 探索Dart 项目 \- 无噪, accessed on January 24, 2026, [https://www.wuzao.com/projects/?language=Dart](https://www.wuzao.com/projects/?language=Dart)  
4. dio\_cookie\_manager \- Dart and Flutter package in HTTP Client ..., accessed on January 24, 2026, [https://fluttergems.dev/packages/dio\_cookie\_manager/](https://fluttergems.dev/packages/dio_cookie_manager/)  
5. bilibili free download \- SourceForge, accessed on January 24, 2026, [https://sourceforge.net/directory/?q=bilibili](https://sourceforge.net/directory/?q=bilibili)  
6. \_posts/2024-07-11-twif.md · typo · linsui / Website · GitLab, accessed on January 24, 2026, [https://gitlab.com/linsui/fdroid-website/-/blob/typo/\_posts/2024-07-11-twif.md](https://gitlab.com/linsui/fdroid-website/-/blob/typo/_posts/2024-07-11-twif.md)  
7. BBLL技术架构揭秘：Android TV客户端的实现原理 \- CSDN博客, accessed on January 24, 2026, [https://blog.csdn.net/gitblog\_01169/article/details/150700998](https://blog.csdn.net/gitblog_01169/article/details/150700998)  
8. \[Bug\] 个人主页无法显示信息· Issue \#274 · orz12/PiliPalaX \- GitHub, accessed on January 24, 2026, [https://github.com/orz12/PiliPalaX/issues/274](https://github.com/orz12/PiliPalaX/issues/274)  
9. 哔哩哔哩-API收集整理：API签名验证失败常见原因与解决方法原创, accessed on January 24, 2026, [https://blog.csdn.net/gitblog\_00639/article/details/152146960](https://blog.csdn.net/gitblog_00639/article/details/152146960)  
10. Bilibili 1024答题隐藏规则曝光，90%的人都答错了第3题 \- CSDN博客, accessed on January 24, 2026, [https://blog.csdn.net/IterStream/article/details/152222588](https://blog.csdn.net/IterStream/article/details/152222588)  
11. hugefiver/mystars \- GitHub, accessed on January 24, 2026, [https://github.com/hugefiver/mystars](https://github.com/hugefiver/mystars)  
12. B站直播弹幕协议详解：哔哩哔哩-API收集整理中的Protobuf消息结构, accessed on January 24, 2026, [https://blog.csdn.net/gitblog\_00597/article/details/152155400](https://blog.csdn.net/gitblog_00597/article/details/152155400)  
13. 哔哩哔哩-API收集整理：protobuf与JSON数据格式转换技巧-CSDN博客, accessed on January 24, 2026, [https://blog.csdn.net/gitblog\_00554/article/details/152148760](https://blog.csdn.net/gitblog_00554/article/details/152148760)  
14. B站用户狂喜！这个开源APP竟能自定义主题+去广告？PiliPala隐藏 ..., accessed on January 24, 2026, [https://developer.aliyun.com/article/1651602](https://developer.aliyun.com/article/1651602)  
15. PiliPlus download | SourceForge.net, accessed on January 24, 2026, [https://sourceforge.net/projects/piliplus.mirror/](https://sourceforge.net/projects/piliplus.mirror/)  
16. Releases · bggRGjQaUbCoE/PiliPlus \- GitHub, accessed on January 24, 2026, [https://github.com/bggRGjQaUbCoE/PiliPlus/releases](https://github.com/bggRGjQaUbCoE/PiliPlus/releases)