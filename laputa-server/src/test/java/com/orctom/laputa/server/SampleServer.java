package com.orctom.laputa.server;

import com.orctom.laputa.server.test.AppConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Controller;

public class SampleServer {

  public static void main(String[] args) {
    ApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class);
    try {
      LaputaService.getInstance()
          .scanPackage("com.orctom.laputa.server.test")
          .forAnnotation(Controller.class)
          .withBeanFactory(context)
          .startup();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
