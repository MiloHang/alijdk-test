.. _OffHeapIncrement:

Off-Heap Memory Increase Abnormality
------------------------------------

The abnormal increase of the off-heap memory will cause the Java process to rise abnormally outside the Heap, and reach the upper threshold of the JVM or the operating system, causing the system index to be abnormal. For example, if the JVM sets parameters such as MaxMetspaceSize or MaxDirectMemorySize, this leak will cause an OOM error.

A common cause of abnormal increase in off-heap memory is leakage. Leakage usually means that the JVM cannot release memory which is no longer used due to incorrect use. In addition, when the program uses too much memory or the JVM configuration is unreasonable, it will also cause an abnormal increase in off-heap.

Fault Performance
^^^^^^^^^^^^^^^^^

Common faults in the case of abnormal off-heap memory increase are as follows:

- The system top command shows that the Java process RSS is significantly out of reasonable range and continues to grow.
- The Java process has disappeared inexplicably. The startup script has set ulimit -c unlimited, but the disk does not leave any core files.
- Display memory continues to grow through `Java NMT`_
- If the JVM defines the Size of Metaspace or DirectMemory, Java process memory will not grow without limit, but when the system threshold is reached, there will be 'OOM errors' and 'Full GC' in the log.
- GC logs frequently report GCs caused by Metaspace
- The application log appears java.lang.OutOfMemoryError: Metaspace
- Found through the dmsg command java thread due to oom killed by the kernel

.. _Java NMT: https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html


Cause of Malfunction
^^^^^^^^^^^^^^^^^^^^

There are many reasons for the increase in out-of-heap memory. Most of the
reasons are related to memory leaks. The memory leak is caused by negligence or
error. The JVM fails to release the memory that is no longer used. A common
method is to observe the increase of rss. Status to confirm this problem. But in
fact, in many cases, the abnormal increase in memory is not caused by memory
leaks. When it is found that the Java process RSS continues to grow, it is
necessary to cooperate with the absolute value of RSS to confirm whether the
heap memory leaks. Most of the JVM configurations on the line will configure
``-Xms`` and ``-Xmx`` to be the same to avoid Heap's shrink and expand, but
before the JVM starts and reaches a stable state, although the heap memory has
been reserved, the OS does not immediately The physical memory is actually
allocated for the entire heap, but the physical page is actually prepared for
the process when the application accesses the corresponding address, so that the
user will observe that the RSS is small at the start of the startup, far less
than a reasonable value (a reasonable value can be passed ``-Xmx``,
``-XX:MaxMetaspaceSize``, ``-XX:MaxDirectMemorySize``,
``-XX:ReservedCodeCacheSize`` values are simply added and the margin is
reasonably estimated), but it has been slowly increased since startup. Although
this situation looks a lot like the performance of a memory leak, since the
absolute value of RSS does not exceed a reasonable value, this situation is
actually normal, and you need to first eliminate this situation when
troubleshooting. In order to avoid interference in this situation, you can
access the entire heap memory after the heap memory allocation by the
``-XX:+AlwaysPreTouch`` parameter. This access will allow the OS to actually
allocate physical memory to the JVM heap, thus eliminating interference.

The possible reasons for the abnormal increase in out-of-heap memory are listed
below:
- Memory leak caused by reading disk files by NIO
- DirectByteBuffer memory leak due to -XX:+DisableExplicitGC parameter
- Memory leak caused by Metaspace
- JDK internal memory leak caused by JDK8 MethodHandle
- Third-party JNI library leaks that users depend on
- Heap memory leak caused by jemalloc
- Memory upgrade upgrade caused by business upgrade
- CodeCache growth

Troubleshooting
^^^^^^^^^^^^^^^

The first task of troubleshooting is to identify memory leaks.

The JDK contains a function `Java NMT`_, which can be dynamically opened by the
jcmd command. With NMT, you can compare the difference between the memory space
of the Java process in NativeMemory between two time points. If it leaks in the
Internal space, There may be a leak inside Hotspot. If it is Unkonwn space, it
may be a leak caused by DirectByteBuffer or JNI library.

.. _Java NMT: https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html

