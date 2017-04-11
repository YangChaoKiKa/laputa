package com.orctom.laputa.translator;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.orctom.laputa.exception.IllegalConfigException;
import com.orctom.laputa.service.config.Configurator;
import com.orctom.laputa.service.exception.TemplateProcessingException;
import com.orctom.laputa.service.model.Context;
import com.orctom.laputa.service.model.RequestMapping;
import com.orctom.laputa.service.translator.content.TemplateContentTranslator;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Freemarker web page renderer
 * Created by hao on 2/19/17.
 */
public class FreemarkerContentTranslator extends TemplateContentTranslator {

  private static final Logger LOGGER = LoggerFactory.getLogger(FreemarkerContentTranslator.class);

  private static final String TEMPLATE_SUFFIX = ".ftl";

  private static final Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);

  private static final boolean isDebugEnabled = Configurator.getInstance().isDebugEnabled();

  private static LoadingCache<RequestMapping, freemarker.template.Template> templates = CacheBuilder.newBuilder()
      .build(
          new CacheLoader<RequestMapping, freemarker.template.Template>() {
            @Override
            public freemarker.template.Template load(RequestMapping mapping) throws Exception {
              return getTemplate0(mapping);
            }
          }
      );

  public FreemarkerContentTranslator() {
    cfg.setClassForTemplateLoading(FreemarkerContentTranslator.class, TEMPLATE_PREFIX);
    cfg.setDefaultEncoding("UTF-8");
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    cfg.setLogTemplateExceptions(false);

    if (isDebugEnabled) {
      cfg.setTemplateUpdateDelayMilliseconds(200);
    }
  }

  @Override
  public byte[] translate(RequestMapping mapping, Object data, Context ctx) throws IOException {
    try {
      freemarker.template.Template template = getTemplate(mapping);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Writer writer = new BufferedWriter(new OutputStreamWriter(out));
      Map<String, Object> model = new HashMap<>(ctx.getData());
      model.put("model", data);
      template.process(model, writer);
      return out.toByteArray();
    } catch (Exception e) {
      throw new TemplateProcessingException(e.getMessage());
    }
  }

  private freemarker.template.Template getTemplate(RequestMapping mapping) throws ExecutionException {
    if (isDebugEnabled) {
      return getTemplate0(mapping);
    }

    return templates.get(mapping);
  }

  private static freemarker.template.Template getTemplate0(RequestMapping mapping) {
    try {
      String templatePath = getTemplatePath(mapping) + TEMPLATE_SUFFIX;
      LOGGER.debug("Template Path: {} for url: {}", templatePath, mapping.getUriPattern());
      return cfg.getTemplate(templatePath);
    } catch (IOException e) {
      throw new IllegalConfigException(e.getMessage());
    }
  }
}
