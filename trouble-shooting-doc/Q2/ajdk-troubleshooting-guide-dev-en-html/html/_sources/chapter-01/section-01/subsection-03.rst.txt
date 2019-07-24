GC trigger exception
--------------------

Since the JVM will be Stop-the-Word at GC, the fewer triggers for the GC, the
better performance. It often means a failure if the frequent or the suspicious
trigger timing GC is observed through the GC log.

In addition, the on-heap memory leak and the off-heap memory leak are also
related to the GC triggering abnormality, some memory leaks will also cause the
GC to trigger frequently. Please check the related troubleshooting when
troubleshooting, read the relevant sections.


Fault performance
^^^^^^^^^^^^^^^^^

The frequent GC is found by GC log. For GC, there is generally an experience: it
is often a fault signal for GC anomalies if the GC throughput is less than 90%,

``GC Throughput = 1 - GC Pause Time / Total Application Run Time``

In absolute terms, a YGC pause time of around 100ms is a very common pause time.
50ms can be used as a general optimization target if the application's response
time requirements are high. 20ms is generally the limit of optimization.

For GCs in the Old Zone, such as the CMS GC, the frequency of the GC is usually
dependent on the application which is in the smooth running state. CMS GC is a
very common frequency in a few hours in general. However, the developer can pay
attention to the performance of the GC which is triggered once in a few minutes
that it maybe a signal of a CMS GC anomaly.

There are many applications that use CMS for garbage collection,
``UseCMSInitiatingOccupancyOnly`` and ``CMSInitiatingOccupancyFraction`` are
used to specify when the CMS GC is triggered (A CMS GC is triggered when the
usage of the old area exceeds ``CMSInitiatingOccupancyFraction``). However,
sometimes the observation of log shows that the frequent CMS GC is triggered
when the usage of the old area does not reach ``CMSInitiatingOccupancyFraction``.
This is often caused by Metaspace fragmentation or CMS fragmentation.

The frequent GC triggering is also a common type of problem which is easy to
confirm in the GC log. Everyone knows that Full GC is a full-stacked
Stop-The-Word GC. It is actually an extreme means that the JVM's GC can't keep
up with the business pace, so the Full GC needs to be avoided as much as
possible. It should be noted that in many monitoring systems of Alibaba, the CMS
GC is also treated as a Full GC and needs to be distinguished. The frequency of
the Full GC varies according to the application, and once a day, once a week, it
is necessary to determine whether the frequency is abnormal in combination with
the specific conditions of the application.

cause of issue
^^^^^^^^^^^^^^

The common reasons are as follows:

- Metaspace/Perm is too high
- Metaspace fragmentation
- GC parameter settings are not reasonable
- CMS memory fragmentation leads to Full GC
- on-heap heavy memory increase abnormality
- off-heap memory increase abnormality
- Business logic error calling ``System.gc``
- Full GC triggered by the user via jmap and other tools
- Unnecessary Full GC caused by expansion of large-sized ``ArrayList``
- Business pressure is relatively high leading to frequent YGC
- javaagent caused an exception GC


Troubleshooting
^^^^^^^^^^^^^^^

.. index:: Metaspace

Metaspace/Perm usage is too high
""""""""""""""""""""""""""""""""
The ``CMSCollector: collect for metadata allocation`` or
``CMS perm gen initiated`` will be printed in the gc log if the ``-verbose:gc``
parameter is enabled. In addition, ``java.lang.OutOfMemoryError: Metaspace`` is
also a signal that Metaspace uses too high. Inspectors can use jstat and gcutil
to observe the usage of Metaspace to confirm the failure. Metaspace/Perm can be
used to troubleshoot the corresponding sections of the on-heap memory anomaly.

.. index:: Metaspace

Metaspace fragmentation
"""""""""""""""""""""""
When the trigger of the GC was found to be caused by Metaspace, the use of
Metaspace was not very high by gcutil. This phenomenon means that Metaspace is
fragmented. This is a known bug in OpenJDK. JDK11 has been fixed to participate
in `Bug <https://bugs.openjdk.java.net/browse/JDK-8198423>`__. Please refer to
the Metspace leak section of the out-of-heap memory leak for troubleshooting.

.. _GCParamAnchor:

GC parameter settings are not reasonable
""""""""""""""""""""""""""""""""""""""""
The abnormality of the GC trigger is usually closely related to the setting of
the GC parameters. Usually, GC Xmx, Xms settings generally have the following
empirical formula:

::

    Java Heap size (-Xmx, -Xms)
      = 3x or 4x Live size of the Old area after Full GC Metaspace size (-XX:MaxMetaspaceSize)
      = 1.2x or 1.5x Live Size of the Metaspace after Full GC Young area size (-Xmn)
      = 1x or 1.5x Full Size of Old Area after Full GC Old Area Size
      = 2x or 3x Full Size of Old Area after Full GC

This GC empirical formula can usually be used as a reference for GC tuning. Of
course, for many scenarios, this empirical formula cannot be completely covered,
and needs specific analysis.

.. index:: YGC, Young GC, Minor Collection, Minor GC

.. note::

    There are many reasons for YGC triggering. For the ParNew/CMS combination,
    the usual cause of YGC is Allocation Failure. In this case, the Young area
    cannot allocate new objects and needs to trigger YGC for garbage collection.
    In addition, if you turn on the ``-XX:CMSScavengeBeforeRemark`` control
    option, ParNew will also be triggered during the final-remark phase of the
    CMS for YGC reclamation. In addition to these two more common reasons, there
    are other reasons, such as the use of ``-XX:+ScavengeALot`` in the Debug
    version of the JDK, will periodically trigger YGC to test. When the JNI code
    runs to Critical-Region, all YGCs will be Pending. When the JNI code exits
    Critical-Region, the garbage collector will make up a YGC. In addition, if
    the user uses the WhiteBox API, YGC can also be dynamically triggered by the
    API in the program.
    
    YGC is triggered when the Young area can no longer allocate objects for G1.
    In addition, the intial-mark phase of Conccurent Cycle is essentially a YGC.
    In addition, G1 may trigger YGC when processing Humongous Object allocation.
    The scenarios in which the Critical-Region and WhiteBox APIs listed above
    trigger the YGC are equally valid for G1. If G1 does not limit the size of
    the Young area, YGC will fully respect ``MaxGCPauseMillis`` which will
    dynamically determine the size of the next Young area based on the
    historical GC pause time data to control the pause time. The GC will choose
    the larger Young area as much as possible. However, the premise is that the
    recovery time estimated by these Young areas cannot exceed
    ``MaxGCPauseMillis``. Of course, the Young area dynamic adjustment must be
    within the limits of ``G1NewSizePercent`` and ``G1MaxNewSizePercent``.
    Therefore, in general, if the historical data is estimated to be relatively
    accurate, the GGC's YGC Paustime will be relatively stable, and the pause
    time will fluctuate around ``MaxGCPauseMillis``.

.. index:: Humongous

.. note::

    An object whose object size is larger than 50% of ``HeapRegionSize`` is
    called Humongous Object in G1. Since this part of the object is relatively
    large, the cost of copying back and forth between Young and Old is too
    large, and G1 allocates Homongous Object directly to the old area. When
    Humongous Object is allocated, it first evaluates whether the existing Heap
    occupancy size reaches the IHOP threshold if the required allocation size is
    met. If the IHOP threshold is reached, a Concurrent Cycle with the
    "concurrent humongous allocation" field will be triggered once. There are
    two main reasons for doing this: First, G1 is worried that the Humongous
    Object consumes too much memory. Doing such an inspection and evaluation
    every time it is allocated can trigger the Conccurent Cycle to prepare for
    the subsequent Mixed GC recycling of the old area as soon as possible. The
    other reason is The first phase of Conccurent Cycle Intial Mark is
    essentially a YGC, YGC will also recycle Humongous Object
    (``-XX: G1EagerReclaimHumongousObjects`` is opened by default in AJDK8).
    Recycling Humongous Object as early as possible can avoid the failure of the
    subsequent Humongous Object allocation. After the evaluation is completed,
    the next assignment function will optimistically make a speculative
    allocation attempt on the Humongous Object. Since the Concurrent Cycle in
    the first step is a concurrent process, the speculative allocation may fail.
    Once the allocation fails, it will proceed. A Homongous Allocation triggers
    the YGC and then continues to try the Humongous Object assignment until it
    succeeds. Interested readers can carefully study the
    ``G1CollectedHeap::attempt_allocation_humongous`` related function part of
    the Hotspot source.

.. index:: Old GC, Major Collection, Major GC, Mix GC