NIO reads disk files causing memory leaks
"""""""""""""""""""""""""""""""""""""""""

Next, you can try to troubleshoot the DirectByteBuffer memory leak caused by NIO
reading the disk file. If this is the fault, then through the
``zprofiler->HeapDump->heap`` memory view, you will find that the capacity
accumulation of ``java.nio.DirectByteBuffer`` is already very large. If
``-XX:MaxDirectMemorySize`` is not set, this occupancy will continue to grow.
In addition, in the ``zprofiler->HeapDump->class`` loading view, you can find
the existence of two classes ``java.nio.channels.FileChannel`` and
``java.nio.ByteBuffer`` by searching. If it can be further determined that the
user's code has a similar code below, it can be confirmed with a high
probability.

.. code-block:: java

    java.nio.ByteBuffer mBuf = ByteBuffer.allocate(size);
    java.io.FileInputStream fIn = new FileInputStream("foo.txt");
    java.nio.channels.FileChannel fChan = fIn.getChannel();
    fChan.read(mBuf);

In this code, the user may be confused: I did not directly allocate
``DirectByteBuffer``, so where does the overflowed DirectByteBuffer come from?
This is actually the details of the JDK implementation. In the execution logic
of fChan.read, the JDK implementation will maintain a ThreadLocal
``BufferCache``. It will check if ``mBuf`` is a ``DirectBuffer``. If not, it
will be equipped with a ``DirectByteBuffer`` that meets the capacity
requirements. Then put it in a ``BufferCache`` of ``ThreadLocal``. It is worth
noting that each Thread maintains a BufferCache of a separate
``DirectByteBuffer``. This Cache is always present and will not be reclaimed
before the Thread exits. If there are many threads in the thread pool in the
application that read files through nio, and Thread The life cycle is longer,
then the total ``DirectMemorySize`` dominated by the BufferCache of these
threads will become very large, and these large number of ``DirectByteBuffer``
caches will have a high probability of being promoted to the old zone. Before
the GC in the Old Zone, these large number of ``DirectByteBuffers`` had no
chance to be recycled, so it was possible to fall into a situation where the
heap memory was consumed by the DirectByteBuffer before the GC occurred in the
Old Zone. This causes an out-of-heavy memory leak (a similar situation can occur
in other scenarios described in other sections). This part of the JDK logic is
reflected in the ``getTemporaryDirectBuffer`` method of the JDK
``sun.nio.ch.Util``, as follows.

.. code-block:: java

    public static ByteBuffer getTemporaryDirectBuffer(int size) {
        // If a buffer of this size is too large for the cache, there
        // should not be a buffer in the cache that is at least as
        // large. So we'll just create a new one. Also, we don't have
        // to remove the buffer from the cache (as this method does
        // below) given that we won't put the new buffer in the cache.
        if (isBufferTooLarge(size)) {
            return ByteBuffer.allocateDirect(size);
        }

        BufferCache cache = bufferCache.get();
        ByteBuffer buf = cache.get(size);
        if (buf != null) {
            return buf;
        } else {
            // No suitable buffer in the cache so we need to allocate a new
            // one. To avoid the cache growing then we remove the first
            // buffer from the cache and free it.
            if (!cache.isEmpty()) {
                buf = cache.removeFirst();
                free(buf);
            }
            return ByteBuffer.allocateDirect(size);
        }
     }

DirectByteBuffer memory leak caused by -XX:+DisableExplicitGC parameter
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

If it is not the above reason, then we try to exclude the DirectByteBuffer
memory leak caused by the -XX:+DisableExplicitGC parameter configuration. In
high-speed network IO frameworks such as Netty, DirectByteBuffer is often used
to ensure the performance of such network IO frameworks. The DirectByteBuffer
itself is a normal Java object, but the backend is associated with an unsafe
allocated heap memory. When the Java object of DirectByteBuffer is recycled by
the GC, the corresponding Unsafe heap memory will be reclaimed. However, the
triggering of Java GC is conditional, which determines that there is a "delay"
for the recovery of ordinary objects (theoretically all objects are reclaimed
when they are delayed until GC), so DirectByteBuffer corresponds. The external
memory of the heap is also delayed and reclaimed. Since the memory outside the
heap is usually large, the delay of the GC will amplify the pressure on the
memory. Therefore, when the JDK allocates ``DirectByteBuffer`` newly, the
``system.gc()`` function will be called in the underlying
``java.nio.Bits.reserveMemory`` method to trigger the Full GC, thereby actively
reclaiming the old ``DirectByteBuffer`` and the corresponding off-heap memory.
However, ``System.gc()`` triggers Full GC, which has a large overhead.
Therefore, many online product operation and maintenance personnel observe the
Full GC caused by ``System.gc()`` through the gc log, and simply pass
``-XX:+DisableExplicitGC``. To prohibit the application of active
``System.gc()``, this does avoid the overhead of the active Full GC, but it will
cause the ``DirectByteBuffer`` memory to be reclaimed in time, and the
out-of-heap memory is exhausted before the CMS GC.

Checking if the JVM startup parameter contains ``-XX:+DisableExplicitGC`` and
checking if the capacity of the heap memory view in zprofiler is normal is often
determined. In addition, by executing oql on the heap_dump, find out all the
``DirectByteBuffers``, this way can also help to confirm the problem.

.. code-block:: sql

    SELECT s as object, s.position as position, s.limit as limit, s.capacity as capacity
    FROM java.nio.DirectByteBuffer s where s.cleaner != null

.. _MetaspaceIncrement:

Metaspace abnormally increased
""""""""""""""""""""""""""""""

Generally speaking, leakage is the main reason for memory increase. Sometimes, for some reason, Metaspace's water level rises abnormally and triggers GC. Although GC can recycle Metaspace, it will not cause leakage in the traditional sense. The situation also needs to be investigated because additional GCs can affect the system's delayed response. When it is found through investigation that the dynamically loaded class suddenly becomes more and more, even if it can be recovered by GC, further root cause needs to be found.

In JDK8, Metaspace mainly stores metadata related to classes, such as method, constantPool, and so on. It should be noted that the memory in Metaspace is actually organized according to ClassLoader. Each time ClassLoader loads a class, it will allocate a small piece from Metaspace and add it to the corresponding Chunk List.

Because Java's running loading mechanism is very flexible, it allows users to customize the ClassLoader. If the user's business logic is flawed and triggers the ClassLoader's defineClass action, it will cause the Metspace to increase abnormally. Some users will continue to instantiate the new ClassLoader. These new ClassLoaders will continue to defineClass and not properly handle the reference relationship, which will also cause memory leaks or abnormal water levels. This bug is more likely to occur when user logic involves dynamic operations such as groovy and js scripting.

To confirm this error is also very simple, through the zprofiler -> Heap Dump -> class loading view and zprofiler -> Heap Dump -> repeat class definition view, if you find a ClassLoader defined class is very many, and the name is similar to script_xxx The style often means that such failures occur. Duplicate class definition views If you find that the same class is repeatedly defined by many ClassLoaders, it usually means a bug. Combined with the jstat -gcutil <pid> command to confirm the proportion of Metaspace, you can basically determine the fault, you need to use the review code to check the real fault point.

In addition to zprofiler and Eclipse MAT, jmap provides permstat (JDK7) and clstats (JDK8) to count the information about classloader loading, as well as the powerful sa-jdi.jar, which also counts Perm/Metaspace. Readers can refer to the corresponding chapter.

If you find that the number of sun.reflect.DelegatingClassLoader is too large (generally reaching thousands), this bug is usually caused by reflection. When the calling method is emitted by Method.invoke, the underlying JDK implementation is generally implemented by NativeMethodAccessorImpl or GeneratedMethodAccessorXXX. If it is NativeMethodAccessorImpl, then the native method is used to implement the method's reflection call, and GeneratedMethodAccessorXXX is the way JDK generates bytecode. Dynamically constructing a class directly calls the implementation. The switching of these two paths is controlled by the threshold of -Dsun.reflect.inflationThreshold. When the number of times the method reflection is called exceeds the threshold, the path of GeneratedMethodAccessorXXX will work. This implementation of JDK is considered from the perspective of efficiency, because the implementation of GeneratedMethodAccessorXXX is much more efficient than the NativeMethodAccessorImpl native, because the bytecode of GeneratedMethodAccessorXXX is finely constructed, and its invoke method will be at the bytecode level. Directly calling the target method essentially turns the reflection call into a non-reflective call. Below is an example of GeneratedMethodAccessorXXX.

.. code-block:: java

    package sun.reflect;

    public class GeneratedMethodAccessor1 extends MethodAccessorImpl {
        public GeneratedMethodAccessor1() {
            super();
        }

        public Object invoke(Object obj, Object[] args)
            throws IllegalArgumentException, InvocationTargetException {
            if (obj == null) throw new NullPointerException();
            try {
                A target = (A) obj;
                if (args.length != 1) throw new IllegalArgumentException();
                String arg0 = (String) args[0];
            } catch (ClassCastException e) {
                throw new IllegalArgumentException(e.toString());
            } catch (NullPointerException e) {
                throw new IllegalArgumentException(e.toString());
            }
            try {
                target.methodxxx(arg0);
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }
    }

To construct an efficient reflection call, the JDK constructs a reflection call for each Method to dynamically construct a GeneratedMethodAccessorXXX class, each class corresponding to a ClassLoadder instance. In the case of multithreading, this situation is exacerbated because the JDK dynamically constructs a GeneratedMethodAccessorXXX logic based on the Method's reflection call. There is no lock protection for performance reasons, that is, if multiple threads are simultaneously reflecting the call to Method At the same time, multiple GeneratedMethodAccessorXXX classes will be constructed at the same time, but only one GeneratedMethodAccessorXXX will be set, then the additional GeneratedMethodAccessorXXX can only be used as the garbage of the class, occupying the memory of Metadata in vain. This problem usually does not cause a leak in JDK8, because G1's -XX:ClassUnloadingWithConccurentMark can control ClassUnload during Concurrent Cycle, and CMS -XX:CMSClassUnloadingEnabled will also perform Metaspace recovery in the Final Remark phase of CMS. But on other older versions of the JDK, this problem may have to be recycled through the JVM's Full GC.

The user can dump the Class memory of GeneratedMethodAccessorXXX to the file through the ClassDump of the SA API, and reply the bytecode of the class through the javap-verbose command, so that it can find out which specific methods are called by reflection, thereby giving application development. The prompt is so that the developer can easily solve the problem by modifying the code according to the prompt.

.. code-block:: bash

    java -classpath ".:$JAVA_HOME/lib/sa-jdi.jar" -Dsun.jvm.hotspot.tools.jcore.filter=MethodAccessorFilter sun.jvm.hotspot.tools.jcore.ClassDump <pid>

Refer to the corresponding section for the usage of SA. The above gives a general usage, where MethodAccessorFilter is an extension tool class written by the user, the code is as follows:

.. code-block:: java

    import sun.jvm.hotspot.tools.jcore.ClassFilter;
    import sun.jvm.hotspot.oops.InstanceKlass;

    public class MethodAccessorFilter implements ClassFilter {
        // ClassDump will call the canInclude method to determine if a class should be dump class.
        // This method guarantees all classes starting with sun.reflect.GeneratedMethodAccessor
        // For example: sun.reflect.GeneratedMethodAccessorXXX will be dumped to the file
        @Override
        public boolean canInclude(InstanceKlass kls) {
            String klassName = kls.getName().asString();
            return klassName.startsWith("sun/reflect/GeneratedMethodAccessor");
        }
    }

Another situation is the memory leak of the implicit Metasapce caused by fragmentation. Since Metaspace is managed by Chunk List and continuously recycled (in the case of Enable ClassUnloading), it is naturally faced with the problem of memory fragmentation. The fate is that JDK8 is not a good way to fragment Metaspace memory, because GC can't perform compact on Metaspace. So if you set the size of the Metaspace, you will generally choose -XX:MetaspaceSize and -XX:MaxMetaspaceSize to set the same value, to minimize the memory fragmentation introduced by Metaspace shrink/grow, and consider adding as much memory as possible to Metaspace. If js, groovy scripts are frequently used in fragmented applications, the application layer uses the cache idea as much as possible to avoid the dynamic loading action triggered by the service. Other than that, there is no good way to mitigate Metaspace memory fragmentation.

.. note::


	The triggering mechanism for Metaspace recycling is more complicated. A water level line is maintained in Metaspace. When the number in Metaspace is higher than this water level line, the GC will be triggered for recycling. This water level line is not fixed, but is constantly dynamically adjusted during the GC process. This adjustment process is controlled by -XX:MinMetaspaceFreeRatio and -XX:MaxMetspaceFreeRatio. The specific control logic is probably like this. After the GC ends, it will first check whether the water level line needs to be raised. It will divide the size occupied by the recovered Metaspace by (1 - MinMetaspaceFreeRatio/100), thus obtaining a target Metaspace value. If the current water level is below this target value, increase the water level to the target value. If the water level line is found to be higher than the target value, then try to lower the water level line. The logic for lowering the water level line is to divide the size of the recovered Metaspace by (1 - MaxMetaspaceFreeRatio/100) to get a target Metaspace size value once the water level When the value exceeds this target value, the water level value is lowered to the target value. This ensures that the water mark is always adjusted within the upper and lower limits of the Metaspace, which is controlled by MaxMetspaceFreeRatio and MinMetaspaceFreeRatio. If the occupancy of Metaspace after GC is small, the upper and lower limits will be narrower. At the same time, the values ​​of the upper and lower limits are relatively small. If the occupancy of Metaspace after GC is relatively large, the upper and lower limits will be wider (the upper limit must not exceed MaxMetaspaceSize), and the upper and lower limits are relatively large. As the size of the Metaspace increases, this watermark will approach MaxMetaspaceSize. This mechanism avoids wasting memory by too large metaspace, and also avoids triggering the GC early by too small metaspace. The JVM options -XX:+PrintGCDetails and -verbose:gc can print out the adjusted thresholds in the gc log.

	In general, when Metaspace is allocated, it will check whether the existing usage is greater than the water level. If it is greater than this, it will trigger the CMS or G1 Concurrent Cycle to recover. The CMS recycles Metaspace in the Final Remark stage. Similarly, G1 is the recovery of Metaspace in the Remark phase of Concurrent Cycle.

If you want to confirm the Metaspace memory fragmentation, you can confirm this by checking if the Metapsace GC triggered by the -XX:MinMetaspaceFreeRatio and -XX:MaxMetaspaceFreeRatio in the GC log is too low. In addition, by checking the occupancy size in Metaspace, if it is found that the GC caused by Metaspace occurs frequently, but the occupied size has been relatively small, this can also be confirmed.

Check the usage of Metspace. In addition to the jstat command, the user can also use the kill -3 java-pid command. Execute this command to print the usage of the heap of the current process to stdout, including the usage of Metaspace. Of course, this command It will also print out all the thread stacks of the java process, which is a very useful command. In addition, there is a -XX:PrintHeapAtGC parameter in the JVM parameter, which can print the memory usage before and after the GC, including the usage status of the Metaspace.

JDK internal memory leak caused by MethodHandle
"""""""""""""""""""""""""""""""""""""""""""""""

