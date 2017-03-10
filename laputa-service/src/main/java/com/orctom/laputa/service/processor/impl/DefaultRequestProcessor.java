package com.orctom.laputa.service.processor.impl;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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
import com.orctom.laputa.utils.FileUtils;
import com.orctom.laputa.utils.SimpleMeter;
import com.orctom.laputa.utils.SimpleMetrics;
import com.typesafe.config.Config;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.CharsetUtil;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.reflect.FastMethod;

import javax.activation.MimetypesFileTypeMap;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.orctom.laputa.service.Constants.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;

/**
 * request processor
 * Created by hao on 1/6/16.
 */
public class DefaultRequestProcessor implements RequestProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRequestProcessor.class);

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

  private static RateLimiter rateLimiter;

  private static Map<String, String> staticFileMapping = new HashMap<>();
  private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");

  private final LoadingCache<String, byte[]> classpathStaticFileContentCache = CacheBuilder.newBuilder()
      .softValues()
      .maximumSize(500)
      .build(new CacheLoader<String, byte[]>() {
        @Override
        public byte[] load(String resource) throws Exception {
          return getContentAsByteArray(resource);
        }
      });

  public DefaultRequestProcessor() {
    initStaticPaths();
    if (LOGGER.isInfoEnabled()) {
      simpleMeter = SimpleMetrics.create(LOGGER).meter(METER_REQUESTS);
    }

    Integer maxRequestsPerSecond = Configurator.getInstance().getThrottle();
    if (null == maxRequestsPerSecond) {
      return;
    }

    rateLimiter = RateLimiter.create(maxRequestsPerSecond);
  }

  private static void initStaticPaths() {
    Config config = Configurator.getInstance().getConfig();

    @SuppressWarnings("unchecked")
    List<Config> staticFileMappingsConfig = (List<Config>) config.getConfigList(CFG_URLS_STATIC_MAPPINGS);
    for (Config staticFileMappingConfig : staticFileMappingsConfig) {
      String uri = staticFileMappingConfig.getString(CFG_URI);
      String path = staticFileMappingConfig.getString(CFG_PATH);
      staticFileMapping.put(uri, path);
      LOGGER.info("Added static content mapping: {} -> {}", uri, path);
    }
  }

  @Override
  public ResponseWrapper handleRequest(FullHttpRequest request) {
    if (LOGGER.isInfoEnabled()) {
      simpleMeter.mark();
    }

    try {
      RequestWrapper requestWrapper = getRequestWrapper(request);

      String mediaType = MIMETYPES_FILE_TYPE_MAP.getContentType(requestWrapper.getPath());

      if (null != rateLimiter && !rateLimiter.tryAcquire(200, TimeUnit.MILLISECONDS)) {
        return new ResponseWrapper(mediaType, TOO_MANY_REQUESTS);
      }

      String staticFilePath = getStaticFileMappingPath(requestWrapper.getPath());
      if (!Strings.isNullOrEmpty(staticFilePath)) {
        return handleStaticFileRequest(requestWrapper, mediaType, staticFilePath);

      } else {
        ResponseTranslator translator = ResponseTranslators.getTranslator(requestWrapper);
        return handleRequest(requestWrapper, translator);
      }

    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
      return new ResponseWrapper(MediaType.TEXT_PLAIN.getValue(), e.getMessage().getBytes(), BAD_REQUEST);
    }
  }

  private String getStaticFileMappingPath(String uri) {
    for (Map.Entry<String, String> entry : staticFileMapping.entrySet()) {
      if (uri.startsWith(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private ResponseWrapper handleStaticFileRequest(RequestWrapper requestWrapper,
                                                  String mediaType,
                                                  String staticFilePath) {
    if (HttpMethod.GET != requestWrapper.getHttpMethod()) {
      return new ResponseWrapper(mediaType, METHOD_NOT_ALLOWED.reasonPhrase().getBytes(), METHOD_NOT_ALLOWED);
    }

    if (isUriInvalid(requestWrapper.getPath())) {
      return fileNotFound(mediaType);
    }

    String uri = requestWrapper.getPath();

    if (uri.endsWith(PATH_SEPARATOR)) {
      uri += PATH_INDEX;
    }

    if (Strings.isNullOrEmpty(staticFilePath)) {
      return fromClasspath(requestWrapper, mediaType, PATH_THEME, uri);
    }

    if (staticFilePath.startsWith(PREFIX_CLASSPATH)) {
      return fromClasspath(requestWrapper, mediaType, staticFilePath.substring(PREFIX_CLASSPATH_LEN), uri);
    }

    return fromFileSystem(requestWrapper, mediaType, staticFilePath, uri);
  }

  private boolean isUriInvalid(String uri) {
    if (uri.isEmpty() || uri.charAt(0) != SLASH) {
      return true;
    }

    uri = uri.replace(SLASH, File.separatorChar);

    return uri.contains(File.separator + DOT) ||
        uri.contains(DOT + File.separator) ||
        uri.charAt(0) == DOT || uri.charAt(uri.length() - 1) == DOT ||
        INSECURE_URI.matcher(uri).matches();
  }

  private ResponseWrapper fromClasspath(RequestWrapper requestWrapper,
                                        String mediaType,
                                        String staticPath,
                                        String uri) {
    if (isModifiedSinceHeaderPresent(requestWrapper)) {
      return new ResponseWrapper(mediaType, NOT_MODIFIED);
    }

    String resource = staticPath + removeTopDir(uri);
    URL url = getClass().getResource(resource);
    if (null == url) {
      return fileNotFound(mediaType);
    }

    try {
      byte[] content = classpathStaticFileContentCache.get(resource);
      return new ResponseWrapper(mediaType, content);
    } catch (ExecutionException e) {
      LOGGER.error(e.getMessage(), e);
      return new ResponseWrapper(MediaType.TEXT_PLAIN.getValue(), INTERNAL_SERVER_ERROR);
    }
  }

  private byte[] getContentAsByteArray(String resource) throws IOException {
    try (InputStream input = getClass().getResourceAsStream(resource);
         ByteArrayOutputStream output = new ByteArrayOutputStream()
    ) {
      if (null == input) {
        return null;
      }

      FileUtils.copy(input, output);
      return output.toByteArray();
    }
  }

  private boolean isModifiedSinceHeaderPresent(RequestWrapper requestWrapper) {
    String ifModifiedSince = requestWrapper.getHeaders().get(HttpHeaderNames.IF_MODIFIED_SINCE);
    return !Strings.isNullOrEmpty(ifModifiedSince);
  }

  private ResponseWrapper fromFileSystem(RequestWrapper requestWrapper,
                                         String mediaType,
                                         String staticPath,
                                         String uri) {
    File file = new File(staticPath + removeTopDir(uri));
    if (isInvalidFile(file)) {
      return fileNotFound(mediaType);
    }

    if (isFileNotModified(requestWrapper, file)) {
      return new ResponseWrapper(mediaType, NOT_MODIFIED);
    }

    return new ResponseWrapper(mediaType, file);
  }

  private boolean isInvalidFile(File file) {
    return !isValidFile(file);
  }

  private boolean isValidFile(File file) {
    String path = file.getPath();
    int endIndex = path.indexOf(SIGN_EXCLAMATION);
    if (endIndex > 0) {
      int startIndex = path.indexOf(SIGN_COLON);
      return isValidFile(new File(path.substring(startIndex > 0 ? startIndex + 1 : 0, endIndex)));
    }
    return file.exists() && !file.isHidden() && !file.isDirectory() && file.isFile();
  }

  private ResponseWrapper fileNotFound(String mediaType) {
    return new ResponseWrapper(mediaType, NOT_FOUND.reasonPhrase().getBytes(), NOT_FOUND);
  }

  private boolean isFileNotModified(RequestWrapper requestWrapper, File file) {
    String ifModifiedSince = requestWrapper.getHeaders().get(HttpHeaderNames.IF_MODIFIED_SINCE);
    if (Strings.isNullOrEmpty(ifModifiedSince)) {
      return false;
    }

    long ifModifiedSinceSeconds = DateTime.parse(ifModifiedSince, HTTP_DATE_FORMATTER).getMillis() / 1000;
    long fileLastModifiedSeconds = file.lastModified() / 1000;
    return ifModifiedSinceSeconds == fileLastModifiedSeconds;
  }

  private String removeTopDir(String uri) {
    int index = uri.indexOf(PATH_SEPARATOR, 1);
    if (0 < index && index < uri.length()) {
      return uri.substring(index);
    }

    return uri;
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
        ResponseWrapper responseWrapper = handleStaticFileRequest(requestWrapper, mediaType, null);
        if (NOT_FOUND != responseWrapper.getStatus()) {
          return responseWrapper;
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
      if (Strings.isNullOrEmpty(referer)) {
        ctx.redirectTo(PATH_403);
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
