package biz;

import com.alibaba.dubbo.rpc.benchmark.RpcBenchmarkClient;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class DubboTest {
    @Test
    public void providerTest() {
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ProviderSample.xml");
        ctx.start();
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            new RpcBenchmarkClient().run(new String[1]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