In order for the JVM to support dynamic language type extensions, the JVM introduced the java.lang.invoke package in JDK7. Many dynamic language extensions like groovy, including nashorn, rely on this package. The java.lang.invoke package will depend on MehtodHandle. Some versions of JDK8 have a memory leak bug in the processing of MethodHandle. If you use groovy, js in the application or dynamically compile the java class through the tool class, then you need Note if this memory leak bug will be triggered. This memory leak is one of the internal implementations of JDK8`Bug <https://bugs.openjdk.java.net/browse/JDK-8152271>`, which has been solved in JDK9 and also solved on 8u152. But this bug still exists in previous versions.


The MethodHandle memory leak bug can be manually triggered by the following code:

.. code-block:: java

    import java.lang.invoke. * ;

    public class Leak {
        public void callMe() {
        }

        public static void main(String[] args) throws Throwable {
            Leak leak = new Leak();

            while(true) {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodType mt = MethodType.fromMethodDescriptorString("()V", Leak.class.getClassLoader());
                // findSpecial leaks some native mem
                MethodHandle mh = lookup.findSpecial(Leak.class, "callMe", mt, Leak.class);
                mh.invokeExact(leak);
           }
        }
    }

When the user compiles and runs the above code, it will find that the process rss is getting bigger. The main reason for this bug is that the lookup.findSpecial method is called to construct MehtondHandle. Each time, a new object of type MemberName is generated. This object is used to store the class field or method information. In fact, only class and simple_name are used. Two fields. This object will be made into a weak reference at the bottom level via the JNIHandles::make_weak_global method, and it will be added to a GrowalbeArray called MemberNameTable (this also creates another important manifestation of this bug. When the bug is triggered, the WeakReference of MemberName The number will be larger and larger). In the current JDK implementation, if the class that creates the MethodHandle is not unloaded, the MemberNameTable will not be destroyed, then the large number of WeakReferences it holds will not be destroyed, and the corresponding JNIHandleBlock will not be released. In the sample code, this method is continuously called, Leak's class will not be unloaded, MemberNameTable will not be destroyed, and the source will continue to add the WeakReference of the MemberName type to the memory, and the memory will be larger and larger. Causes a memory leak. If the upper application does not realize that this is logically circumvented, the probability of this bug being triggered is very high.

