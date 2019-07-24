GC触发异常 
----------

由于JVM在GC时会Stop-the-Word，因此GC的触发频率越少越好。如果通过GC日志观察到GC日志频繁或者触发时机很可疑，往往意味着故障。

另外堆外内存泄漏和堆内内存泄漏的相关故障也和GC触发异常有关，有一些内存泄漏的问题实际上的表现也会造成GC频发触发，排查时需要关联排查，请阅读相关章节。


故障表现
^^^^^^^^

通过GC日志发现GC频繁。对于GC的，一般有一个经验，如果GC吞吐量低于90%，往往是GC异常的故障信号。

``GC吞吐量 = 1 - GC暂停时间 / 应用运行总时间``

从绝对值上来讲，100ms左右的YGC暂停时间是一个非常常见的暂停时间。如果应用的响应时间要求比较高，50ms可以作为一个通用的优化目标。20ms一般是优化的极限。

对于Old区的GC，比如CMS GC, 在应用平稳运行状态，GC的频率通常取决于应用。一般来说，数个小时才会CMS GC一次是一个非常常见的频率，如果在相当长的一段时间内，CMS GC几分钟就会被触发一次，开发人员就可以关注下GC的表现，这有可能是CMS GC异常的一个信号。

目前有很多应用使用CMS进行垃圾回收,UseCMSInitiatingOccupancyOnly和CMSInitiatingOccupancyFraction用于指定CMS GC何时触发(当old区的使用占比超过CMSInitiatingOccupancyFraction时,就会触发一次CMS GC)。但是有时候观察日志发现old区的使用量占比并未达到CMSInitiatingOccupancyFraction时,也会触发频繁CMS GC。这往往是由于Metaspace碎片化或者CMS碎片化造成的。

Full GC的触发比较频繁也是常见的一类问题, 在GC日志中很容易确认。大家知道Full GC是一种针对全堆的Stop-The-Word的GC，实际上是JVM的GC无法跟上业务节奏的一种极端手段，因此Full GC需要被尽可能避免。需要注意的是，在阿里巴巴的很多监控系统中，CMS GC也被当作Full GC，需要加以区别。Full GC的频率根据应用的不同而有所区别，有的一天一次，有的一周一次，需要结合应用的具体情况加以判断是否频率异常。

故障原因
^^^^^^^^

常见原因如下：

- Metaspace/Perm使用过高
- Metaspace碎片化
- GC参数设置不合理
- CMS内存碎片化导致Full GC
- 堆外内存增涨异常
- 堆内内存增涨异常
- 业务逻辑错误调用System.gc
- 用户通过jmap等工具触发的Full GC
- 大尺寸的ArrayList的扩容行为导致的不必要Full GC
- 业务压力比较大导致频繁YGC
- javaagent导致的异常GC


故障排查
^^^^^^^^

.. index:: Metaspace

Metaspace/Perm使用量过高
""""""""""""""""""""""""
如果开启了-verbose:gc参数,gc日志中会打印CMSCollector: collect for metadata allocation 或者 CMS perm gen initiated。另外标准日志中出现java.lang.OutOfMemoryError: Metaspace也是Metaspace使用过高的信号。排查人员可以使用jstat以及gcutil观察Metaspace的使用情况来确认故障。Metaspace/Perm使用过高的排查可以参考堆外内存异常增涨的相应章节进行排查。 

.. index:: Metaspace

Metaspace碎片化
"""""""""""""""
当发现GC的触发是由Metaspace引起的，但通过gcutil观察到Metaspace的使用占比并不是很高，这种现象就意味这Metaspace碎片化。这个是OpenJDK一个已知的bug，JDK11已经进行了修复，参加 `Bug
<https://bugs.openjdk.java.net/browse/JDK-8198423>`__ 。请参考堆外内存泄漏的Metspace泄漏部分进行排查。

.. _GCParamAnchor:

GC参数设置不合理
""""""""""""""""
GC触发不正常通常和GC参数的设置密切相关。通常情况下，GC的Xmx，Xms的设置一般有如下的经验公式:

