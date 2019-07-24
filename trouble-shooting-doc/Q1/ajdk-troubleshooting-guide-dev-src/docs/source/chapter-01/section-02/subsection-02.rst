GC暂停异常
----------

GC暂停时间异常也会影响Java应用的延迟。

产品线上一般推荐把GC日志打开，在GC日志如果发现YGC的usr/sys/real时间明显不合理，往往意味着问题。通常情况下，一次YGC的时间在几十毫秒到几百毫秒之间都可以被视为正常，但如果YGC的时间显示超过1秒，那么这就明显意味着系统出现了异常，需要排查人员的介入。

故障表现
^^^^^^^^

YGC时间变长的表现很容易通过日志监控发现:

- GC日志显示YGC usr/sys/real时间显示比较长, 通常都超过500毫秒

故障原因
^^^^^^^^

可能的原因如下：

- Young区晋升对象太多导致real/usr时间变长
- 磁盘IO影响YGC时间
- swap导致的YGC时间变长
- Weak/Soft/Phantom Reference变多导致GC Reference Processing变长
- G1 Update/Scan RS时间长
- ParNew/CMS Older-Gen scanning时间长
- JDK8相比JDK6实现上的改变导致YGC时间变长
- CMS Final Remark时间长
- 混合部署环境影响

故障排查
^^^^^^^^

Young区晋升对象太多
"""""""""""""""""""

大家知道，Java的GC算法有一个重要的假设，那就是分代假说，假设新分配的对象大部分会在较短的时间内变成垃圾，将会被回收。如果Young区由于种种原因，导致新分配的对象大部分都存活下来并最终晋升到Old区，这时候会造成YGC时间变长。因为Young区对象大量存活，这样在每次YGC时，必然会存在大量的Old到Young区的跨区引用，这使得YGC的CardTable扫描时间会比较长，另外大量存活对象在Survivor区之间来回拷贝也会增加YGC的时间。

要确认该故障，主要是看GC日志，如果发现YGC的时候会发生大量的晋升，并且survivor区存活的size也比较大, 就可以确认这个问题。

磁盘IO
""""""

由于jvm写GC日志的时间也会被统计进GC的时间，如果磁盘IO Load比较重，阻塞了GC日志的写入操作，也会造成GC时间长。在这种情况下，GC日志显示real时间长，但usr, sys时间短。下面是一个磁盘IO造成GC时间长的例子::

    2016-01-14T22:08:28.028+0000: 312052.604: [GC (Allocation Failure) 312064.042: [ParNew
    Desired survivor size 1998848 bytes, new threshold 15 (max 15)
    - age   1:    1678056 bytes,    1678056 total
      : 508096K->3782K(508096K), 0.0142796 secs] 1336653K->835675K(4190400K), 11.4521443 secs] [Times: user=0.18 sys=0.01, real=11.45 secs]
      2016-01-14T22:08:39.481+0000: 312064.058: Total time for which application threads were stopped: 11.4566012 seconds

可以看到虽然user和sys的时间很短，但整个GC暂停时间却是惊人的11.45秒。如果系统监控在这个时间点发现磁盘IO有异常的活动，就能够确认这个问题。

磁盘IO的问题有可能还会有另外一种表现，下面是另外一个gc日志的例子::

    2014-12-10T12:38:44.419+0000: 58758.830: [GC (Allocation Failure)[ParNew: 11868438K->103534K(13212096K), 0.7651580 secs] 12506389K->741669K(17406400K), 0.7652510 secs] [Times: user=0.36 sys=0.01, real=0.77 secs]

这里发现user和real的时间都比较高，而且real和user的比例只有2，而实际上JVM是运行在一个4核的机器上，因此看起来GC线程由于内核的原因发生了停顿。从设计上来讲，GC基本上全部是内存操作，但有一点例外，那就是GC会在执行过程中写一些统计数据到/tmp/hsperfdata下，这个文件写入过程是通过mmap的方式直接通过指针操作来完成的。如果磁盘IO异常，指针操作会引发停顿，从而引起GC Pause异常。mmap引发的停顿的一个表现就是user会比较高。

很多情况下，这种磁盘IO的异常是偶发性的，因此需要借助于一些历史数据的分析才能准确定位，一些常用的工具比如tsar，ioutil都可以发挥作用。

