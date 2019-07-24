GC pause exception
------------------

GC pause time exceptions can also affect the latency of Java applications.
It is generally recommended to open the GC log on the production line. If the usr/sys/real time of YGC is obviously unreasonable in GC log, it often means a problem. Normally, a YGC can be considered normal between tens of milliseconds and hundreds of milliseconds, but if the time of the YGC is more than 1 second, then this obviously means that the system is abnormal and needs to be checked by inspector.

Fault performance
^^^^^^^^^^^^^^^^^

The performance of YGC time is very easy to detect through log monitoring:

- The GC log shows that the YGC usr/sys/real time is long, usually over 500 milliseconds.

Cause of issue
^^^^^^^^^^^^^^

The possible reasons are as follows:

- Too many promotion objects in Young Area make real/usr time longer
- Disk IO affects YGC time
- YGC time caused by swap becomes longer
- More Weak/Soft/Phantom References lead to longer GC Reference Processing
- G1 Update/Scan RS take long time
- ParNew/CMS Older-Gen scanning take long time
- Changes that implementation of JDK8 compared to JDK6’s take a  long YGC time
- CMS Final Remark takes long time
- Hybrid deployment environment impact


Troubleshooting
^^^^^^^^^^^^^^^

Young area promotes too many objects
""""""""""""""""""""""""""""""""""""

As you know, Java's GC algorithm has an important assumption, that is, the generation hypothesis, assuming that most of the newly allocated objects will become garbage in a short period of time and will be recycled. If the Young area causes most of the newly allocated objects to survive and eventually advance to the Old area for various reasons, this will cause the YGC time to become longer. Because the objects in the Young area survive in large numbers, there will be a large number of cross-region references from the Old to Young area at each YGC, which makes the YGC CardTable scan time longer, and a large number of surviving objects are copied back and forth between the Survivor areas. It will also increase the time of YGC.

To confirm the fault, you should check GC log. If there are a large number of promotions, and the size of the survivor area is relatively large, you can confirm this problem.

Disk IO
"""""""

Because jvm writes the GC log time will also be counted into the GC time. If the disk IO Load is busy, blocking write the GC log will also increase the GC time. In this case, the GC log takes long real time, but the usr, sys time is short. The following is an example of a disk IO causing long GC time::

    2016-01-14T22:08:28.028+0000: 312052.604: [GC (Allocation Failure) 312064.042: [ParNew
    Desired survivor size 1998848 bytes, new threshold 15 (max 15)
    - age   1:    1678056 bytes,    1678056 total
      : 508096K->3782K(508096K), 0.0142796 secs] 1336653K->835675K(4190400K), 11.4521443 secs] [Times: user=0.18 sys=0.01, real=11.45 secs]
      2016-01-14T22:08:39.481+0000: 312064.058: Total time for which application threads were stopped: 11.4566012 seconds

It can be seen that although the user and sys time is very short, the overall GC pause time is an amazing 11.45 seconds. If the system monitor finds that the disk IO has abnormal activity at this point in time, it can confirm the problem.

There may be another manifestation of disk IO issues. Here is another example of a gc log::

    2014-12-10T12:38:44.419+0000: 58758.830: [GC (Allocation Failure)[ParNew: 11868438K->103534K(13212096K), 0.7651580 secs] 12506389K->741669K(17406400K), 0.7652510 secs] [Times: user=0.36 sys=0.01, real=0.77 secs]

Here we find that user and real time are relatively high, and the ratio of real to user is only 2, but in fact the JVM is running on a 4-core computer, so it seems that the GC thread has stalled due to the kernel. From the perspective of design, GC is basically all memory operations, but with one exception, the GC will write some statistics to /tmp/hsperfdata during the execution process. This file is written directly through the pointer by mmap. Operation to complete. If the disk IO is abnormal, the pointer operation will cause a pause, causing a GC Pause exception. One manifestation of a pause caused by mmap is that the user will be higher.

In many cases, this disk IO exception is sporadic, so it needs to be accurately located by means of some historical data analysis. Some commonly used tools such as tsar and ioutil can work.

