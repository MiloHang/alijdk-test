.. _HeapIncrement:

Abnormal memory increase in the on-heap memory
----------------------------------------------

The most common reason of abnormal memory increase in the on-heap memory is memory leakage, mainly due to the continue increase of JVM-managed heap caused by improper application usage. If the user does not limit the size of the heap by the usage of parameter ``-Xmx``, the user will find that the RSS is getting larger and larger by the ``top`` command and will be killed by kernel OOM until exceeding the upper limit of the operating system. If the user limits the heap size by ``-Xmx``, the log will report an OOM error.

In addition, the sudden increase in business pressure will also cause an abnormal increase in the on-heap memory.

The phenomenons of issues
^^^^^^^^^^^^^^^^^^^^^^^^^

The most phenomenons of issues:
- If the user does not use ``-Xmx`` to limit on-heap memory, and the top command shows that the Java process RSS is significantly exceed the reasonable range and continues to grow.
- If the user does not use ``-Xmx`` to limit on-heap memory, and the Java process inexplicably disappears, and although the startup script has set ``ulimit -c unlimited``, it does not leave any core files in the disk.
- If the user limits the on-heap memory size by ``-Xmx``, and the Java process log reports an ``OutOfMemory`` error.
- GC logs are frequently GC, and GC is not efficient, can not reclaim memory.

The reasons of issues
^^^^^^^^^^^^^^^^^^^^^
The followings are the reasons of issues:

- Increased business pressure.
- Java memory leakage.

When the performance of the on-heap memory is suspected to be leaked, whether increased business pressure or not should be first be checkout. If the business pressure is relatively large, CPU usage and network IO activity are relatively high, it will often show similar symptoms such as OOM and other on-heap memory leaks.

Another possibility is a Java object leak caused by a logic error in the code.

Troubleshooting
^^^^^^^^^^^^^^^

The troubleshooting of the on-heap is relatively simple. The main idea is to analyze the leak point, find the clue, and then modify the code.

Increased business pressure
"""""""""""""""""""""""""""

This situation is often overlooked, and these two steps can be used to initially confirm such problems:

- System resource usage is increasing, monitoring tools show that the CPU usage, network IO activity and traffic volume increase significantly.
- A large number of Java threads are running, and the user can confirm the number of threads and the current thread status through the JDK's own ``jstack`` command.

Each thread consumes a certain amount of memory when executing business logic. When the number of threads increases and the system pressure increases, the overall memory usage will increases, which puts a lot of pressure on the GC. Even if the number of threads does not change, when the business is busy, each thread will handle more business and consume more memory, which may cause the GC to slow down or even Full GC due to pressure increase.


Java object leakage
"""""""""""""""""""

It is mainly caused by Java logic errors, there are kinds of performance, so the key is to analyze the leak points. There are a variety of tools in the Java ecosystem to support for troubleshooting, such as zprofiler and MAT, which allows you to analyze which surviving objects are currently in the heap, and which objects are directly or indirectly used by the GC Root so that cannot be recycled.

The followings can be used to confirm this kind issue:

- First, generate a HeapDump of the Java process through the ``jmap`` command, and then upload the HeapDump to ``zprofiler/eclipse-mat`` for analysis.
- Then use the class view and memory leak report in zprofiler/eclipse-mat to see which objects occupied the heap in the entire Java process. If you find that Retained Heap of a certain class is more than 10%, it often means such problems.
- It can also be combined with the GC log for further analysis. If GC is found to occur frequently in the GC log, and the GC efficiency is very poor. In addition, there are still a large number of unreasonably large surviving objects after each CMS GC, Mixed GC or Full GC, which can be further determine this issue.

When you find that the memory usage of a certain class is very unreasonable, you can check the following highlights by zprofiler/eclipse-mat:

- Find out these objects are referenced by which objects.
- Find out what the reference links of these objects to the GC Root are. By analyzing the inter-object relationship information and the object memory content on the reference link, the user should be able to find the clues of the leak.

A common reason of leakage is the assignment of very large objects, such as large arrays. AJDK8 supports two parameters ``-XX:+TraceG1HObjAllocation`` (support G1 policy only) and ``-XX:ArrayAllocationWarningSize=xxx`` (support CMS policy only, 512M by default). With these two parameters, you can print out the thread stack when assigning large objects for easy troubleshooting.


If the leak point of the application is obvious, the leak is concentrated on a special class, it can be checked by the type distribution histogram of the surviving object. There is no need for expensive HeapDump analysis. The histogram type distribution can be displayed by using the following jmap command.

.. code-block:: bash

    jmap histo:live $PID

For the CMS GC strategy, AJDK8 also has a parameter ``-XX:+PrintYoungGenHistoAfterParNewGC`` that prints the type distribution histogram through the standard output on the next ParNew GC. The command format is as follows:

.. code-block:: bash

    jinfo -flag +PrintYoungGenHistoAfterParNewGC $PID


Troubleshooting
^^^^^^^^^^^^^^^

Problems caused by increased business pressure can only be solved by increasing memory and optimizing the design. If the increase of memory is not allowed by the conditional restrictions, you can try to troubleshooting user logic errors to guide the optimization design. Although jmap and zprofiler can not find the error point, it can also point out some clues to the optimization.

If it is the problem of Java object leaks, the developer needs to solve the problem by review code according to the clues provided by zprofiler. It depends on the developer's deep understanding of the Java application business logic and middleware system.

