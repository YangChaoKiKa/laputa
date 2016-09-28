package com.orctom.laputa.server.processor.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.orctom.laputa.server.exception.FileUploadException;
import com.orctom.laputa.server.exception.RequestProcessingException;
import com.orctom.laputa.server.processor.PostProcessor;
import com.orctom.laputa.server.processor.PreProcessor;
import com.orctom.laputa.server.config.MappingConfig;
import com.orctom.laputa.server.config.ServiceConfig;
import com.orctom.laputa.server.internal.BeanFactory;
import com.orctom.laputa.server.model.HTTPMethod;
import com.orctom.laputa.server.model.RequestMapping;
import com.orctom.laputa.server.model.RequestWrapper;
import com.orctom.laputa.server.model.Response;
import com.orctom.laputa.server.processor.RequestProcessor;
import com.orctom.laputa.server.translator.ResponseTranslator;
import com.orctom.laputa.server.translator.ResponseTranslators;
import com.orctom.laputa.server.util.ArgsResolver;
import com.orctom.laputa.server.util.ParamResolver;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.reflect.FastMethod;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.charset.Charset;
import java.util.*;

/**
 * request processor
 * Created by hao on 1/6/16.
 */
public class DefaultRequestProcessor implements RequestProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRequestProcessor.class);

  private static final BeanFactory beanFactory = ServiceConfig.getInstance().getBeanFactory();

  private static final byte[] ERROR_CONTENT = {'5', '0', '0'};

  private static final Map<HttpMethod, HTTPMethod> HTTP_METHODS = ImmutableMap.of(
      HttpMethod.DELETE, HTTPMethod.DELETE,
      HttpMethod.HEAD, HTTPMethod.HEAD,
      HttpMethod.OPTIONS, HTTPMethod.OPTIONS,
      HttpMethod.POST, HTTPMethod.POST,
      HttpMethod.PUT, HTTPMethod.PUT
  );
  public static final String FILENAME = "::filename";
  public static final String CONTENT_TYPE = "::content_type";

  @Override
  public Response handleRequest(DefaultHttpRequest req) {
    RequestWrapper requestWrapper = getRequestWrapper(req);

    // pro-processors
    preProcess(requestWrapper);

    RequestMapping mapping = MappingConfig.getInstance().getMapping(
        requestWrapper.getPath(),
        getHttpMethod(requestWrapper.getHttpMethod()));

    String accept = req.headers().get(HttpHeaderNames.ACCEPT);
    ResponseTranslator translator = ResponseTranslators.getTranslator(requestWrapper.getPath(), accept);
    try {
      Object data = processRequest(requestWrapper, mapping);

      // post-processors
      postProcess(data);
      byte[] content = translator.translate(data);
      return new Response(translator.getMediaType(), content);
    } catch (Throwable e) {
      LOGGER.error(e.getMessage(), e);
      return new Response(translator.getMediaType(), ERROR_CONTENT);
    }
  }

  private RequestWrapper getRequestWrapper(DefaultHttpRequest req) {
    HttpMethod method = req.method();
    String uri = req.uri();
    LOGGER.debug("uri = {}", uri);

    if (HttpMethod.POST.equals(method) ||
        HttpMethod.PUT.equals(method) ||
        HttpMethod.PATCH.equals(method)) {
      return wrapPostRequest(req, method, uri);
    } else {
      return wrapGetRequest(method, uri);
    }
  }

  private RequestWrapper wrapPostRequest(DefaultHttpRequest req, HttpMethod method, String uri) {
    HttpPostRequestDecoder decoder = getHttpPostRequestDecoder(req);
    List<InterfaceHttpData> bodyDatas = decoder.getBodyHttpDatas();

    Map<String, List<String>> parameters = new HashMap<>();

    for (InterfaceHttpData bodyData : bodyDatas) {
      if (HttpDataType.Attribute == bodyData.getHttpDataType()) {
        Attribute attribute = (Attribute) bodyData;
        addToParameters(parameters, attribute);
      } else if (HttpDataType.FileUpload == bodyData.getHttpDataType()) {
        FileUpload fileUpload = (FileUpload) bodyData;
        addToParameters(parameters, fileUpload);
        decoder.removeHttpDataFromClean(bodyData);
      }
    }

    return null;
  }

  private void addToParameters(Map<String, List<String>> parameters, Attribute attribute) {
    try {
      String value = attribute.getValue();
      if (Strings.isNullOrEmpty(value)) {
        return;
      }

      String name = attribute.getName();
      List<String> params = parameters.get(name);
      if (null == params) {
        params = new ArrayList<>();
        parameters.put(name, params);
      }
      params.add(value);
    } catch (IOException e) {
      throw new RequestProcessingException(e.getMessage(), e);
    }
  }

  private void addToParameters(Map<String, List<String>> parameters, FileUpload fileUpload) {
    try {
      File uploadedFile = fileUpload.getFile();
      parameters.put(fileUpload.getName(), Lists.newArrayList(uploadedFile.getAbsolutePath()));
      parameters.put(fileUpload.getName() + FILENAME, Lists.newArrayList(fileUpload.getFilename()));
      parameters.put(fileUpload.getName() + CONTENT_TYPE, Lists.newArrayList(fileUpload.getContentType()));
    } catch (IOException e) {
      throw new FileUploadException("Failed to upload file: " + e.getMessage(), e);
    }
  }

  private RequestWrapper wrapGetRequest(HttpMethod method, String uri) {
    QueryStringDecoder queryStringDecoder = getQueryStringDecoder(uri);
    String path = queryStringDecoder.path();
    Map<String, List<String>> queryParameters = queryStringDecoder.parameters();
    return new RequestWrapper(method, path, queryParameters);
  }

  private void preProcess(RequestWrapper requestWrapper) {
    Collection<PreProcessor> preProcessors = beanFactory.getInstances(PreProcessor.class);
    if (null == preProcessors || preProcessors.isEmpty()) {
      return;
    }

    for (PreProcessor processor : preProcessors) {
      processor.process(requestWrapper);
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("pre-processed, {}", requestWrapper.toString());
    }
  }

  private void postProcess(Object data) {
    Collection<PostProcessor> postProcessors = beanFactory.getInstances(PostProcessor.class);
    if (null == postProcessors || postProcessors.isEmpty()) {
      return;
    }

    for (PostProcessor processor : postProcessors) {
    }
  }

  private QueryStringDecoder getQueryStringDecoder(String uri) {
    Charset charset = ServiceConfig.getInstance().getCharset();
    if (null != charset) {
      return new QueryStringDecoder(uri, charset);
    } else {
      return new QueryStringDecoder(uri);
    }
  }

  private HttpPostRequestDecoder getHttpPostRequestDecoder(DefaultHttpRequest req) {
    Charset charset = ServiceConfig.getInstance().getCharset();
    if (null != charset) {
      return new HttpPostRequestDecoder(new DefaultHttpDataFactory(true, charset), req);
    } else {
      return new HttpPostRequestDecoder(new DefaultHttpDataFactory(true), req);
    }
  }

  private HTTPMethod getHttpMethod(HttpMethod method) {
    HTTPMethod httpMethod = HTTP_METHODS.get(method);
    if (null != httpMethod) {
      return httpMethod;
    }

    return HTTPMethod.GET;
  }

  public Object processRequest(RequestWrapper requestWrapper, RequestMapping mapping)
      throws InvocationTargetException, IllegalAccessException {
    Class<?> handlerClass = mapping.getHandlerClass();
    FastMethod handlerMethod = mapping.getHandlerMethod();
    Object target = beanFactory.getInstance(handlerClass);

    Parameter[] methodParameters = mapping.getParameters();
    if (0 == methodParameters.length) {
      return handlerMethod.invoke(target, null);
    }

    Map<String, String> params = ParamResolver.extractParams(
        handlerMethod.getJavaMethod(),
        mapping.getUriPattern(),
        requestWrapper
    );

    Object[] args = ArgsResolver.resolveArgs(methodParameters, params);
    return handlerMethod.invoke(target, args);
  }
}
