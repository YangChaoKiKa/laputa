package com.orctom.laputa.service.internal;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;

public class LaputaServerInitializer extends ChannelInitializer<SocketChannel> {

  private final SslContext sslContext;

  public LaputaServerInitializer(SslContext sslContext) {
    this.sslContext = sslContext;
  }

  @Override
  public void initChannel(SocketChannel ch) {
    ChannelPipeline p = ch.pipeline();
    if (sslContext != null) {
      p.addLast(sslContext.newHandler(ch.alloc()));
    }
    p.addLast(new HttpServerCodec());
    p.addLast(new HttpObjectAggregator(1048576));
    p.addLast(new HttpContentCompressor(1));
    p.addLast(new LaputaServerHandler());
  }
}
