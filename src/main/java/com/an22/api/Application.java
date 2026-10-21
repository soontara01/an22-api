package com.an22.api;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    // instance ใช้ UTC เสมอ — วันธุรกิจคิดด้วย Clock (Asia/Bangkok) ในโค้ด ไม่พึ่ง timezone ของเครื่อง
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(Application.class, args);
  }
}
