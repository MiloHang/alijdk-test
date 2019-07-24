.. _OffHeapIncrement:

堆外内存增涨异常
----------------

堆外内存异常增涨，会造成Java进程在Heap之外的内存不正常的一直上升，达到JVM或者操作系统的上限阈值，从而引起系统指标异常的故障。比如，如果JVM设置了MaxMetspaceSize或者MaxDirectMemorySize等参数，这种泄漏会造成OOM的错误。

堆外内存异常增涨的一个常见原因就是泄漏，泄漏通常是指由于错误使用使得JVM无法释放已经不再使用的内存。另外当程序使用内存过多或者JVM配置不合理，也会导致堆外内存异常增涨。


故障表现
^^^^^^^^

堆外内存增涨异常的故障常见表现如下:

- 系统top命令显示Java进程RSS明显超过合理范围，并且一直持续增长
- Java进程莫名奇妙消失，而且尽管启动脚本已经设置ulimit -c unlimited，但磁盘没有留下任何core文件
- 通过 `Java NMT`_ 显示内存不断增长
- 如果JVM限定了Metaspace或者DirectMemory的Size，Java进程内存虽然不会无限制增长，但达到系统阈值时，会报OOM错误以及Full GC
- GC日志会频繁报告由Metaspace引起的GC
- 应用日志出现java.lang.OutOfMemoryError: Metaspace
- 通过dmsg命令发现java线程由于oom被内核杀死

.. _Java NMT: https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html



故障原因
^^^^^^^^

堆外内存增涨异常的原因有很多，其中大部分原因都和内存泄漏有关，内存泄漏是由于疏忽或错误造成JVM未能释放已经不再使用的内存, 一个常用的手段就是观察rss的增涨状况来确认这个问题。但实际上很多情况下，内存异常增涨并不全是由内存泄漏引起的。当发现Java进程RSS持续增长时，需要同时配合RSS的绝对值一起来看才能确认是否堆外内存泄漏。线上的绝大多数的JVM配置会把-Xms和-Xmx配置成一样从而避免Heap的shrink和expand，但在JVM启动并达到稳定状态之前，虽然已经保留了堆内存，OS并不会立刻实际为整个堆实际分配物理内存，而是在应用访问到相应地址时才会真正为进程准备物理页面，这样用户会观察到启动开始的时候RSS很小，远远小于合理值(合理值可以通过-Xmx, -XX:MaxMetaspaceSize, -XX:MaxDirectMemorySize, -XX:ReservedCodeCacheSize各个值简单相加并给予余量进行合理估算)，但启动后一直缓缓增加。尽管这种情况看起来很像是内存泄漏的表现，但由于RSS绝对值并未超过合理值，这种情况其实是正常表现，排查时需要首先排除这种情况。为了避免这种情况的干扰，可以通过-XX:+AlwaysPreTouch参数在堆内存分配后，会对整个堆内存进行一次访问，这次访问会让OS真正给JVM的堆分配物理内存，从而排除了干扰。


下面列出了造成堆外内存异常增涨的可能原因:

- 由NIO读取磁盘文件造成的内存泄漏
- 由于-XX:+DisableExplicitGC参数导致的DirectByteBuffer内存泄漏
- 由Metaspace造成的内存泄漏
- JDK8 MethodHandle造成的JDK内部内存泄漏
- 用户依赖的第三方JNI库泄漏
- jemalloc造成的堆外内存泄漏
- 业务升级导致的内存需求升级
- CodeCache增长


故障排查
^^^^^^^^

排查的首要任务是确认内存泄漏点。

JDK包含了一个功能 `Java NMT`_ ，该工具可以通过jcmd命令进行动态开启,利用NMT，可以比较两个时间点之间Java进程在NativeMemory使用上各个内存空间的差异, 如果泄漏在Internal空间，有可能是Hotspot内部的泄漏，如果是Unkonwn空间，则有可能是DirectByteBuffer或者JNI库造成的泄漏。

.. _Java NMT: https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html