In the JDK8u152 fix, the JDK adds an intern operation to the MemberName at the bottom, conceptually similar to String.intern, which means that the corresponding MemberName information when constructing MethodHandle is not new every time, but from the cache. Whether the query can reuse an existing one, because the number of classes and members is always limited, thus suppressing the growth of memory, thus solving the problem.

The confirmation of this problem is more difficult, because the memory leak point inside the JDK is more difficult to find. Memory leaks when using `NMT <https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html>`_ found leaks in JDK Internal, and application logic mass production uses groovy Such a dynamic language, and zprofiler's class loading view does load a class like MethodHandle, and you can consider memory leaks for this reason. To fully confirm this problem, you need to pass jemalloc. If the call view of jemalloc finds that MethodHandle occupies a large amount of memory, you can confirm this problem.


Third-party JNI library causes heap memory leaks
""""""""""""""""""""""""""""""""""""""""""""""""

Java applications use third-party JNI libraries for a variety of purposes. Because JNI libraries are implemented in C/C++, memory allocation can completely escape the control of the JVM. If the implementation is flawed or the upper API is used improperly, It is easy to cause an out-of-heap memory leak.

Users can view the Java process's library loading status by using the cat /proc/<pid>/maps or pmap <pid> command. If a Java process is found to load some application-specific so libraries, you can focus on this type of failure. To fully confirm this type of failure, you need a professional memory leak tool like `jemalloc`_ or `gperftools`_. Since AJDK natively integrates jemalloc, it is recommended to use jemalloc to troubleshoot the problem. When the user suspects a memory leak, you can modify the startup script of the Java application, and control the profiling option by export MALLOC_CONF. When the Java application is restarted, the profling data will be generated and the malloc and mmap will be sampled and tracked. When a memory leak occurs, the jeperf command provided by jemalloc can generate the allocation site map of the process malloc memory. If the JNI-related function is found to occupy the main part, then it can be confirmed that the memory leak is generated by the third-party JNI library.

