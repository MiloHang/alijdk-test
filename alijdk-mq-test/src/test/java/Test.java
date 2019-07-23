import com.alibaba.Producer.Consumer;
import com.alibaba.Producer.SyncProducer;

public class Test {

    @org.junit.Test
    public void test() throws Exception {
        String addr = "localhost:9876"; //namesrv ip

        SyncProducer.main(new String[]{addr});
        Consumer.main(new String[]{addr});
    }

}