NIO读取磁盘文件造成内存泄漏
"""""""""""""""""""""""""""

接下来，可以尝试排查NIO读取磁盘文件造成的DirectByteBuffer内存泄漏。如果是这个故障，那么通过zprofiler->HeapDump->堆外内存视图，你会发现java.nio.DirectByteBuffer的capacity累计和已经非常大了，如果-XX:MaxDirectMemorySize没有设置，这个占用会一直增长上去。另外在zprofiler->HeapDump->类加载视图中，你可以通过搜索发现java.nio.channels.FileChannel和java.nio.ByteBuffer两个class的存在。如果可以进一步确定用户的代码有下面类似的代码，就可以很大概率确认是这个问题。

.. code-block:: java

    java.nio.ByteBuffer mBuf = ByteBuffer.allocate(size);
    java.io.FileInputStream fIn = new FileInputStream("foo.txt");
    java.nio.channels.FileChannel fChan = fIn.getChannel();
    fChan.read(mBuf);

在这段代码中，用户可能会比较困惑：我并没有直接分配DirectByteBuffer，那么溢出的DirectByteBuffer是从哪里来的呢？这其实是JDK实现的细节，在fChan.read的执行逻辑中，JDK的实现会维护一个ThreadLocal的BufferCache的，它会检查mBuf是否是一个DirectBuffer，如果不是，会新配一个符合capacity要求的DirectByteBuffer，然后把它放到一个ThreadLocal的BufferCache中去。值得注意的是，每个Thread都会维护一个独立的DirectByteBuffer的BufferCache, 在Thread退出之前，这个Cache是一直存在并不会回收, 如果应用中通过nio读取文件的线程池里的线程很多, 而且Thread的生命周期比较长，那么这些线程的BufferCache所支配的DirectMemorySize总和就会变得很大，而且这些大量的DirectByteBuffer的cache会有很大概率晋升到老区中去。在Old区GC发生之前，这些大量的DirectByteBuffer是没有机会得到回收的，这样就有可能陷入一种境地：在Old区发生GC之前，堆外内存已经被DirectByteBuffer支配消耗完了。这就造成堆外内存泄漏(类似的状况也会在其它章节描述的另外场景中出现)。JDK这部分逻辑体现在JDK的sun.nio.ch.Util的getTemporaryDirectBuffer方法里，如下。

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

-XX:+DisableExplicitGC参数导致的DirectByteBuffer内存泄漏
""""""""""""""""""""""""""""""""""""""""""""""""""""""""

如果不是上述原因，紧接着我们尝试排除-XX:+DisableExplicitGC参数配置造成的DirectByteBuffer内存泄漏。在Netty之类的高速网络IO框架中，DirectByteBuffer是经常使用的，它保证了这类网络IO框架的性能。DirectByteBuffer本身是一个正常的Java对象，只是后端关联着一个Unsafe分配的堆外内存。当DirectByteBuffer的Java对象被GC回收的时候，对应的Unsafe堆外内存才会被回收。但Java GC的触发是有条件的，这就决定了对普通对象的回收是有"延时"(理论上所有对象的回收都是被延时到GC时才被会执行)的，因此DirectByteBuffer对应的堆外内存也是被延时回收的，由于堆外内存通常又比较大，这样GC的延时就会放大对内存的压力。因此JDK在新分配DirectByteBuffer的时候，在底层的java.nio.Bits.reserveMemory方法中会主动调用System.gc()函数来触发Full GC，从而主动回收旧的DirectByteBuffer以及对应的堆外内存。但System.gc()触发的是Full GC，开销比较大，因此很多线上产品运维人员通过gc日志观测到由System.gc()引起的Full GC后，就简单的通过-XX:+DisableExplicitGC来禁止应用主动System.gc()，这么做的确避免了主动Full GC带来的开销，但就会导致DirectByteBuffer内存回收不及时，在CMS GC之前把堆外内存消耗殆尽。

检查JVM启动参数是否包含-XX:+DisableExplicitGC并检查zprofiler里的堆外内存视图的capacity是否正常往往就可以确定这个问题。另外通过对heap_dump执行oql，找出所有的DirectByteBuffer，这种方式也可以帮助确认问题。 

.. code-block:: sql

    SELECT s as object, s.position as position, s.limit as limit, s.capacity as capacity 
    FROM java.nio.DirectByteBuffer s where s.cleaner != null

.. _MetaspaceIncrement:

Metaspace异常增涨
"""""""""""""""""
通常来讲，泄漏是内存增涨的主要原因，有时候由于某些原因，导致Metaspace的水位异常升高从而触发GC，虽然GC能够回收Metaspace，并不会造成传统意义上的泄漏，但这种情况也需要进行排查，因为额外的GC会影响系统的延迟响应。当通过排查发现动态加载的class突然变多，即使能够通过GC回收，也需要进一步找到root cause。