swap
""""

If the operating system has swap enabled, the entire operating system suddenly loads large files and consumes a lot of memory so that swap occurs, which also affects the YGC time. Multiple aspects of swap can affect YGC time.

The first aspect is the impact on the application itself. Swap will pause the application's own logic operations, making it difficult for Java threads to reach safepoint in time, affecting YGC time.

The second aspect is the effect of the hotspot VMThread entering the safepoint. There is a memory operation (serialize thread states page) in the time to safepoint time window. The swap makes the memory operation time greatly longer and affects the safepoint.

Slowly safepoint will cause the user and sys time of the GC log YGC to be much smaller than the real time. In general, since gc is multi-threaded, there will be the following equation:

``usr + sys = real * gc_thread_num``

If the safepoint is slow, it will cause real >> usr. If you find this situation when swap occurs, you can confirm this problem.

Another aspect is the impact of GC. Swap makes object access and object graph traversal dragged down. In this case, usr, real, sys will be very slow.

.. _ReferenceAnchor:

Weak/Soft/Phantom Reference
"""""""""""""""""""""""""""

A large number of Weak/Soft/Phantom References in the application will affect the YGC time, because YGC will have a stage to spend time processing these References. In addition to the YGC time, too many Referencece will affect the Final Remark time of the CMS and G1 Concurrent Cycle. The Final Remark of the G1 Concurrent Cycle/CMS will take time to process the Reference found during the Concurrent phase. If you find that the Ref Proc part of G1 or the weak refs processing part of CMS is more time-consuming in the GC log, you can judge this problem. TraceReferenceGC can further confirm this problem with some additional VM parameters, such as PrintReferenceGC.

.. note::

    Weak/Soft/Phantom Reference is a very special kind of object. When the GC processes Weak Reference, if it finds that referent can only pass Weak Reachable, it will set refrent to null, so that refrent will be discarded in the next GC. Soft Rerence is similar to Weak Reference. The only difference is that Soft Rerence is relatively lazy, and Weak Refrence compares eager, which means that Soft Refrence will persist in memory for a while. Only when Memory is exhausted, Soft Reference will be Being processed. The Phantom Reference is similar to the Weak Reference. There is no difference between the two in terms of eager. The only difference is that Phantom can't get the referent value before or after the referent is determined to be Weak Reachable, thus avoiding referent in the user. The layer was leaked. In addition, in the early JDK version, the Phantom Refernce required the user code to explicitly call the clear() function, which is different from the Weak/Soft Reference being automatically emptied by referent, otherwise there is a risk of OOM.

.. note::

    Due to the special semantics of WeakReference (Weak/Soft/Phantom), it also imposes an additional burden on the GC. Taking G1's WeakReference processing as an example, there are two types of reference processors, one is _ref_processor_stw and the other is _ref_precessor_cm. _ref_processor_stw is used in STW pause (young/mixed/full gc), and _ref_processor_cm is used in Concurrent Cycle.

    The workflow of _ref_processor_stw is probably like this. When the GC traverses the Copy object, if it accesses a new WeakReference object that has not been accessed, if the referent's reachability has no way to decide, the WeakRefernce object will be placed. Go to a discovered list. After the end of Copy, it will come back to the additional traversal process to process the discovered list. If the referent is strongly reachable during the traversal process, the corresponding WeakReference will be removed from the discovered list. Otherwise, the WeakReference will be operated. The refenent member is set to null. After the above stage, the discovered list is the last real WeakReference list. If the user registers the queue when creating the WeakReference, the discovered list will be pushed into the corresponding queue.

    The workflow of _ref_processor_cm is similar to _ref_processor_stw, except that the discovery function of _ref_processor_stw is opened at the beginning of the STW GC, and the processing of the discovered list is ended at STW GC Copy. The discovery function of _ref_processor_cm is opened in Initial Mark, and the processing of discovered list is performed in Remark. So _ref_processor_cm handles the WeakReference found by concurrent-mark in the entire Concurrent Cycle, while _ref_processor_stw handles the WeakReference found when swt executes GC Copy.

.. note::

    A common use of WeakReference is to track temporary objects. If the -XX:MaxTenuringThreshold is set too small, the promotion age threshold of jvm dynamic calculation may be relatively small, which makes WeakReference will be promoted early, resulting in this part of WeakReference not being timely. It was discovered early in YGC. This may surprise app developers, it's worth noting.

