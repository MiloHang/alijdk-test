应用暂停异常
------------

由于JVM会暂停mutator线程来执行各种各样的虚拟机内部任务，如果暂停时间比较长，会对应用的RT延迟有比较严重的影响。

故障表现
^^^^^^^^

应用暂停时间长，往往有这么几个表现：

- 业务调用超时
- 通过打印或者Profling发现某些很简单的方法执行时间不合理的长，比如一次System.getCurrentMills()有几百ms
- -XX:+PrintGCApplicationStoppedTime参数显示暂停时间长

故障原因
^^^^^^^^

常见原因:

- CountedLoop的JIT优化
- 操作系统状态异常导致Safepoint进入时间慢
- Safepoint进入后的清理时间慢 
- Safepoint过于频繁

故障排查
^^^^^^^^

CountedLoop的JIT优化
""""""""""""""""""""
CountedLoop的JIT优化有可能导致进入Safepoint时间慢从而影响应用暂停。在循环中，JIT Compiler应该在循环的回边中生成Safepoint Poll的指令，但为了提高性能，JIT会去除Counted Loop中Safepoint Poll的相关指令。Counted Loop是指循环的初始值，边界，步长不会被循环改变的Loop。如果这种Loop的循环次数很多，代码指令一直没有机会获得Safepoint的通知，就会进入Safepoint很慢。下面的代码片段显示了几个常见的循环模式，代码注释里标注了哪些是Counted Loop，哪些不是。

.. code-block:: cpp

    // 1. counted = reps is int/short/byte
    for (int i = 0; i < reps; i++) {
        // You had plenty money, 1922
    }

    // 2. Not counted
    for (int i = 0; i < int_reps; i+=2) {
        // You let other women make a fool of you
    }

    // 3. Not counted
    for (long l = 0; l < int_reps; i++) {
        // You're sittin' down and wonderin' what it's all about
    }

    // 4. Should be counted, but treated as uncounted
    int i = 0;
    while (++i < reps) {
        // You ain't got no money, they will put you out
    }

    // 5. Should be counted, but treated as uncounted
    while (i++ < reps) {
        // Why don't you do right, like some other men do
    }

    // 6. Should be counted, and is!
    while (i < reps) {
        // Get out of here and get me some money too
        i++;
    }


想要确认这个问题，可以通过-XX:+PrintSafepointStatistics参数观察spin的时间，spin表示VMThread轮询所有running的线程等待它们停止所花费的时间，如果这个时间很长，就可以初步判断是这个问题。通过-XX:TraceSafepoint和verbose:gc参数，可以在GC日志中打印出Safepoint的相关具体动作，参数打开时VMThread轮训running线程的时候会打印出线程的相关信息，如果发现某个线程在轮询过程中长时间是running的状态，就可以进一步确定是哪个线程。再进一步通过jstack和perf-map-agent，就可以进一步发现出问题的具体方法，从而为修正创造机会。另外JDK还有一个SafepointTimeout参数可以设置一个超时时间，一旦进入Safepoint发生超时，就会在标准输出打印出相关的线程信息。

AJDK有一个JVM参数-XX:PrintStacksOnSafepointTimeout，在进入Safepoint发生超时后直接打印出栈信息，定位问题会更加方便。