在JDK8里，Metaspace主要存储的是class的相关元数据，比如method，constantPool等。需要注意的是Metaspace里的内存实际上是按照ClassLoader来组织的，ClassLoader每加载一个class，都会从Metaspace中分配一小块，添加到对应的Chunk List中去。

由于Java的运行加载机制非常灵活，允许用户自定义ClassLoader，如果用户的业务逻辑存在缺陷，不断触发ClassLoader的defineClass动作，那么就会造成Metspace异常增涨。有的是用户会不断实例化新的ClassLoader，这些新的ClassLoader会不断defineClass，并且没有正确处理引用关系，这也会造成内存泄漏或者水位异常增高。当用户逻辑涉及到动态运行groovy，js脚本类似的操作时，这种bug会比较容易出现。

要确认这种错误也很简单，通过zprofiler -> Heap Dump -> 类加载视图 以及 zprofiler -> Heap Dump -> 重复类定义视图，如果你发现某个ClassLoader定义的class非常多，而且命名有着script_xxx类似的样式，往往意味着这类故障的发生。重复类定义视图里如果你发现了同样的class被许多ClassLoader重复定义通常也意味着bug。再结合jstat -gcutil <pid>命令确认下Metaspace的占比，基本就可以确定这个故障了，需要用户通过review代码来排查到真正故障点。

除了zprofiler和Eclipse MAT，jmap提供了permstat(JDK7)以及clstats(JDK8)来统计classloader加载的相关信息，还有强大的sa-jdi.jar，也能统计Perm／Metaspace的情况, 读者可以参考相应的章节。

如果发现sun.reflect.DelegatingClassLoader的数目过多(一般到达上千个)，这个bug通常是由反射引起的。当通过Method.invoke来发射调用方法时，JDK底层实现一般是通过NativeMethodAccessorImpl或者GeneratedMethodAccessorXXX来实现，如果是NativeMethodAccessorImpl，那么是通过native方法来实现方法的反射调用，而GeneratedMethodAccessorXXX是JDK通过生成字节码的方式动态构造一个class直接调用实现的，这两条路径的切换是通过-Dsun.reflect.inflationThreshold的阈值来控制的，当方法反射调用的次数超过阈值，则GeneratedMethodAccessorXXX的路径会奏效。JDK的这个实现是从效率的角度来进行考量的，因为GeneratedMethodAccessorXXX的实现相比NativeMethodAccessorImpl的native而言要高效很多，因为GeneratedMethodAccessorXXX的字节码是被精巧构造的，它invoke方法会在字节码层面直接调用target方法，本质上就把反射调用变成了一个非反射调用。下面就是一个GeneratedMethodAccessorXXX的例子。

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

为了构造高效的反射调用，JDK会构造对每个Method的反射调用动态构造一个GeneratedMethodAccessorXXX类，每个类都对应一个ClassLoadder实例。当在多线程的情况下，这种情况会被恶化，因为JDK根据Method的反射调用动态构造一个GeneratedMethodAccessorXXX的逻辑出于性能考虑是没有锁保护的，也就是说，如果多个线程同时反射调用Method，会同时构造多个GeneratedMethodAccessorXXX类，但只有一个GeneratedMethodAccessorXXX会被设置有效，那么另外多出来的GeneratedMethodAccessorXXX只能作为class的垃圾，白白占用Metadata的内存。这个问题在JDK8中通常不会造成泄漏，因为在G1的-XX:ClassUnloadingWithConccurentMark可以在Concurrent Cycle的时候控制ClassUnload，CMS的-XX:CMSClassUnloadingEnabled也会在CMS的Final Remark阶段进行Metaspace回收。但在其它旧版本的JDK上，这个问题可能就不得不通过JVM的Full GC来回收了。

