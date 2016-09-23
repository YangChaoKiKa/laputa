package com.orctom.laputa.server;

import com.google.common.base.Preconditions;
import com.orctom.exception.ClassLoadingException;
import com.orctom.laputa.server.config.MappingConfig;
import com.orctom.laputa.server.config.ServiceConfig;
import com.orctom.laputa.server.internal.BeanFactory;
import com.orctom.laputa.server.internal.Bootstrapper;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.util.Collection;

/**
 * Serving http
 * Created by hao on 9/10/15.
 */
public class LaputaService {

  private static final Logger LOGGER = LoggerFactory.getLogger(LaputaService.class);

  private static LaputaService INSTANCE = new LaputaService();

  private String[] basePackages;
  private Class<? extends Annotation> annotation;

  private LaputaService() {
  }

  public static LaputaService getInstance() {
    return INSTANCE;
  }

  public LaputaService scanPackage(String... basePackages) {
    this.basePackages = basePackages;
    return this;
  }

  public LaputaService forAnnotation(Class<? extends Annotation> annotation) {
    this.annotation = annotation;
    return this;
  }

  public LaputaService withBeanFactory(BeanFactory beanFactory) {
    ServiceConfig.getInstance().setBeanFactory(beanFactory);
    return this;
  }

  public LaputaService withBeanFactory(final ApplicationContext beanFactory) {
    ServiceConfig.getInstance().setBeanFactory(new BeanFactory() {
      @Override
      public <T> T getInstance(Class<T> type) {
        return beanFactory.getBean(type);
      }

      @Override
      public <T> Collection<T> getInstances(Class<T> type) {
        return beanFactory.getBeansOfType(type).values();
      }
    });
    return this;
  }

  public void startup() throws Exception {
    validate();
    loadMappings();
    bootstrapHttpsService();
    bootstrapHttpService();
  }

  private void bootstrapHttpsService() {
    Config config = ServiceConfig.getInstance().getConfig();
    try {
      int port = config.getInt("server.https.port");
      new Bootstrapper(port, true).start(); // start https in separate thread
    } catch (Exception e) {
      LOGGER.error("Failed to start https service, due to: {}", e.getMessage());
    }
  }

  private void bootstrapHttpService() {
    Config config = ServiceConfig.getInstance().getConfig();
    try {
      int port = config.getInt("server.http.port");
      new Bootstrapper(port, false).run(); // not start http in separate thread
    } catch (Exception e) {
      LOGGER.error("Failed to start http service, due to: {}", e.getMessage());
    }
  }

  private void validate() {
    Preconditions.checkArgument(null != basePackages, "'base packages' not set");
    Preconditions.checkArgument(null != annotation, "'annotation' to scan not set");
  }

  private void loadMappings() throws ClassLoadingException {
    MappingConfig.getInstance().scan(annotation, basePackages);
  }

}
