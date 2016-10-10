package com.orctom.laputa.server.util;

import com.orctom.laputa.server.annotation.Param;
import com.orctom.laputa.server.exception.ParameterValidationException;
import com.orctom.utils.ClassUtils;
import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utils to set/get properties or invoke methods dynamically
 * Created by hao on 1/5/16.
 */
public abstract class ArgsResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(ArgsResolver.class);
  private static final String DOT = ".";

  public static Object[] resolveArgs(Parameter[] methodParameters, Map<String, String> paramValues) {
    if (0 == methodParameters.length) {
      return null;
    }

    Object[] args = new Object[methodParameters.length];

    Map<Parameter, Integer> complexParameters = new HashMap<>();
    int resolved = resolveSimpleTypeArgs(paramValues, methodParameters, args, complexParameters);

    if (methodParameters.length != resolved) { // complex types exist
      resolveComplexTypeArgs(paramValues, args, complexParameters);
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

  private static void resolveComplexTypeArgs(Map<String, String> paramValues,
                                            Object[] args,
                                            Map<Parameter, Integer> complexParameters) {
    for (Map.Entry<Parameter, Integer> entry : complexParameters.entrySet()) {
      Parameter parameter = entry.getKey();
      String paramName = parameter.getAnnotation(Param.class).value();
      Class<?> type = entry.getKey().getType();
      int index = entry.getValue();

      Map<String, String> params = retrieveParams(paramValues, paramName);
      if (params.isEmpty()) {
        params = paramValues;
      }

      Object arg = generateAndPopulateArg(params, type);

      if (null != arg) {
        args[index] = arg;
      }
    }
  }

  private static Object generateAndPopulateArg(Map<String, String> paramValues, Class<?> type) {
    Object bean = BeanUtil.createNewInstance(type);
    try {
      BeanUtils.populate(bean, paramValues);
      return bean;
    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
      return null;
    }
  }

  private static Map<String, String> getNestedParamValues(Map<String, String> paramValues) {
    return paramValues.entrySet().stream()
        .filter(entry -> entry.getKey().contains(DOT))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static Object resolveSimpleTypeValue(
      Map<String, String> paramValues, String paramName, Class<?> type) {
    String value = paramValues.remove(paramName);
    if (null == value) {
      throw new IllegalArgumentException("Missing param: " + paramName);
    }
    try {
      if (String.class.isAssignableFrom(type)) {
        return value;
      } else if (Integer.class.isAssignableFrom(type) || int.class.isAssignableFrom(type)) {
        try {
          return Integer.valueOf(value);
        } catch (NumberFormatException e) {
          throw new ParameterValidationException("Invalid param value: " + value + ", is not integer");
        }
      } else if (Double.class.isAssignableFrom(type) || double.class.isAssignableFrom(type)) {
        try {
          return Double.valueOf(value);
        } catch (NumberFormatException e) {
          throw new ParameterValidationException("Invalid param value: " + value + ", is not double");
        }
      } else if (Float.class.isAssignableFrom(type) || float.class.isAssignableFrom(type)) {
        try {
          return Float.valueOf(value);
        } catch (NumberFormatException e) {
          throw new ParameterValidationException("Invalid param value: " + value + ", is not float");
        }
      } else if (Long.class.isAssignableFrom(type) || long.class.isAssignableFrom(type)) {
        try {
          return Long.valueOf(value);
        } catch (NumberFormatException e) {
          throw new ParameterValidationException("Invalid param value: " + value + ", is not long");
        }
      } else {
        throw new IllegalArgumentException("Unsupported param type" + type + " " + paramName);
      }
    } catch (NumberFormatException e) {
      throw new ParameterValidationException("Invalid digit: " + value);
    }
  }

  private static Map<String, String> retrieveParams(Map<String, String> paramValues, String paramName) {
    return paramValues
        .entrySet()
        .stream()
        .filter(item -> item.getKey().startsWith(paramName) && item.getKey().length() > paramName.length())
        .collect(Collectors.toMap(
            item -> item.getKey().substring(item.getKey().indexOf(paramName) + paramName.length() + 1),
            Map.Entry::getValue));
  }
}