用户可以通过SA API的ClassDump来将GeneratedMethodAccessorXXX的Class内存dump到文件，并通过javap -verbose命令将class的字节码回复出来，就可以排查出这些通过反射调用的具体方法是哪些，从而给应用开发者以提示，这样开发者可以比较容易得根据提示通过修改代码来解决这个问题。

.. code-block:: bash

    java -classpath ".:$JAVA_HOME/lib/sa-jdi.jar" -Dsun.jvm.hotspot.tools.jcore.filter=MethodAccessorFilter sun.jvm.hotspot.tools.jcore.ClassDump <pid>

SA的用法参考相应章节。上面给出了一个大概的用法，其中MethodAccessorFilter是用户自己编写的扩展工具类，代码如下:

.. code-block:: java

    import sun.jvm.hotspot.tools.jcore.ClassFilter;  
    import sun.jvm.hotspot.oops.InstanceKlass;  

    public class MethodAccessorFilter implements ClassFilter { 
        // ClassDump会调用canInclude方法来判断某个class是否应该dump class
        // 这个方法保证了所有以sun.reflect.GeneratedMethodAccessor开头的类
        // 比如:sun.reflect.GeneratedMethodAccessorXXX都会被dump到文件里 
        @Override  
        public boolean canInclude(InstanceKlass kls) {  
            String klassName = kls.getName().asString();  
            return klassName.startsWith("sun/reflect/GeneratedMethodAccessor");  
        }  
    }  

另外一种情况是碎片化造成的隐式Metasapce的内存泄漏，由于Metaspace是通过Chunk List来管理，同时不断被回收(Enable ClassUnloading的情况下), 自然也会面临着内存碎片化的问题。要命的是JDK8对于Metaspace内存碎片化没有很好的办法，因为GC无法对Metaspace进行compact。所以如果设置Metaspace大小的时候，一般会选择-XX:MetaspaceSize和-XX:MaxMetaspaceSize设置成相同的数值，尽可能缓解Metaspace shrink/grow引入的内存碎片化，另外考虑尽可能多的为Metaspace分配内存。如果碎片化的应用中频繁使用js,groovy脚本，应用层尽可能多利用缓存的思路，避免业务触发的动态加载的动作。除此之外并没有很好的缓解Metaspace内存碎片化的方法。

.. note:: 

    Metaspace回收的触发机制比较复杂，在Metaspace中会维护一个水位线，当Metaspace中的数量高于这个水位线时就会触发GC进行回收。这个水位线并不是固定的，而是在GC的过程中不断动态调整，这个调整的过程受到-XX:MinMetaspaceFreeRatio和-XX:MaxMetspaceFreeRatio的控制。具体的控制逻辑大概是这样的，GC结束后首先会检查水位线是不是需要升高，它会把回收后的Metaspace占用的尺寸除以(1 - MinMetaspaceFreeRatio/100), 这样得到一个目标Metaspace值，如果目前的水位线低于这个目标值，则提高水位线至目标值。如果发现水位线高于目标值，那么就开始尝试降低水位线，降低水位线的逻辑是，根据回收后的Metaspace的大小除以(1 - MaxMetaspaceFreeRatio/100)，得到一个目标Metaspace大小值，一旦水位值超过这个目标值，就降低水位值至目标值。这样做就确保了水位线一直在当前Metaspace大小，MaxMetspaceFreeRatio和MinMetaspaceFreeRatio三者共同控制的Metaspace上下限范围内进行调整，如果GC后Metaspace的占用量比较小，那么这个上下限的范围就会比较窄，同时上下限的数值也相对较小，如果GC后Metaspace的占用量比较大，那么这个上下限的范围就会比较宽(上限一定不会超过MaxMetaspaceSize)，同时上下限的数值也相对比较大。随着Metaspace占用尺寸的增加，这个水位线会向MaxMetaspaceSize趋近。这个机制避免过大的metaspace浪费内存，同时也避免过小的metaspace提早触发GC。JVM选项-XX:+PrintGCDetails和-verbose:gc可以在gc日志中把这些调整的阈值完整得打印出来。

    一般情况下，Metaspace的分配的时候会检查现有的使用量是否大于水位线，如果大于就会触发CMS或者G1的Concurrent Cycle来进行回收，CMS是在Final Remark阶段进行Metaspace的回收，类似的，G1是在Concurrent Cycle的Remark阶段进行Metaspace的回收。

