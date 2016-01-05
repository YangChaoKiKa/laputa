package com.orctom.laputa.server.processor.impl;

import com.orctom.laputa.server.util.ParamResolver;
import com.orctom.laputa.server.model.HTTPMethod;
import com.orctom.laputa.server.config.MappingConfig;
import com.orctom.laputa.server.model.RequestMapping;
import com.orctom.laputa.server.internal.BeanFactory;
import com.orctom.laputa.server.model.Response;
import com.orctom.laputa.server.processor.RequestProcessor;
import com.orctom.laputa.server.translator.ResponseTranslator;
import com.orctom.laputa.server.translator.ResponseTranslators;
import com.orctom.laputa.server.util.ArgsResolver;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * request processor
 * Created by hao on 1/6/16.
 */
public class DefaultRequestProcessor implements RequestProcessor {

	private static final Logger LOGGER = LogManager.getLogger();

	private static final byte[] ERROR_CONTENT = {'5', '0', '0'};

	@Override
	public Response handleRequest(DefaultHttpRequest req) {
		HttpMethod method = req.getMethod();
		String uri = req.getUri();

		// remove hash
		uri = removeHashFromUri(uri);

		// get query string
		int questionMarkIndex = uri.indexOf("?");
		String queryStr = null;
		if (questionMarkIndex > 0) {
			queryStr = uri.substring(questionMarkIndex + 1);
			uri = uri.substring(0, questionMarkIndex);
		}
		System.out.println("uri      = " + uri);
		System.out.println("queryStr = " + queryStr);

		RequestMapping mapping = MappingConfig.getInstance().getMapping(uri, getHttpMethod(method));

		ResponseTranslator translator = ResponseTranslators.getTranslator(req);
		try {
			Object data = processRequest(uri, queryStr, mapping);
			byte[] content = translator.translate(data);
			return new Response(translator.getMediaType(), content);
		} catch (Throwable e) {
			LOGGER.error(e.getMessage(), e);
			return new Response(translator.getMediaType(), ERROR_CONTENT);
		}
	}

	private String removeHashFromUri(String uri) {
		int hashIndex = uri.indexOf("#");
		if (hashIndex > 0) {
			return uri.substring(0, hashIndex);
		} else {
			return uri;
		}
	}

	private HTTPMethod getHttpMethod(HttpMethod method) {
		if (HttpMethod.DELETE == method) {
			return HTTPMethod.DELETE;
		}
		if (HttpMethod.HEAD == method) {
			return HTTPMethod.HEAD;
		}
		if (HttpMethod.OPTIONS == method) {
			return HTTPMethod.OPTIONS;
		}
		if (HttpMethod.POST == method) {
			return HTTPMethod.POST;
		}
		if (HttpMethod.PUT == method) {
			return HTTPMethod.PUT;
		}
		return HTTPMethod.GET;
	}

	public Object processRequest(String uri, String queryString, RequestMapping mapping)
			throws InvocationTargetException, IllegalAccessException {
		Class<?> handlerClass = mapping.getHandlerClass();
		Method handlerMethod = mapping.getHandlerMethod();
		Object target = BeanFactory.getFactory().getInstance(handlerClass);

		Parameter[] methodParameters = handlerMethod.getParameters();
		if (0 == methodParameters.length) {
			return handlerMethod.invoke(target);
		}

		Map<String, String> params = ParamResolver.extractParams(
				handlerMethod, mapping.getUriPattern(), uri, queryString);

		Object[] args = ArgsResolver.resolveArgs(handlerMethod, params);
		return handlerMethod.invoke(target, args);
	}
}
