package com.alibaba.dubbo.rpc.benchmark;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Main {
    public static void main(String[] args) {
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
