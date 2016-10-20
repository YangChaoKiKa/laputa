package com.orctom.laputa.service.exception;

import com.orctom.laputa.exception.FastException;

import javax.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Param validation exception
 * Created by chenhao on 10/9/16.
 */
public class ParameterValidationException extends FastException {

  private List<String> messages = new ArrayList<>();

  public ParameterValidationException(String message) {
    messages.add(message);
  }

  public ParameterValidationException(Set<ConstraintViolation<Object>> violations) {
    for (ConstraintViolation<Object> violation : violations) {
      System.out.println(violation.getPropertyPath().getClass());
      messages.add(violation.getMessage() + ": '" + violation.getInvalidValue() + "'");
    }
  }

  @Override
  public String getMessage() {
    StringBuilder msg = new StringBuilder();
    for (String message : messages) {
      msg.append(message).append(", ");
    }
    return msg.deleteCharAt(msg.length() - 2).toString();
  }

  public List<String> getMessages() {
    return messages;
  }
}