.. note::

    The WeakReference processing of CMS is somewhat similar to that of G1's _ref_processor_cm, but the WeakReference of CMS has only one _ref_processor, and the discovery function is opened in Initial Mark, and the processing operation is in Remark. In theory, the processing mechanism of CMS and G1's _ref_processor_cm is similar. G1's Concurrent Cycle scans and marks the entire Heap. Although Concurrent Cycle is not GC in nature, the WeakReference found during this period will also be cleaned up (WeakReference.referent Set to NULL), in theory, as long as the frequency of CMS and G1 Concurrent Mark is consistent, the processing timeliness and efficiency of WeakReference are also comparable. However, in the practice of Elasticsearch and Hbase, it is found that G1 does not process the reference as timely as CMS. The specific reason is not clear. For details, please refer to this `Bug <https://bugs.openjdk.java.net/browse/JDK-8182982>`_ 。

G1 Update/Scan RS take a long time
""""""""""""""""""""""""""""""""""

G1 maintains a RemSet data structure for each HeapRegion. This data structure records the address of another Card memory (512 bytes) that the other Region points to the current Region, so that this HeapRegion can be quickly in the GC. Confirm which memory has a reference to itself, which avoids the entire Heap scan. This design is very helpful for improving GC efficiency. When the Mutator thread updates or writes a new object member, the Card Pointer of the dirty is recorded in the Post Write Barrier. The VM then updates these dirty card pointers to the RemSets of each HeapRegion in a concurrent manner, a process called Concurrent Refine. When the GC starts, these Dirt Card Pointers may only handle most of them, and a small part is still not fully processed, so Update RS will be needed to handle this small number of dirty card pointers. This part of the time is the source of the Update RS time.

When the HeapRegion processes GC, since the object is moved, Scan RemSet is needed to update the members that point to the moved objects, and the objects in the RemSet are also scanned as part of the GC Root. This part is counted as Scan RS time.

If you find that G1's GC log shows that these two parts take a long time, you can confirm this problem.

ParNew/CMS Older-Gen scanning take a long time
""""""""""""""""""""""""""""""""""""""""""""""

Everyone may have discovered that the G1 log is relatively detailed and can print out the detailed information of each step of the GC. ParNew/CMS is relatively rudimentary and cannot print out the statistics of each substep of the GC. Fortunately, in AJDK, there is a unique parameter -XX:+PrintGCRootsTraceTime, which is used to count the detailed time-consuming time of the sub-steps of the ParNew/CMS GC. It mainly includes the time of each Roots Processing and memory copy. One important indicator is the older-gen scanning time. This indicator is similar to G1's Scan RS. It also uses Card's data structure to mark Old to Young references. The scan determines the GC Roots for the ParNew GC and updates the corresponding pointer to the moved object after the GC Move.

This part of the GC log shows that it takes a long time to draw attention.

Implementation changes of JDK8 compared to JDK6's result in longer ParNew time
""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

In the process of JDK upgrade, sometimes such problems will be found. For example, upgrading from JDK6 to JDK8 adopts CMS and the same hardware configuration. The business pressure remains unchanged, but the time of ParNew YGC is obviously increased compared with JDK6, although there is no More than 1 second, but a 10ms pause in JDK6 may become 40ms in JDK8, but the JDK8's gc interval is significantly longer, such as this Issue.
<https://aone.alibaba-inc.com/issue/12732290?spm=a2o8d.corp_prod_issue_list.0.0.6c7d6a85Wr2hXe>`__ 。

This situation has also been discussed in the community `Discussion
<http://mail.openjdk.java.net/pipermail/hotspot-gc-use/2015-February/002124.html>`__ ，but for the time being there is no clear conclusion. Due to the large changes in the implementation of the 6 to 8 code, it is difficult to end up with the change in that version. Although the gc time of JDK8 becomes longer, the optimization of gc interval compensates the performance of the application to some extent. So this can be seen as a trade-off between JDK implementations and should not be considered a failure.