.. note::

    There are many reasons for GC triggering in the Old District. Taking the CMS
    as an example, as an old GC, the CMS is usually triggered because the
    threshold of ``CMSInitingOccupancyFraction`` is reached. In addition, the
    lack of Metaspace memory will trigger the CMS, and another situation is
    often ignored which is the ``ConcurrentMarkSweepThread`` background who will
    also trigger the Old GC when it determines that the old zone cannot meet the
    next promotion requirement. If the available size of the old area is less
    than the historical average promotion size or the current use size of the
    young area, it is considered that the next promotion requirement cannot be
    met. The ``CMSCollector: collect because incremental collection will fail``
    will be printed in the gc log if -verbose:gc is enabled. You can check if
    the gc log is due to the situation. It is worth noting that this situation
    needs to be distinguished from the failure of the YGC promotion guarantee.
    Both of these cases involve checking whether the promotion is possible, so
    it is similar, but the triggering scenario is different. YGC first checks
    the promotion guarantee, once it fails, The Full GC will be triggered, and
    ``ConcurrentMarkSweepThread`` will do the promotion guarantee check, which
    will trigger the CMS GC. These two scenarios complement each other, and the
    CMS GC triggers the CMS GC as early as possible through the promotion
    guarantee check to avoid the Full GC caused by the failure of the YGC
    promotion guarantee. The reader may be curious as to why the YGC failed to
    trigger the CMS GC when the promotion failed. This is because YGC has
    already started because of the Hotspot implementation. At this time, the
    promotion guarantee failed. The Young area is in an inconsistent state and
    cannot trigger the concurrent CMS GC, but the Full GC that triggers a
    stop-the-world is indeed feasible.

    G1's Old Zone recovery is performed by Mixed GC. When YGC is finished, it
    will check the Heap usage size. A Concurrent Cycle will be triggered if the
    IHOP threshold is reached. The Initial Mark phase in Concurrent Cycle is
    essentially a YGC. In the Concurrent Cycle, G1 will scan the entire heap
    concurrently, and calculate the survival ratio of the objects in each
    Region. When the Concurrent Cycle ends, it's usually followed by a YGC and
    then the Mixed GC. The way Mixed GC works is essentially the same as YGC.
    The main difference is that the area it cleans up includes a part of Old
    Region in addition to Young Region. Mixed GC recovery first checks whether
    the recyclable waste is greater than the threshold ratio defined by
    G1HeapWastePercent. Only above this threshold will trigger the recovery
    because GC is very expensive. If the recovery efficiency is not good, there
    is very little garbage, and G1 is not necessary to trigger. GC. Mixed GC
    prefers the Old area with a small survival ratio so that as much waste as
    possible can be recycled. If the survival ratio in a region is high, greater
    than the threshold of ``G1MixedGCLiveThresholdPercent``, then the Region is
    worthless to recycle, and the Region is ignored. Mixed GC also fully
    respects ``MaxGCPauseMillis``, which will select Old Region as much as
    possible, provided that the estimated recovery times for these Old Region
    and Young Region do not add up to MaxGCPauseMillis. If ``MaxGCPauseMillis``
    is small, Mixed GC is difficult to meet the requirements, it will also
    choose a minimum number of Old Region List for recycling, the minimum queue
    length is controlled by ``G1MixedGCCountTarget`` (default is 8), with all
    Old Region candidates list Dividing the length by ``G1MixedGCCountTarget``
    to get the number is the minimum reclaim length. By erasing this length and
    selecting all Old Region candidates according to the high to low survival
    rate, the old Mixed Recycling List of the Mixed GC is obtained and copied
    and recovered. Essentially, ``G1MixedGCCountTarget`` means that the
    recycling of the Old Zone is allowed to pass through multiple Mixed GCs,
    but not too many times, and must not exceed the number of times
    ``G1MixedGCCountTarget`` has agreed. Below is an example of a GC log printed
    by the Mixed GC at the G1Ergonomics Level.

    ::

        8822.704: [G1Ergonomics (Mixed GCs) continue mixed GCs, reason: candidate old regions available, candidate old regions: 444 regions, reclaimable: 4482864320 bytes (16.06 %), threshold: 10.00 %]