如果想确认Metaspace内存碎片化，通过检查GC日志中-XX:MinMetaspaceFreeRatio和-XX:MaxMetaspaceFreeRatio限定的Metapsace GC触发水位线是否过低就可以确认是这种情况。另外，通过检查Metaspace中的占用尺寸，如果发现频发发生Metaspace引起的GC，但其实占用尺寸一直比较小，也可以确认这种情况。

检查Metspace的使用情况，除了jstat命令，用户也可以使用kill -3 java-pid命令，执行这个命令可以把当前进程的堆的使用情况打印到stdout里，其中也包括Metaspace的使用情况，当然这个命令也会打印出java进程所有的线程栈，这是一个非常好用的命令。另外JVM参数中有一个-XX:PrintHeapAtGC参数，可以打印GC前后的内存使用状况，其中也包括Metaspace的使用状况。

MethodHandle造成的JDK内部内存泄漏
"""""""""""""""""""""""""""""""""
为了让JVM支持动态语言类型扩展，JVM在JDK7的时候就引入了java.lang.invoke包, 很多动态语言扩展像groovy，包括nashorn都依赖这个包。而java.lang.invoke包会依赖MehtodHandle, 可JDK8的某些版本在MethodHandle的处理上面存在内存泄漏的bug，如果你在应用中用到了groovy,js或者通过工具类动态compile java类，那么就需要注意是否会触发这个内存泄漏的bug。这个内存泄漏是JDK8在内部实现上的一个 `Bug <https://bugs.openjdk.java.net/browse/JDK-8152271>`__ ，已经在JDK9进行了解决，在8u152上也进行了解决。但在之前的版本上这个bug依然存在。

MethodHandle内存泄漏的bug可以通过下面的代码手动构造触发:

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

用户在编译运行上述代码，会发现进程rss不断变大。这个bug的主要原因是lookup.findSpecial方法被调用去构造MehtondHandle的过程中，每次都会生成一个新的类型为MemberName的对象，这个对象用来存储class的field或者method信息，实际上只有class和simple_name两个字段。这个对象在底层会通过JNIHandles::make_weak_global方法使之成为weak reference，并且不断被添加到一个叫做MemberNameTable的GrowalbeArray里去(这也造成了这个bug另外一个重要表现，当bug被触发时，MemberName的WeakReference的数量会越来越大)。在目前的JDK实现中，如果创建MethodHandle的class不被卸载的话，MemberNameTable也不会被销毁，那么它保存的大量WeakReference也一直不会销毁，底层对应的JNIHandleBlock也不会被释放。在示例代码中，这个方法不断被调用，Leak的class不会被卸载，MemberNameTable不会被销毁，并且源源不断得添加MemberName类型的WeakReference到内存中去，占用的内存就会越来越大，从而造成内存泄漏。如果上层应用不意识到这一点在代码逻辑上加以规避，这个bug被触发的概率是很高的。

在JDK8u152的修复中，JDK在底层的为MemberName增加了一个intern操作，概念上类似String.intern, 也就是说构造MethodHandle的时候对应的MemberName信息不是每次都重新new一个新的，而是从缓存中查询是否可以复用一个已有的，由于类以及成员的数量总是有限的，这样就抑制了内存的增长，从而解决了这个问题。

这个问题的确认比较困难，因为内存泄漏点在JDK内部，比较难以发现。内存泄漏时如果通过 `NMT <https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/tooldescr007.html>`_ 发现泄漏在JDK Internal，并且应用逻辑大量生产使用了groovy这样的动态语言，而且zprofiler的类加载视图确实加载了MethodHandle这样的类，就可以考虑内存泄漏是由这个原因产生的。完全确认这个问题需要通过jemalloc，如果jemalloc的调用视图发现MethodHandle占用了比较大的内存，就可以确认这个问题。

第三方JNI库造成堆外内存泄漏
"""""""""""""""""""""""""""
Java应用出于各种各样的目的会使用第三方的JNI库，JNI库由于是C/C++实现的，内存分配完全可以逃离JVM的控制，这时如果实现有缺陷或者上层API使用不当，也很容易造成堆外内存泄漏。

