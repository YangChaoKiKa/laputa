package com.orctom.laputa.server.util;

import com.google.common.base.Splitter;
import com.orctom.laputa.server.annotation.Param;
import com.orctom.utils.ClassUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
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

  private static final Logger LOGGER = LoggerFactory.getLogger(ArgsResolver.class);

  public static Object[] resolveArgs(Method method, Map<String, String> paramValues) {
    Parameter[] methodParameters = method.getParameters();
    if (0 == methodParameters.length) {
      return null;
    }

    Object[] args = new Object[methodParameters.length];

    Map<Parameter, Integer> complexParameters = new HashMap<>();
    int resolvedSimple = resolveSimpleTypeArgs(paramValues, methodParameters, args, complexParameters);

    if (methodParameters.length != resolvedSimple) { // complex types exist
      int resolvedComplex = resolveComplexTypeArgs(paramValues, args, complexParameters, false);

      boolean isOmitRootPathAllowed = (0 == resolvedSimple) && (0 == resolvedComplex);
      if (isOmitRootPathAllowed) {
        Map<String, String> nestedParamValues = getNestedParamValues(paramValues);
        resolveComplexTypeArgs(nestedParamValues, args, complexParameters, true);
      }
    }

    return args;
  }

  private static int resolveSimpleTypeArgs(Map<String, String> paramValues,
                                           Parameter[] methodParameters,
                                           Object[] args,
                                           Map<Parameter, Integer> complexParameters) {
    int count = 0;
    for (int i = 0; i < methodParameters.length; i++) {
      Parameter parameter = methodParameters[i];
      Class<?> type = parameter.getType();

      if (ClassUtils.isSimpleValueType((type))) {
        String paramName = parameter.getAnnotation(Param.class).value();
        args[i] = resolveSimpleTypeValue(paramValues, paramName, type);
        count++;
      } else {
        complexParameters.put(parameter, i);
      }
    }

    return count;
  }

  private static int resolveComplexTypeArgs(Map<String, String> paramValues,
                                            Object[] args,
                                            Map<Parameter, Integer> complexParameters,
                                            boolean isOmitRootPathAllowed) {
    int count = 0;
    for (Map.Entry<Parameter, Integer> entry : complexParameters.entrySet()) {
      Parameter parameter = entry.getKey();
      String paramName = parameter.getAnnotation(Param.class).value();
      Class<?> type = entry.getKey().getType();
      int index = entry.getValue();
      Object arg = generateAndPopulateArg(paramValues, type, paramName, isOmitRootPathAllowed);

      if (null != arg) {
        args[index] = arg;
        count++;
      }
    }

    return count;
  }

  private static Object generateAndPopulateArg(Map<String, String> paramValues,
                                               Class<?> type,
                                               String paramName,
                                               boolean isOmitRootPathAllowed) {
    Object instance = createNewInstance(type);
    try {
      boolean populated = false;
      for (Map.Entry<String, String> paramValue : paramValues.entrySet()) {
        String property = paramValue.getKey();
        if (!isOmitRootPathAllowed && !property.startsWith(paramName)) {
          continue;
        }

        initializeNestedBean(instance, type, property);
        String value = paramValue.getValue();
        try {
          PropertyUtils.setProperty(instance, property, value);
          populated = true;
        } catch (Exception e) {
          LOGGER.warn(e.getMessage(), e);
        }
      }

      return populated ? instance : null;
    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
      return null;
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

  private static Object resolveSimpleTypeValue(
      Map<String, String> paramValues, String paramName, Class<?> type) {
    String value = paramValues.remove(paramName);
    if (null == value) {
      throw new IllegalArgumentException("Missing param: " + paramName);
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
    } else {
      throw new IllegalArgumentException("Unsupported param type" + type + " " + paramName);
    }
  }

  private static void initializeNestedBean(Object instance, Class<?> type, String property) {
    int index = property.indexOf(".");
    if (index < 0) {
      return;
    }

    String prop = property.substring(0, index);
    String next = property.substring(index + 1);
    try {
      Class<?> nestedType = type.getDeclaredField(prop).getType();
      Object nested = createNewInstance(nestedType);
      PropertyUtils.setProperty(instance, prop, nested);
      initializeNestedBean(nested, nestedType, next);
    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
    }
  }
}
