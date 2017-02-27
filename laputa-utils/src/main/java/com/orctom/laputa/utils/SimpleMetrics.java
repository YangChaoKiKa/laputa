package com.orctom.laputa.utils;

import com.orctom.laputa.exception.IllegalArgException;
import com.orctom.laputa.model.Metric;
import com.orctom.laputa.model.MetricCallback;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple metrics counter
 * Created by hao on 8/7/16.
 */
public class SimpleMetrics {

  private final Logger logger;
  private final long period;
  private final TimeUnit unit;
  private Map<String, SimpleMeter> meters;
  private Map<String, Callable<String>> gauges = new LinkedHashMap<>();
  private MetricCallback callback;

  private ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = Executors.defaultThreadFactory().newThread(r);
    t.setName("simple-metrics");
    t.setDaemon(true);
    return t;
  });

  private SimpleMetrics(Logger logger, long period, TimeUnit unit) {
    if (null == logger) {
      throw new IllegalArgException("org.slf4j.Logger is required.");
    }
    this.logger = logger;
    this.period = period;
    this.unit = unit;
    resetMeters();
    startReporter();
  }

  public static SimpleMetrics create(Logger logger) {
    return new SimpleMetrics(logger, 30, TimeUnit.SECONDS);
  }

  public static SimpleMetrics create(Logger logger, long period, TimeUnit unit) {
    return new SimpleMetrics(logger, period, unit);
  }

  public void resetMeters() {
    meters = new LinkedHashMap<>();
  }

  private void startReporter() {
    es.scheduleAtFixedRate(this::report, period, period, unit);
  }

  public void shutdown() {
    try {
      unit.sleep(period);
    } catch (InterruptedException ignored) {
    }
    es.shutdown();

    meters = null;
    gauges = null;
  }

  public void mark(String key) {
    meter(key).mark();
  }

  public SimpleMeter meter(String key) {
    SimpleMeter meter = meters.get(key);
    if (null != meter) {
      return meter;
    }

    meter = new SimpleMeter();
    synchronized (this) {
      SimpleMeter old = meters.put(key, meter);
      if (null != old) {
        meter.increaseBy(old.getValue());
      }
    }
    return meter;
  }

  public void gauge(String key, Callable<String> callable) {
    gauges.put(key, callable);
  }

  public void setGaugeIfNotExist(String key, Callable<String> callable) {
    gauges.putIfAbsent(key, callable);
  }

  public void setCallback(MetricCallback callback) {
    this.callback = callback;
  }

  private void report() {
    reportGauges();
    reportMeters();
  }

  private void reportGauges() {
    for (Map.Entry<String, Callable<String>> entry : gauges.entrySet()) {
      try {
        String key = entry.getKey();
        String value = entry.getValue().call();
        logger.info("gauge: {}, {}", key, value);
        sendToCallback(key, value);
      } catch (Exception e) {
        logger.error("failed to collect gauge: {}, due to {}", entry.getKey(), e.getMessage());
        logger.error(e.getMessage(), e);
      }
    }
  }

  private void reportMeters() {
    float duration = unit.toSeconds(period);
    for (Map.Entry<String, SimpleMeter> entry : meters.entrySet()) {
      String key = entry.getKey();
      SimpleMeter meter = entry.getValue();
      int count = meter.getValue();
      meter.reset();
      float rate = count / duration;
      logger.info("meter: {}, count: {}, mean: {}/s", entry.getKey(), count, rate);
      sendToCallback(key, count, rate);
    }
  }

  private void sendToCallback(String key, String value) {
    if (null != callback) {
      callback.onMetric(new Metric(key, value));
    }
  }

  private void sendToCallback(String key, int value, float rate) {
    if (null != callback) {
      callback.onMetric(new Metric(key, value, rate));
    }
  }
}