::

    Java Heap大小(-Xmx,-Xms) = 3x 或者 4x Full GC之后的Old区的Live Size
    Metaspace大小(-XX:MaxMetaspaceSize) = 1.2x 或者 1.5x Full GC之后的Metaspace区的Live Size
    Young区大小(-Xmn) =  1x 或者 1.5x Full GC之后的Old区的Live Size
    Old区大小 = 2x 或者 3x Full GC之后的Old区的Live Size

这个GC经验公式通常可以作为一个GC调优的参考，当然对于很多场景，这个经验公式并不能覆盖完全，需要具体情况具体分析。

.. index:: YGC, Young GC, Minor Collection, Minor GC

.. note::

    YGC触发的原因很多，对于ParNew/CMS组合，YGC的通常原因是Allocation Failure, 这种情况下Young区无法分配新的对象，需要触发YGC进行垃圾回收。另外，如果打开-XX:CMSScavengeBeforeRemark控制选项，在CMS的final-remark阶段也会触发一次ParNew来进行YGC回收。除了这两种比较常见的原因，还有一些其他的原因，比如在Debug版本的JDK中使用了-XX:+ScavengeALot，会定期触发YGC来进行测试。当JNI代码运行到Critical-Region的时候，所有的YGC会被Pending，当JNI代码退出Critical-Region的时候，垃圾回收器会补一个YGC。另外，如果用户使用了WhiteBox API，也可以在程序中通过API动态触发YGC。
    
    对于G1, 在Young区无法再新分配对象的时触发YGC，另外Conccurent Cycle的intial-mark阶段本质上也是一次YGC, 此外G1在处理Humongous Object分配的时候也有可能会触发YGC。前面列举的Critical-Region和WhiteBox API触发YGC的场景对G1同样有效。G1如果没有限定Young区的大小，YGC会充分尊重MaxGCPauseMillis，它会根据历史GC暂停时间数据来动态得决定下一次Young区的大小从而来控制暂停时间, GC会尽可能得选择更大的Young区，但前提是这些Young区估算出来的回收暂停时间不能超过MaxGCPauseMillis，当然Young区动态调整必须在G1NewSizePercent和G1MaxNewSizePercent约束的范围之内。因此通常来讲，如果历史数据估算得比较准的话，G1的YGC Paustime相对会比较平稳，暂停时间会在MaxGCPauseMillis左右波动。

.. index:: Humongous

.. note::

    G1中把对象尺寸大于HeapRegionSize 50%的对象称之为Humongous Object，由于这部分对象尺寸比较大，在Young区和Old区之间来回拷贝的代价太大，G1把Homongous Object直接分配在了老区。Humongous Object分配的时候，首先会评估如果满足要求的分配尺寸，现有的Heap占用尺寸是否达到IHOP的阈值，如果达到了IHOP的阈值，会触发一次的带有"concurrent humongous allocation"字段的Concurrent Cycle, 这么做的主要原因有两个：一是G1担心Humongous Object消耗内存太快, 每次分配的时候做这么一次检查评估可以尽早触发Conccurent Cycle为后续的Mixed GC回收老区做准备，另外的原因就是Conccurent Cycle的第一个阶段Intial Mark本质上是一次YGC，YGC也会对Humongous Object进行回收(-XX:G1EagerReclaimHumongousObjects在AJDK8是默认打开的)，尽早回收Humongous Object可以避免后面Humongous Object的分配失败。在评估执行完成后，接下来分配函数会乐观的对Humongous Object进行一次投机分配尝试，由于第一步的Concurrent Cycle是一次并发的过程，这次投机分配有可能失败，一旦分配失败, 就会进行一次Homongous Allocation触发YGC，然后继续重复尝试Humongous Object分配直至成功。感兴趣的读者可以仔细研究下Hotspot源码中G1CollectedHeap::attempt_allocation_humongous相关的函数部分。

.. index:: Old GC, Major Collection, Major GC, Mix GC