用户可以通过cat /proc/<pid>/maps或者pmap <pid>命令可以查看Java进程的库加载情况，如果发现某个Java进程加载了一些应用特有的so库，就可以重点怀疑这类故障。要完全确认这类故障，需要借助于 `jemalloc`_ 或者 `gperftools`_ 这样专业的内存泄漏工具。由于AJDK原生集成了jemalloc，因此推荐使用jemalloc来排查问题。当用户怀疑内存泄漏时，可以修改Java应用的启动脚本，通过export MALLOC_CONF来控制profiling选项，当重新启动Java应用后就会生成profling数据并对malloc及mmap进行采样跟踪。当内存泄漏出现时，通过jemalloc提供的jeperf的命令，可以生成进程malloc内存的allocation site图谱，如果发现JNI相关的函数占据了主要部分，那么就可以确认内存泄漏是由第三方JNI库生成的。

.. _jemalloc: http://jemalloc.net
.. _gperftools: https://github.com/gperftools

业务变化导致的内存需求升级
""""""""""""""""""""""""""
这种内存需求增大很容易被误认为是堆外内存泄漏，比如业务复杂了，引入了更复杂的中间件，导致加载的Class变多了，或者由于业务压力变大，JNI消耗的内存也变多了等等。

Java应用发现内存泄漏时，可以先检查一下变更列表，比如中间件是否有升级，中间件的升级有可能会加载更多的Class从而消耗更多的Metaspace。如果使用了JNI库，可以检查下机器的CPU使用率和IO读写状况 ,因为CPU使用率或者IO读写比较高通常会是业务量比较大的一个信号, 为了服务更多的业务，JNI的内存消耗也会有一定的增长。如果有这些情况，可以尝试扩大内存，调大内存限制到一个合理值，如果调大内存限制发现并没有异常发生，就可以确认是这类问题。

.. note::

    一个常见的错误是只利用CPU Load来作为业务繁忙的标志，但如果有大量线程阻塞在IO上或者睡眠了，陷入D状态或者S状态，此时CPU Load虽然很高，但CPU使用率并不高，这并不意味着业务一定繁忙。

Codecache增长
"""""""""""""
Codecache也是JVM堆外内存的一部分，主要用来存放JVM生成的native code，比如JIT编译的方法，JNI Stub，Interpreter生成的一些方法等等，其中JIT编译生成的方法占据了Codecache的绝大部分。这部分内存也有可能泄漏。

.. note::

    Codecache在Java运行的过程当中如果发现Codecache满了是会触发回收机制的，回收分为两个阶段，一个阶段是Mark，这个阶段会标记编译方法nmethod的热度，用来决定nmethod是否可以回收，另外一个阶段就是Sweep，这个阶段会根据Codecache里各个nmethod的热度来执行真正的内存清理。Mark阶段是在每个safepoint退出的时候执行，safepoint退出的时候会执行一系列的清理操作，其中的一个操作就是mark_active_nmethods(), 这个操作的大概思路就是为每个nmethod维护一个计数器，代表nmethod的热度，mark_ative_nmethod会扫描所有Java线程的stack，对于stack出现的nmethod，执行计数器加1操作，这样对于热度低于一定阈值的nmthod就可以在后面的Sweep阶段进行回收了。Sweep阶段会检查Codecache是否满了，一旦满了就会根据Mark阶段计算的热度尝试进行内存清理，这种清理会在两个地方进行，一个是在compiler loop里，在从CompileQueue中尝试获取CompileTask之前会进行一次清理尝试，第二个地方就是CompileQueue在执行get()操作获取CompileTask时也会进行尝试，这个地方的清理尝试JVM考虑得非常仔细，除了在get()操作一开始进行清理尝试，另外它也充分考虑到CompileQueue长时间为空阻塞导致清理不及时的情况，如果CompileQueue长时间为空，它也会每隔一定的时间来尝试进行清理(这个时间间隔是由-XX:NmethodSweepCheckInterval控制), 这就一定程度上保证了清理的及时性。

