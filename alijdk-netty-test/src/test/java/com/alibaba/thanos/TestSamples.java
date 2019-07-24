package com.alibaba.thanos;

import org.junit.Test;

public class TestSamples {

    @Test
    public void test01() {

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ChatServer.main(new String[]{});
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        try {
            Thread.sleep(2000);
            ChatClient.main(new String[]{});
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