.. note::

    Old区的GC触发原因很多，以CMS为例，作为一种Old区的GC，CMS通常是由于CMSInitingOccupancyFraction的阈值被达到被触发，另外Metaspace内存不足也会触发CMS， 而且还有一种情况常常被忽略，那就是ConcurrentMarkSweepThread后台判断old区不能满足下次晋升的需求时也会触发Old GC。如果old区的可用大小同时小于历史平均晋升大小或者当前young区的使用大小,则认为不能满足下次晋升的需求,如果开启了-verbose:gc,gc日志中会打印CMSCollector: collect because incremental collection will fail。可以通过检查gc日志评估是否是由于该情况。值得注意的是，这种情况需要和YGC晋升担保失败加以区分，这两种情况都涉及到检查晋升是否可能满足，因此有点类似，但是触发的场景不同，YGC一开始检查晋升担保，一旦失败，会触发Full GC，而ConcurrentMarkSweepThread做晋升担保检查，是会触发CMS GC。这两种场景相互补充，CMS GC通过晋升担保检查来尽早触发CMS GC，从而尽可能避免YGC晋升担保失败导致的Full GC。读者可能好奇，为什么YGC一开始晋升担保失败选择的是Full GC不触发CMS GC呢？这是因为由于Hotspot实现的原因，YGC已经开始，这时候晋升担保失败的化，Young区处于一种不一致的状态，无法触发并发的CMS GC，但触发一个stop-the-world的Full GC确是可行的。

    G1的Old区回收是通过Mixed GC来进行的，当YGC结束后会检查Heap的使用大小，如果达到了IHOP的阈值，就会触发一次Concurrent Cycle，Concurrent Cycle中的Initial Mark阶段本质是一次YGC。在Concurrent Cycle里G1会并发得扫描整个堆，计算各个Region里对象的存活比率。当Concurrent Cycle结束的时候，通常接下来会是一次YGC，然后就会进行Mixed GC。Mixed GC的工作方式本质上和YGC没有区别，主要的区别在于它清理的区域除了Young Region还包括一部分Old Region。Mixed GC回收先检查可回收的垃圾是否大于G1HeapWastePercent限定的阈值比例，只有高于这个阈值比率才会触发回收，因为GC是很昂贵的，如果回收效率不佳，垃圾很少，G1是没有必要触发GC。Mixed GC优先选择存活比率比较小的Old区域，这样可以尽可能多得回收垃圾。如果某个Region里存活比率很高，大于G1MixedGCLiveThresholdPercent的阈值，那么这个Region回收起来没什么价值，就会忽略这个Region。Mixed GC也会充分尊重MaxGCPauseMillis，它会尽可能多的选择Old Region，但前提是这些Old Region和Young Region的预估回收时间加起来不超过MaxGCPauseMillis。如果MaxGCPauseMillis比较小，Mixed GC很难满足要求，它也会选择一个最小个数的Old Region List来进行回收，这个最小队列的长度由G1MixedGCCountTarget(默认为8)来控制，用所有Old Region候选者列表的长度除以G1MixedGCCountTarget得到数字就是最小回收长度，根绝这个长度并按照存活率由高到低对所有Old Region候选者进行选择，就得到当前Mixed GC的Old区回收列表并进行拷贝回收。本质上， G1MixedGCCountTarget表达的意思是Old区的回收虽然允许通过多次Mixed GC进行，但次数不能太多，不得超过G1MixedGCCountTarget约定的次数。下面是一条Mixed GC在G1Ergonomics Level打印出来的GC日志的例子。

    ::

        8822.704: [G1Ergonomics (Mixed GCs) continue mixed GCs, reason: candidate old regions available, candidate old regions: 444 regions, reclaimable: 4482864320 bytes (16.06 %), threshold: 10.00 %]

.. note::

    Concurrent Cycle结束之后按常理设想应该会进行Mixed GC，但G1选择先进行一次YGC，然后才是Mixed GC, 如果选择打开JVM参数-XX:+PrintAdaptiveSizePolicy，你会在GC日志中看到在Concurrent Cycle之后，会取消一次Mixed GC，原因是因为"do not start mixed GCs, reason: concurrent cycle is about to start", 至于为什么G1选择取消前一次Mixed GC的深层次原因不得而知，感兴趣的读者可以研究下G1CollectorPolicy::record_collection_pause_end方法。