由于Codecache是由JVM内部控制的，一般来说256M对绝大多数应用都是足够的，但由于应用的特殊性或者JVM内部的bug，也会造成泄漏。确认泄漏的方法很简单，通过gcutil, NMT, jconsole, visualVM或者MXBean的MemoryPool等工具可以汇报出code cache size的大小，另外-XX:PrintCodeCache,-XX:PrintCodeCacheOnCompilation也可以在标准输出里获取code cache的相关情况，如果发现code cache不断增加并且达到CodeCacheSize的上限，就可以确认这个问题。在有的JDK版本中，code cacche满会有下面的日志出现，通过文本搜索可以帮助确认这个问题。

``Java HotSpot(TM) 64-Bit Server VM warning: CodeCache is full. Compiler has been disabled.``

发现CodeCache泄漏后需要进一步排查到底是哪些方法造成的泄漏。如果使用的是AJDK，AJDK提供了一个有用的功能。

``jcmd pid CodeCache.dump code_cache_dump_log_file_path=./xx.log``

借助于这个功能，用户可发现CodeCache是由哪些类的哪些方法组成的，编译层级是多少，size分别有多大。如果你用的是OracleJDK或者OpenJDK，那么可以尝试使用SA来把Code Cache里面的方法打印出来。

.. note::
    
    使用SA方法很多，一种是利用clhsdb，attach到目标进程后，用户可以通过js并利用SA API来扩展命令，然后通过jsload／jseval运行扩展命令。另外一种就是直接通过SA API扩展java工具类，然后利用sa-jdi.jar来加载工具类。无论是通过clhsdb js还是sa-jdi.jar java的方式来扩展，底层都是通过sa.codeCache.iterate的sa api来实现对codecache的遍历输出。sa的具体使用方式，参考后续相应的章节。

如果通过上述方式，发现编译的方法不够均匀，比如某一方法被重复编译了很多次，并且占据了大部分空间，就可以怀疑是JVM的 `Bug <https://aone.alibaba-inc.com/issue/10946676?spm=a2o8d.corp_prod_issue_list.0.0.1fb56a85V2WcU1>`__ 。在早期版本的JDK实现中，某个osr方法的编译任务会有可能由于bug导致无法正确处理编译状态，导致同一个方法会被重复编译很多次，从而导致了泄漏。如果您使用的是AJDK 8.3.6以上的版本，这个问题已经被修复，修复的方法是通过修改JVM，使得编译线程在获取编译任务之后，会更加严格得检查相同编译层级的方法是否已经存在了，如果存在就忽略该编译任务。这个Bug的修复在AJDK 8.3.6已经明确被修复，社区OpenJDK暂时没有发现相关的Bug报告，但可以肯定的是至少在OpenJDK的早期版本，这个Bug是被验证存在的。OpenJDK有可能在后续的版本通过另外的途径进行了修复，但这个没有在社区Bug系统中体现出来，如果使用社区OpenJDK，需要用户自己加以排查，尽可能使用高版本的发布。如果编译方法的大小比较均匀，没有重复编译的发生，通常认为只是Codecache Size不够造成的，并非泄漏。

