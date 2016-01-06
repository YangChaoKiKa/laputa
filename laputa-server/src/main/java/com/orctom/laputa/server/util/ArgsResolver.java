package com.orctom.laputa.server.util;

import com.orctom.laputa.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FIXME
 * Utils to set/get properties or invoke methods dynamically
 * Created by hao on 1/5/16.
 */
public abstract class ArgsResolver {

	public static Object[] resolveArgs(Method method, Map<String, String> paramValues) {
		Parameter[] methodParameters = method.getParameters();
		if (0 == methodParameters.length) {
			return null;
		}

		Object[] args = new Object[methodParameters.length];

		Map<Parameter, Integer> complexParameters = new HashMap<>();
		resolveSimpleValueTypeArgs(paramValues, methodParameters, args, complexParameters);
		resolveComplexValueTypeArgs(paramValues, methodParameters, args, complexParameters);

		return args;
	}

	private static void resolveSimpleValueTypeArgs(Map<String, String> paramValues,
												   Parameter[] methodParameters,
												   Object[] args,
												   Map<Parameter, Integer> complexParameters) {
		for (int i = 0; i < methodParameters.length; i++) {
			Parameter parameter = methodParameters[i];
			Class<?> type = parameter.getType();
			if (ClassUtils.isSimpleValueType((type))) {
				args[i] = resolveSimpleValueType(paramValues, parameter.getName(), type);
			} else {
				complexParameters.put(parameter, i);
			}
		}
	}

	private static void resolveComplexValueTypeArgs(Map<String, String> paramValues,
													Parameter[] methodParameters,
													Object[] args,
													Map<Parameter, Integer> complexParameters) {
		Map<String, String> nestedParamValues = getNestedParamValues(paramValues);
		boolean hasNestedParamValues = !nestedParamValues.isEmpty();
		for (Map.Entry<Parameter, Integer> entry : complexParameters.entrySet()) {
			String paramName = entry.getKey().getName();
			Class<?> type = entry.getKey().getType();
			int index = entry.getValue();
			Object arg = createNewInstance(type);
			if (hasNestedParamValues) {
				for (Map.Entry<String, String> paramValue : nestedParamValues.entrySet()) {
					String name = paramValue.getKey();
					String value = paramValue.getValue();
					//TODO set nested property
				}
			} else {
				for (Map.Entry<String, String> paramValue : paramValues.entrySet()) {
					String name = paramValue.getKey();
					String value = paramValue.getValue();
				}
			}
		}
	}

	private static Map<String, String> getNestedParamValues(Map<String, String> paramValues) {
		return paramValues.entrySet().stream()
				.filter(entry -> entry.getKey().contains("."))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static Object createNewInstance(Class<?> type) {
		try {
			return type.newInstance();
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static Object resolveSimpleValueType(
			Map<String, String> paramValues, String paramName, Class<?> type) {
		String value = paramValues.remove(paramName);
		if (null == value) {
			throw new IllegalArgumentException("Failed to populate param: " + paramName);
		}
		if (String.class.isAssignableFrom(type)) {
			return value;
		} else if (Integer.class.isAssignableFrom(type) || int.class.isAssignableFrom(type)) {
			return Integer.valueOf(value);
		} else if (Double.class.isAssignableFrom(type) || double.class.isAssignableFrom(type)) {
			return Double.valueOf(value);
		} else if (Float.class.isAssignableFrom(type) || float.class.isAssignableFrom(type)) {
			return Float.valueOf(value);
		} else if (Long.class.isAssignableFrom(type) || long.class.isAssignableFrom(type)) {
			return Long.valueOf(value);
		} else  {
			throw new IllegalArgumentException("Unsupported param type" + type + " " + paramName);
		}
	}
}