CMS Final Remark takes a long time
""""""""""""""""""""""""""""""""""

If you find that the Remark phase of the GC log is long and the ratio is large, you can confirm this problem. There are usually several types of CMS Final Remark performances:

- Paralle Rescan is a long time. Since the object marked by the concurrent phase of the CMS may change, this stage needs to re-scan the reference of the Young area to the Old area. If there are too many objects in the Young area, this part of the time may become longer. -XX:+CMSScavengeBeforeRemark Open will try YGC before CMS Final Remark, which can alleviate this situation.
- WeakReference Processing takes a long time. Final Remark will clean up the WeakReference found during the Concurrent phase, and if this number is too large, it will also affect the processing time. Investigators can also refer to :ref:`这里<ReferenceAnchor>`.
- Class Unloading takes a long time. Class Unloading is performed during the Final Remark phase. There are many reasons for this length of time. One is to uninstall too many Classes, you can try out code optimization by looking at the standard output log to see which classes are frequently uninstalled by -XX:+TraceClassUnloading. The other is that there are very few classes to uninstall, but the time is also very long. This situation is generally too much ClassLoader or Codecache scan time is relatively long.

.. note::

    The condition that Class is unloaded is actually very demanding. It will only be unloaded when the corresponding class_loader is not alive. In other words, a class is unloaded with all classes under the same class_loader. All classes under a class_loader are unloaded at the same time. To achieve this condition, all classes loaded by this class_loader cannot have instances, and there can be no living objects in the heap with references to the class_loader. Another point, the operation of class unload is relatively "inefficient" in a sense, it will traverse all class_loader, and then determine whether class_loader is garbage through is_alive_closure, if it is garbage, it will actually mark all the corresponding class data. Was cleaned up.

    The above action is just to mark the class can be cleaned up, when you really clean up the metaspace, you need to further scan the stack and codecache to finally decide whether the metadata can be deleted.

    Therefore, there are two possible reasons for class unloading time. One is that the number of ClassLoaders is too much. The traversal of the class_loader list will take a long time. This stage is either CMS or G1 serial, except for optimization logic. Way. The other one is the scan of stack and codecache when metaspace is cleaned. Because the codecache is too large, it takes a long time. This part is not logically controlled, but G1 is optimized compared to CMS, which is to make the scan of codecache parallel. The class unload time in G1 Remark is better than CMS. This explains why sometimes the classes that are actually uninstalled are few, but Class Unload takes a long time. Interested students can refer to the Hotspot related source code of ClassLoaderDataGraph::clean_metaspaces() and ClassLoaderDataGraph::do_unloading().


.. note::

    Unlike CMS, G1 Log does not normally print the time of Remark in various stages such as Class Unload in Concurrent Cycle. Users can control the output by -XX:G1LogLevel=finest.

.. note::

    CMS Final Remark is to finalize and mark the live objects of the Old District, this stage is Stop-The-World. CMS used to be the Conccurrent stage. In the Conccurrent stage, Mutator is constantly updating and manipulating the young area. At this time, the live object marked by ConccurrentMark will miss some living objects. (For example, you have 10 rooms, you are from the first Looking into the 10th room to look for your glasses, hope to see the 10 houses you can find the glasses you forgot to put in, if you have a naughty child, secretly put in the process of looking for you In the case of the glasses, you can sneak it back to the room you have already found, then you will never find the glasses. Therefore, in the Final Remark stage, you need to scan the objects and members of these changes in the Old section of the Conccurrent stage through DirtyCard. Pointers to avoid certain missing objects, and the Young area and all thread stacks will be rescanned to avoid omissions. If there are many objects in the Young area, then the scanning of the Young area at this stage may be longer.

.. note::

    Class Unloading has a special kind of scenario that uninstalls the Lambda Class. Lambda Class is a very special Class. For example, if a Java class A defines a lambda expression in A's main method, javac will decide whether to generate the corresponding method according to the body of the expression. If the variable captured by the lambda expression is not Any member involved in any enclosing instance, javac will make the lambda expression body desuger a static lambda method. If the capture variable involves any instance member of enclosing instances, javac will de-sweet the lambda body into an instance lambda. Method, in some cases, such as list.filter (String::isEmpty) lambda expression without parameter list, javac will not add any method for A (JVM will call directly through the methodHandle of invokeVirutal).

    Lambda expressions can be divided into stateless and stateful. Stateless lambda expressions do not capture any local mutable variables in any context, while stateful lambda expressions capture mutable variables in context.

    Where the Lambda expression appears, javac compiles and generates the corresponding invokedynamic instruction. The bootstrap method of the parsing process of these instructions is usually LambdaMetafactory.metafactory. The metfactory delegates the runtime target method to a dynamic class constructor. Therefore, when the invokedymaic of a lambda expression is executed, an instance of a dynamic class is usually generated on the stack.

    The actual execution of a Lambda expression is actually performed by a dialog interface such as a dynamic class instance. Dynamic class generation is performed in the previous bootstrap method. The bootstrap method dynamically generates a Class using Unsafe.defineAnonymousClass when generating a CallSite instance. The dynamic class bytecode will include a call to the "de-sweet" method. A Lambda expression returns a type.

    In the JVM, the anonymous class is generated in a special way, each annoymous class will generate a separate ClassLoaderData, and the generated klass data will not be perceived by the SystemDictionary and host class loader, ClassLoaderData will have a flag to mark whether it is anonymous of. In the context of a Lambda expression, each invokedynamic generates a corresponding anonymous dynamic class. If the Lambda expression is used more, the number of anonymous class ClassLoaders will be more, and it will take longer to traverse ClassLoaderData when uninstalling.

    In addition, anonymous classes may have a certain impact on performance, because the class_loader_data of the anonymous class has only one class, and is not related to the host class loader. The probability of being unloaded is larger than that of the normal inner class, which may cause the class to be re-created load.


.. note::

    Invokedynamic provides the JVM with the ability to extend dynamic languages. Take invokevirtual as an example. invokevirtual is used to handle polymorphism. Its parameters will specify a method vtable slot according to the type. When the runtime is actually executed, it will read according to the specific call instance and the vtable slot offset. Take the corresponding specific method and then execute it. In the above process, the key part is the "decision" or "distribution" of the method. This decision/distribution process is determined by the JVM runtime based on the specific data of the calling instance and is not subject to user control. Invovekdyamic essentially opens a way for the user's java code to "determine" which method (MethodHandle) to use.

    When the invokedynamic instruction is executed, the first call will fall into the parsing process. The parsing will further parse the relative CONSTANT_Dynamic_info and the associated constant pool and call the Bootstrap method to determine the target method MethodHandle to be run. The calling process of the Bootstrap method is actually parsing. As part of the actual call parameters of the Bootstrap method, it is prepared by the JVM during the constant pool parsing process. Once the parsing is successful, the method that runs on the stack is used to execute the MethodHanlde to complete the entire invokedynamic instruction. The parsing process returns a CallSite instance and is then permanently bound to the dynamic call site. When multiple threads execute invokedynamic for the first time at the same time, it is possible that multiple CallSite instances will be parsed and generated in parallel, but the JVM will only select one for binding, and the others will be ignored. When the subsequent program is executed to the same dnvokedynamic instruction for the second time, the state of the call point has been changed from unlaced to laced, and the JVM will not re-execute the resolution process of the bootstrap. The JVM will directly obtain the real one based on the cached CallSite instance. The target method is called with the variables on the stack. The invocationdynamic parsing process is somewhat similar to class loading. When the program executes to a bytecode instruction, if the class of the parsing constant pool is found to be not loaded, the parsing and loading process of the class will be started. After the loading is completed, the real execution will be started. Bytecode instructions. From the working principle, both invokedymaic and class lazy loading have similarities.


Hybrid deployment environment impact
""""""""""""""""""""""""""""""""""""
Many Java applications are deployed on the docker container and are deployed on the same physical machine as other applications. Individual applications are likely to interact with each other. For example, different applications are bound to the same CPU, causing an application's CPU to be stolen by other busy applications, which will make the application GC pause sys time is relatively high. Users can check the configuration and indicators of the physical host, such as IO usage, free memory, CPU usage, sys time ratio, Cgroup CPU binding, and so on.

Trouble solving
^^^^^^^^^^^^^^^

The main reason for the promotion of too many objects in the Young District to cause YGC time is too long is to find ways to reduce the number of promotions. The logical flaw in business code is a common cause of a lot of promotion, such as the application not releasing objects in time. To solve this problem, the user needs to find out where the promotion object is distributed and solve it by reviewing the code. Unfortunately, there is no easy-to-use tool for helping users find out which of these promotions are and where they are distributed. AJDK will add the Trace function to the promotion object in the new JET (Java Event Trace) framework. Users can find out which objects are promoted and where to allocate them through zprofiler. In addition, if the user misconfigures -XX:MaxTenuringThreshold or the Young area is too small, it will also cause a lot of promotion. It is necessary to adjust the Young area appropriately and increase the threshold. The size setting of the Young area requires a more reasonable value to be set by the formula according to the GC situation. See here.Reference :ref:`这里<GCParamAnchor>`.

The YGC time caused by disk IO and operating system swap is long and needs to be solved by the operating system level.

One mitigation method for Weak/Soft/Phantom Reference is to enable -XX:+ParallelRefProcEnabled to enable multithreading to speed up the processing of Reference. But this may be a way to cure the symptoms. The most fundamental reason is to find out why there are so many Weak/Soft/Phatom References. Where are these Refrences assigned? Is there any possibility of optimization in design?


There are several ways to try G1 Update/Scan RS for a long time.

For Update RS, the first thing to consider is to adjust the size of -XX:G1HeapRegionSize. If the HeapRegion Size is larger, then the HeapRegion cross-region reference will be less, so the workload of updating RemSet is also smaller, thus shortening Update. RS time, but on the other hand, doing so will increase the workload of copying and promotion, so this approach requires a comprehensive consideration.

Secondly, consider the adjustment of -XX:G1RSetUpdatingPauseTimePercent and -XX:G1ConcRefinementThreads parameters, G1RSetUpdatingPauseTimePercent to control the percentage of Update RS in the whole STW time of GC, G1ConcRefinementThread control the number of threads of Concurrent Refine, the purpose of doing this is to make Update RS work as much as possible Concurrent This is done, which reduces the workload of the Update RS at the time of GC and shortens the time.

Finally, consider the -XX:-ReduceInitialCardMarks parameter to turn off the large object Concurrent RS Update optimization. When the VM processes large objects, it will try to combine the separate processing of multiple Dirty Card Pointers into one batch. This optimization may also be in some scenarios. Pressure on the GC's Update RS can be considered for shutdown.


For Scan RS, consider -XX:G1RSetRegionEntries for improvement. Since each HeapRegion of G1 maintains a list of RemSets, this memory actually appears to the VM as an additional overhead. To control this overhead, the VM introduces an optimization called remembered set coarsening. This optimization is to save space when the VM finds that a ReapSet List of a HeapRegion is too long. This compression will cause the Region corresponding to each RemSet pointer to become larger, so that the space scanned by the Scan RS becomes larger when GC is scanned. Then, the time is getting longer. This can be confirmed in the GC log by -XX:G1SummarizeRSetStatsPeriod. The solution is to increase -XX:G1RSetRegionEntries to avoid remembered set coarsening if there is room for memory.

If none of the above approaches can be improved, then the program itself needs to be optimized to reduce cross-regional references. AJDK's newly developed JET feature can analyze cross-region references and can help analyze such issues.

The long-term solution of ParNew/CMS Older-Gen scaning is similar to the solution of G1 Scan RS. Since there are no special parameters to control the behavior, optimizing the code is often the only choice.

CMS Final Remark is long, and you need to analyze the specific problem. If it is Parallel Rescan, you can add -XX:+CMSScavengeBeforeRemark, so that the Young GC will be triggered before the CMS Final Remark to ensure the size of the young area is as small as possible, thus reducing the CMS. Final Remark time. Incidentally, -XX:+ScavengeBeforeFullGC as an analogy parameter should also be added. This parameter can be used to perform Young GC before FullGC, thus reducing the overhead of single-threaded FullGC and speeding up the Full GC. If the Class Unload time is long, you can consider changing G1. In addition, you may need to optimize the code and reduce the number of class loaders. If the WeakReference is long, you can refer to the previous Weak/Soft/Phantom Reference processing method.

The impact of the hybrid deployment environment on GC pause times needs to be addressed at the OS and container scheduling level.