.. _jemalloc: http://jemalloc.net
.. _gperftools: https://github.com/gperftools

Memory demand upgrade caused by business changes
""""""""""""""""""""""""""""""""""""""""""""""""

This increase in memory requirements can easily be mistaken for out-of-heap memory leaks, such as business complexity, the introduction of more complex middleware, resulting in more loaded classes, or due to business pressure, JNI consumes memory More and more.

When a Java application finds a memory leak, you can first check the change list. For example, if the middleware is upgraded, the middleware upgrade may load more classes and consume more Metaspace. If you use the JNI library, you can check the CPU usage and IO read and write status of the machine. Because CPU usage or IO reading and writing is relatively high, it is usually a signal with a large amount of traffic. In order to serve more services, JNI Memory consumption will also increase. If you have these situations, you can try to expand the memory and increase the memory limit to a reasonable value. If you increase the memory limit and find that there is no abnormality, you can confirm that it is such a problem.

.. note::

	A common mistake is to use only CPU Load as a sign of busy business, but if there are a large number of threads blocking on IO or sleeping, falling into D state or S state, CPU load is high, but CPU usage is not High, this does not mean that the business must be busy.

Codecache growth
""""""""""""""""

Codecache is also part of the JVM heap memory, mainly used to store the native code generated by the JVM, such as JIT compilation methods, JNI Stub, some methods generated by Interpreter, etc., JIT compiler generation methods occupy the vast majority of Codecache. This part of the memory may also leak.

.. note::


	Codecache in the process of running Java will find that the Codecache is full, it will trigger the recycling mechanism. The recycling is divided into two phases. One phase is Mark. This phase marks the heat of the nmethod compilation method to determine whether nmethod can be recycled. One stage is Sweep, which performs real memory cleanup based on the heat of the various nmethods in Codecache. The Mark phase is executed when each safepoint exits. When the safepoint exits, it performs a series of cleanup operations. One of the operations is mark_active_nmethods(). The general idea of ​​this operation is to maintain a counter for each nmethod, representing nmethod. Heat, mark_ative_nmethod will scan all Java thread stacks, for the nmethod of the stack, perform a counter plus one, so that nmthod with a heat below a certain threshold can be recycled in the following Sweep phase. The Sweep phase will check if the Codecache is full. Once it is full, it will try to clean the memory according to the heat calculated by the Mark stage. This cleanup will be done in two places, one in the compiler loop, before trying to get the CompileTask from the CompileQueue. A cleanup attempt will be made. The second place is that CompileQueue will also try to get the CompileTask when it performs the get() operation. The cleanup attempt of this place is very careful, except for the cleanup attempt at the beginning of the get() operation. It also fully considers that the CompileQueue is not empty for a long time, and if the CompileQueue is empty for a long time, it will try to clean it at regular intervals (this time interval is controlled by -XX:NmethodSweepCheckInterval). This will ensure the timeliness of cleaning to a certain extent.

Since Codecache is internally controlled by the JVM, 256M is generally sufficient for most applications, but it can also cause leaks due to the particularity of the application or bugs within the JVM. The method of confirming the leak is very simple. The code cache size can be reported by tools such as gcutil, NMT, jconsole, visualVM or MXBean's MemoryPool. In addition, -XX:PrintCodeCache, -XX:PrintCodeCacheOnCompilation can also obtain the code cache in the standard output. In this case, if you find that the code cache keeps increasing and reaches the upper limit of CodeCacheSize, you can confirm this problem. In some JDK versions, the code cacche will have the following log, and a text search can help confirm the problem.

``Java HotSpot(TM) 64-Bit Server VM warning: CodeCache is full. Compiler has been disabled.``

After discovering the CodeCache leak, you need to further investigate which methods are causing the leak. If you are using AJDK, AJDK provides a useful feature.

``jcmd pid CodeCache.dump code_cache_dump_log_file_path=./xx.log``

With this function, the user can find out which classes of CodeCache are composed of which methods, what is the compilation level, and how big the size is. If you are using OracleJDK or OpenJDK, you can try to use SA to print out the methods in Code Cache.

.. note::

	There are many ways to use SA. One is to use clhsdb. After attaching to the target process, the user can extend the command through js and use the SA API, and then run the extended command through jsload/jseval. The other is to extend the java tool class directly through the SA API, and then use sa-jdi.jar to load the tool class. Whether it is extended by clhsdb js or sa-jdi.jar java, the bottom layer is the traversal output of codecache through sa api of sa.codeCache.iterate. For the specific usage of sa, refer to the corresponding chapters.

If you find that the compilation method is not uniform enough in the above way, for example, if a method is repeatedly compiled many times and takes up most of the space, you can suspect that the JVM's `Bug <https://aone.alibaba-inc.com /issue/10946676?spm=a2o8d.corp_prod_issue_list.0.0.1fb56a85V2WcU1>`__ . In earlier versions of the JDK implementation, the compilation task of an osr method may fail to properly handle the compilation state due to a bug, resulting in the same method being repeatedly compiled many times, resulting in a leak. If you are using AJDK 8.3.6 or later, this problem has been fixed. The fix is ​​to modify the JVM so that the compile thread will check the same compile level method more strictly after obtaining the compile task. If you exist, ignore the compilation task. The fix for this bug has been fixed in AJDK 8.3.6. The community OpenJDK has not found relevant bug reports for the time being, but it is certain that at least in the early versions of OpenJDK, this bug was verified. OpenJDK may be fixed in another way in subsequent versions, but this is not reflected in the community bug system. If you use the community OpenJDK, you need to check it yourself and use the high version release as much as possible. If the size of the compilation method is relatively uniform and there is no repeated compilation, it is generally considered that the Codecache Size is not enough, not a leak.

Memory leak caused by jemalloc
""""""""""""""""""""""""""""""


Jemalloc is a very good memory allocator that can be used to replace glibc malloc, and provides a lot of powerful profiling tools to help analyze memory leaks. It has many improvements in performance, fragmentation, memory footprint and so on. Some applications also try to use jemalloc by hijacking jvm by exporting LD_PRELOAD=xxx, or using jemalloc in the JNI library.

But unfortunately jemalloc has bugs in some early versions, there may be a memory leak, and jemalloc needs to be upgraded to circumvent this bug. At `jemalloc release notes
As can be seen in <https://github.com/jemalloc/jemalloc/releases>`_, there are bug fixes related to memory leaks at least in versions 4.3.1 and 4.1.1. Users are advised to use the latest jemalloc version.

To troubleshoot such problems, you can use /proc/<pid>/maps to determine if jemalloc is loaded. If it is determined that jemalloc has been loaded and there are no other valid reasons, you can suspect this problem and need to check the version further.

Troubleshooting
^^^^^^^^^^^^^^^

If the cause of the fault is detected, it is generally easier to solve it.

The memory leak caused by NIO reading disk files needs to change the way Java writes. For example, the application layer explicitly allocates DirectByteBuffer through a special thread. This allocated DirectByteBuffer is allocated and controlled by user logic. Or instead of using NIO to read the disk file.

The -XX:+DisableExplicitGC parameter causes a DirectByteBuffer memory leak that requires changing the JVM startup parameters. Remove the -XX:+DisableExplicitGC parameter. If you care about the Full GC overhead caused by system.gc(), you can use -XX in the case of CMS. The +ExplicitGCInvokesConcurrent parameter and -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, so system.gc() will trigger the CMS and class unload in the concurrent phase. The CMS is much less expensive than the Full GC.

G1 can also use ExplicitGCInvokesConcurrent. After opening this parameter option, G1 triggers a concurrent-mark phase when system.gc() is called. The concurrent-mark will perform YGC recovery in the initial-mark phase and determine the subsequent mixed gc. Recycling the old district.

The increase of Metaspace requires the user to check the logic of code dynamic class loading. If it is caused by dynamic logic such as groovy, js, etc., it can be solved by caching ideas, and the cached classes are cached. Other scenarios require the user to handle the analysis flexibly. The leak caused by GeneratedMethodAccessor reflects the specific method name and class name, and the user needs to modify the code by the cache.

Since Metaspace/Perm stores Class related data, for CMS, the safe way is to open -XX:CMSClassUnloadingEnabled to ensure that Class can be uninstalled by CMS. G1 also has a -XX:ClassUnloadingWithConccurentMark to control the unloading of the Class, thus ensuring that g1 can unload Class in the ConccurrentMark phase.

JDK8
The JDK internal memory leak caused by MethodHandle requires the user to upgrade the JDK to 8u152 or JDK9. If it is not possible to upgrade, then try to use the cache idea to cache the MethodHandle to temporarily bypass this problem.

The user leakage caused by the JNI library needs to trace the source of the JNI library to see if it is a usage error. Many third-party libraries have some restrictions on usage. For example, some objects need to explicitly call close, etc. Check according to the programming manual of the JNI library. If it are not these obvious problems, you need to go deep into the source code for deep debugging.

The memory leak caused by Codecache, if it is determined that the method is repeatedly compiled, you can consider upgrading the JDK to the latest version, if the latest version can not be solved, you can try to expand the size of Codecache through JVM parameters, expanding the memory is almost always improved. In addition, the JVM parameter is used to force the compilation of some methods, which is also a temporary walkaround. The JVM also provides a set of control parameters for Codecache, allowing users to reduce some of the cache size by sacrificing some of the performance, such as -XX:InlineSmallCode, -XX:MaxInlineLevel, -XX:MaxInlineSize, -XX:MinInliningThreshold, -XX:InlineSynchronizedMethods, etc. Users can use it as appropriate for the temporary program according to the situation.

The memory leak caused by jemalloc can usually be solved by upgrading the jemalloc version. If it can't be solved, it is recommended to return to glibc's malloc implementation.

The above measures can not be resolved, you can try to simply expand the memory further positioning problem.