.. note::

    It should be assumed that the Mixed GC should be performed after the
    Concurrent Cycle is finished, but G1 chooses to perform YGC first, then
    Mixed GC. If you choose to open the JVM parameter
    ``-XX:+PrintAdaptiveSizePolicy``, you will see the Concurrent Cycle in the
    GC log. The Mixed GC will be canceled because
    ``"do not start mixed GCs, reason: concurrent cycle is about to start"``. As
    for why the G1 chooses to cancel the deep-rooted reason of the previous
    Mixed GC, interested readers can study The
    ``G1CollectorPolicy::record_collection_pause_end`` method.

.. index:: Full GC

.. note::

    The triggering of the Full GC is more complicated.

    In the CMS GC, the direct cause of Full GC was the failure of the YGC
    promotion. YGC will estimate the success of promotion according to certain
    algorithms before each GC. This is called promotion guarantee. If the
    promotion guarantee is successful, then YGC will proceed, otherwise YGC will
    fall into the state of attempt failure. If the YGC fails, at this point the
    memory will be in an inconsistent state, it will notify the CMS GC to
    recycle, and the CMS GC will execute a StopTheWorld compressed GC (this
    compressed GC has two options, one is to use Serial) GC to do Full GC, the
    other is CMS's own STW only recycles the foreground collector of the old
    area, the specific choice needs to be determined according to the parameters
    ``UseCMSCompactAtFullCollection`` and ``CMSFullGCsBeforeCompaction``, by
    default, Serial Full GC). When the YGC fails, if it happens to be in a CMS
    Concurrent phase, the Concurrent phase will be interrupted. At this point,
    the GC log will insert a CMS-related record containing the words concurrent
    mode failure in the middle of the ParNew GC log. It should be noted that
    when a concurrent mode failure occurs, it generally means that the Full GC
    (foreground collector will not be enabled under the default parameters),
    although this time gc does not necessarily mark the Full GC. If the YGC
    fails and is not in a CMS Concurrent phase, the gc log will simply report a
    Full GC with no words like concurrent mode failure.

    Even if the promotion guarantee is successful, YGC may still fail to try.
    This is because the promotion estimate is based on historical data
    estimates. If the application's behavior changes drastically, this
    prediction is not allowed. At this point, the processing flow of the YGC
    attempt failure is consistent with the processing flow of the previous
    promotion guarantee failure.

    The following code shows the logic of the promotion guarantee failure. As
    you can see from the code below, there are two conditions for the promotion
    guarantee: either the old area has more space than the historical average
    promotion size, or the old area has more space than the new generation of
    the current size.

    .. code-block:: cpp

        299 bool TenuredGeneration::promotion_attempt_is_safe(size_t max_promotion_in_bytes) const {
        300   size_t available = max_contiguous_available();
        301   size_t av_promo = (size_t)gc_stats()->avg_promoted()->padded_average();
        302   bool   res = (available >= av_promo) || (available >= max_promotion_in_bytes);
        303   if (PrintGC && Verbose) {
        304     gclog_or_tty->print_cr(
        305       "Tenured: promo attempt is%s safe: available("SIZE_FORMAT") %s av_promo("SIZE_FORMAT"),"
        306       "max_promo("SIZE_FORMAT")",
        307       res? "":"not", available, res? ">=":"<",
        308       av_promo, max_promotion_in_bytes);
        309   }
        310   return res;
        311 }


    There are many reasons why YGC attempts to fail. The most common one is that
    the CMS GC triggers too late. The CMS GC has a parameter
    ``CMSInitiatingOccupancyFraction``, which is used to control the trigger
    threshold of the CMS GC, the default is 60%. We know that CMS is a 
    Concurrent GC. It does not suspend the Mutator thread to complete garbage
    collection as much as possible. However, as long as the Mutator is not
    completely suspended, it is inevitable that the Concurrent phase of the GC
    will continue to have objects assigned or promoted to the old zone. If the
    threshold is set too high (such as 90%), then the free space left by the CMS
    trigger is too small, the object in the old area is filled too fast, and the
    CMS is too full to process. The second common cause is because CMS memory is
    fragmented and there is a lot of fragmented space, which makes the promotion
    guarantee always successful, but the actual YGC execution is caught in the
    failure of the attempt. The third common reason is that sometimes the
    application behavior suddenly changes, and the memory allocation pressure
    suddenly becomes large in a short period of time, which makes the YGC
    promotion attempt fail. In addition, the situation of
    `Floating Garbage <https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/cms.html#sthref36>`_
    will also be the scene of concurrent mode failure. In the case of oiling,
    Floating Garbage essentially reduces the recycling efficiency of CMS, so
    that the conccurrent concurrency phase of CMS cannot match the filling of
    the old area.

    In G1, the trigger of Full GC is an extreme case, and there are usually
    several scenarios triggered. The first one is caused by Metaspace. When
    Metasapce reaches the MaxMetaspaceSize limit and no longer allocates memory,
    the JVM triggers the GC to unload the class. Prior to JDK8u40, class unload
    was triggered by the Full GC, so this is very common. After JDK8u40, class
    unload is no longer triggered by Full GC, but there will also be a Full GC
    triggered by Metadata. This is because Metaspace cannot meet the allocation
    trigger Conccurrent Cycle (initail-mark, concurrent-mark and so on). When
    Metasapce is uninstalled and cleaned up, Metaspace cleanup is concurrent
    with concurrent metaspace allocation. In theory, Metaspace does not
    necessarily guarantee that free memory will be cleaned up quickly. For
    efficiency reasons, Metaspace is still optimistic that the Concurrent Cycle
    will be triggered to make a distribution attempt. If the concurrent phase is
    very fast, this speculative allocation will succeed. If the conccurent phase
    is slow, then this speculative allocation will fail, but fail. The Full GC
    will be triggered. Another case is to consider ``CompressedClassSpaceSize``.
    When ``UseCompressedOops`` and ``UseCompressedClassesPointers are opened,
    the Klass data is actually stored in a separate memory controlled by
    ``CompressedClassSpaceSize``. If the ``CompressedClassSpaceSize`` limit is
    unreasonable, it will also trigger the Full GC caused by Metaspace.

    The Full GC in the second case is also often encountered, in which case a
    large number of GCs with ``"to-space exhausted"`` fields will occur before
    the Full GC. ``"to-space exhausted"`` occurred because G1 reserved enough
    space through ``G1ReservePercent``, causing evcuate to fail.
    ``"to-space exhausted"`` itself is expensive and can seriously affect the
    GC's pause time. If ``"to-space exhausted"`` is followed by a Full GC, this
    situation means that the application has undergone a lot of promotion during
    this time, and the rhythm of the Old Zone GC cannot keep up with the
    application's allocation behavior. In some scenarios,
    ``"to-space exhausted"`` does not always follow the Full GC, but it should
    be emphasized that in either case, in JDK8, ``"to-space exhausted"`` is very
    expensive, in a certain These scenes are even more time consuming than the
    Full GC itself. This
    `Bug <https://bugs.openjdk.java.net/browse/JDK-8155256>`_ has been fixed in
    JDK9, the pause time for ``"to-space exhausted"`` is equivalent to a normal
    YGC/Mix GC in JDK 9. In general, ``"to-space exhausted"`` can be
    circumvented by adjusting GC parameters, such as adjusting IHOP and
    ``G1ReservePercent``.


    In the third case, the Full GC is caused by the Concurrent Cycle. In this
    case, the Concurrent Cycle's concurrent-mark is not completed yet, and the
    memory is used up. Then the GC execution is impossible to talk about. In
    this case, the only choice for the JVM is really only the Full GC. This
    situation is also common in GC logs. Below is an example of the output of a
    GC log.

    ::

        57929.136: [GC concurrent-mark-start]
        57955.723: [Full GC 10G->5109M(12G), 15.1175910 secs]
        57977.841: [GC concurrent-mark-abort]

    It can be seen that when the concurrent-mark-start is started shortly, the
    Full GC happens directly. In this case, a common reason is that the large
    allocation of humongous objects causes the concurrent-mark to fail to
    complete.

    It is worth mentioning that, whether it is CMS or G1, Full GC as a kind of
    helpless response in extreme scenes, it needs to be avoided as much as
    possible. JDK is also optimized for gc in this extreme scenario. For G1,
    `JEP307 <http://openjdk.java.net/jeps/307>`_ tries to do Full GC through
    multithreading. This function is in JDK10. Will be released. For CMS, AJDK
    has a parameter ``-XX:+CMSParallelFullGC``, which can also speed up Full GC
    in parallel with multiple threads.