swap
""""

如果操作系统开启了swap，整个操作系统突然加载大文件消耗大量内存从而发生了swap，那么也会影响YGC时间。swap多个方面会影响YGC时间。

第一个方面是对应用本身的影响，swap会停顿应用本身的业务逻辑操作，使得Java线程很难及时走到safepoint, 影响YGC时间。

第二个方面是对hotspot VMThread进入safepoint的影响，time to safepoint的时间窗口內会有内存操作(serialize thread states page)，swap使得内存操作时间大大变长从而影响safepoint。

safepoint慢会导致GC日志YGC的user和sys时间远小于real时间，在通常情况下由于gc是多线程的，会有如下的关系:

``usr + sys = real * gc_thread_num``

如果safepoint慢，会使得 real >> usr， 如果在swap发生时观察到这个现象可以确认这个问题。

另外一个方面就是GC的影响，swap使得对象访问，对象图谱遍历都会被拖累。这种情况usr, real, sys都会很慢。

.. _ReferenceAnchor:

Weak/Soft/Phantom Reference
"""""""""""""""""""""""""""

应用程序中大量存在Weak/Soft/Phantom Reference是会影响YGC时间的，因为YGC会有一个阶段来花时间处理这些Reference。除了YGC时间，Referencece太多还会影响CMS和G1 Concurrent Cycle的Final Remark时间，G1 Concurrent Cycle/CMS的Final Remark会花时间处理在Concurrent阶段发现的Reference。在GC日志中如果发现G1的Ref Proc部分或者CMS的weak refs processing部分耗时比较严重，就可以判断是这个问题。通过一些额外的VM参数，比如PrintReferenceGC, TraceReferenceGC可以进一步确认这个问题。

.. note::
    
    Weak/Soft/Phantom Reference是一类很特殊的对象，当GC处理Weak Reference时，如果发现referent只能通过Weak Reachable的时候，会把refrent置成null，从而在下次GC时确保refrent会被丢弃。Soft Rerence和Weak Reference类似，唯一的区别在于Soft Rerence相对比较lazy，而Weak Refrence比较eager，也就是说Soft Refrence会在内存中多坚持一会儿，只有当Memory被耗尽的时候，Soft Reference才会被被处理。Phantom Reference也和Weak Reference有点类似，在eager程度上两者并没有什么区别，唯一的区别在于无论当referent被判定为Weak Reachable之前还是之后，Phantom都无法获取referent的值，从而避免了referent在用户层被泄漏，另外还有一点，在早期的JDK版本中，Phantom Refernce需要用户代码显式得调用clear()函数，这一点和Weak/Soft Reference会被自动清空referent不同，否则会有OOM的风险。

.. note::

    由于WeakReference(Weak/Soft/Phantom)的特殊语义，也会给GC带来额外的负担。以G1的WeakReference处理为例，reference的处理器有两种，一种是_ref_processor_stw，另外一种是_ref_precessor_cm。_ref_processor_stw在STW暂停(young/mixed/full gc)中使用，_ref_processor_cm是在Concurrent Cycle中使用。

    _ref_processor_stw的工作流程大概是这样的，在GC遍历Copy回收对象时，如果访问到一个新的没有被访问过的WeakReference对象，如果referent的可达性还没有办法决定时，就会把这个WeakRefernce对象放到一个discoverd list里。在Copy结束的之后，会回来额外遍历处理这个discovered list，遍历的过程当中，如果发现那么这个referent是strong reachable的，那么相应的WeakReference就会从discovered list中去掉，反之，则就会操作WeakReference将refenent成员置成null。经过上述阶段，discovered list就是最后真正的WeakReference list，如果用户在创建WeakReference时注册了queue，那么discovered list就会被push进相应的queue当中。

    _ref_processor_cm的工作流程和_ref_processor_stw类似, 区别在于_ref_processor_stw的发现功能是在STW GC一开始就被打开，而discovered list的处理是在STW GC Copy结束。而_ref_processor_cm的发现功能是在Initial Mark打开，而discovered list的处理是在Remark进行。因此_ref_processor_cm处理的是整个Concurrent Cycle里concurrent-mark发现的WeakReference，而_ref_processor_stw处理的是stw执行GC Copy的时候发现的WeakReference。

.. note::

   WeakReference的一个常见用法是用来跟踪临时对象，如果-XX:MaxTenuringThreshold设置的过小，jvm动态计算的晋升age阈值有可能算得比较小，这使得WeakReference会被提早被晋升，导致这部分WeakReference无法及时在YGC时被及早发现。这有可能会让应用开发人员赶到惊讶, 值得注意一下。

