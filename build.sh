cd alijdk-test;
# set MAVEN_OPTS=-XX:+UseWisp2
# alijdk-httpclient-test,alijdk-netty-test,
# models=alijdk-dubbo-test
# mvn -pl  $models clean test   -Dcom.alibaba.wisp.daemonWorker=false  >> test.log


cd jar
# hello-world-2.0.1.RELEASE.jar
#java -jar  -XX:+UseWisp2  -Dcom.alibaba.wisp.daemonWorker=false  SpringBootWebFlux-1.0-SNAPSHOT.jar > jar.log
java -jar   -Dcom.alibaba.wisp.daemonWorker=false  SpringBootWebFlux-1.0-SNAPSHOT.jar > jar1.log