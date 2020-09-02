package com.tesco.aqueduct.pipe.codec.netty;

import com.tesco.aqueduct.pipe.codec.BrotliCodec;
import com.tesco.aqueduct.pipe.codec.GzipCodec;
import com.tesco.aqueduct.pipe.codec.netty.handler.CodecClientHandler;
import com.tesco.aqueduct.pipe.codec.netty.handler.HttpCodecHandler;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;

import javax.inject.Singleton;

@Singleton
public class CodecPipelineCustomizer implements BeanCreatedEventListener<ChannelPipelineCustomizer> {

/*
    private final BrotliCodec brotliCodec;
    private final GzipCodec gzipCodec;

    @Inject
    public CodecPipelineCustomizer(BrotliCodec brotliCodec, GzipCodec gzipCodec) {
        this.brotliCodec = brotliCodec;
        this.gzipCodec = gzipCodec;
    }
*/

    @Override
    public ChannelPipelineCustomizer onCreated(BeanCreatedEvent<ChannelPipelineCustomizer> event) {

        ChannelPipelineCustomizer customizer = event.getBean();

        if (customizer.isServerChannel()) {
            customizer.doOnConnect(pipeline -> {
                pipeline.addAfter(
                        ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC,
                        "codec",
                        new HttpCodecHandler(new GzipCodec(), new BrotliCodec())
                );
                return pipeline;
            });
        } else {
            customizer.doOnConnect(pipeline -> {
                pipeline.addAfter(
                        ChannelPipelineCustomizer.HANDLER_HTTP_CLIENT_CODEC,
                        "codec",
                        new CodecClientHandler()
                );
                return pipeline;
            });
        }
        return customizer;
    }
}