.. note::

    CMS的WeakReference处理和G1的_ref_processor_cm有些类似，但CMS的WeakReference只有一个_ref_processor,也是发现功能是在Initial Mark打开，而处理操作是在Remark。理论上CMS和G1的_ref_processor_cm的处理机制是类似的, G1的Concurrent Cycle会扫描和标记整个Heap，虽然Concurrent Cycle本质上不是GC，但在此期间发现的WeakReference也会做清理(把WeakReference.referent设置成NULL)，理论上只要CMS和G1 Concurrent Mark的频率保持一致，那么WeakReference的处理及时性和效率也是相当的。但在Elasticsearch和Hbase的实践中，发现G1对reference的处理不如CMS及时，具体的原因尚不明确, 具体可以参见这个 `Bug <https://bugs.openjdk.java.net/browse/JDK-8182982>`_ 。

G1 Update/Scan RS时间长
"""""""""""""""""""""""

G1为每个HeapRegion都维护了一个RemSet的数据结构，这个数据结构记录了别的Region指向当前Region的一块Card内存的地址(512字节大小)，这样这块HeapRegion在GC的时候就能很快确认哪几块内存有指向自己的引用，这样避免了整个Heap的扫描，这个设计对提高GC效率非常有帮助。当Mutator线程更新或写入新的对象成员时，在Post Write Barrier里记录了dirty的Card Pointer。然后VM以一种并发的方式来把这些dirty card pointer更新到各个HeapRegion的RemSet里，这个过程称之为Concurrent Refine。当GC开始时候，这些Dirt Card Pointer有可能只处理了大部分，仍有一小部分尚未处理完全，因此会需要进行Update RS来处理这一小部分dirty card pointers。这部分时间就是Update RS时间的来源。

当HeapRegion进行GC的时候，由于对象进行了移动，因此需要Scan RemSet来更新那些指向这些被移动对象的成员，同时RemSet里的对象也会作为GC Root的一部分来进行扫描，这部分就被统计为Scan RS时间。

如果发现G1的GC日志显示这两部分时间长，则可以确认这个问题。

ParNew/CMS Older-Gen scanning时间长
"""""""""""""""""""""""""""""""""""

大家可能都已经发现了，G1的日志相对而言是比较详细的，能够打印出GC各个步骤的详细信息。而ParNew/CMS相对而言则比较简陋，无法打印出GC的各个子步骤的统计。幸运的是，在AJDK里，有一个特有的参数-XX:+PrintGCRootsTraceTime, 用来统计ParNew/CMS GC的子步骤详细耗时时间。其中主要包括各个Roots Processing以及内存Copy的时间，其中一个重要的指标是older-gen scanning时间，这个指标有点类似G1的Scan RS，也是利用Card的数据结构来标记Old到Young的引用，通过Card的扫描可以确定ParNew GC时的GC Roots，以及在GC Move以后更新相应的指向被移动对象的指针。

GC日志中显示这部分时间长，就需要引起注意。

JDK8相比JDK6实现上的改变导致ParNew时间变长
""""""""""""""""""""""""""""""""""""""""""

在JDK升级的过程当中有时候会发现这样的问题，比如从JDK6升级到JDK8，都采用CMS和相同的硬件配置，业务压力不变，但发现ParNew YGC的时间与JDK6相比明显增长，虽然没有超过1秒，但原本在JDK6中的一次10ms的暂停在JDK8中可能变成了40ms，但JDK8的gc间隔却明显变长了，比如这个 `Issue 
<https://aone.alibaba-inc.com/issue/12732290?spm=a2o8d.corp_prod_issue_list.0.0.6c7d6a85Wr2hXe>`__ 。

这种情况在社区中也有 `讨论
<http://mail.openjdk.java.net/pipermail/hotspot-gc-use/2015-February/002124.html>`__ ，但暂时没有明确的结论，由于6到8代码的实现变动很大，很难最终到底是那个版本的改动导致了这个变化。虽然JDK8的gc时间变长，但gc间隔的优化从一定程度上补偿了应用的性能。因此这可以看作是JDK实现的一种权衡，不应被视为故障。