CMS memory fragmentation leads to Full GC
"""""""""""""""""""""""""""""""""""""""""

The CMS GC is essentially a Mark-Sweep GC algorithm that does not defragment the
reclamation area. When fragmentation occurs, although there are still a lot of
memory through gcutil, the Full GC will be triggered for defragmentation. If you
observe the GC day and you will find the moment when the Full GC occurs, the
proportion of the Old area is not very high. This phenomenon can be confirmed as
the Full GC caused by CMS fragmentation.

On-heap memory increase abnormality
"""""""""""""""""""""""""""""""""""

:ref:`On-heap memory increase abnormality<OffHeapIncrement>` is also very likely
to cause GC trigger exception, where
:ref:`Metaspace abnormal increase<MetaspaceIncrement>` is important to
investigate.

Off-heap memory increase abnormality
""""""""""""""""""""""""""""""""""""

:ref:`Off-heap memory increase abnormality<HeapIncrement>` could also result to
trigger exception eazily.

Full GC triggered by the user through tools such as jmap
""""""""""""""""""""""""""""""""""""""""""""""""""""""""

Jmap can also trigger Full GC, such as:

.. code-block:: bash

    Jmap histo:live $pid
    Jmap dump:live $pid

These Full GCs are generally performed by the user or the system for diagnostic
purposes. The following is an example of a Full GC log triggered by jmap
``histo:live``:

