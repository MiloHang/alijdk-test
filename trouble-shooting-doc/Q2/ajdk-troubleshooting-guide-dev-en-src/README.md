# Welcome to AJDK Troubleshooting Guide

AJDK Troubleshooting Guide is a handbook to help engineers diagnose different kinds of JDK problems as fast as possible. It is a book maintained by markdown file. You can choose your preferable language version.

# Installation

Please install pythyon2.7+.

Please install sphnix.
```
sudo /home/tops/bin/pip -v install sphinx -i https://pypi.python.org/simple/
```

You also can choose to install sphnix from source code.
```
git clone https://github.com/sphinx-doc/sphinx/
cd sphinx
sudo /home/tops/bin/pip -v install . -i https://pypi.python.org/simple/
```

Build the guide.
```
cd docs
make html
cp build/html/* $ANY_WHERE_YOU_WANT
```

# Online Reading
- [DailyBuild中文版最新](http://10.101.89.158/atg/latest/index.html)
- [中文版v1.0.0](http://ci.jvm.alibaba.net/vmfarm/AlibabaJDKTroubleshootingGuide/v1.0.0/index.html)
- [English](开发中)

# Contact
- jvm@list.alibaba-inc.com
- JVM答疑钉钉群