CMS Final Remark时间长
""""""""""""""""""""""

如果发现GC日志的Remark阶段时间比较长，占比比较大, 就可以确认这个问题。CMS Final Remark时间长的表现通常有这么几种:

- Paralle Rescan时间长。由于CMS的concurrent阶段被标记了的对象有可能发生了变化，这个阶段需要重新扫描Young区到Old区的引用，如果Young区对象太多，可能会导致这部分时间变长。-XX:+CMSScavengeBeforeRemark打开会在CMS Final Remark前尝试一次YGC，可以缓解这种情况。
- WeakReference Processing时间长。Final Remark会清理在Concurrent阶段发现的WeakReference，如果这个数量太多，也会影响处理时间。排查人员也可参考 :ref:`这里<ReferenceAnchor>` 的相关内容。
- Class Unloading时间长。在Final Remark阶段会进行Class Unloading的操作，这部分时间长的原因有很多种。一种是卸载的Class太多，通过-XX:+TraceClassUnloading可以在标准输出日志里看一下哪些Class被频繁卸载可以尝试一下代码优化。另外一种是卸载的Class很少，但时间也很长。这种情况一般就是ClassLoader太多或者Codecache扫描时间比较长。

.. note::

    Class被unload的条件其实很苛刻的，它只有在对应的class_loader不是活着的时候，才会被卸载掉。也就是说，某一个class是和同一个class_loader下的所有class一起被卸载掉的，一个class_loader下的所有class是同时被卸载的。要达到这种条件，这个class_loader加载的所有class都不能有实例，而且heap中不能存在任何活着的对象有引用指向这个class_loader。另外一点,class unload的操作某种意义上是相对比较“低效”的，它会遍历所有的class_loader，然后通过is_alive_closure判断class_loader是否是垃圾，如果是垃圾才会真正标记所对应的所有classes数据可以被清理。
    
    上面的动作只是标记class可以被清理，真正清理metaspace的时候还需要进一步扫描stack和codecache来最终决定metadata是否可以删除。
    
    因此class unloading时间长有两个可能的原因，一个是ClassLoader的数量太多，class_loader链表的遍历就会花较长时间，这个阶段无论是CMS还是G1都是串行的，除了优化逻辑没有什么好的办法。另外一个就是metaspace清理的时候对stack和codecache的扫描由于codecache太大导致时间长, 这一部分逻辑上不好控制，但G1相比CMS做了一个优化，就是把codecache的扫描做成了并行，因此G1 Remark中的class unload时间是要优于CMS的。这就解释了为什么有时候真正卸载的class很少，但Class Unload花费的时间却比较长。有兴趣的同学可以参考ClassLoaderDataGraph::clean_metaspaces()和ClassLoaderDataGraph::do_unloading()的Hotspot相关实现源码。


.. note::

    不像CMS，G1 Log默认通常不会打印Concurrent Cycle里Remark各个阶段比如Class Unload的耗时，用户可以通过-XX:G1LogLevel=finest来控制输出相关信息。

.. note::
   
    CMS Final Remark是为了最后确定并标记Old区的活对象，这个阶段是Stop-The-World的。CMS在之前都是Conccurrent阶段，在Conccurrent阶段Mutator还在不断更新和操作young区，此时ConccurrentMark标记的活对象会漏掉一些活着的对象（打个比方，你有10间屋子，你从第1间到第10间一间间看过去找你的眼镜，希望看完这10间房子就能找到你忘记放在哪一间的眼镜，如果你有一个调皮的孩子，偷偷把在你找的过程中，把眼镜从原来的地方偷偷把它放回你已经找过的房间，那么你将永远找不到眼镜），因此在Final Remark阶段需要通过DirtyCard扫描Conccurrent阶段Old区中这些变化的对象以及成员指针来避免某些被遗漏的对象，另外Young区和所有线程的Stacks也会重新扫描一边避免遗漏。Young区如果对象很多，那么这个阶段对Young区的扫描就可能比较长。

.. note::

    Class Unloading有一类特殊的场景，就是卸载Lambda Class。Lambda Class是一种非常特殊的Class。举个例子，比如某个Java类A，在A的main方法里定义了一个lambda表达式，javac会根据表达式的body来决定是否需要多生成相应的方法，如果是lambda表达式捕获的变量不涉及任何enclosing instance（外部类对象）的任何成员，javac会把lambda表达式body desuger成一个static lambda method，如果捕获变量涉及了enclosing instances的任何实例成员，javac会把lambda body 脱糖成一个instance lambda method，有的情况下比如类似这样的list.filter(String::isEmpty)没有参数列表的lambda表达式，javac不会为A增加任何方法(JVM会直接通过invokeVirutal的MethodHandle的方式来进行调用)。

    Lambda表达式又可以分为stateless和stateful两种，stateless的lambda表达式不会捕获任何上下文中的任何局部mutable变量，而stateful的lambda表达式则会捕捉上下文中的mutable变量。
    
    在Lambda表达式出现的地方，javac会编译生成相应的invokedynamic指令，这些指令的解析过程的bootstrap方法通常是LambdaMetafactory.metafactory, metfactory会把运行时的目标方法委托给一个动态类的构造函数。因此当Lambda表达式的invokedymaic执行完成以后通常会在栈上生成一个动态类的实例。 

    Lambda表达式的真正执行实际上是通过对动态类实例的invokeinterface之类的来执行的。动态类的生成是在前面bootstrap方法中进行的，bootstrap方法在生成CallSite实例的时候会利用Unsafe.defineAnonymousClass动态生成Class，动态class的字节码中会包括对"脱糖"方法的调用, 类型是一个Lambda表达式返回类型。

    在JVM中，anonymous class是生成是比较特殊的，每个anonoymous class都会生成一个独立的ClassLoaderData, 而且生成的klass数据不会被SystemDictionary和host class loader感知, ClassLoaderData中会有一个标志来标记是否是匿名的。在Lambda表达式的场景中，  每个invokedynamic都会生成一个对应的匿名动态类。如果Lambda表达式使用得比较多，那么匿名类ClassLoader的数量也会比较多，在卸载的时候遍历ClassLoaderData花费的时间也会比较长。

    另外，匿名类可能对性能会有一定的影响，因为匿名类的class_loader_data只有一个类，而且不和host class loader相关，被卸载的概率是比一般的内部类要大的，可能会导致Class需要重新加载。


.. note::
    
    invokedynamic为JVM提供了动态语言的扩展能力。以invokevirtual为例，invokevirtual是用来处理多态的，它的参数会根据类型指定某个方法vtable slot，当运行时真正执行的时候，它会根据具体的调用实例以及vtable slot偏移量来读取对应的具体方法到底是哪个，然后执行。在上述过程中，关键的部分就是方法的“决定”或者“分发”。这个决定／分发过程是JVM运行时根据调用实例的具体数据决定的，不受用户控制。而invovekdyamic本质上开了一个口子，让用户的java代码可以自由“决定”该使用哪个方法(MethodHandle)。

    invokedynamic指令执行的时候，第一次调用会陷入解析过程，解析会进一步解析指令相对的CONSTANT_Dynamic_info以及相关的常量池并调用Bootstrap方法来决定即将运行的目标方法MethodHandle，Bootstrap方法的调用过程实际上是解析的一部分, Bootstrap方法的实际调用参数是由JVM在常量池解析过程中准备的。一旦解析成功，后面就会使用活在栈上的参数来执行MethodHanlde从而完成整个invokedynamic指令。解析的过程会返回一个CallSite实例，然后被永久绑定在这个dynamic call site（动态调用点）。当多个线程同时第一次执行invokedynamic时，有可能会平行得有多个CallSite实例被解析生成，但JVM只会选择一个进行绑定，其它就会被忽略。后续程序再第二次执行到同样的dnvokedynamic指令时，调用点的状态已经由unlaced变成laced，JVM就不会再重新执行bootstrap的解析过程了, JVM会根据已经缓存的CallSite实例直接获取真正的目标方法并使用栈上的变量加以调用。invokedynamic的解析过程有点类似class的加载，当程序执行到某一个字节码指令时，如果解析常量池的class发现并没有加载，就会启动class的解析加载过程，加载完成后才开始执行真正的字节码指令。从工作原理上invokedymaic和class延迟加载两者有类似之处。
     

混合部署环境影响
""""""""""""""""
很多Java应用都是部署在docker容器上, 是和别的应用混合部署在同一台物理机上的。各个应用是有可能相互影响的。比如不同的应用绑定在相同的CPU上，导致某个应用的CPU被别的繁忙的应用偷走，这会使得应用GC暂停的sys时间比较高。用户可以检查物理宿主机的相关配置和指标加以确定, 比如IO使用情况，free memory，CPU使用情况，sys时间占比, Cgroup CPU绑定情况等等。