.. index:: Full GC

.. note::

    Full GC的触发比较复杂。

    在CMS GC中，Full GC的直接诱因就是YGC晋升失败。YGC在每次GC前会根据一定的算法来预估晋升是否成功，这被称之为晋升担保，如果晋升担保成功，那么YGC就会进行，否则YGC就陷入尝试失败的状态。如果YGC尝试失败，此时由于内存处于一种不一致的状态，它会通知CMS GC进行回收，CMS GC此时会执行一个StopTheWorld的压缩GC(这个压缩的GC有两种选择，一种是用Serial GC来做Full GC，另外一种是CMS自己的STW只回收老区的foreground collector，具体的选择需要根据参数UseCMSCompactAtFullCollection和CMSFullGCsBeforeCompaction来决定，默认情况下会选择Serial Full GC)。当YGC尝试失败时，如果碰巧处于一个CMS Concurrent阶段，Concurrent阶段会被打断，此时GC日志则会在ParNew的GC日志中间插入一段包含concurrent mode failure字样的CMS相关记录。需要注意的是，当concurrent mode failure发生时，一般情况下一定意味着Full GC(foreground collector在默认参数下不会被启用)，尽管这次gc并不一定标记了Full GC。如果YGC尝试失败时，并没有处于一个CMS Concurrent阶段，那么gc日志就会简单得报一个Full GC, 并没有concurrent mode failure之类的字样。

    即使晋升担保成功，YGC仍然有可能尝试失败，这是因为晋升的预估毕竟只是根据历史数据的估算，如果应用的行为发生了剧烈变化，这种预测就不准了。此时YGC尝试失败的处理流程和前面晋升担保失败的处理流程是一致的。

    下面的代码显示的是晋升担保失败的逻辑，从下面的代码中可以看出，晋升担保成功的条件有两个：要么老区的可用空间大于历史上平均的晋升size，要么老区的可用空间大于新生代目前的size。

    .. code-block:: cpp

        299 bool TenuredGeneration::promotion_attempt_is_safe(size_t max_promotion_in_bytes) const {
        300   size_t available = max_contiguous_available();
        301   size_t av_promo  = (size_t)gc_stats()->avg_promoted()->padded_average();
        302   bool   res = (available >= av_promo) || (available >= max_promotion_in_bytes);
        303   if (PrintGC && Verbose) {
        304     gclog_or_tty->print_cr(
        305       "Tenured: promo attempt is%s safe: available("SIZE_FORMAT") %s av_promo("SIZE_FORMAT"),"
        306       "max_promo("SIZE_FORMAT")",
        307       res? "":" not", available, res? ">=":"<",
        308       av_promo, max_promotion_in_bytes);
        309   }
        310   return res;
        311 }


    YGC尝试失败的原因有很多，最常见的一个是CMS GC触发的太晚，CMS GC有一个参数CMSInitiatingOccupancyFraction，用来控制CMS GC的触发阈值，默认是60%。我们知道，CMS是一种Concurrent GC，它尽可能不暂停Mutator线程来完成垃圾回收，但只要Mutator没有被完全暂停住，那么不可避免GC的Concurrent阶段会不断有对象被分配或晋升到老区。如果阈值设得过高（比如90%），那么CMS触发时留下的可用空间太小，老区的对象填充的速率太快，CMS来不及处理完成Old就被占满了。第二个常见的原因是因为CMS内存碎片化，有大量碎片化的空间，这使得导致晋升担保总是成功，但实际YGC执行的时候陷入尝试失败的境地。第三个常见的原因是因为有时候是因为应用行为突然发生变化，短时间内内存分配压力突然变大，使得YGC晋升尝试失败。另外，`Floating Garbage <https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/cms.html#sthref36>`_ 的情况也会为concurrent mode failure的场景火上浇油，Floating Garbage本质上会降低CMS的回收效率，使得CMS的conccurrent并发阶段无法与old区的填充匹配。

    在G1中，Full GC的触发是一种极端情况下应对，通常触发的场景有这么几种，第一种就是Metaspace引起的。当Metasapce达到MaxMetaspaceSize上限无法再分配内存的时候，JVM会触发GC来卸载class，在JDK8u40以前，class unload是通过Full GC触发的，因此这种情况非常常见。在JDK8u40以后，class unload不再通过Full GC触发了，但同样也还会有Metadata触发的Full GC，这是因为，当Metaspace无法满足分配触发Conccurrent Cycle(initail-mark, concurrent-mark and so on)进行Metasapce的卸载和清理的时候， Metaspace的清理是concurrent并发的得和Metaspace分配同时发生的，理论上Metaspace并不一定会保证空闲内存一定会很快被清理出来，此时出于效率的考虑，Metaspace还是乐观得在Concurrent Cycle被触发后投机得进行一次分配尝试，如果concurrent阶段进行得很快，这种投机分配会成功，如果conccurent阶段比较慢，那么这种投机分配就会失败，一但失败就会触发Full GC。另外一种情况是考虑CompressedClassSpaceSize, 当UseCompressedOops和UseCompressedClassesPointers打开的时候，实际上Klass数据是存放在一块由CompressedClassSpaceSize控制的独立的内存里，如果CompressedClassSpaceSize限制的不合理，那么也会触发由Metaspace引起的Full GC。

    第二种情况下的Full GC也会经常遇到，这种情况下Full GC之前会发生大量的带有"to-space exhausted"字段的GC。 "to-space exhausted"的发生是因为G1通过G1ReservePercent预留的空间不够了，导致evcuate无法成功。"to-space exhausted"本身就比较昂贵,会严重影响GC的暂停时间。如果"to-space exhausted"之后又紧接着发生了Full GC，这种情况下就意味着这段时间内应用发生了大量晋升，Old区GC的节奏跟不上应用的分配行为。在有些场景下，"to-space exhausted"之后并不一定总是跟着Full GC，但需要强调的是，无论是哪种情况，在JDK8中，"to-space exhausted"是非常昂贵的，在某些场景下甚至比Full GC本身还要耗时，在JDK9中已经fix了这个 `Bug <https://bugs.openjdk.java.net/browse/JDK-8155256>`_ ，在JDK9中"to-space exhausted"的暂停时间和一次普通的YGC/Mix GC相当。通常来说，"to-space exhausted"可以通过GC参数的调整来进行规避，比如调整IHOP和G1ReservePercent。


    第三种情况下的Full GC是在Concurrent Cycle的过程中导致的, 在这种情况下，Concurrent Cycle的concurrent-mark还没有完成，内存就用完了，那么GC的执行也就无从谈起，这种情况下JVM的唯一选择确实只剩下Full GC了。这种情况在GC日志中也很常见，下面是一个GC日志的输出例子。

    ::

        57929.136: [GC concurrent-mark-start]
        57955.723: [Full GC 10G->5109M(12G), 15.1175910 secs]
        57977.841: [GC concurrent-mark-abort]

    可以看到，当concurrent-mark-start启动不久之后，就直接发生了Full GC。这种情况下，一种常见的原因是humongous对象的大量分配造成concurrent-mark无法完成。

    值得一提的是，无论是CMS还是G1，Full GC作为极端场景下一种无奈应对，是需要尽可能避免的。JDK对于这种极端场景下的gc也进行了优化，对于G1, `JEP307 <http://openjdk.java.net/jeps/307>`_ 就尝试通过多线程来做Full GC，这个功能在JDK10中会release。对于CMS，AJDK有一个参数-XX:+CMSParallelFullGC也可以多线程并行加快Full GC。

