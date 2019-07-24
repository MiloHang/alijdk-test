package com.alibaba.thanos;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * @author sulong
 */
public class ChatServerHandler extends SimpleChannelInboundHandler<String> {

    private static List<Channel> channels = new ArrayList<>();

    //读取数据
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String s) {
        Channel channel = ctx.channel();

        System.out.println("s = " + s);
        String address = channel.remoteAddress().toString().substring(1);
        for (Channel channel1 : channels) {
            if (channel1 != channel) {
                channel1.writeAndFlush(address + "：说->" + s+"\r\n");
            }
        }
    }

    //通道就绪
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channels.add(channel);
        System.out.println("----" + channel.remoteAddress().toString().substring(1) + ":上线------");
    }

    //通道未就绪
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        System.out.println("----" + channel.remoteAddress().toString().substring(1) + ":下线------");
        channels.remove(channel);
        ctx.channel().parent().close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("出现异常的原因--->"+cause.getMessage());
    }
}
