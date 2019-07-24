.. _memory_issues:

memory issues
=============
Java memory is divided into two categories, one is on-heap memory, and the other
is off-heap memory.

Off-heap memory refers to memory that is not managed by the JVM garbage
collector. This kind of memory generally includes Metaspace, memory allocated
through Unsafe, memory that served for JVM, and the memory maintained by the
internal user JNI (these kinds of memory is mainly allocated by c/cpp
traditional ``new``, ``malloc`` operators, it should be noted that the objects
created by the APIs such as ``NewObject``, ``NewObjectArray`` are not counted as
off-heap memory.) Because this part of the memory is not managed by the JVM
garbage collector, if it is used incorrectly, it is easy to cause memory issues.

Corresponding to the off-heap memory is the on-heap memory, it is mainly refers
to the memory managed by the JVM garbage collector, including the objects
allocated by the new operator in the Java and the objects created by the JNI
method such as ``NewObject``, ``NewObjectArray``. These objects are managed
automatically by the garbage collector, users do not need to explicitly perform
memory release operations. The garbage collector will automatically release the
memory if it finds that the object is no longer used by the program according to
certain policies.

Memory issues are most failures that Java applications often encounter. The
performance of such failures is varied. For example, some memory issues can
cause GC anomalies, which will affect the response time and throughput of the
application. Some leakage of off-heap memory will directly causes OOM Killer of
OS, there are also memory issues such as CodeCache that may cause the
application's CPU load to suddenly rise and so on.

After receiving the alarm, the troubleshooting personnel can quickly identify
such issues through system commands (such as ``top``, ``tsar``, ``jstat``, etc.),
checkout gc logs and application service logs, and then classify the issues, so
that enter the corresponding entry of this specification for troubleshooting.

Before troubleshooting the memory issues, the troubleshooting personnel needs to
have a correct understanding of the memory concept and knowledge points, please
refer to the corresponding chapter. There will also be links in the
troubleshooting process to jump.

.. toctree::
    :maxdepth: 1

    subsection-01
    subsection-02
    subsection-03
