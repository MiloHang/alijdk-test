.. atg documentation master file, created by
   sphinx-quickstart on Mon Jul 16 20:26:57 2018.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

===============================
阿里巴巴JDK故障排查指南
===============================

在线上运维和日常开发过程中，工程师往往会遇到各种各样的JDK问题。这些问题诊断起来比较困难，集团一直以来缺乏这类问题的标准处理规范，各个BU工程师之间的经验也无法共享，彼此之间的重复劳动很多。

AJDK Troubleshooting Guide是由Alibaba JVM团队整理出的一套JDK问题处理规范，描述总结集团各个BU线上遇到的各种Java问题的现象，原因，诊断步骤以及处理方法。基于这套规范，工程师可以方便快捷的定位出问题，并给出解决方案。并且这套规范吸取了各个BU工程师的各方面经验，也使得JDK故障排查经验能在集团范围内得以共享，提高了集团Java开发及运维水平。

综上所述，Alibaba JDK Troubleshooting Guide(ATG)项目的主要目标:

- 规范标准化集团的JDK问题排查流程，提高故障解决效率。
- 总结沉淀集团JDK故障排查经验，提高开发人员Java水平。
- 为集团运维故障排查自动化，工具化提供知识储备。

这套规范主要针对AJDK8。 AJDK8是由Alibaba JVM团队维护的OpenJDK 8u分支，相比社区分支，AJDK8魔改了很多模块，开发了很多有用的特性，并在工具方面提供了更加强大的支持，更加安全，高效和可靠。



.. toctree::
   :maxdepth: 2
   
   chapter-01/section-01/section.rst
   chapter-01/section-02/section.rst


Indices and tables
==================

* :ref:`genindex`
* :ref:`modindex`
* :ref:`search`
