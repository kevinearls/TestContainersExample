package io.kevinearls.trydocker;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;

public class TestThis {
    @Test
    public void simpleTest() throws Exception{
        //System.out.println("WT dude");
        GenericContainer gc = new GenericContainer("hello-world");
        gc.start();

        WaitingConsumer waitingConsumer = new WaitingConsumer();
        ToStringConsumer toStringConsumer = new ToStringConsumer();
        Consumer<OutputFrame> composedConsumer = toStringConsumer.andThen(waitingConsumer);
        gc.followOutput(composedConsumer);

        waitingConsumer.waitUntilEnd(30, TimeUnit.SECONDS);

        String utf8String = toStringConsumer.toUtf8String();
        System.out.println(utf8String);
    }
}

/*class StringConsumer<OutputFrame> implements Consumer<OutputFrame> {
    public void accept(OutputFrame outputFrame) {
        String wtf = new String(outputFrame.getBytes());
        System.out.println("In accept, got [" + outputFrame.getClass().getCanonicalName() + "]");

    }
}*/