::
    
    [Full GC (Heap Inspection Initiated GC) 2018-03-29T15:26:51.070+0800: 51.754: [CMS: 82418K->55047K (131072K), 0.3246618 secs] 138712K->55047K (249088K), [Metaspace: 60713K- >60713K(1103872K)], 0.3249927 secs] [Times: user=0.32 sys=0.01, real=0.32 secs]


The following is an example of a full GC log triggered by jmap ``dump:live``:

::

    [Full GC (Heap Dump Initiated GC) 2018-03-29T15:31:53.825+0800: 354.510: [CMS2018-03-29T15:31:53.825+0800: 354.510: [CMS: 55047K->56358K (131072K), 0.3116120 Secs] 84678K->56358K(249088K), [Metaspace: 62153K->62153K(1105920K)], 0.3119138 secs] [Times: user=0.31 sys=0.00, real=0.31 secs]

This problem can be confirmed by troubleshooting the GC Cause field of the Full
GC in the GC log.

Business logic error calling System.gc
""""""""""""""""""""""""""""""""""""""

Business logic will display System.gc for a variety of purposes, triggering CMS
or Full GC. If you find GC Cause with System.gc in the GC log, you can confirm
this problem. To further troubleshoot the actual source of System.gc, the
troubleshooter can use `BTrace <https://github.com/btraceio/btrace>`__ to grab
the real caller.

Unnecessary Full GC caused by expansion of large size ArrayList
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

The expansion of ArrayList is a very normal behavior, but due to the coincidence
of the expanded scene, it may lead to unnecessary Full GC. Consider the
following code

.. code-block:: java
    
    Import java.util.ArrayList;
    Import java.util.Date;
    Import java.util.List;
    Public class Test {
        Public static void newMem(int memMb) {
            List list = new ArrayList();
            For (int i = 0; i < memMb * 1000; i++) {
                Byte[] bytes = new byte[1024 * i];
                For (int j = 0; j < bytes.length; j++) {
                    Bytes[j] = 0;
                }
    List.add(bytes);
            }
    System.out.println("finish new memory:" + System.currentTimeMillis());
        }

        Public static void main(String[] args) {
    System.out.println("memMB:" + args[0]);
    Int memMb = Integer.valueOf(args[0]);
    newMem(memMb);
    Try {
    Thread.sleep(10000 * 1000);
            }
    Catch (InterruptedException e) {
    e.printStackTrace();
            }
        }
    }

In the above code, newMem allocates a relatively large array. When newMem
returns, it is reasonable to say that list is used as a temporary object. It
should be reclaimed by GC. If the size of the young area is small, then this
part of newMem will be copied multiple times. Old area, if the old area of the
system is also relatively small, then this part of the promotion of the old area
will theoretically trigger the old gc, this time should find that the list is
already a dead object can be recycled. But you will be surprised to find that
this part of the object can not be recovered by the Old GC, such as CMS, but has
to enter the Full GC, once the Full GC, the memory has been successfully
recycled. This is a counterintuitive that why can't the memory that CMS can't
recycle?

The answer is very simple, which is caused by the expansion. When the newMem
loop is expanded for the last time, the ArrayList is internally allocated a new
array of size, and then the old array memory is passed through the shallow copy
of System.arrayCopy. Copy it, this constitutes a cross-regional reference from
the new generation to the old district. When newMem returns, even if Old GC
occurs at this time, since old gc does not trigger young gc, the reference to
the new area will prevent this part of the memory from being recycled, so that a
large number of arrays in newMem cannot be recycled. But once the newMem
returns, it is the Full GC. The expanded list of the existing area will be
recycled, so that the old area can be cleaned normally.

Large business pressure leads to frequent YGC
"""""""""""""""""""""""""""""""""""""""""""""
This is also a common reason. If you find through the GC log, the frequency of
YGC is relatively high, and the recovery effect of GC is also good. At the same
time, it is found that the pressure of CPU and business indicators is relatively
high. It can be considered that the business pressure is relatively large. Is
not a malfunction. In this case, the user may need to optimize the code. The
main idea is to avoid a large amount of memory allocation by means of the object
pool. With Eclipse MAT, users can analyze the distribution of memory surviving
objects, as well as the class name, to provide clues to the user's code changes.
In addition, jmap's histo command can also provide clues in this regard.

Exception GC caused by javaagent
""""""""""""""""""""""""""""""""
The application loads the javaagent for various purposes. Even if the
application does not actively load through the -javaagent parameter, some
systems forcibly inject agents for some java processes through SA APIs for
security reasons. In general, a good javaagent will control its behavior and
avoid excessive impact on the business Java process, but sometimes the logical
exception of the javaagent will allocate a large number of objects, causing an
abnormal GC. These abnormal GCs are very confusing, because users are likely to
think only from the perspective of their own business logic, feel that their
business TPS is very low, there should be no pressure on the GC, thus ignoring
the existence of javaagent.

Such problems need to ask the system administrator or view the configuration and
application logs to find out. Generally, the injection of javaagent usually has
log printing. There are also some clues to help locate problems, such as jstack
found a large number of javaagent threads, such as jmap's heapdump generated a
large number of javaagent objects and so on.

Troubleshooting
^^^^^^^^^^^^^^^
Metaspace is too high to cause frequent GC triggering. It can be solved by
[Metspace caused by memory leak](#Metaspace caused by memory leak). Metspace
fragmentation problem solution could be also found
[Metaspacememory leak](#Metaspace Memory leaks are also available. If the GC
parameter setting is unreasonable, please adjust the JVM startup parameters
according to the empirical formula in the corresponding section
:ref:` related section <GCParamAnchor>`.

The scene of ``generatedMethodAccessorXXX`` is a special kind of scenario. In
general, you can try to avoid multi-threaded reflection through logical
modification. You can also consider caching Method.

For CMS Full GC, if it is caused by CMS fragmentation, there is no good
solution for the moment. One way is to increase the memory. The other method is
to switch to G1. In addition, trying to lower ``CMSInitiatingOccupancyFraction``
will also reduce the possible line of Full GC. The last thing to emphasize is
that it is also important to set the appropriate memory size according to the
formula.

For G1/CMS Full GC, if it is triggered by Metadata, try to increase the size of
Metaspace, and refer to the relevant section of Metaspace in the out-of-heap
memory increase exception for Metaspace tuning. The general approach is to
increase the size of Metaspace and pass Control the upper and lower bounds of
Metaspace to avoid fragmentation caused by Metaspace's expansion/shrink. In
addition, the G1 Full GC is also improved based on the previous GC parameter
settings.

The Full GC triggered by the user through tools such as jmap needs to find out
the specific reason why the user executes this command.

Business logic error calling System.gc belongs to the problem of code logic. The
caller is located through BTrace. It is necessary to combine the code to
demonstrate the necessity. It can be placed in midnight and other times when the
traffic is not busy, Full GC avoids the impact on the business. In addition, if
you do not want to go deep into the caller, it is also optional to directly
enable ``-XX:+DisableExplicitGC`` to directly disable system.gc and use
``-XX:+ExplicitGCInvokesConcurrent`` to replace Full GC with CMS GC.

The Full GC problem caused by ArrayList expansion can be achieved by setting a
reasonable estimate of the capacity to avoid unnecessary expansion.
``-XX:+CMSScavengeBeforeRemark`` can solve this problem by making a YGC before
CMS Final Remark, so that the garbage in the Young area can be recycled.

The exception GC caused by javagent requires the logic of the review javaagent
to be modified by code.

Frequent YGC caused by business pressure, users need to optimize their code.
