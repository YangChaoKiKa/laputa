package com.orctom.laputa.service.internal;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import com.orctom.laputa.exception.IllegalConfigException;
import com.orctom.laputa.service.LaputaService;
import com.orctom.laputa.service.config.Configurator;
import com.orctom.laputa.service.config.MappingConfig;
import com.orctom.laputa.service.exception.FileUploadException;
import com.orctom.laputa.service.exception.ParameterValidationException;
import com.orctom.laputa.service.exception.RequestProcessingException;
import com.orctom.laputa.service.exception.TemplateProcessingException;
import com.orctom.laputa.service.model.*;
import com.orctom.laputa.service.processor.PostProcessor;
import com.orctom.laputa.service.processor.PreProcessor;
import com.orctom.laputa.service.processor.RequestProcessor;
import com.orctom.laputa.service.translator.ResponseTranslator;
import com.orctom.laputa.service.translator.ResponseTranslators;
import com.orctom.laputa.service.translator.TemplateResponseTranslator;
import com.orctom.laputa.service.util.ArgsResolver;
import com.orctom.laputa.service.util.ParamResolver;
import com.orctom.laputa.utils.ClassUtils;
import com.orctom.laputa.utils.SimpleMeter;
import com.orctom.laputa.utils.SimpleMetrics;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.reflect.FastMethod;

import javax.activation.MimetypesFileTypeMap;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.orctom.laputa.service.Constants.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;

/**
 * request processor
 * Created by hao on 1/6/16.
 */