故障解决
^^^^^^^^

Young区晋升对象太多造成YGC时间太长的故障主要办法是想办法减少晋升数量。业务代码的逻辑缺陷是造成大量晋升的常见原因，比如应用没有及时释放对象等。要解决这个问题，用户需要通过review代码的方式找出晋升对象是从哪里分配出来并加以解决。遗憾的是暂时没有一个好用的工具能搞帮助用户找出这些晋升的对象是哪些以及从哪里分配上来。AJDK会在新的JET(Java Event Trace)框架中增加对晋升对象的Trace功能，用户可以通过zprofiler找出哪些晋升的对象以及分配地点。另外如果用户错误配置了-XX:MaxTenuringThreshold或者Young区分配的太小，也会造成大量晋升，需要适当调大Young区和提高阈值。Young区的大小设置需要根据GC情况通过公式设定更加合理的值。参见 :ref:`这里<GCParamAnchor>`.

磁盘IO和操作系统swap造成的YGC时间长，需要操作系统层面来加以解决。

Weak/Soft/Phantom Reference时间长有一个缓解方法就是启用-XX:+ParallelRefProcEnabled，从而启用多线程加快Reference的处理。但这可能是一个治标不治本的方法，最根本的原因是要找出为什么会有这么多的Weak/Soft/Phatom Reference，这些Refrence是从哪里分配的？在设计上是否存在优化的可能。