jemalloc造成的内存泄漏
""""""""""""""""""""""
jemalloc是一个非常优秀的内存分配器，可以用来代替glibc malloc，而且提供了很多很强大的profiling工具帮助分析内存泄漏，它在性能，碎片化，memory footprint等方面都有很多改进。一些应用方也自己尝试通过export LD_PRELOAD=xxx的方式来通过劫持jvm的方式使用jemalloc, 或者在JNI库里使用jemalloc.。

但遗憾的是jemalloc在早期某些版本上存在bug，有可能会持续内存泄漏，需要升级jemalloc来规避这方面的bug。在 `jemalloc release notes
<https://github.com/jemalloc/jemalloc/releases>`_ 中可以看出，至少在4.3.1版本和4.1.1版本上都还有memory leak相关的bug fix。建议用户使用最新的jemalloc版本。

要排查此类问题，可以通过/proc/<pid>/maps 来确定是否有jemalloc被加载，如果确定jemalloc已被加载，而且没有其他合理的理由，可以怀疑是这个问题，需要进一步检查版本。

故障解决
^^^^^^^^
如果排查出了故障原因，解决起来一般相对比较容易。

NIO读取磁盘文件造成的内存泄漏需要改变Java的写法，比如应用层明确通过专门的线程来分配DirectByteBuffer，这个分配的DirectByteBuffer通过用户逻辑进行分配和管控。再或者就是不使用NIO来读取磁盘文件。

-XX:+DisableExplicitGC参数导致的DirectByteBuffer内存泄漏需要更改JVM启动参数，去掉-XX:+DisableExplicitGC参数，如果在意system.gc()带来的Full GC开销太大，在CMS的情况下可以使用-XX:+ExplicitGCInvokesConcurrent参数以及-XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, 这样system.gc()会触发CMS并在concurrent阶段进行class卸载，CMS相对于Full GC而言开销还是小很多。

G1也可以使用ExplicitGCInvokesConcurrent，在打开该参数选项后，G1在system.gc()被调用时触发一个concurrent-mark阶段，concurrent-mark会在initial-mark阶段进行YGC回收，并且决定后续的mixed gc来回收老区。

Metaspace的增涨需要用户检查代码动态类加载的逻辑，如果是由于使用groovy，js等动态逻辑造成的，可以通过缓存的思路来解决，缓存已经加载过的类。其它场景需要用户自己灵活处理分析。GeneratedMethodAccessor造成的泄漏排查出反射具体方法名和类名后需要用户通过缓存的思路自行修改代码。

由于Metaspace/Perm里存放的都是Class相关数据，对于CMS而言，安全的方式是打开-XX:CMSClassUnloadingEnabled，保证Class能被CMS卸载。G1也有一个-XX:ClassUnloadingWithConccurentMark来控制Class的卸载，从而确保g1能在ConccurrentMark阶段卸载Class.

JDK8 MethodHandle造成的JDK内部内存泄漏需要用户升级JDK到8u152或者JDK9，如果无法升级，那么尝试利用缓存的思路来缓存MethodHandle可以暂时绕过这个问题。

JNI库造成的用户泄漏，需要追溯JNI库的来源，看是否是用法上的错误，很多第三方的库对于用法都有一些限制，比如有些对象要显式得调用close等等，这些约定用户需要根据JNI库的编程手册进行检查。如果不是这些显而易见的问题，就需要深入源码进行深度调试了。

Codecache造成的内存泄漏，如果确定是Method重复编译的原因，可以考虑升级JDK到最新版本，如果最新版本无法解决，可以尝试通过JVM参数扩大Codecache的大小，扩大内存几乎总是有改进的。另外通过JVM参数强制排除某些方法的编译，也是一种临时的walkaround。JVM针对Codecache也提供了一系列控制参数，允许用户能够通过牺牲一部分性能来降低一部分Cachesize尺寸，比如-XX:InlineSmallCode, -XX:MaxInlineLevel, -XX:MaxInlineSize, -XX:MinInliningThreshold, -XX:InlineSynchronizedMethods等，用户可以根据情况作为临时方案酌情使用。

jemalloc造成的内存泄漏一般通过升级jemalloc版本可以解决，如果实在无法解决，建议还是退回到glibc的malloc实现。

上述措施都无法解决的化，可以尝试简单扩大内存进一步定位问题。
