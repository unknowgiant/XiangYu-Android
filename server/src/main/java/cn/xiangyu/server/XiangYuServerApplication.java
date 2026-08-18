package cn.xiangyu.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XiangYuServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(XiangYuServerApplication.class, args);
    }
}