操作系统异常导致Safepoint进入时间慢
"""""""""""""""""""""""""""""""""""

Java的GC需要所有mutator线程进入safepoint才能进行回收工作，如果有线程进入safepoint的时间比较长，那么就会导致整个应用暂停时间长，而且这部分暂停往往无法反映到gc日志里，往往会被排查人员忽略。

由于操作系统异常导致线程无法快速进入safepoint的原因有很多，要想确认safepoint的问题，可以尝试打开参数-XX:+PrintGCApplicationStoppedTime和-XX:+PrintSafepointStatistics, -XX:PrintSafepointStatisticsCount。通过这个参数可以得到进入safepoint的线程的一些统计信息，比如等待block的线程进入safepoint花了多少时间，spin等待runable线程进入safepoint花了多少时间，所有mutator线程进入synchronized状态(stopped)花了多少时间, 每次GC的STW的时间统计等等。有了这些数据就可以判断是否是safepoint引起的问题。如果使用的是AJDK，可以通过设置+PrintSafepointStatisticsTimeout，把超时的safepoint的线程栈打印出来，这对debug问题也会有很大的帮助。

由于操作系统异常导致safepoint进入慢通常是用户无法控制的。一个常见的原因就是swap, swap大量发生会使得Java mutator线程的内存访问被内核阻塞，自然也无法很快被safepoint停下来。这种场景下，可以同时观察PrintSafepointStatistics输出的spin, block, sync字段，如果spin, block，sync都比较长，可以帮助确认这个问题。

.. note::

    mmap可以映射文件向用户返回address，一个常见的误解是，mutator线程似乎可以直接通过地址加偏移量的方法来访问这块空间，这种访问有可能因为磁盘IO的原因被内核延迟，从而对进入safepoint也会有影响。但实际上这种情况一般并不会发生，因为mutator线程在Java层面无法通过地址加偏移量来获得像C语言一样的访问，JVM需要考虑数组Object Header，因此JVM访问实际产生的偏移会比C语言要大一些，是不符合mmap的访问要求的。实际上，Java对mmap地址空间的访问只能通过Unsafe的native API来进行。而native方法是不会对JVM进入safepoint产生影响的，原因参见本小节后面的段落。

除了影响操作mutator进入safepoint，大量的swap也会影响hotspot自身进入safepoint的执行，hotspot VMThread在notify safepoints之前，会进行一个如下的serialize_thread_state的操作，这个操作的目的是利用mprotect()系统调用来达到memory barrier指令的效果。通过更改memory_serialize_page的页面属性，就可以强制cpu同步cache line和内存，将thread的一些状态信息强制同步刷新，从而为safepoint notify做准备。

.. code-block:: cpp
    
    // Serialize all thread state variables
    void os::serialize_thread_states() {
      // On some platforms such as Solaris & Linux, the time duration of the page
      // permission restoration is observed to be much longer than expected  due to
      // scheduler starvation problem etc. To avoid the long synchronization
      // time and expensive page trap spinning, 'SerializePageLock' is used to block
      // the mutator thread if such case is encountered. See bug 6546278 for details.
      Thread::muxAcquire(&SerializePageLock, "serialize_thread_states");
      os::protect_memory((char *)os::get_memory_serialize_page(),
                     os::vm_page_size(), MEM_PROT_READ);
      os::protect_memory((char *)os::get_memory_serialize_page(),
                     os::vm_page_size(), MEM_PROT_RW);
      Thread::muxRelease(&SerializePageLock);
    }

根据 `社区的讨论 <https://bugs.openjdk.java.net/browse/JDK-8187809>`_ ，之所以不直接使用memory barrier指令，是因为Oracle发现memory barrier指令比较昂贵，在某些CPU上开销是serialize_thread_states的两倍多。当然设计上JDK也采取了一些trick的方式来避免硬件上同一个cache line的false sharing的逻辑，比如不同线程通过thread pointer的地址来做hash从而在page中进行散列避免不同线程的thread state变量命中同一cache line(参见JDK MacroAssembler::serialize_memory的实现)。

os::protect_memory会调用到os的mprotect，在物理内存不够，大量swap的情况下，mprotect会被拖住很长时间，从而导致safepoint进入慢。从-XX:+PrintSafepointStatistics来看你会发现尽管spin和block时间为0，但sync时间比较长。具体原因请参见，`kernel bug 5493 <https://bugzilla.kernel.org/show_bug.cgi?id=5493>`_ 。

另外, 如果Java进程存在大量的mmap后的指针访问，这也会影响到hotspot内部的mprotect, 这是因为mprotect有flush tlb的操作，这里面会持有spin lock，在mmap频繁发生缺页异常的时候，会被锁block。

另外机器上别的进程对CPU的干扰也要考虑。这种情况会导致部分线程饥饿法要花比较长的时间获得执行机会进入safepoint。如果CPU导致VMThread饥饿，那么表现就是sync时间长，spin以及block时间短。如果是CPU导致Java Mutator线程饥饿，那么表现就是sync，spin，block时间都长。

另外，有的同学看到JDK源码里os::serialize_thread_states里面会尝试持有一个叫SerializePageLock的mutex，可能会质疑，如果这个mutex加锁竞争也会导致操作变长从而引起sync时间长的现象啊？一眼看起来觉得这个质疑很有道理，但实际上担心是多余的。在很早的JDK实现里，是没有这把锁的。当VMThread决定要进行safepoint notice的时候，在dirty polling page之前，VMThread会按照os::serialize_thread_states的逻辑对memory_seriable_page先mprotect page read，然后mprotect page rw，如果没有这把锁的保护，当mptrotect page read执行完成以后，如果这时其它mutator线程进行Thread state transition执行memory_seriable_page的写操作时，由于page已经设置为只读属性，那么此时就会触发SIGSEGV, 然后不断得无限循环尝试写这个memory_seriable_page，这个无限循环有可能会抢占CPU，导致VMThread一直饥饿，始终无法执行下一步近在咫尺的mprotect page rw，这样就陷入一个恶性循环，VMThread始终hang在os::serialize_thread_states里，系统CPU飙高。后来JDK解决了这个问题，加入了这把锁，在SignalHandler里也会检查这把锁，这样就解开了这个循环。由于只有SignalHandler里会和os::serialize_thread_states竞争这把锁，而SignalHandler是一种非常不常见的操作，因此这个锁不会产生激烈竞争，这个担心也就无从谈起了。

.. note::

    safepoint指的是一个指令执行范围，线程运行到这个范围被认为是“安全”的，线程到达safepoint后，别的线程可以安全得观测和操作“安全”线程的相关状态。JIT编译时会在指令中插入一些额外的poll safepoint flag相关的指令，这样Java线程就可以以一种“合理”的间隔来检查safepoint flag从而决定线程是否进入safepoint。通常来讲，JIT会在这么几个地方插入poll safepoint flag相关的指令：

    - Interpreter执行两条字节码之间
    - C1/C2编译的非CountedLoop的回边(后面会有CountedLoop具体的解释) 
    - C1/C2编译的Method Exit。注意，如果方法被inline，编译器会移除对应的Method Exit部分的poll safepoint flag

    如果你打开-XX:+PrintAssembly，可以在标准输出中搜索“poll” 和 “poll return”等字符，这些字符作为coments出现的地方就是poll safepoint flag相关的指令。

    JNI是一种特殊的方法，JNI方法运行的时候本身就被认为是处在safepoint，因为JNI方法本身的运行在JVM看来就已经是“安全”的，没有必要花费精力把JNI的执行打断。但当JNI方法退出返回到Java层时，会进行poll safepoint flag来决定是否暂停JNI方法的执行。

Safepoint进入后的清理时间慢 
"""""""""""""""""""""""""""

