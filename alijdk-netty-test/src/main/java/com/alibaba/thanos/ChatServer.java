package com.alibaba.thanos;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * @author sulong
 */
public class ChatServer {

    private static EventLoopGroup bossGroup = new NioEventLoopGroup();
    private static EventLoopGroup workerGroup = new NioEventLoopGroup();

    public static void main(String[] args) throws InterruptedException {

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        System.out.println("----服务器开始配置----");
        serverBootstrap.group(bossGroup,workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG,128)
                .childOption(ChannelOption.SO_KEEPALIVE,true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("decoder",new StringDecoder());//解码器
                        pipeline.addLast("encoder",new StringEncoder());//编码器
                        pipeline.addLast(new ChatServerHandler());//处理器
                    }
                });
        System.out.println("----服务器配置完成----");
        System.out.println("----服务器开始启动----");
        ChannelFuture cf = serverBootstrap.bind(9999).sync();
        cf.channel().closeFuture().sync();

        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
        System.out.println("==============");

    }
/*
    static void shutDown(){

        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
        System.out.println("==============");
    }*/
}
