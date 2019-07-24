Application pause Exception
---------------------------

Since the JVM will suspend the mutator thread to execute various virtual machine
internal tasks. If the pause time is too long, it will have a serious impact on
the RT delay of the application.

Fault performance
^^^^^^^^^^^^^^^^^

Long-time pause of application often have several performances:

- Business call timeout
- By printing or Profling, it is found that some simple methods are unreasonably
  long, such as one call of ``system.getCurrentMills()`` with hundreds of ms
- Parameter ``-XX:+PrintGCApplicationStoppedTime`` shows long pause time

Cause of fault
^^^^^^^^^^^^^^

Common reasons:

- CountedLoop JIT Optimization
- Operating system status exeception causes slowly entry of Safepoint
- The cleaning time after entry of Safepoint is slow
- Safepoint is too frequent

Troubleshooting
^^^^^^^^^^^^^^^

CountedLoop JIT Optimization
""""""""""""""""""""""""""""
CountedLoop JIT optimization has the potential to cause slow entry into Safepoint and thus affect application suspension. In the loop, JIT Compiler should generate Safepoint Poll instructions in the loop's back edge, but to improve performance, JIT removes the instructions from Safepoint Poll in the Counted Loop. Counted Loop refers to the loop whose initial value, boundary, and step size are not changed cyclically. If the loop has a lot of loops and the code instruction cannot have a chance to get Safepoint notifications, it will be slow to get into Safepoint. The following code snippet shows several common loop patterns, which are labeled as Counted Loop and which are not.

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


To confirm the problem, you can observe the spin time with the parameter ``-XX:+PrintSafepointStatistics``. Spin indicates that VMThread polls all running threads for the time it takes to stop. It can be judged initially that this is the problem if the time is long. Through the ``-XX:TraceSafepoint`` and ``verbose:gc`` parameters, you can print out the specific actions of Safepoint in the GC log. When the parameter is opened, the VMThread will print the relevant information of the thread when the rotation thread is running. If it is found that a thread is running for a long time during the polling process, you can further determine which thread it is. Further through jstack and perf-map-agent, you can further discover the specific method of the problem, thus creating opportunities for revision. In addition, the JDK also has a SafepointTimeout parameter that can set a timeout period. Once a timeout occurs in Safepoint, the relevant thread information will be printed on the standard output.

AJDK has a JVM parameter ``-XX:PrintStacksOnSafepointTimeout``, which prints out the stack information directly after entering Safepoint timeout, which makes the positioning problem more convenient.

Operating system status exeception causes slowly entry of Safepoint
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

Java GC requires all mutator threads to enter the safepoint in order to recover. If a thread enters the safepoint for a long time, it will cause the entire application to pause for a long time, and such pause is often not reflected in the gc log, and will be ignored by the investigator.

There are many reasons why a thread cannot quickly enter a safepoint due to an operating system exception. To confirm a safepoint problem, try opening the parameters ``-XX:+PrintGCApplicationStoppedTime`` and ``-XX:+PrintSafepointStatistics``, ``-XX:PrintSafepointStatisticsCount``. Through the parameter, you can get some statistics about the thread entering the safepoint, such as how long it takes for the thread waiting for the block to enter the safepoint, how long it takes for the spin to wait for the runable thread to enter the safepoint, and how much time is spent by all the mutator threads entering the synchronized state (stopped) , every time the GC STW time statistics and so on. With this data, you can determine if it is a problem caused by safepoint. If you are using AJDK, you can print out the thread stack of the timeout safepoint by setting ``+PrintSafepointStatisticsTimeout``, which will also help to debug the problem.

The slow entry of a safepoint due to an operating system exception is usually beyond the control of the user. A common reason is swap, a large number of swaps will cause the memory access of the Java mutator thread to be blocked by the kernel, and reasonably it will not be immediately stopped by the safepoint. In this scenario, you can observe the spin, block, and sync fields of the ``PrintSafepointStatistics`` output at the same time. If the spin, block, and sync are long, it can help confirm this problem.

.. note::

    Mmap can map files to return address to the user. A common misconception is that the mutator thread seems to be able to access the space directly by address plus offset. This access may be delayed by the kernel due to disk IO, thus Entering safepoint will also be influential. But in fact this situation does not generally happen, because the mutator thread can not get access through the address plus offset like C language at the Java level, JVM needs to consider the array Object Header, so the actual offset generated by the JVM access will be larger than the C language and it does not meet the access requirements of mmap. In fact, Java access to the mmap address space can only be done through Unsafe native API. The native method does not affect the JVM entering safepoints. See the paragraphs later in this section for the reasons.
	
In addition to affecting the operation of the mutator into the safepoint, a large number of swaps will also affect the hotspot itself into the safepoint implementation, hotspot VMThread will perform a ``serialize_thread_state`` operation before the notify safepoints, the purpose of the operation is to use the system call ``mprotect()`` to achieve the effect of the memory barrier instruction. By changing the page attribute of the ``memory_serialize_page``, cpu can be forced to synchronize the cache line and memory, and force some state information of the thread to be synchronously refreshed, thus preparing for the safepoint notify.

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

According to `the community discussion <https://bugs.openjdk.java.net/browse/JDK-8187809>`_ , the reason why the memory barrier instruction is not used directly is that Oracle finds that the memory barrier instruction is expensive, and the overhead on some CPUs is more than twice of ``serialize_thread_states``. Of course, the JDK design also adopts some tricks to avoid the logic of false sharing of the same cache line on the hardware. For example, different threads hash in the page to avoid state variables of different threads hit the same cache line through the address of the thread pointer. (see the JDK ``MacroAssembler::serialize_memory implementation``).

``Os::protect_memory`` will call mprotect of os. In case of insufficient physical memory and a large amount of swap, mprotect will be dragged for a long time, which will cause the safepoint to enter slowly. From ``-XX:+PrintSafepointStatistics`` you will find that although the spin and block times are 0, the sync time is longer. For details, see `kernel bug 5493 <https://bugzilla.kernel.org/show_bug.cgi?id=5493>`_ .

In addition, if the Java process has a large number of pointer accesses after mmap, it will also affect the mprotect inside the hotspot, because mprotect has the operation of flush tlb, which will hold the spin lock, when the mmap frequently has a page fault exception, the block will be locked.

In addition, other processes on the machine should also consider the interference of the CPU. The situation will cause some thread starvation methods to take a long time to get execution opportunities into safepoint. If the CPU causes VMThread to starve, then the performance is long-time sync, short-time spin and block. If the CPU causes the Java Mutator thread to starve, then the performance is long-time sync, spin, and block.

In addition, the ``os::serialize_thread_states`` in the JDK source code will try to hold a mutex called ``SerializePageLock``. if this mutex lock competition will cause the operation to become longer and cause long-time sync phenomenon? At first glance, it seems that this question is very reasonable, but in fact it is redundant. In the early JDK implementation, there was no such lock. When VMThread decides to perform safepoint notice, VMThread will mprotect page read to ``memory_seriable_page`` according to the logic of ``os::serialize_thread_states`` firstly, then mprotect page rw, if there is no protection of this lock, when mtrortect page read is executed, if other mutator threads perform the Thread state transition to execute the ``memory_seriable_page`` write operation, since the page has been set to the read-only property, then SIGSEGV will be triggered at this time, and then an infinite loop will continue to try to write this ``memory_seriable_page``. The infinite loop may seize the CPU, causing VMThread to be hungry, and the next mprotect page rw, which is close to the next step, cannot be executed. This is a vicious circle. VMThread is always in ``os::serialize_thread_states``, and the system CPU is high. Later, the JDK solved the problem, added the lock, and checked the lock in the SignalHandler, thus unlocking the loop. Since only SignalHandler will compete with ``os::serialize_thread_states`` for this lock, and SignalHandler is a very uncommon operation, this lock will not cause fierce competition, and this worry will not be discussed.

.. note::

    Safepoint refers to the scope of an instruction execution. The thread running to this range is considered to be "safe". After the thread reaches the safepoint, other threads can safely observe and manipulate the relevant state of the "safe" thread. When the JIT compiles, it inserts some additional poll safepoint flag into the instruction, so that the Java thread can check the safepoint flag at a "reasonable" interval to determine whether the thread enters the safepoint. In general, JIT will insert the instructions related to the poll safepoint flag in such places:

    - Interpreter performs between two bytecodes
    - The back edge of non-CountedLoop compiled by C1/C2 (there will be a specific explanation of CountedLoop) 
    - Method Exit compiled by C1/C2. Note that if the method is inline, the compiler will remove the poll safepoint flag from the corresponding Method Exit section

    If you turn on ``-XX:+PrintAssembly``, you can search for the characters "poll" and "poll return" in the standard output. These characters appear as the instructions related to the poll safepoint flag.

    JNI is a special method. When the JNI method is running, it is considered to be in safepoint. Because the JNI method itself is running "safe" in the JVM, there is no need to spend the effort to interrupt the execution of JNI. . However, when the JNI method exits and returns to the Java layer, the poll safepoint flag is used to determine whether to suspend the execution of the JNI method.

The cleaning time after entry of Safepoint is slow
""""""""""""""""""""""""""""""""""""""""""""""""""

After all threads have entered Safepoint, VMThread will perform some cleanup actions, such as Deflate Idle Monitor, update inline cache, climb stack tag nmethod heat, rotation gc log, etc. (see the ``SafesSynchronize::do_cleanup_tasks`` function of Hotspot source for specific logic). These cleanup actions may also be slower and cause timeout failures. The user can confirm this by opening the parameter ``-XX:TraceSafepointCleanupTime``.

Safepoint is too frequent
"""""""""""""""""""""""""

Safepoint is not just caused by GC, some special scenes will also cause safepoint, such as ``RevokeBiasLock``. Due to the particularity of the scene, some applications of BiasLock not only cannot play an optimization role, but have a large number of RevokeBias, which frequently trigger safepoint. The problem can be determined by observing the GC log with ``-XX:+PrintSafepointStatistics``.

.. note::

    BiasLock is a special optimization of the VM for Java Lock. Generally speaking, the implementation of Lock is generally implemented based on the mutex provided by the OS, but the mutux of the os layer involves the switching of the kernel state, and the execution overhead is relatively large. However, some studies have shown that most Java Locks do not compete. Based on this assumption, VM implements a bold Thin Lock optimization, which is locked with a cheaper Thread ID CAS method. If CAS fails, then It means competition, it will be inflation into an OS level weight lock. If multi-threaded locks do not compete (the same lock-protected code shared by different threads overlaps in the execution sequence), Thin Lock is much more efficient than monitor. However, VM is not satisfied with Thin Lock. Since Thin Lock needs to perform CAS operations every time it locks and releases, although CAS is cheaper than OS mutex, but the VM is more radical to implement a Bias Lock optimization. Bias Lock's optimization method is to find a way to avoid performing CAS operations every time you lock and release. It assumes that if most of the application's scenarios are locked, it will only be held and released by a certain Thread. If this is the case, then There is no need to execute the CAS command every time lock and unlock, but the CAS is executed when the Thread is locked for the first time, and then it is not released until it is found that the lock is used by other threads before the revoke baised action, revoke baised The consequence is to have the thread pass the CAS rebias to another revoking thread (CAS success) or revoke to the thin lock (CAS failed). It should be noted that the operation of RevokeBiased requires a safepoint



.. note::

    The essence of optimization is speculation, and the JVM's optimization of locks fully demonstrates this. JVM developers have found that in most scenarios, locks are only used by one thread. Even if the lock is used by multiple threads, the real competition is very small. The key logic execution of the lock protection of different threads is also interleaved. Will overlap. Based on this assumption, the JVM's lock implementation is fully speculative. It first uses the scenario where the speculative lock is only used by one thread, and uses very cheap ordinary memory operations to express the lock operation. If the speculation fails in this case, the JVM will try to speculate. The lock does not really compete with the scene, using a cheaper CAS operation to express the lock operation, if the speculation fails, the JVM will fall into the OS lock. If the assumption is true, the JVM's investment opportunities bring significant performance gains. If the assumption fails, these speculations will incur additional overhead compared to using the OS lock directly. If the application's competition is really fierce, users need to actively turn off these speculative optimizations.

Fault resolution
^^^^^^^^^^^^^^^^
Due to the abnormality of the operating system caused by the slow entry into the safepoint, there may be many reasons, such as swap, mmap large page fault operation, high disk io, noisy neigbour, etc. These need system engineers to solve from the system level, JVM level can not do more More things. If the confirmation is caused by the slow execution of mprotect, you can try to use the UseMemBar parameter to bypass, but it will not cure the problem. The other part may be due to JIT optimization of the Counted Loop.

If the safepoint caused by the JIT optimization of countedLoop is slow, there are several ways to try to solve it:

- Consider using the JVM parameter ``-XX:+UseCountedLoopSafepoints``, this parameter will ensure that the JIT compiler will add Safepoint Polling every time CountedLoop is back, but it may cause Crash on some JDK versions, see `Bug <https://bugs.openjdk.java.net/browse/JDK-8161147>`__ . And it may sacrifice a bit of performance.
- The ``-XX:CompileCommand='exclude,binary/class/Name,methodName'`` parameter is used to disable compilation of problematic methods, as well as some performance loss.
- Rewrite your code so that the compiler doesn't recognize it as CountedLoop, or simply insert Safepoint manually via the function call ``Thread.yield()``.

The slower cleanup solution after Safepoint entry is more complicated. If the rotate gc log is slow, you may need to redirect the gc log. Other reasons require specific analysis of specific issues.

Safepoint is too frequent. If it is caused by BiasedLock, the VM parameter ``-XX:-UseBiasedLocking`` can be solved. Other scenarios cause frequent safety points require further analysis.