CMS内存碎片化导致Full GC
""""""""""""""""""""""""

CMS GC本质上是一种Mark-Sweep的GC算法，它不会对回收区域进行碎片整理，当碎片化发生时，尽管通过gcutil来看内存还有不少，也会触发Full GC进行碎片整理。如果观察GC日也会发现Full GC发生的时刻，Old区的占比并不算很高，这种现象就可以确认是CMS碎片化导致的Full GC。

堆外内存增涨异常
""""""""""""""""

:ref:`堆外内存增涨异常<OffHeapIncrement>` 也很容易造成GC的触发异常, 其中 :ref:`Metaspace异常增涨<MetaspaceIncrement>` 是需要重点考察的。

堆内内存增涨异常
""""""""""""""""

:ref:`堆内内存增涨异常<HeapIncrement>` 也很容易造成GC的触发异常。

用户通过jmap等工具触发的Full GC
"""""""""""""""""""""""""""""""

jmap也可以触发Full GC，比如：

.. code-block:: bash

    jmap histo:live $pid
    jmap dump:live $pid

这些Full GC一般都是由用户或者系统出于诊断目的进行的，下面是jmap histo:live触发的Full GC日志例子：

::
    
    [Full GC (Heap Inspection Initiated GC) 2018-03-29T15:26:51.070+0800: 51.754: [CMS: 82418K->55047K(131072K), 0.3246618 secs] 138712K->55047K(249088K), [Metaspace: 60713K->60713K(1103872K)], 0.3249927 secs] [Times: user=0.32 sys=0.01, real=0.32 secs]