在所有线程都进入Safepoint之后, VMThread会执行一些清理动作，比如Deflate Idle Monitor，更新inline cache, 爬栈标记nmethod热度，rotation gc日志等等（具体逻辑参见Hotspot源码的SafepointSynchronize::do_cleanup_tasks函数）。这些清理动作也有可能比较慢导致超时故障。用户可以通过打开-XX:TraceSafepointCleanupTime参数来确认这个问题。

safepoint过于频繁
"""""""""""""""""

safepoint不仅仅只是GC引起的，一些特殊的场景也会引起safepoint，比如RevokeBiasLock。有些应用由于场景的特殊性，因此这个BiasLock的优化不仅没有起到优化的作用，反而造成的大量的RevokeBias，频繁触发safepoint。这个问题可以通过-XX:+PrintSafepointStatistics观察GC日志加以确定。

.. note::
    
    BiasLock是VM针对Java Lock做的一个特殊优化。通常来讲，Lock的实现一般基于OS提供的mutex等来实现，但这种os层面的mutux会涉及到内核状态的切换，执行开销比较大。然而一些研究表明大部分Java Lock并不会发生竞争，基于这个假设，VM实现了一个大胆的Thin Lock的优化，这种优化用一个比较廉价的Thread ID CAS方式来加锁，如果CAS失败，则意味着会竞争，会inflation成一个OS层面的重量锁。如果多线程的锁并没有发生竞争(不同线程共享的同一把锁保护的代码在执行序列上发生重叠)，Thin Lock相比monitor而言执行效率大大提升。然而VM并不满足于Thin Lock，由于Thin Lock每次加锁和释放动作都需要执行CAS操作，尽管CAS相比OS的mutex已经比较廉价了，但VM更激进得实现了一个Bias Lock的优化。Bias Lock这个优化的方法是想办法避免每次加锁和释放都执行CAS操作，它假设如果应用的大部分场景下是锁只会被某一个Thread来持有和释放，如果是这种场景那么就没有必要每次lock和unlock都执行CAS指令，而是在Thread第一次lock的时候执行CAS，然后一直不释放，直到发现这个lock被别的线程使用，才进行revoke baised的动作，revoke baised的后果是让线程通过CAS rebias到另外一个revoking thread（CAS success）或者revoke到thin lock上去(CAS failed)。需要注意的是RevokeBiased的操作需要进safepoint。



.. note::

    优化的本质就是一种投机，JVM对于锁的优化充分体现了这一点。JVM的开发者发现，在大多数场景下，锁只会被一个线程使用，即使锁被多个线程使用，真正的竞争也是很少的，不同线程的被锁保护的关键逻辑执行也是交错进行不会重叠。基于这个假设，JVM的锁实现充分投机，它先投机锁只会被一个线程使用的场景，用非常廉价的普通内存操作来表达锁的操作，如果这种情况的投机失败了，JVM会尝试投机锁不会真正发生竞争的场景，用一个比较廉价的CAS操作来表达锁的操作，如果这个投机也失败了，JVM才会陷入OS的锁。如果假设成立，JVM的投机会带来性能上显著的收益，如果假设失败，这些投机相比直接使用OS的锁反而会带来额外的开销。如果应用的竞争确实比较激烈，用户需要主动关闭这些投机优化。

故障解决
^^^^^^^^
由于操作系统异常引起的进入safepoint慢，其原因可能有很多，比如swap，mmap大量缺页操作，disk io高，以及noisy neigbour等等，这些需要系统工程师从系统层面加以解决，JVM层面无法做更多的事情。如果确认是mprotect执行慢造成的，可以尝试利用UseMemBar参数来绕过, 但这治标不治本。另外一部分则有可能是由于JIT对Counted Loop的优化引起的

如果是countedLoop的JIT优化导致的safepoint慢，有这么几种方法可以尝试解决：

- 考虑使用JVM参数-XX:+UseCountedLoopSafepoints，这个参数会确保JIT编译器在每次CountedLoop的回边加入Safepoint Polling，但有可能在某些JDK版本上造成Crash，参见 `Bug <https://bugs.openjdk.java.net/browse/JDK-8161147>`__ 。而且这个有可能会牺牲一点性能。
- 通过-XX:CompileCommand='exclude,binary/class/Name,methodName'参数来禁止有问题方法的编译，同样也会损失一部分性能。
- 重写你的代码，让编译器不要把它识别成CountedLoop，或者干脆手动通过Thread.yield()函数调用来插入Safepoint。

Safepoint进入后的清理时间慢的解决办法比较复杂，如果是rotate gc日志慢，可能需要重定向gc日志。其它原因需要具体问题具体分析。

safepoint过于频繁如果是BiasedLock造成的，VM参数-XX:-UseBiasedLocking可以解决。其它场景造成频繁safepoint需要进一步分析。