G1 Update/Scan RS时间长的问题可以尝试这么几个方法。

对于Update RS， 首先可以考虑的是调整-XX:G1HeapRegionSize大小，如果HeapRegion Size越大，那么理论上HeapRegion的跨区引用就会变少，因此更新RemSet的工作量也变小了，从而缩短了Update RS时间，但从另外一方面来看，这么做对会使得拷贝和晋升的工作量变大了，因此这个做法需要综合考量。

其次考虑-XX:G1RSetUpdatingPauseTimePercent和-XX:G1ConcRefinementThreads参数的调整, G1RSetUpdatingPauseTimePercent控制Update RS在GC整个STW时间的百分比，G1ConcRefinementThread控制Concurrent Refine的线程数，这么做的目的都是尽可能让Update RS工作尽量Concurrent得完成，使得GC时Update RS的工作量减小从而缩短时间。

最后考虑-XX:-ReduceInitialCardMarks参数来关闭大对象Concurrent RS Update优化，VM当处理大对象时，会尝试把多次Dirty Card Pointer的分别处理合并成一个批处理，这个优化也有可能在某些场景下对GC的Update RS造成压力，可以考虑关闭。


对Scan RS，可以考虑-XX:G1RSetRegionEntries加以改善。由于G1的每个HeapRegion维护一个RemSet列表，这个内存实际上在VM看来是额外的开销，为了控制这个开销，VM引入了一个叫做remembered set coarsening的优化。这个优化是当VM发现某个HeapRegion的RemSet List太长的时候，会进行一个压缩来节省空间，这个压缩会导致每一个RemSet指针对应的Region变大，这样GC时Scan RS扫描的空间就变大了，那么时间也变长了。通过-XX:G1SummarizeRSetStatsPeriod在GC日志中可以确认这种状况。解决方法就是在内存有余量的情况下增加-XX:G1RSetRegionEntries避免remembered set coarsening。

如果上述手段都不能改善，那么就需要优化程序本身了, 减少跨区引用。AJDK新开发的JET功能可以分析跨区引用，可以帮助分析此类问题。

ParNew／CMS Older-Gen scaning时间长的解决思路和G1 Scan RS的解决思路类似，由于没有特殊参数可以控制行为，优化代码往往成了唯一选择。

CMS Final Remark时间长，要具体问题具体分析，如果是Parallel Rescan长，可以添加-XX:+CMSScavengeBeforeRemark，这样在CMS Final Remark之前会触发一次Young GC，保证young区的size尽可能小，从而减少CMS Final Remark时间。顺便提一句，-XX:+ScavengeBeforeFullGC作为一个可以类比的参数，也应该被加上，这个参数可以在FullGC之前先做一遍Young GC，从而减少单线程FullGC的开销，加快Full GC的速度。如果是Class Unload时间长，可以考虑换G1，另外有可能需要优化下代码，减小class loader的数量，如果WeakReference的时间长，可以参考前面Weak/Soft/Phantom Reference时间长的处理办法。

混合部署环境对GC暂停时间造成的影响需要从OS和容器调度的层面加以解决。