下面是jmap dump:live触发的Full GC日志例子：

::

    [Full GC (Heap Dump Initiated GC) 2018-03-29T15:31:53.825+0800: 354.510: [CMS2018-03-29T15:31:53.825+0800: 354.510: [CMS: 55047K->56358K(131072K), 0.3116120 secs] 84678K->56358K(249088K), [Metaspace: 62153K->62153K(1105920K)], 0.3119138 secs] [Times: user=0.31 sys=0.00, real=0.31 secs]

通过排查GC日志里Full GC的GC Cause字段可以确认这个问题。

业务逻辑错误调用System.gc
"""""""""""""""""""""""""

业务逻辑会出于各种各样的目的会显示得调用System.gc，从而触发CMS或者Full GC，如果在GC日志中发现GC Cause有System.gc的字样就可以确认是这个问题。为了进一步排查System.gc的真正调用来源，排查人员可以利用 `BTrace <https://github.com/btraceio/btrace>`__ 来抓出真正的调用方。

大尺寸ArrayList的扩容行为导致的不必要Full GC
""""""""""""""""""""""""""""""""""""""""""""

ArrayList的扩容是一种非常正常的行为，但由于扩容的场景的巧合，可能导致不必要的Full GC。考虑如下代码

.. code-block:: java
    
    import java.util.ArrayList;
    import java.util.Date;
    import java.util.List;
    public class Test {
        public static void newMem(int memMb) {
            List list = new ArrayList();
            for (int i = 0; i < memMb * 1000; i++) {
                byte[] bytes = new byte[1024 * i];
                for (int j = 0; j < bytes.length; j++) {
                    bytes[j] = 0;
                }
                list.add(bytes);
            }
            System.out.println("finish new memory:" + System.currentTimeMillis());
        }
        
        public static void main(String[] args) {
            System.out.println("memMB:" + args[0]);
            int memMb = Integer.valueOf(args[0]);
            newMem(memMb);
            try {
                Thread.sleep(10000 * 1000);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

在上面的代码中，newMem分配了一个比较大的数组，当newMem返回时按理说list作为临时对象，应该能被GC回收，如果young区的大小比较小，那么这部分newMem都会经过多次copy进入old区，如果系统的Old区也比较小，那么这部分晋升old区的对象理论上会触发old gc，这时候应该会发现list已经是死对象可以进行回收。但是你会惊讶的发现，这部分对象实际上是无法被Old GC比如CMS回收掉的，而是不得不进入到Full GC，一到了Full GC，内存却被成功的回收掉了。这很违反直觉，CMS回收不了的内存为什么Full GC可以回收？

答案很简单，就是扩容造成的，当newMem最后一次循环发生扩容时，ArrayList是在内部新分配了一个新的size的数组，然后将旧的数组内存通过System.arrayCopy的浅拷贝的方式将旧内容复制过来，这就构成了一个新生代到老区的跨区引用。当newMem返回时，即使这时候发生Old GC，由于old gc并不会触发young gc，新区的引用也会阻止这部分内存的回收，从而使得newMem里的一系列大量数组无法被回收。但一旦newMem返回后发生的是Full GC，young区存在的扩容的list会被回收掉，从而old区也就能正常被清理。

业务压力比较大导致频繁YGC
"""""""""""""""""""""""""
这个也是常见的原因，如果通过GC日志发现，YGC的频率发生比较高，而且GC的回收效果也不错，同时发现CPU，业务指标的压力也相对是一个高水位，可以认为是业务压力比较大造成，并不是故障。这种情况下，用户可能需要进行代码优化，主要思路就是通过object pool这样的方式，避免大量的内存分配。借助于Eclipse MAT，用户可以分析出内存存活对象的分布，以及类名，从而为用户的代码修改提供线索。另外jmap的histo命令也可以提供这方面的线索。

javaagent造成的异常GC
"""""""""""""""""""""
应用出于各种目的会加载javaagent，即使应用自己没有通过-javaagent参数主动加载，有的系统出于安全的原因会强制通过SA API之类的方式为某些java进程注入agent。一般来讲，好的javaagent会控制自己的行为，避免对业务Java进程造成过大的影响，但有时候逻辑异常的javaagent会分配大量的对象，从而引起异常GC。这些异常的GC很具有迷惑性，因为用户很可能只从自己业务逻辑的角度来思考问题，觉得自己业务的TPS很低，不应该有GC的压力，从而忽视了javaagent的存在。

这类问题需要询问系统管理员或者查看配置以及应用日志加以发现，一般javaagent的注入通常会有日志打印。另外还有一些线索可以帮助定位问题，比如jstack发现大量的javaagent线程，比如jmap的heapdump中产生了大量的javaagent的对象等等。

故障解决
^^^^^^^^
Metaspace使用过高造成频繁的GC触发，可以通过[Metaspace造成的内存泄漏](#Metaspace造成的内存泄漏)来进行排查解决， Metspace碎片化的问题在[Metaspace造成的内存泄漏](#Metaspace造成的内存泄漏)也提供了方案。GC参数设置不合理请根据相应的 :ref:`相关章节<GCParamAnchor>` 中的经验公式合理调整JVM启动参数。

generatedMethodAccessorXXX的场景是一种特殊的场景，一般情况下可以尝试通过逻辑修改来避免多线程反射，也可以考虑缓存Method。

对于CMS Full GC而言，如果是由于CMS碎片化造成的，暂时没有什么好的解决方法，一种方法是增加内存，另外一种方法就是换用G1。另外，尝试调低CMSInitiatingOccupancyFraction，也会减少Full GC的可能行。最后需要强调的是，根据公式设置合适的各个内存大小也是非常重要的。

对于G1/CMS Full GC而言，如果是Metadata触发的，尝试增加Metaspace空间大小，同时参考堆外内存增涨异常中Metaspace的相关章节进行Metaspace调优，一般的做法是调大Metaspace大小，并且通过控制Metaspace的上下限参数避免Metaspace的expansion/shrink造成的碎片化。另外，基于前面的GC参数设置也会改善G1 Full GC。

用户通过jmap等工具触发的Full GC需要查找出用户执行这个命令的具体原因是什么。

业务逻辑错误调用System.gc属于代码逻辑的问题，通过BTrace定位出调用方，需要结合代码来论证必要性，可以将它放到午夜等流量不繁忙的时候进行Full GC避免对业务的影响。另外如果不想深究调用方，直接开启-XX:+DisableExplicitGC直接强制disable System.gc以及通过-XX:+ExplicitGCInvokesConcurrent用CMS GC来代替Full GC也是可选项。

ArrayList扩容造成的Full GC问题，可以通过设置预估合理的capacity，从而避免不要的扩容。-XX:+CMSScavengeBeforeRemark可以可以解决这个问题，通过强制CMS Final Remark之前做一次YGC，从而使得扩容在Young区的垃圾也能被回收掉。

javagent造成的异常GC需要review javaagent的逻辑通过代码加以修改。

业务压力大造成的频繁YGC，用户需要进行代码优化。