public class LaputaRequestProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(LaputaRequestProcessor.class);

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private static SimpleMeter simpleMeter;
  private static final String METER_REQUESTS = "requests";
  private static final byte[] ERROR_CONTENT = INTERNAL_SERVER_ERROR.reasonPhrase().getBytes();

  private static final String FILE = ".file";
  private static final String FILENAME = ".originalFilename";

  private static final String CONTENT_TYPE = ".contentType";

  private static final HttpDataFactory HTTP_DATA_FACTORY = new DefaultHttpDataFactory(
      Configurator.getInstance().getPostDataUseDiskThreshold(),
      Configurator.getInstance().getCharset()
  );

  private static final Map<HttpMethod, HTTPMethod> HTTP_METHODS = ImmutableMap.of(
      HttpMethod.DELETE, HTTPMethod.DELETE,
      HttpMethod.HEAD, HTTPMethod.HEAD,
      HttpMethod.OPTIONS, HTTPMethod.OPTIONS,
      HttpMethod.POST, HTTPMethod.POST,
      HttpMethod.PUT, HTTPMethod.PUT
  );

  private static final MimetypesFileTypeMap MIMETYPES_FILE_TYPE_MAP = new MimetypesFileTypeMap();

  private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

  private static ServiceLoader<RequestProcessor> requestProcessors = ServiceLoader.load(RequestProcessor.class);

  private static RateLimiter rateLimiter;

  LaputaRequestProcessor() {
    if (LOGGER.isInfoEnabled()) {
      simpleMeter = SimpleMetrics.create(LOGGER).meter(METER_REQUESTS);
    }

    Integer maxRequestsPerSecond = Configurator.getInstance().getThrottle();
    if (null == maxRequestsPerSecond) {
      return;
    }

    rateLimiter = RateLimiter.create(maxRequestsPerSecond);
  }

  ResponseWrapper handleRequest(FullHttpRequest request) {
    if (LOGGER.isInfoEnabled()) {
      simpleMeter.mark();
    }

    try {
      RequestWrapper requestWrapper = getRequestWrapper(request);

      String mediaType = MIMETYPES_FILE_TYPE_MAP.getContentType(requestWrapper.getPath());

      if (null != rateLimiter && !rateLimiter.tryAcquire(200, TimeUnit.MILLISECONDS)) {
        return new ResponseWrapper(mediaType, TOO_MANY_REQUESTS);
      }

      for (RequestProcessor requestProcessor : requestProcessors) {
        if (requestProcessor.canHandleRequest(requestWrapper)) {
          ResponseWrapper responseWrapper = requestProcessor.handleRequest(requestWrapper, mediaType);
          if (null != responseWrapper) {
            return responseWrapper;
          }
        }
      }

      ResponseTranslator translator = ResponseTranslators.getTranslator(requestWrapper);
      return handleRequest(requestWrapper, translator);

    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
      return new ResponseWrapper(MediaType.TEXT_PLAIN.getValue(), e.getMessage().getBytes(), BAD_REQUEST);
    }
  }

  private ResponseWrapper handleRequest(RequestWrapper requestWrapper, ResponseTranslator translator) {
    Context ctx = getContext(requestWrapper);

    String mediaType = translator.getMediaType();

    long start = System.currentTimeMillis();
    // pre-processors
    preProcess(requestWrapper, ctx);

    try {
      MappingConfig mappingConfig = MappingConfig.getInstance();
      RequestMapping mapping = mappingConfig.getMapping(
          requestWrapper.getPath(),
          getHttpMethod(requestWrapper.getHttpMethod())
      );

      if (null == mapping) {
        for (RequestProcessor requestProcessor : requestProcessors) {
          ResponseWrapper responseWrapper = requestProcessor.handleRequest(requestWrapper, mediaType);
          if (null != responseWrapper && NOT_FOUND != responseWrapper.getStatus()) {
            return responseWrapper;
          }
        }

        mapping = mappingConfig._404();
      }

      String permanentRedirectTo = mapping.getRedirectTo();
      if (!Strings.isNullOrEmpty(permanentRedirectTo)) {
        return new ResponseWrapper(permanentRedirectTo, true);
      }

      Object data;
      try {
        data = processRequest(requestWrapper, ctx, mapping);
      } catch (ParameterValidationException e) {
        data = new ValidationError(e.getMessages());
        ctx.put("error", e.getMessage());
        onValidationError(translator, requestWrapper, ctx);
      }

      String redirectTo = ctx.getRedirectTo();
      if (!Strings.isNullOrEmpty(redirectTo)) {
        if (null != data) {
          LOGGER.warn("`return null;` probably is missing after `context.redirectTo(...`");
        }
        return new ResponseWrapper(redirectTo, false);
      }

      // post-processors
      Object processed = postProcess(data);

      long end = System.currentTimeMillis();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("{} took: {}ms", requestWrapper.getPath(), (end - start));
      }

      byte[] content = translator.translate(mapping, processed, ctx);
      boolean is404 = PATH_404.equals(mapping.getUriPattern());
      return new ResponseWrapper(mediaType, content, is404 ? NOT_FOUND : OK);
    } catch (ParameterValidationException e) {
      return new ResponseWrapper(mediaType, e.getMessage().getBytes(UTF8), BAD_REQUEST);
    } catch (IllegalConfigException | TemplateProcessingException e) {
      LOGGER.error(e.getMessage());
      return new ResponseWrapper(mediaType, ERROR_CONTENT, INTERNAL_SERVER_ERROR);
    } catch (Throwable e) {
      LOGGER.error(e.getMessage(), e);
      return new ResponseWrapper(mediaType, ERROR_CONTENT, INTERNAL_SERVER_ERROR);
    }
  }

  private RequestWrapper getRequestWrapper(FullHttpRequest request) {
    HttpMethod method = request.method();
    String uri = request.uri();

    if (HttpMethod.POST.equals(method) ||
        HttpMethod.PUT.equals(method) ||
        HttpMethod.PATCH.equals(method)) {
      return wrapPostRequest(request);
    } else {
      return wrapGetRequest(request, method, uri);
    }
  }

  private Context getContext(RequestWrapper requestWrapper) {
    Context ctx = new Context();
    ctx.put(KEY_URL, requestWrapper.getPath());
    ctx.put(KEY_REFERER, requestWrapper.getHeaders().get(HttpHeaderNames.REFERER));
    return ctx;
  }

  private RequestWrapper wrapPostRequest(FullHttpRequest request) {
    String data = getRequestData(request);

    HttpPostRequestDecoder decoder;
    try {
      decoder = new HttpPostRequestDecoder(HTTP_DATA_FACTORY, request);
    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
      LOGGER.error("Decoder exception: {}", data);
      throw new RequestProcessingException(e.getMessage(), e);
    }

    List<InterfaceHttpData> bodyDatas = decoder.getBodyHttpDatas();

    Map<String, List<String>> parameters = new HashMap<>();

    try {
      for (InterfaceHttpData bodyData : bodyDatas) {
        if (HttpDataType.Attribute == bodyData.getHttpDataType()) {
          Attribute attribute = (Attribute) bodyData;
          addToParameters(parameters, attribute);

        } else if (HttpDataType.FileUpload == bodyData.getHttpDataType()) {
          FileUpload fileUpload = (FileUpload) bodyData;
          addToParameters(parameters, fileUpload);
        }
      }

      return new RequestWrapper(request.method(), request.headers(), request.uri(), parameters, data);

    } catch (HttpPostRequestDecoder.EndOfDataDecoderException e) {
      return new RequestWrapper(request.method(), request.headers(), request.uri(), parameters, data);

    } finally {
      decoder.destroy();
    }
  }

  private void addToParameters(Map<String, List<String>> parameters, Attribute attribute) {
    try {
      String value = attribute.getValue();
      if (Strings.isNullOrEmpty(value)) {
        return;
      }

      String name = attribute.getName();
      List<String> params = parameters.computeIfAbsent(name, k -> new ArrayList<>());
      params.add(value);
    } catch (IOException e) {
      throw new RequestProcessingException(e.getMessage(), e);
    }
  }

  private void addToParameters(Map<String, List<String>> parameters, FileUpload fileUpload) {
    try {
      File uploadedFile = fileUpload.getFile();
      parameters.put(fileUpload.getName() + FILE, Lists.newArrayList(uploadedFile.getAbsolutePath()));
      parameters.put(fileUpload.getName() + FILENAME, Lists.newArrayList(fileUpload.getFilename()));
      parameters.put(fileUpload.getName() + CONTENT_TYPE, Lists.newArrayList(fileUpload.getContentType()));
    } catch (IOException e) {
      throw new FileUploadException("Failed to upload file: " + e.getMessage(), e);
    }
  }

  private RequestWrapper wrapGetRequest(FullHttpRequest request, HttpMethod method, String uri) {
    QueryStringDecoder queryStringDecoder = getQueryStringDecoder(uri);
    String path = queryStringDecoder.path();
    Map<String, List<String>> queryParameters = queryStringDecoder.parameters();
    String data = getRequestData(request);
    return new RequestWrapper(method, request.headers(), path, queryParameters, data);
  }

  private String getRequestData(FullHttpRequest request) {
    return request.content().toString(CharsetUtil.UTF_8);
  }

  private void onValidationError(ResponseTranslator translator, RequestWrapper requestWrapper, Context ctx) {
    if (translator instanceof TemplateResponseTranslator) {
      String referer = (String) ctx.get(KEY_REFERER);
      if (Strings.isNullOrEmpty(referer) || requestWrapper.getPath().equals(referer)) {
        ctx.redirectTo(PATH_400);
        return;
      }
      StringBuilder url = new StringBuilder(referer);
      if (referer.contains(SIGN_QUESTION)) {
        url.append(SIGN_AND);
      } else {
        url.append(SIGN_QUESTION);
      }
      url.append("error=").append(ctx.get("error"));
      ctx.redirectTo(url.toString());
    }
  }

  private <T> Collection<T> getBeansOfType(Class<T> type) {
    return LaputaService.getInstance().getApplicationContext().getBeansOfType(type).values();
  }

  private void preProcess(RequestWrapper requestWrapper, Context ctx) {
    Collection<PreProcessor> preProcessors = getBeansOfType(PreProcessor.class);
    if (preProcessors.isEmpty()) {
      return;
    }

    for (PreProcessor processor : preProcessors) {
      processor.process(requestWrapper, ctx);
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("pre-processed, {}", requestWrapper.toString());
    }
  }

  private Object postProcess(Object data) {
    Collection<PostProcessor> postProcessors = getBeansOfType(PostProcessor.class);
    if (postProcessors.isEmpty()) {
      return data;
    }

    List<PostProcessor> processors = new ArrayList<>(postProcessors);
    Object processed = data;
    if (processors.size() > 1) {
      processors.sort(Comparator.comparingInt(PostProcessor::getOrder));
    }
    for (PostProcessor processor : processors) {
      LOGGER.debug("processing post-processor: #{}", processor.getOrder());
      processed = processor.process(processed);
    }

    return processed;
  }

  private QueryStringDecoder getQueryStringDecoder(String uri) {
    Charset charset = Configurator.getInstance().getCharset();
    if (null != charset) {
      return new QueryStringDecoder(uri, charset);
    } else {
      return new QueryStringDecoder(uri);
    }
  }

  private HTTPMethod getHttpMethod(HttpMethod method) {
    HTTPMethod httpMethod = HTTP_METHODS.get(method);
    if (null != httpMethod) {
      return httpMethod;
    }

    return HTTPMethod.GET;
  }

  private Object processRequest(RequestWrapper requestWrapper, Context ctx, RequestMapping mapping)
      throws InvocationTargetException, IllegalAccessException {
    FastMethod handlerMethod = mapping.getHandlerMethod();
    Object target = mapping.getTarget();

    // process @Data
    Class<?> dataType = mapping.getDataType();
    if (null != dataType) {
      if (ClassUtils.isSimpleValueType(dataType)) {
        return handlerMethod.invoke(target, new Object[]{requestWrapper.getData()});
      } else {
        Object arg = JSON.parseObject(requestWrapper.getData(), dataType);
        return handlerMethod.invoke(target, new Object[]{arg});
      }
    }

    Map<String, Class<?>> paramTypes = mapping.getParamTypes();
    if (paramTypes.isEmpty()) {
      return handlerMethod.invoke(target, null);
    }

    Map<String, String> params = ParamResolver.extractParams(mapping, requestWrapper);

    Object[] args = ArgsResolver.resolveArgs(params, paramTypes, ctx);

    validate(target, handlerMethod.getJavaMethod(), args);

    try {
      return handlerMethod.invoke(target, args);
    } catch (InvocationTargetException e) {
      LOGGER.error("Some parameter is missing while invoking " + handlerMethod.getJavaMethod());
      throw new ParameterValidationException("Some parameter is missing, please check the API doc.");
    }
  }

  private void validate(Object target, Method method, Object[] args) {
    ExecutableValidator executableValidator = VALIDATOR.forExecutables();
    Set<ConstraintViolation<Object>> violations = executableValidator.validateParameters(target, method, args);
    if (violations.isEmpty()) {
      return;
    }

    throw new ParameterValidationException(violations);
  }
}
