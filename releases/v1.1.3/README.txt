墨鱼阅读 v1.1.3 (Android)

文件: moyu-reader-1.1.3.apk
SHA256: 106D678F1B9DA3D098FA8660675B2DDA02461B1BB4470A8BBC9EEECD574B3BA7
大小: 2.36 MB

  包名: com.moyu.reader   versionCode: 14   versionName: 1.1.3
  应用名: 墨鱼阅读        权限: 无

本版重点：滚动为什么滚不动 —— 找到真正的根因（有 Compose 源码依据 + 自动化测试证明）

【根因】手势层是"盖在正文之上的兄弟节点"，它把命中测试截断了

  Compose 的命中测试在碰到一个指针输入节点、而它没有声明「与兄弟共享」时，
  会**直接截断**，画在它下面的兄弟节点根本不进入命中路径。源码依据
  （NodeCoordinator.PointerInputSource.shareWithSiblings）：

      if (child.outerCoordinator.shouldSharePointerInputWithSiblings()) {
          hitTestResult.acceptHits(); return true    // 继续命中下面的兄弟
      }
      return false                                   // 就此打住

  而 Modifier.pointerInput 建出来的节点，sharePointerInputWithSiblings 默认是 false。

  之前的布局是：

      Box {
          Column(verticalScroll)              <- 收不到任何事件，因为它在下面
          Box(fillMaxSize().pointerInput{})   <- 全屏手势层，把命中截断了
      }

  所以「只要不消费事件，滚动就能继续收到拖动」这个想法从根上就是错的：
  **事件压根没送到滚动层**。这也解释了为什么前两轮把正文从
  SelectionContainer 换成 BasicTextField、再换成普通 Text，全都没用 ——
  问题不在正文，而在它上面的那一层。顺带也解释了更早的「长按毫无反应」。

  现在：手势层挂在**根节点自己**身上（父），子树（滚动容器、按钮、面板）
  都是它的孩子。父子之间不会互相截断，且 Main 阶段是冒泡的 ——
  子节点先处理；子节点消费过的手势（滚动、点到按钮、拖进度条），
  父层就不再重复处理。

【自动化证明】新增 ReaderPageGesturesTest（5 条，JVM 上真实注入触摸事件）

  - 父节点上的手势层不会挡住子树的滚动  → 向上滑动后 scrollState.value > 0
  - 盖在正文之上的兄弟手势层会让滚动彻底失效 → scrollState.value 必须为 0
    （这条专门钉住框架规则：要是哪天它开始失败，才说明可以改回叠加写法）
  - 子树没人消费时点击仍然会被手势层收到
  - 子树消费过的手势不会再被当成点击（点按钮不会顺带翻页）
  - 横向滑动会触发热区回调

  为此把测试依赖接到了 JVM 侧（ui-test-junit4 + Robolectric），
  「手势到底有没有生效」从此不用靠肉眼在真机上试。

【你问的第二件事】滚动模式下底部为什么没有电池/页码

  因为地脚（页码 / 时间 / 电量）原先只画在分页视图里，滚动模式压根没有这一段。
  现在滚动模式也是两段式：滚动区 weight(1f) + 底部地脚，页码按滚动位置折算。
  电量广播的注册也提到了阅读页最外层，两种模式共用同一个电量值
  （之前它写在分页视图内部，滚动模式即使有地脚也拿不到电量）。

累计单测：17 个文件 144 条用例，0 失败，零编译警告。

仍未做（如实列出）
  - PDF 打开后不能翻页阅读（只建书目记录，没接 PdfRenderer）
  - EPUB 目录不按规范读（不解析 NCX / nav.xhtml）
  - 备份导出 / 导入没有实现
  - 「朗读引擎」只是文案，朗读进行中改语速/音调不生效
  - 长按选词已按你的要求删除，查词与划线暂时没有入口
