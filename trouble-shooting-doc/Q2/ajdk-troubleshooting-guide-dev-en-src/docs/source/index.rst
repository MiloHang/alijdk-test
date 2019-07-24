.. atg documentation master file, created by
   sphinx-quickstart on Mon Jul 16 20:26:57 2018.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

===============================
AJDK Troubleshooting Guide
===============================

In the process of online operation and daily development, engineers often
encounter various JDK problems. These problems are difficult to diagnose, our
group has been lack of standard guidance of such problems, the engineers'
experience of each BU can not be shared, and there is a lot of duplication of
work between them.

AJDK Troubleshooting Guide is a set of JDK problem handling specifications
compiled by Alibaba JVM team. It summarizes and describes the phenomena of Java
problem, the reasons, how to diagnose. Based on this set of specifications,
engineers can locate problems easily and quickly, and give solutions. Moreover,
this set of specifications absorbs the experience of all BU engineers in various
aspects, and also enables troubleshooting experience to be shared within the
group, thus improves the level of Java development and operation and maintenance
of the group.

In summary, the main objectives of Alibaba JDK Troubleshooting Guide(ATG)
project are as follows:

- Standardize the JDK troubleshooting process of Group to improve the efficiency
  of troubleshooting.
- Summarize the experience of JDK troubleshooting in Group to improve the Java
  development level of developers.
- Provide knowledge reserve for automation and instrumentalization of group
  operation and maintenance troubleshooting.

This set of specifications is aim at AJDK8. AJDK8 is an OpenJDK 8U branch
maintained by the Alibaba JVM team. Compared with the community branch, AJDK8
has changed many modules, developed many useful features, and provided more
powerful support in tools, which is more secure, efficient and reliable.



.. toctree::
   :maxdepth: 2

   chapter-01/section-01/section.rst
   chapter-01/section-02/section.rst


Indices and tables
==================

* :ref:`genindex`
* :ref:`modindex`
* :ref:`search`
