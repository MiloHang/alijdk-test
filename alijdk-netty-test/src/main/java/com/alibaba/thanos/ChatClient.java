package com.alibaba.thanos;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;


/**
 * @author sulong
 */
public class ChatClient {
    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("decoder",new StringDecoder());
                        pipeline.addLast("encoder",new StringEncoder());
                        pipeline.addLast(new ChatClientHandler());
                    }
                });
        ChannelFuture sync = bootstrap.connect("127.0.0.1", 9999).sync();

        Channel channel = sync.channel();

        System.out.println("========"+channel.localAddress().toString().substring(1)+"===========");

        System.out.println("--------------客户端启动，发送若干次消息并主动关闭------------------");
        int loop = 10;
        while ((loop--) > 0) {
            String s = "消息"+loop;
            System.out.println("s = " + s);
            channel.writeAndFlush(s+"\r\n");
            // Thread.sleep(1000);
        }
        group.shutdownGracefully();
    }
}
